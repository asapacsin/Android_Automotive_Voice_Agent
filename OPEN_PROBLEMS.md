# Open problems

> Active defects blocking `CURRENT_MILESTONE.md`. Reported by the product owner from real device use on 2026-09-15.
> Each stays here until it is fixed **and** verified. Do not close one on a green build.

---

## P1 — 小诺 talks over Amap navigation guidance

**Status:** ✅ RESOLVED 2026-09-16 — verified on device by the product owner
**Reported:** 2026-09-15, real use with navigation running
**Severity:** High — makes the assistant unusable while driving, which is the product's entire context

### Symptom

While Amap is delivering turn-by-turn guidance, 小诺's reply audio plays **at the same time**. Two voices speak simultaneously and neither is intelligible.

### Required behaviour (product owner)

During active car navigation, 小诺 should **stay quiet by default**. It should speak only for short, necessary confirmations — for example acknowledging that music was closed — not for full conversational replies layered over navigation guidance.

**Clarified 2026-09-15 (supersedes the ducking-only approach):**

> "for the amap self own app voice conflict part, you can just mute the self own device when amap running, but keep the voice when some function change like turn on or turn off something"

So the rule is stricter than audio ducking: while navigation is running, 小诺's reply audio is **muted outright**, except within a short window after a tool action succeeded (music started/stopped, app opened, navigation started). Audio-focus ducking remains in place underneath, but this product rule is the primary mechanism.

Implementation: `NavigationState` (`navigating`, `allowConfirmation()`, `shouldMuteSpeech()`, `reset()`), with `AndroidToolDispatcher` opening a 10 s confirmation window on any Accepted tool, `AndroidPlaybackPort.enqueue` dropping frames while muted, and the flag cleared on session stop/release.

**Known limitation:** nothing detects when navigation *ends*. The flag clears when the voice session stops. If the driver finishes navigating but keeps the same session open, 小诺 stays muted until the session ends. A `stop_navigation` tool would resolve this; not yet specced.

**Update 2026-09-16 — partially addressed, and deliberately NOT closed.** The *embedded* navigation lifecycle now detects arrival: `onArriveDestination` / `onEndEmulatorNavi` reach `AmapNaviViewHost.stopNavigation`, and a new ended-callback resyncs `NavigationPhase` to `ARRIVED`/`STOPPED`. Device-verified 2026-09-16 (`nav_stopped reached=true reason=emulator_end`, then `nav_flow_ended phase=STOPPED` on the manual path).

That fixes the **new** `NavigationPhase` state machine only. The flag this section is about is the **legacy `NavigationState`**, which still clears solely on session stop or `exit_navigation_mode` — the two are separate objects and are not merged until SPEC-005 Phase 4. So the muting behaviour described above is unchanged on the installed build. Do not read the arrival fix as closing P1's limitation.

### What is already known

- Reply playback uses `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` on the media stream (`PcmAudioPlayer`).
- `AudioFocusController` requests `AUDIOFOCUS_GAIN_TRANSIENT` when speech begins and abandons it when speech ends (per utterance, since the task20 change).
- **CONFIRMED ROOT CAUSE (measured 2026-09-15):** we request and abandon focus correctly per utterance, but we never **react** to losing it. `dumpsys audio` shows only `requestAudioFocus` / `abandonAudioFocus` pairs from `AudioFocusController` — no focus-loss handling exists anywhere in the code. `AudioFocusController` stores `lastFocusState` but nothing consumes it, and `PcmAudioPlayer` keeps writing to its `AudioTrack` regardless. So when Amap takes focus for guidance, we simply keep talking over it.
- Amap navigation guidance typically uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, which does not automatically silence a media-stream track.

### Acceptance (per `ACCEPTANCE_TESTS.md`)

L5 — on device, with navigation actively running: 小诺 must not speak over a navigation prompt, and a short tool confirmation must still be audible and intelligible.

---

## P2 — 「关闭音乐」 still does not stop the music by voice

**Status:** ✅ RESOLVED 2026-09-16 — verified on device with log evidence
**Reported:** 2026-09-15, after the `control_music` tool shipped
**Severity:** High — the user cannot stop audio they started

### Symptom

Saying 「关闭音乐」 does not stop the bundled music.

### What is already known

- The tool **exists and works**: `control_music` with `action: play|stop` is declared in `BaiduFlexProtocol.sessionUpdate`, validated in `FlexFunctionCallAssembler`, and dispatched by `AndroidToolDispatcher` to `BundledMusicPlayer.play()` / `.stop()`.
- Verified via ADB on 2026-09-15: `control_music play` → 1 MediaPlayer track; `control_music stop` → 0 tracks; both `result=Accepted`. So the **execution path is proven good**.

### ROOT CAUSE — MEASURED 2026-09-15 (not speculation)

`adb logcat -s NovaVoice:D` shows:

```
transcript=你: 帮我播一下音乐。
tool=control_music args=[action] result=✓ control_music
transcript=小诺: 音乐已经播放。
transcript=你: 关闭音乐。          <- no tool line follows
transcript=你: 关掉音乐。          <- no tool line follows
```

Conclusions:

- The speech **does** reach the model. Both stop phrases were transcribed correctly, so this is NOT a microphone or echo problem.
- **Play works, stop does not.** The model selects `control_music` for 「播放」 but not for 「关闭」/「关掉」.
- The model produced **no reply at all** to either stop phrase. Persona rule 4 successfully stopped it fabricating 「音乐已关闭」, but it neither acted nor said it could not — it simply went silent.
- Most likely cause: the tool description is English-only — `"Play or stop the assistant's built-in music player"` — and the `action` enum (`play` / `stop`) carries no binding to the Chinese verbs the user actually says. Nothing in the declaration tells the model that 关闭/关掉/停止/别放了 map to `action: "stop"`.

### Acceptance (per `ACCEPTANCE_TESTS.md`)

L5 — on device, with music playing, the spoken phrase 「关闭音乐」 stops playback, and the log shows `tool=control_music` with `action=stop`.

### ⚠️ Do NOT reopen P2 on the 2026-09-16 "play music doesn't work" report

The product owner reported that 「播放音乐」 stopped working. **That is P3, not a P2 regression.** Verified the same day:

```
09:55:43  debug_tool tool=control_music arg=play result=Accepted
dumpsys:  piid:2031 u/pid:10263/27336 MediaPlayer state:started usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC
```

The execution path produces real audio on demand. The tool is still declared (a 4th tool was added the same day, and `navigate_to` fired successfully on that build at 09:49:55, proving the declarations were accepted). What failed was the **model choosing to call the tool**, after its context was flooded with Amap guidance transcribed as driver speech — see the severity correction under [P3](#p3--amaps-guidance-is-transcribed-as-the-drivers-speech).

A hypothesis that the new no-argument tool schema had broken the whole tools array was **investigated and disproved** by the 09:49:55 `navigate_to` success. Do not spend time re-testing it.

---

## Rules for these two

- Do **not** mark either RESOLVED on the basis of a build, a unit test, or an ADB-triggered tool call. Both require L5 device evidence with a human voice.
- P1 and P2 interact: if the microphone is saturated by 小诺's own speech and Amap's guidance (P1), the user's 「关闭音乐」 may never arrive (P2). Fixing P1 may partly fix P2 — but that must be demonstrated, not assumed.

---

## Closure evidence (2026-09-16)

**P2 — closed.** Exactly the log line the acceptance criteria demanded:

```
transcript=你: 关闭音乐。
tool=control_music args=[action] result=✓ control_music
transcript=小诺: 音乐已经关闭。
```

The fix was binding the Chinese stop verbs into the tool description plus a persona rule forbidding silence in place of action.

**P1 — closed.** Navigation launched via `androidamap://navi`, zero session errors, and the product owner confirms 小诺 no longer talks over guidance while short tool confirmations still come through.

---

## P3 — Amap's guidance is transcribed as the driver's speech

**Status:** OPEN — Option A falsified 2026-09-16. **Option C (VAD mitigation) implemented 2026-09-16, compile- and test-verified only, NOT device-verified.** **Direction changed the same day by [ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md):** with the Amap SDK embedded, guidance speech is started *by our process* and its playing state is queryable — the exact signal SPEC-002 could not observe from outside. The proper fix is now v2 Phase 7 of [SPEC-005](SPECS/SPEC-005-embedded-amap-mvp.md); wake-word gating remains complementary. Until then the physical problem (guidance → speaker → mic) persists on the installed build.
**Found:** 2026-09-16, in the logs of the successful P1/P2 test
**Severity:** ~~Medium — does not break the product~~ → **RAISED TO HIGH 2026-09-16. It does break the product.**

### Severity correction — measured, not theorised

The original classification was wrong. Device evidence from a real session (09:49–09:52 on 2026-09-16) shows P3 **causing tool failure**, not merely wasting quota:

```
09:49:55  tool=navigate_to args=[destination] result=✓ navigate_to     <- tools work
09:50:13  transcript=你: 即将右转。                                      <- Amap's voice, as "the driver"
09:50:14  transcript=小诺: 即将右转。                                    <- 小诺 parroting Amap
09:50:22  transcript=你: 高德地图持续为您导航这个车                        <- Amap again
09:51:06  transcript=你: 小洛小洛，帮我播一下音乐。
09:51:07  transcript=小诺: 已为你播放音乐。                               <- NO tool= line. Fabricated.
```

`navigate_to` fired correctly at 09:49:55, so the tool declarations were accepted and working. Ninety seconds later, after the context had filled with Amap's guidance transcribed as user turns, the model answered a music request by **claiming success without calling the tool** — a direct violation of persona rule 3 (绝对不许谎报结果).

**Conclusion: P3 degrades the model's behaviour until tool calling stops working.** The product owner reported this as "i say play music it suddenly wont work". That symptom is P3, not a music defect. P2 is not regressed — `control_music` still works when invoked (proven by ADB) and the tool is still declared.

This also raises the priority of SPEC-002 from "cost saving" to "correctness".

### Symptom

While navigating, the microphone picks up Amap's spoken guidance and Baidu transcribes it as user input:

```
transcript=你: 走右侧车道即将在红绿灯右转，我的fuck。
transcript=小诺: 没听清，再说一遍。
```

The navigation mute rule silences 小诺's *replies*, so the driver never hears these — but the audio still goes up to Baidu and the model still generates responses to it.

### Why it matters

- Consumes Baidu quota continuously during every drive.
- A garbled transcript could eventually resemble a command and trigger a real tool call.
- Also appeared as repeated 「没听清，再说一遍」 replies to noise.

### Plan

A full plan exists: **[SPEC-002 — Stop sending Amap's guidance to Baidu](SPECS/SPEC-002-navigation-uplink-mute.md)**. **Approach chosen 2026-09-16: Option A** — observe other apps' playback via `AudioManager.registerAudioPlaybackCallback` and gate the uplink only while a `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` player is active. Option B (gate on audio-focus loss) was rejected as the primary approach because it needs a session-long focus hold, which is the exact defect that caused P1.

**Phase 1 was run on 2026-09-16 and Option A did not survive it.** Measured on the test device with Amap in live turn-by-turn: Amap's guidance track reports **`USAGE_MEDIA`**, not `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` — that usage does not appear anywhere on the device — so Option A's gate condition can never become true. The two obvious repairs are also blocked: `getClientUid()` is redacted to `-1` (so we cannot gate by Amap's uid, nor exclude our own music, which is likewise `USAGE_MEDIA`), and Amap's track stays `state:started` for the whole navigation rather than per utterance, so gating on "any started player" would make 小诺 deaf for the entire drive.

One hypothesis could still rescue the approach — that each spoken prompt opens a separate short-lived `piid` — but it needs Amap actually speaking, which a stationary phone with weak GPS could not produce. Full measurements and the options now open are in SPEC-002.

Headline of that plan, because it is the thing most likely to be got wrong: **do not gate the microphone for the whole navigation.** Navigation is exactly when the driver needs hands-free speech, and nothing currently detects when navigation ends (see P4), so a blanket gate could leave 小诺 deaf indefinitely. The uplink must be gated only while Amap is *actually speaking* — a few seconds at a time.

Phase 1 of the plan is a device probe to establish whether `AudioManager.registerAudioPlaybackCallback` can observe another app's `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` playback. That experiment gates the entire approach and must run before any fix is written.

### Acceptance

L5 — during active navigation, no transcript lines appear that originate from Amap's guidance over a sustained drive.

---

## P4 — No `stop_navigation` tool

**Status:** **Implemented 2026-09-16 as `exit_navigation_mode` — compile- and test-verified, NOT yet device-verified (L5 outstanding). Superseded in design by [ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md):** under the embedded SDK, navigation end/arrival arrive as callbacks and 「停止导航」 becomes a real `stop_navigation` through `NavigationController` (v2 §24). `exit_navigation_mode` stays as the interim on the installed build and is retired at SPEC-005 Phase 4.
**Found:** 2026-09-16
**Severity:** Low–Medium

### Symptom

Observed in the same test:

```
transcript=你: 导航结束了，下次见哦。
transcript=小诺: 已结束导航，下次见。
```

There is no `stop_navigation` tool, so nothing was executed. The model acknowledged a state change it could not perform — borderline against persona rule 3 (绝不许谎报结果).

### Why it matters

- `NavigationState.navigating` only clears when the voice session stops, so 小诺 stays muted after the drive ends (the limitation recorded under P1).
- A `stop_navigation` tool would both end the mute state and give the model a real action instead of an acknowledgement.

### Acceptance

L5 — saying 「结束导航」 clears the navigation state, 小诺 resumes speaking normally, and the log shows a `stop_navigation` tool call.

### Device evidence 2026-09-16 — the *embedded* end-of-navigation path, not the voice tool

The embedded SDK's completion callbacks are now wired and **device-verified**, which removes the
"nothing detects when navigation ends" premise for the embedded path:

```
18:52:32  nav_started type=2                                  (emulator, 5029 m route)
18:55:06  nav_emulator_end
18:55:06  nav_stopped reached=true reason=emulator_end        x1, no manual stop issued
```

Manual stop is idempotent and resyncs the state machine:

```
nav_stopped reached=true reason=manual          x1
nav_flow_ended reason=manual phase=STOPPED      x1
nav_stop_skipped already_inactive=true          x1   (second request)
```

**Still outstanding for P4 itself:** `exit_navigation_mode` — the *voice* tool — remains
**NOT device-verified**; nobody has said 「结束导航」 to the device. Arrival was driven by the
SDK's emulator, not a real vehicle. `onArriveDestination` (the real-GPS callback) has **never
fired on this device**; only `onEndEmulatorNavi` has. Both are wired, but only one is observed.

---

## P5 — The embedded map never locates the phone

**Status:** OPEN — **both identified root causes FIXED and verified on device 2026-09-16, but that was necessary and NOT sufficient. A third, structural cause is now confirmed by measurement.**

### Fix round 1 (2026-09-16) — verified, and still not enough

Shipped: `WAKE_LOCK` added to the manifest; `MainActivity` now requests `ACCESS_FINE_LOCATION` (it only ever requested COARSE); `AmapNaviViewHost` gained `startLocation()` → `AMapNavi.startGPS()`, `stopGPS()` on pause/destroy, an `AMapNaviViewListener`, and `isGpsReady()`. Device-verified on a genuinely clean state (the ADB-granted permission was **revoked first**, so the fix was tested against what a real user has):

| Check | Before | After |
| --- | --- | --- |
| App itself prompts for FINE | never | ✅ `GrantPermissionsActivity`, asking for 精准定位 |
| `SecurityException … WAKE_LOCK` | present | ✅ **0** |
| `SecurityException … ACCESS_FINE_LOCATION` | present | ✅ only the single pre-grant one; none after |
| `amap_gps` log | absent | ✅ `skipped=no_fine_permission` → `startGPS=true` |
| SDK receives locations | `locations = 0` | ✅ `locations = 10` under injection |
| **Map shows the position** | absent | ❌ **still absent** |

### Root cause 3 — `AMapNaviView` does not render a position without a route (CONFIRMED, not inferred)

The obvious reading of "no fix indoors" was **wrong**, and sending the product owner outdoors would have wasted their time. Proven with the adb mock-location provider:

- High-accuracy request went from `locations = 0` to **`locations = 10`** once valid fixes were injected — the SDK **did** receive location.
- The map rendered **identically** (105,251 / 105,892 / 105,485 bytes across three captures).

So the SDK gets location and draws nothing. `AMapNaviView` is a **navigation** view: it renders the vehicle along a *calculated route*, and Phase 1 has no route by design.

This is the **E5** risk recorded as *(unverified)* in [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md), now confirmed. The design pre-authorised the remedy: fall back to `com.amap.api.maps.MapView` + `MyLocationStyle` for the idle/browse state and reserve `AMapNaviView` for Phase 2's real navigation. Both classes are confirmed present in the resolved artifact.

**Consequence to accept openly:** that means hosting two map surfaces swapped by `NavigationPhase`, and it invalidates the Phase 1 row 2 evidence, which was gathered against `AMapNaviView`.

### Fix round 2 (2026-09-16) — `AMap.setMyLocationEnabled` tried on the existing view, and it is not enough

Before paying for a second map surface, the cheaper option was tried: `AMapNaviView.getMap()` returns the underlying `com.amap.api.maps.AMap`, which owns the my-location layer. *(This option was invisible in an earlier filtered `javap` dump and only appeared when the full method list was taken — a filter hiding the answer is a recurring failure mode in this project.)*

Implemented in `AmapNaviViewHost.enableMyLocation()`: `MyLocationStyle().myLocationType(LOCATION_TYPE_LOCATE).showMyLocation(true).interval(2000)`, then `map.isMyLocationEnabled = true`.

**Verified present in the shipped APK** by searching the dex string table for the new log literals (`amap_gps myLocation=` found in `classes16.dex`) — necessary because the APK came out byte-for-byte the same size as the previous build.

Measured result:

| Check | Result |
| --- | --- |
| `amap_gps startGPS=true` | ✅ |
| `amap_gps myLocation=enabled` | ✅ — the call succeeded |
| Device online | ✅ WiFi connected; `restapi.amap.com` answers in 21 ms |
| SDK receiving location | ✅ high-accuracy request reached **42** fixes |
| Amap tile/network errors in logcat | ✅ **none** |
| Map appearance | ⚠️ changed from pale green to **solid blue** — so the call had a real effect |
| **Position marker** | ❌ **absent** |
| Map tiles / road network | ❌ **absent** |

**Conclusion: `AMapNaviView` does not render a browsable map or a position marker without an active route**, even with location, network and the my-location layer enabled. Every environmental explanation is excluded by measurement. Both the green and the blue are background fills, not map content.

### What remains — a design change, not a tweak

The E5 fallback is the remaining option and is **pre-authorised by the approved Phase 1 design**: use `com.amap.api.maps.MapView` + `MyLocationStyle` for the **idle/browse** state, and reserve `AMapNaviView` for Phase 2 when a route exists. Both classes are confirmed present in the artifact, and `AMap` exposes `setMyLocationEnabled`, `setMyLocationStyle`, `setMyLocationType`, `getMyLocation` and `setLocationSource`.

Costs to accept before doing it:
1. Two map surfaces swapped by `NavigationPhase`.
2. Phase 1 acceptance **row 2 evidence is invalidated** and must be re-gathered against the new surface.
3. `AMap.setLocationSource(...)` may still be required if the SDK expects the app to feed fixes rather than sourcing them — untested, and deliberately not pre-built.

**Not yet attempted, and worth considering first:** it is possible this is not a defect at all but Phase 2 work — `AMapNaviView` may render correctly the moment `startNavi()` succeeds. If the product tolerates an empty map until navigation starts, P5 reduces to "no idle map", which the reference UI does not obviously require. That is a product judgement, not an engineering one.

---

## ✅ P5 — PRODUCT-OWNER DECISION 2026-09-16: Option 1 accepted

> "Our product requirement is that the embedded map works correctly **during active navigation**; an interactive/browsable idle map is not currently required."

**Status of each item, as decided:**

| Item | Status |
| --- | --- |
| Location permission (`ACCESS_FINE_LOCATION` never requested) | **FIXED AND VERIFIED** — the app now prompts for 精准定位 itself; verified on a clean state with the ADB grant revoked first |
| `AMapNavi.startGPS()` never called | **FIXED AND VERIFIED** — `amap_gps startGPS=true`; SDK went from `locations = 0` to `42` |
| `WAKE_LOCK` missing from the manifest | **FIXED AND VERIFIED** — SecurityException count `0` (was present every launch) |
| Idle `AMapNaviView` renders blank | **ACCEPTED for Phase 1. NOT a blocker.** No further work |

**Explicit instructions attached to this decision — do not violate them:**

- **Do NOT implement the `MapView` fallback** (E5). It stays unbuilt.
- **Do NOT experiment further with `AMap.setLocationSource(...)`** unless Phase 2 navigation itself fails to render.
- **Stop spending cycles on the idle map.**

### Re-test trigger — when Phase 2 starts a real route

P5 is **reopened only** if, with an active route, any of these fail. Reopen with that evidence attached:

1. Map tiles render.
2. Vehicle / location marker appears.
3. Route line draws.
4. Camera behaviour is correct (follows, zooms, centres).

Until then P5 is closed for Phase 1 purposes. The blank idle map is a known, accepted behaviour — **not** an unexplained defect, and not something a future agent should "fix" on sight.

**Original analysis, kept for the record:**
**Reported:** 2026-09-16 by the product owner, immediately after the Phase 1 build was installed
**Severity:** **High** — this is a map-first product ([ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md)) whose map does not know where the driver is. Route planning in Phase 2 cannot start without an origin.

### Symptom

> "Embedded Amap opens, but it does not locate the phone's current position/address."

Confirmed independently: the Amap surface renders (attribution, its own 全览 control, vector buildings) but shows **no position marker and no road network** — an almost empty pale canvas.

### Root cause 1 — `ACCESS_FINE_LOCATION` is declared but never requested

*(evidence)* `MainActivity.kt:91–92` requests **only** `ACCESS_COARSE_LOCATION`:

```kotlin
if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
}
```

`ACCESS_FINE_LOCATION` **is** in the manifest, so the SDK believes it may use GNSS, and on a clean install throws:

```
java.lang.SecurityException: Neither user 10263 nor current process has android.permission.ACCESS_FINE_LOCATION.
  at android.location.LocationManager.addNmeaListener
  at com.amap.api.navi.core.r.d(InternalLocation.java:5)
  at com.amap.api.navi.AMapNavi.getInstance → AmapNaviViewHost.kt:19
```

### Root cause 2 — nothing ever starts location, and this is the larger one

*(evidence)* `AmapNaviViewHost` does exactly three things: privacy compliance, `AMapNavi.getInstance(...)`, `AMapNaviView(context)`. There is **no** view listener, **no** `AMapNaviViewOptions`, and **no location start of any kind**.

`AMapNaviView` is a **navigation** view — it renders the vehicle along a *calculated route*. With no route and nothing requesting a fix, it has nothing to centre on.

**Proof that root cause 1 alone does not explain it:** `ACCESS_FINE_LOCATION` was granted manually over ADB and the map looked **byte-for-byte the same** (screenshots 104,222 B and 105,251 B). The permission unblocked the NMEA callback; it did not make anything ask for a position.

This is the **E5 risk** recorded as *(unverified)* in [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md), now confirmed: *"whether `AMapNaviView` renders a useful base map with no active route — if blank with a valid key, fall back to `com.amap.api.maps.MapView`."*

### Related defect found in the same pass — `WAKE_LOCK` is missing

*(evidence)* Absent from **both** our source manifest and the merged manifest, so neither we nor the SDK declares it. It throws on every launch and `pm grant` cannot fix it — it needs a manifest line.

```
java.lang.SecurityException: Neither user 10263 nor current process has android.permission.WAKE_LOCK.
```

### ✅ Determined 2026-09-16 — read out of the resolved artifact, not guessed

`javap` against `navi-3dmap-location-search-11.2.100_3dmap11.2.100_loc11.2.100_sea9.8.1.jar` (5847 entries) *(evidence)*:

| API | Present | Relevance |
| --- | --- | --- |
| `AMapNavi.startGPS()`, `startGPS(long)`, `startGPS(long, int)` | ✅ | **The missing call.** `AmapNaviViewHost` never invokes it |
| `AMapNavi.stopGPS()`, `isGpsReady()` | ✅ | `isGpsReady()` gives an objective pass/fail for the fix |
| `AMapNaviView.setAMapNaviViewListener(...)` | ✅ | Never set |
| `AMapNaviView.getViewOptions()` / `setViewOptions(...)` / `setNaviMode(int)` / `displayOverview()` | ✅ | Centring and mode control |
| `AMapNaviViewOptions.setLayoutVisible(boolean)`, `setPointToCenter(double,double)`, `setZoom`, `setTilt`, `setAutoChangeZoom` | ✅ | `setLayoutVisible(false)` would also remove Amap's own 全览/退出 controls that currently collide with our bottom bar |
| `AMapLocationClient`, `AMapLocationClientOption`, `AMapLocationListener` | ✅ | Available if the Navi path proves insufficient |
| `MapView`, `AMap`, `MyLocationStyle` | ✅ | The E5 fallback remains available |

**So the fix is the Navi SDK's own `startGPS()`, not a fallback to `MapView`.** Noted for later, not part of this fix: `AMapNavi.setUseInnerVoice(boolean, boolean)` is present, which is the navigation-voice control [SPEC-002](SPECS/SPEC-002-navigation-uplink-mute.md) Phase 7 needs — the signal that was unobservable from outside the process is directly controllable from inside it.

### Acceptance

**L5** — on a **fresh install** (no ADB-granted permissions): the app requests `ACCESS_FINE_LOCATION` itself, no `SecurityException` appears for location or `WAKE_LOCK`, and the map centres on the device's actual position with a visible position marker.

---

## P6 — Wake word produces no response

**Status:** **NOT A DEFECT — the feature is unimplemented.** Recorded so it is not re-diagnosed as a bug.
**Reported:** 2026-09-16 by the product owner
**Severity:** Medium — it is wanted work, but nothing is broken

### Symptom

> "Saying the configured wake word produces no response."

### Why this is the expected behaviour of absent code

There is **no wake-word integration in the installed APK**. Four independent reasons, each sufficient on its own:

1. **The detector targets the wrong SDK.** `wake/IflytekWakeWordDetector.kt` is written against **AIKit** (`com.iflytek.aikit.core`, `AiHelper`, ability `e867a88f2`). The product owner **replaced AIKit with the MSC SDK** the same day — see [FINDINGS-2026-09-16-iflytek-msc-sdk.md](SPECS/FINDINGS-2026-09-16-iflytek-msc-sdk.md).
2. **The MSC SDK is not in the project at all.** `Msc.jar`, `libmsc.so`, `libw_ivw.so` and `<APPID>.jet` exist only in `D:\桌面\SDK`; nothing has been staged into `app/`.
3. **The feature flag defaults to false** *(evidence: `WakeWordSettings.kt` — `prefs.getBoolean(KEY_ENABLED, false)`)*, and no UI sets it.
4. **Nothing calls the entry point.** No code path invokes `VoiceSessionGateway.start()` from a detector. The gateway seam exists and is proven working (row 8), but the wake word is not attached to it.

### What must happen, in order

1. **Settle the ADR-006 premise first.** It chose "share our existing PCM stream" *because AIKit accepted external audio*. For MSC this is **unverified**: `AUDIO_SOURCE = "-1"` and `writeAudio(...)` both appear in `WakeDemo.java:146,155` but are **commented out**, though the identical pattern works for dictation in `IatDemo.java:140,154`. A ~30-minute device experiment decides whether ADR-006 stands or is superseded. **Do not write the integration before this returns** — the same discipline that stopped SPEC-002 being built on a false premise.
2. Swap the SDK: stage `Msc.jar` + both `.so` files + `assets/ivw/wakeword.jet` (renamed from `<APPID>.jet` so the credential never appears in a filename); remove the obsolete `AIKit.aar` and AIKit assets (also reclaims ~7 MB of the 204 MB APK).
3. Rewrite the detector against `VoiceWakeuper` / `WakeuperListener`, using `IVW_THRESHOLD`, `IVW_SST=wakeup`, `KEEP_ALIVE=1`, `IVW_NET_MODE=0` (offline; modes 1–2 upload audio), and **not** setting `IVW_AUDIO_PATH` (the demo writes a minute of microphone audio to disk).
4. Wire detection → `VoiceSessionGateway.start()` — the seam proven on device in Phase 1 row 8.

**The credential blocker is gone.** MSC needs only an APPID, which is already supplied and matches the `.jet` filename. Every earlier status line saying "blocked on iFlytek `apiKey`/`apiSecret`" is superseded.

### Acceptance

**L5** — saying 你好小诺 with the app idle produces a detection callback, `VoiceSessionGateway.start()` is invoked, and the log shows `state=CONNECTING` then `LISTENING` with the map overlay reflecting it. Plus a sustained false-accept measurement with navigation guidance and music playing.

---

### Migration to MSC completed 2026-09-16 (compile-level) — with one architectural gap found on review

`IflytekWakeWordDetector` rewritten against `com.iflytek.cloud.VoiceWakeuper`; AIKit fully removed (AAR, assets, and every source reference); `IflytekResourceInstaller` deleted as dead (MSC reads the resource from assets via `ResourceUtil`); `WakeWordController` added as a single process-lifetime owner; detection calls `VoiceSessionGateway.start()`. Tests **214 distinct, 0 failures** (was 210; `isCompleteWhenAllThreeFieldsAreNonBlank` → `isCompleteWhenAppIdIsNonBlank`, since MSC completeness is appId-only).

Privacy settings verified **by reading the parameter block**, not by grep — a grep matched the explanatory comment and looked like a violation:

| Parameter | Value | Why it matters |
| --- | --- | --- |
| `IVW_NET_MODE` | `"0"` | Modes 1–2 enable 闭环优化, which **uploads audio**. Offline per ADR-001 |
| `IVW_AUDIO_PATH` | **unset** | The vendor demo writes the last minute of microphone audio to disk |
| `AUDIO_FORMAT` | **unset** | Same |
| `IVW_RES_PATH` | `ivw/wakeword.jet` | Constant, **not** rebuilt from the APPID, so the credential never appears in a filename |

### ⚠️ GAP — the detector will own the microphone, contradicting ADR-006

`writeFrame(pcm, first, last)` is implemented and maps correctly to `writeAudio(pcm, 0, pcm.size)`, but **nothing in the app calls it**. `PcmAudioCapture` was deliberately not modified, so no PCM is fed in, and `startListening(listener)` makes the MSC engine **open the microphone itself**.

That is precisely the mic-ownership conflict [ADR-006](DECISIONS/ADR-006-wake-word-aikit-shared-capture.md) exists to avoid, and it will collide with `PcmAudioCapture` the moment a voice session runs — two owners of one `AudioRecord`, a defect class this project has already paid for twice.

Not a fault of the migration: the task scoped `PcmAudioCapture` as untouchable, and the fan-out is genuinely separate work. But **P6 is not complete until capture is shared**, and the API premise for doing so is already confirmed (`VoiceWakeuper.writeAudio` exists, same signature as the dictation engine the vendor demo exercises successfully).

**Also to revisit:** `WakeWordController.bind()` is invoked from `DebugVoiceLog.init` — a workaround for `MainActivity` being off-limits. It functions, but a wake-word owner does not belong in a logging initialiser.

**Still unverified (L5):** MSC initialisation on device, real detection of 你好小诺, entry into the session pipeline, repeated cycles without leaked or duplicated listeners, whether `KEEP_ALIVE=1` truly continues listening, and the false-accept rate.

---

## P7 — Voice session dies right after a spoken `navigate_to`

**Status:** ✅ RESOLVED 2026-09-16 — verified by human voice on device (product owner confirmed)
**Found:** 2026-09-16, first human-voice run of wake word → 「帮我导航到拱北口岸」

```
tool=navigate_to ... ✓
error=BAIDU_FLEX_API_REJECTED Invalid value: 0.750000. Cannot update a session's turn
      detection threshold while input audio is in progress, current value is 0.620000.
state=ERROR  (x25)
```

**Cause:** the P3 Option C VAD mitigation resent `session.update` with a raised threshold the
moment `NavigationState` flipped. Baidu Flex refuses that while audio is flowing (always, in a
live session), and the client treated the refusal as fatal. Every later utterance was lost —
which is also why saying a new destination "did not change the list".

**Fix:**
1. No mid-session `session.update`; the navigation threshold applies at the next connect.
2. A refused `session.update` ("cannot update a session") is no longer a session-fatal error.
3. `navigate_to` now returns `awaiting_route_selection_on_screen`, and its description tells the
   model not to claim navigation has started and to call again when the driver changes destination.
4. `requestDestination` while already `NAVIGATING` stops the old guidance first.

Device (ADB): 万达 → 4 candidates, then 拱北口岸 → list replaced with 5. Wake engine re-initialised
after install (`MSPLogin ret:0`, `sessionBegin ErrCode:0`, `recording`).

**Human-voice verification 2026-09-16 21:13–21:14** (0 `state=ERROR` lines in the session):

```
wake_detection
你: 到万达。            → navigate_to ✓ → 4 candidates → 小诺: 请在屏幕上选择路线。
tap → route 13 → nav_active_route meters=20269 → navigation started
你: 不是这个换成拱北口岸。 → navigate_to ✓ → 5 candidates (list replaced while navigating)
你: 来威力商场          → navigate_to ✓ → 5 candidates
tap → route 13 → nav_active_route meters=16009 → navigation started
```

---

## P8 — 「帮我播放音乐」 ignored during navigation; on-screen history grows without bound

**Status:** FIXED 2026-09-16 — tests + build + installed; **human-voice re-test pending**

- **Music:** device log 21:18:30 shows `你: 帮我播放音乐。` → `THINKING` → `LISTENING` with **no tool call and no
  reply**. The execution path was proven fine the same minute (`control_music play` over ADB during active
  navigation → `MediaPlayer state:started`). Cause: persona rule 4 says 「导航进行中…保持安静」, which the model
  applied to commands too. The persona is stored on the device, so the fix went into `FLEX_TOOL_RULE`, which is
  appended to every session: "quiet" means no chit-chat; every command must still call its tool.
- **History:** the transcript bubble appended every line forever. It now keeps only the latest exchange
  (2 lines) — `recentTranscript`, unit-tested.
