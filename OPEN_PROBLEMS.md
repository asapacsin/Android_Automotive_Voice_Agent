# Open problems

> Active defects blocking `CURRENT_MILESTONE.md`. Reported by the product owner from real device use on 2026-09-15.
> Each stays here until it is fixed **and** verified. Do not close one on a green build.

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

> **Narrowed 2026-09-18 by the product owner — see [P18](#p18--the-map-opens-at-an-old-position-after-a-restart).**
> The map now renders and is draggable, so the "blank idle map" this decision accepted no longer
> describes what is on screen. The owner asked for the camera to find the driver on every cold
> start, and that is now implemented with an explicit `moveCamera` — **still no `MapView` fallback
> and still no `setLocationSource`**, so the two prohibitions above stand as written.

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

## P46 — Assistant-spoken guidance (SPEC-018) gives up on the first prompt and never comes back

**Status:** OPEN, found 2026-10-03 while recording the demo video. PC emulator nova_api34, build 9696ce5 (`claude/10-3`), Azure voice on, 助手播报导航 on. Short drive to 长隆海洋王国 started by voice, emulator speed 60 km/h. **Owner 2026-10-15:** offered A/B options (P46 wait ~1.2 s for her reply; D-9 drop an unchecked reply; P48 code-written confirmations) and chose to leave this alone for now - current behaviour stays; not to be reopened without the owner.

**Seen:** the driver hears no guidance from 小诺. Every prompt goes to Amap's own voice.

### Evidence (NovaVoice log, timings only)

| Time (s after 开始导航) | Event |
| --- | --- |
| 0.0 | `nav_navigation_started`; Gemini opens a turn for the confirmation 「导航已为您开始」 (`gemini_turn_open`) |
| 1.4 | `gemini_prompt_refused id=g1 reason=turn_open`, then `guidance_route prompt=g1 to=amap reason=SEND_FAILED`, then `nav_guidance_fallback_tts accepted=true` |
| 1.4 → 40 | g2 … g36: every one `to=amap reason=AMAP_SPEAKING waited_ms=0`. No prompt is sent to the assistant again |

### Cause

1. `GuidanceRelay.pump()` treats a `turn_open` refusal as a final failure, and hands the prompt to Amap at once. The relay's deadline would have allowed it to wait about 1 s for the confirmation turn to close.
2. Once one prompt is with Amap, `amapReason()` returns `AMAP_SPEAKING` while the Amap bracket is open or the backlog is not empty. On the emulator, prompts arrive every ~3 s, so the bracket never closes and the relay never gets the voice back.

### Fix direction (owner: `GuidanceRelay`)

- Treat `turn_open` as "not yet": keep the prompt queued and retry when the turn closes, within `deadlineMs`, as for the other wait reasons.
- Let a later prompt return to the assistant once Amap finishes the one in progress, instead of following the backlog for the rest of the drive.
- Add a unit test with a confirmation turn open when g1 arrives. Then re-run the drive on the emulator and check for `guidance_route … to=assistant reason=SENT`.

The toggle is experimental and off by default, so a driver on default settings is not affected. The demo video was recorded with it off.

## P47 — Steady background noise: Gemini never detects the start of speech

**Status:** gate deadlock FIXED 2026-10-15 (JVM + live harness, see below); behaviour in a real noisy cabin still open. Found 2026-10-08 while recording the new-functions demo. PC emulator nova_api34, build e0ba94f (`claude/10-3`), Gemini VAD start LOW / end HIGH (4f857e9), host audio bridge as the microphone.

**Seen:** a fan-like noise bed (brown + pink noise, steady) was mixed into the uplink from 3 s before the driver's line 「你喜欢什么颜色」 (synthetic speech, about −23 dBFS RMS). Gemini logged **no `ACTIVITY_START`** and the driver got no reply.

| Noise RMS (before the app's input gain) | `ACTIVITY_START` | Reply |
| --- | --- | --- |
| −32 dBFS | none | none |
| −40 dBFS | none | none |
| −46 dBFS | none | none |
| off (same clip, same session setup) | yes, 1.9 s later `ACTIVITY_END` | yes |

The app did capture the speech: `session_diag peak` rose to about 10 900 during the line, with the noise at about 700, `gated=false`, and no uplink drops in that window. So the audio reached the client; the server VAD did not open a turn.

**Hypothesis:** START_SENSITIVITY_LOW sets its threshold relative to a background that is not silent, so a steady floor raises it above the speech. Before 4f857e9 the start sensitivity was higher. The P45 change fixed turns that never *ended* in room noise; this one never *starts*.

**Next:**
1. Repeat with a real microphone and a real fan (DEMO_10-3 MIC-1, `HUMAN_REQUIRED`). Synthetic noise on the emulator is software-path evidence only.
2. If it reproduces, compare start sensitivity HIGH with end HIGH on the same clips. Then decide between server VAD tuning and client-side activity signals (`activityStart`/`activityEnd` with automatic detection off) — that choice is an ADR.

### Root cause found and fixed, 2026-10-15 (`claude/10-15`, planner)

**Not the server.** `tools/gemini-live-probe/vad_noise_probe.py` streams the same recipe (fan-like
brown+pink bed from t=0, a driver line at 3 s, speech -23 dBFS) straight to the API with the app's
VAD settings: `ACTIVITY_START` 0.26-0.52 s after the onset at noise -32 and -40 dBFS, START LOW and
HIGH, on gemini-3.1-flash-live-preview and gemini-3.8-live (6 sessions); she replied every time.

**Our gate.** `SpeechUplinkGate` learned the noise floor only from frames it already judged quiet,
starting from RMS 80 (threshold ~176). A bed already present at start (-32 dBFS = RMS ~820) made
every frame "voiced": the gate opened on the fan and never closed. The JVM live harness now takes a
continuous noise loop (`NOVA_LIVE_NOISE_PCM`, under every frame; `NOVA_LIVE_CLIPS` for the -23 dBFS
clips): tryout at -32 dBFS, old gate - **0 `UPLINK_GATE_SEGMENT` in the whole session**, 11/19 turns
opened. **Fix:** minimum statistics (R. Martin, IEEE TSAP 2001; the quietest frame of the last
1.5 s is noise, since speech has gaps) raises the floor when even the quietest recent frame is
"voiced"; trusted only after a full window so 1.2 s of voicing at session start stays speech
(`SpeechUplinkGateTest.aClosedSegmentReportsItsShape`). `SpeechUplinkGateNoiseBedTest` (5; 3 fail
with the fix removed).

**Live, same harness, Gemini 3.1 (free), one run each:** quiet tryout 19/19 heard (19 segments, as
before; u_can 3.0 s and u_call 8.3 s were model time after the tool call - identical gate timing to
the passing run). Noise -40 dBFS (17 dB SNR): old gate 16 turns / 3 lines unanswered, new gate 18 /
1. Noise -32 dBFS (9 dB SNR, raw noise): segments 0 -> 16-17, turns 11 -> 14-16; lines still missed
and replies slower in noise (the gate's hangover keeps the uplink open longer).

**Still open:** speech 9-17 dB over *unsuppressed* noise is harsher than the phone, which records
with `VOICE_COMMUNICATION` and the platform `NoiseSuppressor` before the gate; slower replies in noise
and the remaining misses are for the cabin test with a real mic (MIC-CABIN-001, DEMO_10-3 MIC-1).

### P45 addendum 3, 2026-10-08: the stall and the warm-up on build e0ba94f

- **The stall again, with a clean uplink:** after a sleep and wake, a 1.2 s line got `ACTIVITY_START` 1.5 s after the wake but `ACTIVITY_END` only **12.0 s** later. Nothing followed the line but digital silence, and the host bridge reported no drops in that window. The driver heard the reply about 12 s after he stopped.
- **The warm Azure connection after a ≥ 60 s sleep (574e0ff) is inconsistent.** Over 4 tries, the first `azure_tts_first_audio` was 1161, 424, 362 and 1512 ms. The demo target is ≤ 1000 ms.
- **Emulator caveat:** in takes longer than about a minute, the host bridge itself began dropping uplink audio (`dropped_ms` rising by up to 87 s). Turns lost that way are not app evidence, and they are excluded above.

## P48 — The assistant voice lags: slow first reply, gaps between clauses, chat replies held until generation ends

**Status:** OPEN, 2026-10-08. Owner requirement: [docs/DEMO_REQUIREMENTS.md](docs/DEMO_REQUIREMENTS.md) — no laggy AI speech. PC emulator nova_api34, build e0ba94f, Azure voice on. **Owner 2026-10-15:** offered A/B options (P46 wait ~1.2 s for her reply; D-9 drop an unchecked reply; P48 code-written confirmations) and chose to leave this alone for now - current behaviour stays; not to be reopened without the owner.

| Symptom | Measured | Cause | Owner |
| --- | --- | --- | --- |
| First reply after app start or sleep: Azure first audio 1.2–1.9 s, and 4–5 s from the end of speech to her voice | 5 takes | `AzureSpeechVoice.warmUp()` sends an anonymous HEAD. That warms TLS at most, never the synthesis path, and nothing logs whether it ran | `AzureSpeechVoice` |
| Gaps of 0.1–0.36 s mid-reply | 18 joins in 3 takes | `AssistantVoiceRevoicer` starts clause N+1's request only after clause N has fully downloaded (`previous?.join()` before `synthesize`). A short first clause (「好的。」, ~0.6 s of audio) runs out before the next clause's first audio (~0.33–0.45 s after its request) | `AssistantVoiceRevoicer` |
| Chat replies held 1.0–1.65 s | `TURN_HOLD UNCLASSIFIED_CLAIM` → `TURN_RELEASE no_claim_made` ≈ Gemini `gen_ms` | The UNCLASSIFIED_CLAIM rule is settled only at response end (`DriverTurn.onAssistantText` returns Wait). The safety rule is correct; the speed-up is SPEC-014's clause release, which needs a design decision | `DriverTurn` (SPEC-014) |

The first two are being fixed now. The third is left for the SPEC-014 decision: it is safety-relevant (I-1), so it is not changed inside a latency fix.

**Fix (rows 1–2), 2026-10-08:** `AzureSpeechVoice.warmUp()` now sends one real one-character synthesis request (same headers, configured voice; audio discarded; at most once per 15 s; logs `azure_warm ms= code=`), and `AssistantVoiceRevoicer` warms the voice when a session opens. Clause N+1 is now requested while clause N is fetched or played (at most two requests in flight per reply), buffered and emitted strictly in order; `assistant_voice_gap_ms` logs the silence before each later clause. Unit-tested only; device measurement pending. Row 3 stays OPEN.

### P48 measurement after dcc609a + d0c66cf + 7673d72 (2026-10-08, emulator)

Measured from the driver's **last audible word**, not the end of the clip; edge-tts pads about 1 s.

- **Fixed:**
  - The first Azure clause took 1.7–1.9 s and now takes 0.26–0.6 s (`azure_warm` at session start).
  - `assistant_voice_gap_ms` is 0 at every clause join (25 joins).
  - 你都能帮我干嘛 is no longer dropped.
- **Still slow:** 2.6–4.2 s from the last word to her voice. After `ACTIVITY_END`, the time goes to:

  | Part | Time | Owner |
  | --- | --- | --- |
  | Gemini end-of-speech detection | 1.0–2.0 s after the last word (`silenceDurationMs` 500 cut 0.45 s, but was reverted: unvalidated cut-off risk) | server VAD |
  | Gemini's first message | 0–1.5 s | server |
  | Gemini's second turn after a tool call | 0.7–1.2 s | server |
  | **Claim-gate hold until generation completes** | 0.9–1.8 s on every chat reply | `DriverTurn` (row 3) |
  | Azure first audio | 0.26–0.49 s | done |

  Row 3 (SPEC-014 clause release) is now the largest piece the app controls. It needs the owner's decision: ADR-011 Revision 2 deferred it.

## P51 — 「导航去横琴镇」 + 「第一个」 chose the place the car was already at

**Found 2026-10-12, before any local take of the commute demo, by running the app's own place search against the real Amap Web API at the demo origin** (`DemoCommuteAmapLiveTest`). The JVM harness simulates the route, so its passing takes never showed it.

**Symptom (measured, real Amap):** at 横琴·澳门青年创业谷 the nearby search for 横琴镇 returned 横琴澳门青年创业谷 itself first, then four more places, all within 650 m. Amap's driving route there is 26 m long. 「第一个」 would end the drive at once, and c3–c5 (蚊子, 歌, 前面堵不堵) would be spoken with no route. The same ranking gave a bus stop for 横琴口岸 and a hotel and a gym for 横琴总部大厦.

**Root cause:** `AmapPoiClient` asked `place/around` with `sortrule=distance`. Amap then ranks the nearest *fuzzy* keyword match first, so nearby places that only share a word come first. Research: Amap's 周边搜索 documentation (lbs.amap.com/api/webservice/guide/api/search) lists `sortrule` as `distance` (the default) or `weight` (综合排序, relevance and distance together). Nothing else was found on this behaviour.

**Fix:** named places use `AmapPoiClient.NEARBY_SORT_RULE = "weight"`. A request for the nearest place (「附近的麦当劳」, 「离我最近的加油站」: `DestinationQuery.isNearbyRequest`, wired into `LiveDestinationCandidateSource` for the first time) still sends `distance` (`NEAREST_SORT_RULE`). This follows the independent review. Measured at the same origin with `weight`:

| Query | First result | Distance |
| --- | --- | --- |
| 横琴镇 | 横琴镇人民政府 | 2533 m (route 2855 m, about 257 s at the emulator's 40 km/h) |
| 横琴口岸 | 珠海横琴口岸 | |
| 横琴总部大厦 | 横琴总部大厦 | |
| 麦当劳 | the 896 m branch | |
| 加油站 | stations at 1.0–1.9 km | |

`DemoCommuteAmapLiveTest` fails on the old ranking (632 m) and passes on the new one.

**Resolved — GCJ-02 search origin** (decided by decider (xhigh), 2026-10-12: SDK conversion moved to AmapCoordinates and used by the search):
- The nearby search sent the platform's WGS-84 fix where Amap expects GCJ-02: 618 m off at the demo origin (measured, Amap `assistant/coordinate/convert` coordsys=gps), so near-me ranking and the distances spoken were off by that much.
- Fix: `nav/amap/AmapCoordinates.toGcj02` is the one SDK `CoordinateConverter` call; `AmapNaviViewHost` and `LiveDestinationCandidateSource` both use it. Saved places are never converted. The log line `nav_search_origin converted=` carries a boolean only.
- Research: Amap Android 坐标转换, lbs.amap.com/api/android-sdk/guide/computing-equipment/coordinate-transformation; the Web `place/around` API has no coordsys parameter, so it takes GCJ-02.
- Live (2026-10-12, `NOVA_LIVE_AMAP=1 DemoCommuteAmapLiveTest`, GCJ origin): 2/2 passed; `c_pick -> 横琴镇人民政府 2045 m (of 5)`.

**Still open:**
- A brand named *without* a near-me word (「导航去星巴克」) now ranks by relevance, so a slightly farther branch may come first (星巴克: third result 9.8 km).
- `NAV-SEARCH-001`'s device evidence was taken under nearest-first ranking. Named-place searches need a device re-check.

**Before a local take:** `python tools/demo/recorder/record_demo.py --preflight` checks all of this on the emulator with no voice session open:
- the Qwen config code;
- the app's Amap Web key;
- the GPS fix;
- the app's own search for 横琴镇 (at least 1.7 km away);
- a Navi SDK route to it.

## P52 — A used-up Qwen free tier was reported as QWEN_PROTOCOL_ERROR

**Found 2026-10-12, take 9** (cloud JVM, qwen3.5-omni-plus-realtime, after its free tier ran out). The session failed 57 ms after `session.created` as `QWEN_PROTOCOL_ERROR`, so neither the driver nor we could see that the quota was the reason. The raw event was not logged (I-8), so its exact shape is unknown.

**Research:** Alibaba Model Studio's error-code page (alibabacloud.com/help/en/model-studio/error-code) documents two relevant errors:
- `403 AllocationQuota.FreeTierOnly`: "The free tier of the model has been exhausted…", seen with "use free tier only" on;
- `400 Arrearage`: "Access denied, please make sure your account is in good standing".

DashScope's plain error body is `{code, message, request_id}`, with no `type`.

**Root causes, each reproduced in `QwenOmniClientTest.aQuotaRefusalOfTheSessionIsReportedAsQuotaNeverAsAProtocolError` with a mock server:**
1. A type-less frame reached the shared parser, which throws on a missing type. This gave `QWEN_PROTOCOL_ERROR`, the take 9 symptom.
2. `Arrearage` contains "access denied", and the key markers were checked first. This gave `QWEN_AUTH_FAILED`, which wrongly blames the API key.
3. An `error` sent as a bare string was read as empty. This gave `QWEN_PROVIDER_ERROR`.

**Fix (`QwenOmniDialect`):**
- A type-less frame that carries `code` or `error` is parsed as an error.
- `freetieronly` and `arrearage` count as quota, and the quota markers are checked before the key markers.
- `errorOf` reads a string `error` as its message.

A type-less frame with neither field is still a protocol error. All four documented shapes now end as `QWEN_QUOTA_EXHAUSTED` (`ErrorClass.RATE_LIMIT`).

**Still open:** not yet seen live. The next time a model's quota runs out, the log line `state=ERROR err=QWEN_QUOTA_EXHAUSTED` confirms it.

## P53 — c_nav waits for the real place search

**Found 2026-10-12, Gemini rehearsals** (cloud JVM, the app's real Amap place search for the first time). c_nav (「导航去横琴镇」) took 6.0 s and 4.7 s against its 3.5 s budget, because the search took 2.52 s and 2.25 s before any reply. Qwen takes 6 and 8, with an instant (simulated) search, answered c_nav in 2.78 s and 2.89 s, so the search may take about 0.6 s at most.

**Cause:**
- Every `AmapPoiClient` built its own `OkHttpClient`, so the search never reused the connection the session-start warm-up opened; a cold TLS handshake through a proxy costs about 2.9 s.
- The proxy path itself (the PC routes Amap through Clash).

**Fix:**
- `AmapPoiClient`'s default `http` is one process-wide client (`SHARED_HTTP`, same 4 s timeouts); `AmapPoiClientTest` proves two default clients share one connection.
- `nav_resolve_candidates` logs `ms=` (a duration only, I-8); `check_req.py` prints it beside c_nav for information.
- Debug `preflight` times the search (`preflight_search ... ms=`) and repeats it warm (`preflight_search_warm ms=`); `record_demo.py --preflight` fails above 0.5 s and names the fix: route `restapi.amap.com` direct (Clash `DOMAIN-SUFFIX,amap.com,DIRECT`).
- The JVM live demo fetches the real 横琴镇 result before the session opens and answers c_nav from it; the candidates are real, the network time is out of the take.

**Decision:** decided by decider (xhigh) 2026-10-12. Option A (an early spoken line) was rejected because it reverses the owner's SPEC-020 timing (no cue before 7 s).

**Research:** the Gemini Live tools documentation says function calling on 3.1 Flash Live is synchronous only, so the model cannot speak while the tool runs. Vapi and LiveKit speak an acknowledgement for slow tools.

**Contingent owner question** (only if the PC still measures > 0.5 s with Amap direct and warm):
1. For `navigate_to` only, one fixed line 「好，我找一下。」 about 2 s after the driver stops speaking, through the existing cue path. This changes SPEC-020's table; the decider recommends it.
2. `check_req.py` times a navigation turn from when the candidates arrive. This changes DEMO_REQUIREMENTS §2.
3. Record c_nav only on a network where the search fits.

**Still open:** the device and PC measurement with the rebuilt debug app (`preflight_search ms=`), and the next Gemini rehearsal.

## P54 — 「附近有加油站吗」 while parked was answered 「没在导航，查不了」

**Found 2026-10-13, first tryout run** (`NOVA_LIVE_SCENES=tryout`, Gemini, docs/DEMO_TRYOUT.md): a first-time driver's 「附近有加油站吗」 with no route. The model called `query_live_info` `along_route` (its description lists 「路上有加油站吗」), got `NOT_NAVIGATING`, whose advice said 「请用一句话如实说现在没有在导航」, and answered 「都说了没在导航，查不了。」 The claim gate dropped a first reply, two corrections followed, and the last one was still running when the driver said 「算了，不去了」, which then took 11.3 s.

**Fix:** the existing nearby search (`navigate_to` → `DestinationQuery.isNearbyRequest`, distance-sorted) is named for this case in three places: `navigate_to`'s description (「附近有加油站吗」 with no route → destination=附近的加油站), `query_live_info`'s along_route line (no route → not along_route), and the `NOT_NAVIGATING` advice (a nearby-place question → call navigate_to instead of saying 没在导航). SPEC-011's failure row is updated. Research: Gemini function-calling best practice is a specific description saying when to use each tool (ai.google.dev/gemini-api/docs/function-calling, "Best practices"); nothing more specific found for overlapping tools.

**Evidence:** second tryout run, u_gas `navigate_to✓` 2.5 s, u_cancel 2.4 s (was 11.3 s); 18/19 lines PASS. Golden tool declarations updated; `:app:testDebugUnitTest` 1843/0, `:behavior-test:test` 131/0.

## P55 — 10-13 bug: 「嗲一点」 changes neither the style on screen nor how she speaks

**Reported by the owner 2026-10-13** after watching the demo video: after 「能不能说话再稍微嗲一点」 the
simulated car panel's 说话风格 stayed `default` and her replies did not sound different; the demo
showed no before/after and no switch back. Owner's requirement: solve it, and hand over a demo that
shows the function changing on screen and her delivery changing, before and after, and back.

**Evidence (recorded runs, c_sweet and the turns after it):**

| Run | `set_speaking_style` called? | After it |
| --- | --- | --- |
| Qwen take 3 | no | no reply, then plain 「座椅升高了一档」 |
| Qwen take 6 | no — said 「可以，那我就稍微甜一点说话」, nothing ran, nothing corrected it | plain |
| Gemini commute rehearsal 2026-10-12 | yes | 「哼，真是麻烦…」 — tsundere wording, not sweet |
| Gemini drive rehearsal 2026-10-13 | yes | plain 「座椅已经升高了一档」 |

Measured on the Gemini audio (median F0 / F0 spread / pace): default turns ≈ 205 Hz, 7 st, 7.5 chars/s;
the two turns right after the switch 245 Hz, 12+ st, 5.9 chars/s; from the third turn back to ≈ 210 Hz.

**Causes:**
1. A style request with no tool call is not corrected (the claim gate covers car actions, not style).
2. The style enters the session instructions only when a session connects
   (`PersonaProfiles.compose` in `OpenAiRealtimeClient` / `GeminiLiveClient` `instructionsWithContext`).
   Qwen keeps one session for the whole drive, so a mid-drive change lives only in one tool result and
   fades within a turn or two.
3. The tool result and the voice-style line say 「声音不变」, telling an end-to-end voice model not to
   change how it sounds.

**Status:** open. The persistence design is with the decider (AGENTS.md hard rule); a style scene
(`NOVA_LIVE_SCENES=style`: same question before, after 嗲一点, two turns later, after 正常一点, with
measured pitch/range/pace on the video and a grader) is being built to prove the fix.

**Sub-fix (no tool call), 2026-10-13:** `UtteranceIntentResolver` now maps a speaking-style request
(嗲/撒娇/傲娇, or a style adjective next to 说话/语气/口气/腔调; bare 「甜一点」 does not count) to
`speech.speaking_style`, and `ActionClaimGuard` nudges once per utterance, naming `set_speaking_style`,
when such a request is answered without the call and without an honest refusal (takes 6/7/8 ended in a
question, which `declines()` alone would have let through). Decided by the decider agent. Known cost: on
qwen3.5-omni-plus a nudge can provoke the 50002 crash (docs/DEMO_COMMUTE.md:137-146). JVM evidence only.

**Status update 2026-10-13 (causes 2 and 3; decided by the decider):** fixed in code, JVM-proven,
not yet heard on a live session. Cause 2: after a tool result, when `SpeakingStyleState.current`
differs from the style last delivered (`DeliveredStyle`), the client re-sends the setup instructions
once at an idle point - Qwen as an instructions-only `session.update` before the tool reply's
`response.create` (a `session.*` refusal is logged and non-fatal), Gemini as a model-role
`clientContent` with `turnComplete=false` (SPEC-021 B10, FUZZY_DRIVING_ARCHITECTURE §7). Cause 3: the
style lines now say 音色不变 but the delivery must audibly change (sweet: 语速稍慢、句尾轻轻上扬、
「嘛」「呀」「啦」); the tool's instruction is derived from `PersonaProfiles.toneLines`. Cause 1: the
sub-fix above. Sources: DashScope client-events page, LiveKit `update_instructions` (openai and
google plugins), Google AI forum thread 83359.
- Review fixes 2026-10-13 (offline-proved); open live questions offline tests cannot answer: DashScope's real refusal shape for a mid-session instructions-only `session.update`; Gemini G-1d for a model-role `turnComplete=false` turn; whether a resumed Gemini session re-reads `systemInstruction`.

**Live evidence 2026-10-13 (style scene, `NOVA_LIVE_SCENES=style`, graded by `tools/demo/live/style_check.py`):**

| Run | Code | Model | s_mood2 / s_seat / s_mood3 vs s_mood1 (F0) | Result |
| --- | --- | --- | --- | --- |
| before | before the fix | gemini-3.1-flash-live | +6 % / +13 % / +1 % — faded | FAIL s_mood2, s_mood3 |
| fix1 | re-delivery | gemini-3.1-flash-live | +1 % / +5 % / +3 %; sweet wording held, pace −25..−34 % | FAIL — 3.1 does not raise pitch on request |
| fix38 | re-delivery | gemini-3.8-live | +35 % / +4 % / +33 % | PASS (after the F0-alone rule) |
| final | + review fixes | gemini-3.8-live | −3 % / −2 % / −5 %; sweet wording held | FAIL — delivery not reliable with the first wording |
| final2 | + sweet voice line asks for a higher, softer pitch | gemini-3.8-live | +5 % (+2.8 st, −22 % pace) / +13 % / +13 % (+2.2 st) | **PASS** |

In every run with the fix: the panel switched sweet→default, `style_redelivered` was logged once per switch, no session error. Holding the style is fixed; how strongly her delivery changes varies run to run (one PASS with the current wording, not yet a rate). The sweet voice line now asks for 音调比平时明显高一点、声音更软更轻 (planner, 2026-10-13, after the "final" run). Video: `demo-videos/style_before_after_2026-10-13.mp4` (before, then after). Open: whether Ayano on Qwen changes delivery at all (owner's go for one short session).

**Research 2026-10-13 (rule 6), for the Ayano switch-back test:** Alibaba's Qwen-Omni page
(alibabacloud.com/help/en/model-studio/qwen-omni, fetched 2026-10-13) documents for the Qwen3.5-Omni
series: "Audio control: Supports adjusting volume, speaking rate, and emotion through instructions" —
the mechanism the styles use. Not stated: the same for qwen3.8 realtime, a separate pitch control, or
that it holds for a cloned voice. The voice-cloning page (qwen-omni-voice-cloning) advises recording the
sample in the "target emotion (such as friendly or serious)", so a clone may keep the sample's manner,
and names a "qwen3.5-omni talker system instruct mode". Gemini, for comparison: "affective dialog" is
"not supported in Gemini 3.1 Flash Live" (ai.google.dev live-guide). The switch-back test (owner says
when; Gemini first, Ayano last) is the style scene on the Ayano model, graded by style_check.py against
her measured default (take8: ~220-235 Hz, pace 7-11 chars/s).

## P56 — 「帮我把天窗打开」 opened all four windows

**Status:** OPEN - live confirmation pending. The sunroof guard is in the code (JUnit `ToolCallGuardSunroofTest` 7/0, reviewer PASS 2026-10-13), but no live run has exercised it yet (tryout run 4: the model did not try a window).

**Found 2026-10-13, third tryout run** (`NOVA_LIVE_SCENES=tryout`, gemini-3.1-flash-live-preview, after the P55 fix; docs/DEMO_TRYOUT.md): the model heard 「帮我把天窗打开」 correctly and called `control_window` {action=open, window=all}; the dispatcher ran it and all four windows went to 100 %. The two earlier tryout runs refused this line, so it is model variance — and a prompt rule (`FLEX_TOOL_RULE` 「没有对应工具的请求（例如调音量、开天窗）要用一句话说明暂时不支持」) is not enforcement (AGENTS.md rule 4). SPEC-006 F-7 already required "refuse; do not execute a *nearby* supported action", but only the music case (`MEDIA_LIBRARY_UNSUPPORTED`) was enforced.

**Fix:** `ToolCallGuards.sunroofOnWindowTool`, in the dispatcher's existing guard chain **and** in the `ComfortServer` step route (so 「有蚊子，把天窗关上」 via `run_scenario{mosquito}` cannot open a window either): a `control_window` call whose driver words name one of `UtteranceIntentResolver`'s cues for `sunroof_doors_lights_wipers` (天窗/天幕/后备箱/车门/车灯/雨刷 - the resolver owns the vocabulary; 天幕 was added there) and, after removing those cues, no window cue and no other 窗 is refused `UNSUPPORTED_BODY_PART` before anything moves; the advice (`ToolFailureAdvice`) says it is a part 小诺 cannot control, nothing ran, the windows did not move, and asks for one honest sentence. It only judges words of the turn being answered (`currentEpoch >= capabilityEpoch`): when the call arrives before this turn's transcript, the text is the previous turn's, and the call is let through rather than guessed at. On Gemini the input transcript is flushed when the turn opens, before the call is dispatched (`GeminiLiveClient.openTurn`). Research: no web search tool in this session; followed the repository's own precedent for the same failure class (SPEC-006 F-7 / SPEC-017 `unsupportedMedia`).

**The first version (f0d3a27) would not have matched the observed run.** Gemini 3.1 input transcripts are character-spaced since 2026-10-12 - the run heard 「帮 我 把 天 窗 打 开 。」, so `"天窗" in said` was false. Measured over Gemini 3.1 runs since 10-12: every heard line spaced (tryout 19/19 in each of 3 runs, commute rehearsals 12/12); Gemini 3.8: 0 spaced; Qwen takes 1-8: 0 spaced. The same spacing made `DriverTurn.classify` / `UtteranceIntentResolver` treat action lines as conversation on 3.1 since 10-12 (u_window, u_sunroof logged TURN_HOLD kind=CONVERSATION). Fixed once, at the adapter: `GeminiLiveClient.flushInput` runs the transcript through `TranscriptText.normalize` (whitespace touching CJK or CJK/full-width punctuation removed, spaces between Latin/digit tokens kept) before both the pipeline and `UserTranscript`, so the UI shows unspaced text too. The guard reads its text through the same helper in case a spaced line ever reaches DriverContext raw. The Qwen path is unchanged (its transcripts are not spaced).

**Residual:** 「天窗不用开，开左前的就行」 and 「把车门玻璃降一点」 are refused (no 窗 left after removing 天窗/车门; it fails safe: nothing moves and she says it is not supported) - accepted. Gemini 3.1 keeps numbers whole (「调 到 22 度」 in all three tryout runs), so joining CJK-adjacent spaces cannot split a number. Qwen ordering: when the transcript arrives after the call, the guard lets the call through (fail-open); unverified live.

**Evidence:** JUnit XML on the P56 worktree: `ToolCallGuardSunroofTest` 7/0 (real dispatcher: ok=false, no window moved; the live spaced line refused normalised and raw; 天幕, 车门, 后备箱 refused; spaced 「把 我 这 边 的 窗 户 开 一 点 。」 and 「天窗和车窗都打开」 pass; a 天窗 from the previous turn does not block this turn's window call), `ComfortScenariosTest` 16/0 (mosquito with 天窗 leaves every window shut, step error UNSUPPORTED_BODY_PART; stuffy with 天窗 still powers the climate and raises the fan; 「有蚊子」 alone still opens to 50 %), `GeminiLiveClientTest` 72/0 (a spaced inputTranscription reaches UserTranscript unspaced and classifies ACTION), `UtteranceIntentResolverTest` 4/0, `TranscriptTextTest` 3/0. Mutations: removing the flushInput normalisation fails the client test; removing the scenario-route guard fails the comfort test. Live re-run on Gemini: see DEMO-TRYOUT-001.

**Review:** first version REVISE (spaced transcripts, second vocabulary, scenario path); revision PASS (independent reviewer, 2026-10-13). One run of `aSunroofNamedWithAScenarioNeverOpensAWindow` failed once right after the executor reverted its own mutation of the scenario-route guard (same assertion the mutation breaks); not reproduced in 10 further runs in two separate build directories (planner 5× `--rerun-tasks`, reviewer 4× plus a full run); the code path is synchronous with no shared state (reviewer). Read as stale build output, not a flake in the guard.

**Live (DEMO-TRYOUT-001, run 4, gemini-3.1-flash-live-preview at d160a3e):** 19/19 after the goodbye rule; u_sunroof refused with no tool call (the model did not try a window this time, so the guard was not exercised live - its proof is the JVM tests); none of the 19 heard lines reached the app spaced; u_hot, u_temp and u_window held as `kind=ACTION` (they were `CONVERSATION` while transcripts were spaced), u_can as `CAPABILITY_HELP`.

## P62 — Turn-by-turn guidance is not spoken in her voice (demo used a male voice)

**Status:** OPEN 2026-10-16. Owner test 10-16 ([docs/owner-tests/test-10-16.md](docs/owner-tests/test-10-16.md)):
"the amap voice is male and not the same as gemini".

**Measured:** in the demo the guidance track is not Amap's audio at all. The emulator runs with
`-no-audio`, so the host bridge re-voices each guidance sentence with edge-tts
`zh-CN-YunxiNeural` (a male voice, chosen in `host_audio_bridge.py` "so it is not her"). In the app,
`AmapGuidanceVoice` leaves Amap's inner voice on, because the SPEC-018 relay (ADR-014: guidance
spoken by the assistant, Amap only as the fallback) is behind the developer toggle, off by default,
and its step-0 measurements (`GUIDANCE-EMU-001`, G-1 … G-1f) have never run.

**Plan:** run the demo with the relay on (Gemini declares `verbatimPromptSpeech`), which is
ADR-014's decided design and doubles as `GUIDANCE-EMU-001`'s first run on the GitHub emulator; the
host voices only what the relay hands to Amap's fallback, in a female voice; measure G-1 (time to
her audio) and the fallback count. Limit to state in the hand-over: the product session is Qwen,
which does not declare `verbatimPromptSpeech` (SPEC-018 2026-10-10 note), so on Qwen guidance stays
in Amap's own voice until that is solved.

**Run 1, 2026-10-16 (nav demo run 38843628215 at e9f8535, relay on, 3000 ms measurement deadline,
`ci/nav-demo runs/run_20261016_0326_e9f8535`):** she spoke no guidance. 242 `guidance_route` lines for
about 24 real prompts: 1 SEND_FAILED (a driver turn was open at navigation start), then 61
AMAP_SPEAKING and 197 `nav_guidance_fallback_tts accepted=true`, and after a Gemini close
(`code=1008 reason_class=other`, ~3 min into the drive, while asleep, never reconnected) 180
NOT_ACTIVE. Cause of the flood: with the inner voice off, each fallback `playTTS` came back through
`onGetNavigationText` as new guidance, so the relay fed itself; each fallback is bracketed for the
mic gate, so the driver's climate/music/ETA lines were dropped and she went to sleep. Measured on the
way (GUIDANCE-EMU-001): G-1b+ - `playTTS` with the inner voice off fired no `TTSPlayListener`
start (`nav_guidance_play_start` 0 in the whole run), so the relay's length-estimate bracket is the
gate; G-1f - `onGetNavigationText` does fire with the inner voice off, including for our own
`playTTS`. **Fix:** `GuidanceRelay` drops the echo of a sentence it handed to Amap within 10 s, once
(`guidance_echo_dropped`; GuidanceRelayTest +2). Searched for Amap SDK reports of custom
`playTTS` text reappearing in `onGetNavigationText`: no web search from this container, nothing
found in the SDK's Javadoc copies under the scratchpad; the echo is inferred from the counts.

**Run 2 (nav demo run 38847890114 at 128494c, echo fix in):** she spoke the guidance. 23 prompts
went to her and all 23 were claimed and judged MATCH (her words agreed with Amap's on directions and
distances); first audio 626-1371 ms after the text (median 754 ms). 18 went to Amap's fallback, 15 of
them after a Gemini 1008 close (fixed in fe8933e: no longer terminal). Video:
`demo-videos/navigation_demo_her_voice_guidance_2026-10-16.mp4`. The voice is Gemini's, not Ayano.

**Run 3 (nav demo run 38849988938 at 876e20c, 120 km/h to arrival):** 47 prompts spoken by her, 43
MATCH, 1 MISMATCH_DIRECTION and 1 MISMATCH_NUMBER (both re-spoken by Amap), then FIDELITY_STRIKES
handed 2 more to Amap (strike limit 2 per navigation); 12 echoes dropped live
(`guidance_echo_dropped`), 12 fallbacks in total. First audio 571-1610 ms (3 of 47 over 1200 ms).
The drive reached 十字门站; a 1008 close (reason_class=session) no longer ended the session.
Video: `demo-videos/navigation_demo_arrival_her_voice_2026-10-16.mp4`.

**Status:** FIXED for the demo on Gemini, verified live (runs 2 and 3). Limit: the product session
(Qwen) does not declare `verbatimPromptSpeech`, so the shipped app keeps Amap's voice for guidance
until a Qwen relay is solved; GUIDANCE-EMU-001 still lacks G-1b audibility, G-1c, G-1d and a
network-cut repeat.

## P64 — The emulator demo runs at about 6.5 frames per second

**Status:** IMPROVED, rest is a test-rig limit (2026-10-16): 6.5 → 10.6-10.7 fps. Originally OPEN 2026-10-16. Owner test 10-16: "the simulation is laggy".

**Measured:** `ffprobe` on the demo video: 2861 frames in 440 s, average 6.5 fps. The emulator has
2 cores and 2 GB; the GitHub runner has no GPU, so the map is drawn by SwiftShader on the CPU; the
Amap SDK ships ARM-only native libraries (`arm64-v8a`, `armeabi-v7a` in the AAR), so on the x86_64
emulator the whole navigation engine and map renderer run under ARM translation; the in-guest
`screenrecord` encodes 1080x2340 on the same CPUs.

**Plan:** give the emulator the runner's 4 cores and 4 GB, record at half resolution, report the
measured fps in the run summary. Limit to state: a phone or car head unit renders the same app on a
GPU with native ARM code; the emulator cannot match that, so the demo frame rate is a test-rig
limit, not the app's.


**Run 1 (38843628215):** 4 cores / 4 GB and recording at 576x1248 raised the average from 6.5 to 8.4
fps (4542 frames in 540 s). Better, still not smooth: the remaining cost is ARM translation and
software rendering, which this rig cannot remove.

**Run 2 (38847890114):** 10.7 fps (5780 frames in 540 s); the echo loop no longer burns CPU.

## P65 — With her voice on the guidance, a talkative route puts her to sleep between driver lines

**Status:** OPEN 2026-10-16. Found by nav demo run 38849988938 (relay on, 120 km/h): after the
climate reply she went to 休眠中 and the music, ETA and gas lines got no answer (tools: climate only).
**Measured:** 47 guidance prompts in ~10 min (about one every 12 s); the microphone is gated while
guidance plays (P3, SPEC-012), and `ListeningLifecycle` sleeps after 30 s with no driver speech
(`SLEEP_AFTER_INACTIVITY_MS`). The scripted driver speaks every 20 s regardless of guidance, so two
consecutive lines landing in guidance windows make 30 s of "silence". At 90 km/h (run 38847890114,
23 prompts) climate, music and live info all worked. **Open question:** whether guidance time should
pause the inactivity countdown (a lifecycle rule; SPEC-012/SPEC-018 own it), or the demo driver
should wait for guidance to finish as a real driver mostly does.

**Measured from the run's own tracks (decider, 2026-10-16):** the climate answer ended at ~83 s (the
countdown's last restart); the music line (95.75-96.6 s) fell entirely inside her guidance audio
(95.35-98.25 s; guidance began 0.4 s before the driver, so SPEC-012 B6 R0 could not protect it); 聆听中
→ 休眠中 at 112.9 s, exactly 30 s after the restart; ETA, gas and music-off then arrived while asleep.
One gated line is enough (the next one comes ~35 s after the last restart). Her audio plus the tail
covered 31% of the drive at 120 km/h, 23% at 90.

**Decision (decider, 2026-10-16): B, test rig only.** `docs/LISTENING_LIFECYCLE.md` already says the
countdown is not extended by navigation guidance (P17's device criterion), and SPEC-018 B6 says
guidance never changes the listening state; pausing the countdown (A) would be a lifecycle contract
change for the owner, would not save the gated line, and would only have moved the deadline 0.65 s
past the next line. The demo driver now waits, at most 10 s, until the app's uplink is open and she is
quiet (`host_audio_bridge.py --wait-quiet`, read from `speech_arbiter out=uplink decision=`), never
repeats a line and never wakes her; the summary records the waits, listening transitions by reason,
the uplink-closed count and `droppedGuidance`. **Limit to state:** a driver who starts speaking while a
guidance prompt plays is not heard (P3 / ADR-014 §4, an accepted cost), and after 30 s with no heard
request she sleeps (P17); the wake word resumes her. Research: no usable web results from this
container (search engines blocked or irrelevant); from general knowledge, consumer follow-up windows
count from the end of the assistant's own reply and are not stretched by other audio.

**Amendment (decider, 2026-10-16), after runs 38853123513 and 38860693378:** the music line collided
deterministically with a guidance prompt whose audio began 0.1 s after the line started (95.8 / 95.9 s);
every wait was 0 ms because the uplink was still open while the prompt was in flight (SENT → audio
648-1526 ms). The rig now also waits while a prompt is in flight: `guidance_route … to=assistant
reason=SENT` marks it, the next uplink CLOSED or 3.5 s clears it (`host_audio_bridge.py --selftest`).
The scripted driver reads an app log line a real driver cannot see. Product limit unchanged: a driver
who starts in the ~0.3-0.5 s before her guidance audio, before SPEC-012 R0 can arm, is cut off (P3 /
ADR-014 §4, accepted). Proved first by a short piece run (`[navpiece]`, first six lines), before any
full demo (owner rule 2026-10-16).

**Piece check passed (nav demo piece run 38869906819 at 1df8c66, first six lines, relay on, 90 km/h):**
navigation started; climate, music and ETA all answered with their tools while she spoke 7 guidance
prompts (7/7 MATCH); no line cut. The deterministic 95.8 s collision did not recur (the session-ready
wait shifted the timeline), so the in-flight wait is proved offline (`--selftest`), not yet live.

## P66 — A mumble right after a barge-in and a music stop called `set_speech_output`

**Status:** OPEN 2026-10-17. Fixed at JVM level, merged 2026-10-17 (`ToolCallGuards.speechOutputNotRequested`,
`ToolCallGuardSpeechOutputTest`; reviewer PASS after one REVISE round, which added 住嘴/住口/闭上嘴/别啰嗦了/
不许说话/噓 and tightened resume so 「你可以再说一遍吗」 is refused). Closes after u_mumble passes on the
next DEMO-TRYOUT-001 Gemini run. Accepted false allows (err toward the model acting): 安静 anywhere,
「别念错」, 「不准说谎」; accepted false refusals (harmless, the lifecycle resumes on any meaningful
transcript): 「可以讲话了」, 「你可以说啦」.
Found by the tryout re-run (cloud JVM harness at 323fc29, app code =
87d5287, gemini-3.1-flash-live-preview, simulated car; DEMO-TRYOUT-001). Line u_mumble 「嗯……那个……」
(890 ms), which by docs/DEMO_TRYOUT.md should produce no tool and no claim. It came 11 s after
u_barge 「等一下」 and u_mstop. Log (timings only): `TURN_HOLD reason=UNCLASSIFIED_CLAIM
kind=CONVERSATION`, then `gemini_tool_call tool=set_speech_output args=valid`, dispatched `result=✓`,
then she spoke (spoke=true, 1.9 s). The log keeps argument keys only, so the mode is not known. The
19/19 takes of 2026-10-14..16 had u_mumble PASS.
**Reading:** the model acted on a non-request, probably carrying over the barge-in context. Nothing
in the dispatcher asks whether the driver's own words asked for a speech-output change. This is
the I-1 / "model's words are not evidence" family: a tool that changes how she talks runs on a line
with no request. **Next:** a JVM characterisation of what `set_speech_output` does on a
CONVERSATION-classified turn, and whether its guard should, like other repeat-sensitive tools,
require a request in the transcript. Not decided; no re-run to chase variance.
**Decided 2026-10-17 (decider, option A):** a request-required guard on the
`ToolCallGuards.vagueExitWhileNavigating` pattern: `ToolCallGuards.speechOutputNotRequested`,
chained in `AndroidToolDispatcher.guard` after `vagueExitWhileNavigating`, refuses
`set_speech_output` (either mode) with `SPEECH_OUTPUT_NOT_REQUESTED` unless this turn's words ask
her to stop or resume talking (`ListeningIntent.classify == SHUT_UP`, or a bounded stop/resume
grammar kept beside `isExplicitEnd`). Stale or missing words (call before its transcript) pass,
like the other words-based guards; logged `guard_speech_output decision=…` with codes and epochs
only. Why it matters beyond the chip: a ✓ `set_speech_output` result sets `DriverTurn.proven` and
releases claims as `execution_proved` (DriverTurn.kt:104-108), and `silent` enters SILENT_WAIT then
SLEEP. Rejected: B (characterise only: leaves I-4 open), C (refuse all tools on CONVERSATION turns:
the dispatcher cannot see the kind, and 「停止说话」 classifies CONVERSATION), fail-closed on late
transcripts (dropped a real 「停止说话」 on 2026-09-28), a prompt rule (AGENTS rule 4).
Research: Gemini function-calling best practices, "Validate function calls before executing"
(ai.google.dev/gemini-api/docs/function-calling); Live API transcription has no guaranteed
ordering (SPEC-010). Nothing specific found on spurious calls on filler speech.
Residual: a mumble whose call beats its transcript still runs (count `decision=stale_words`); any
tool call, even a refused one, still releases a chat reply as `tool_called` (DriverTurn.kt:733-734),
which is a separate question. Build waits on the executor worktree vendor files.
Tests (new `ToolCallGuardSpeechOutputTest`, modelled on `ToolCallGuardVagueExitTest`): u_mumble refused
for both modes through the dispatcher (no executor call, no chip); 嗯/那个/等一下/音乐先关了吧/还要开多久/
说话能不能嗲一点 refused; 闭嘴/别说了/不要说了/你先别说了/停止说话/停止說話/安静点/别出声/别吵了/少说两句/
shut up/stop talking run with `true`; 继续说/接着说/可以说话了/繼續說 run with `false`; every
`ListeningIntent` SHUT_UP phrase allowed; call before transcript, null context, epoch 0 pass; other
tools untouched; advice says nothing was done. Docs on landing: LISTENING_LIFECYCLE.md:143-144,
CAPABILITIES.md:30, ARCHITECTURE.md:64, TEST_MATRIX DEMO-TRYOUT-001 `related`.

## P67 — 「空调」 can operate the AC twice: the local path and the model's call with a different action

**REOPENED 2026-10-19:** the AFF-AC re-measure failed on the real-vendor Voice checks run 39198920117
at adfa384 (`ci/voice-evidence runs/run_20261019_0515_adfa384`, device_skeletons.txt): the model's
`gemini_tool_call tool=control_climate` arrived before `affordance_match id=climate`, the local
`screen_action=voice_climate ok=true` ran, and then the model's `tool=control_climate args=[action]
result=✓` ran too - no DUPLICATE_IN_TURN. The onset was server-only (`gemini_driver_turn_fallback
source=voice_activity`). Working hypothesis: the two sides claim under different epochs
(`capabilityEpoch()` read at different moments, or from different counters), which the
same-epoch `CapabilityClaimGroupTest` cannot catch. **Not reproduced (2026-10-19, exec/p67-r):** a JVM
chain with the real GeminiLiveClient (scripted socket), the DriverContext its pipeline installs,
ScreenAffordanceRunner and AndroidToolDispatcher, in the observed order (and with the transcript after
the call), executes control_climate once: both sides claim under turn.epoch (the server fallback runs
beginDriverTurn -> onSpeechStarted(turn.epoch); openTurn flushes the transcript under the same epoch).
So the epoch hypothesis is wrong for that chain. Remaining candidates: something starts a new driver
turn between the two claims in the app wiring (revoicer / provider local speech activity), the
context being null or a different instance at one claim, or the model call bypassing guard(). Next: a
text-free `claim src=... epoch=... ctx=... ok=...` log at both claim points, then one AFF-AC
re-measure on Gemini.

**Cause found 2026-10-19 (P67-LOG claim lines, Voice checks run 39285245437 at c6b2790):** the model's
second call was `control_climate get_state`, a read: `claim src=LOCAL group=power epoch=3` then
`claim src=MODEL group=get_state epoch=3`, same context; the read ran because it is not the power group.
**Fix merged 2026-10-19 (P67-FIX):** a model `get_state` on a tool the local path claimed in the same
epoch is refused `DUPLICATE_IN_TURN` (SPEC-010 A7); a model-first read never blocks the local path.
JVM: app unit 2070/0, behavior-test PASS, reviewer PASS. Closes after AFF-AC passes on the next Voice checks run.

**Status:** OPEN 2026-10-17. Fixed at JVM level, merged 2026-10-17: `claimCapability` keys on the
control (`CapabilityClaimGroupTest` 7/7; app debug 1958/0, behavior-test 132/0 in the planner's
rerun; reviewer PASS, unverified E2E). It closes after the AFF-AC re-measure on the next voice-checks
run (AFFORDANCE-DEVICE-001). Reviewer note: the model call in run 39005653831 logged `args=[action]`
with no `value`, so it was the opposite power action (or get_state), which is exactly what is now refused.
Found by voice checks run 39005653831 at aee3ff0 (app code 87d5287, Gemini
debug override), AFF-AC under the corrected oracle (AFFORDANCE-DEVICE-001).
**Seen (text-free skeleton):** `gemini_tool_call tool=control_climate` (before the transcript), then
`affordance_match id=climate` and `screen_action=voice_climate ok=true` (the local path ran), then
`tool=control_climate args=[action] result=✓`: the model's call **executed**. Her reply was spoken
(`spoke=true`). In the two earlier runs today the same call was refused `DUPLICATE_IN_TURN`.
**Cause:** `DriverContext.claimCapability` keys on `epoch|tool|action`. The local plan claims
`power_on` or `power_off` from the current state; when the model sends a different action for the
same control (the log keeps only argument keys, so most likely the opposite power action, or a
temperature change), its key differs and both run. SPEC-010 B4's own rule is "one execution per turn,
whichever side is first"; the `(tool, action)` key is the mechanism falling short of it.
**Fix direction (planner, within B4):** claim a *control*, not an exact action. Normalise the action
into a capability group before keying (climate `power_on`/`power_off` -> `power`; music
`play`/`stop`/`toggle` -> `playback`; the map recentre has one action), so a second claim on the same
control in the same turn is refused `DUPLICATE_IN_TURN`. Leave temperature adjustments keyed by action,
because "空调" never plans one. A JVM test drives the local claim then a model call with the
opposite action and expects `DUPLICATE_IN_TURN`; AFF-AC on the emulator re-measures it. Update the
B4 text to say "same control".

## Archived (closed) problems

- P1 — 小诺 talks over Amap navigation guidance — ✅ RESOLVED 2026-09-16 — → docs/OPEN_PROBLEMS_ARCHIVE.md
- P2 — 「关闭音乐」 still does not stop the music by voice — ✅ RESOLVED 2026-09-16 — → docs/OPEN_PROBLEMS_ARCHIVE.md
- P3 — Amap's guidance is transcribed as the driver's speech — FIXED 2026-09-19 (reconciled; the → docs/OPEN_PROBLEMS_ARCHIVE.md
- P4 — No `stop_navigation` tool — SUPERSEDED 2026-09-16 by [ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md) → docs/OPEN_PROBLEMS_ARCHIVE.md
- P5 — The embedded map never locates the phone — CLOSED 2026-09-19 — reconciled, → docs/OPEN_PROBLEMS_ARCHIVE.md
- P6 — Wake word produces no response — RESOLVED 2026-09-19. 「你好小诺」 fires → docs/OPEN_PROBLEMS_ARCHIVE.md
- P7 — Voice session dies right after a spoken `navigate_to` — ✅ RESOLVED 2026-09-16 — → docs/OPEN_PROBLEMS_ARCHIVE.md
- P8 — 「帮我播放音乐」 ignored during navigation; on-screen history grows without bound — FIXED 2026-09-16 — tests → docs/OPEN_PROBLEMS_ARCHIVE.md
- P9 — Quiet speech got no response (Baidu VAD floor) — FIXED 2026-09-17 — speech → docs/OPEN_PROBLEMS_ARCHIVE.md
- P10 — Model answered one turn late in long sessions — FIXED 2026-09-17 — speech → docs/OPEN_PROBLEMS_ARCHIVE.md
- P11 — Navigation could not be ended by voice — FIXED 2026-09-17 — speech → docs/OPEN_PROBLEMS_ARCHIVE.md
- P12 — Camera stayed open in the background — FIXED 2026-09-17 — device → docs/OPEN_PROBLEMS_ARCHIVE.md
- P13 — Navigation gave no spoken guidance — FIXED 2026-09-17 — device → docs/OPEN_PROBLEMS_ARCHIVE.md
- P14 — Camera question put the session into ERROR — FIXED 2026-09-17 — unit → docs/OPEN_PROBLEMS_ARCHIVE.md
- P15 — Replies claiming an action that never ran — FIXED 2026-09-18, reconciled 2026-09-19 → docs/OPEN_PROBLEMS_ARCHIVE.md
- P16 — Destination and route could only be chosen by tapping — FIXED 2026-09-17 — unit/client → docs/OPEN_PROBLEMS_ARCHIVE.md
- P17 — The microphone streamed to Baidu for the whole session — FIXED 2026-09-17 — unit → docs/OPEN_PROBLEMS_ARCHIVE.md
- P18 — The map opens at an old position after a restart — FIXED 2026-09-18 — verified → docs/OPEN_PROBLEMS_ARCHIVE.md
- P19 — A session that lost the network never came back — FIXED 2026-09-18 — unit → docs/OPEN_PROBLEMS_ARCHIVE.md
- P20 — Environmental noise became a spoken conversational turn — FIXED 2026-09-18 — unit/client → docs/OPEN_PROBLEMS_ARCHIVE.md
- P21 — A no-tool request could be answered with a claim, or an invention — FIXED 2026-09-18 — unit/client → docs/OPEN_PROBLEMS_ARCHIVE.md
- P22 — A song we cannot play would have been "played" — FIXED 2026-09-19 — unit → docs/OPEN_PROBLEMS_ARCHIVE.md
- P23 — A misheard driver could be told the car had acted — FIXED and device-verified 2026-09-19. → docs/OPEN_PROBLEMS_ARCHIVE.md
- P24 — The same correction was sent twice — FIXED 2026-09-20; device-verified. Regression: → docs/OPEN_PROBLEMS_ARCHIVE.md
- P25 — A mis-transcription let a song we cannot play be "played" — FIXED and device-verified 2026-09-20. → docs/OPEN_PROBLEMS_ARCHIVE.md
- P26 — After 「开始导航」 the screen stayed a generic 2D map — ✅ RESOLVED 2026-09-20 — → docs/OPEN_PROBLEMS_ARCHIVE.md
- P27 — After navigation starts, 小诺 says 「没听清」 to her own voice — FIXED 2026-09-20; device [ECHO-001](TESTMATRIX.yaml) → docs/OPEN_PROBLEMS_ARCHIVE.md
- P28 — 「你能做什么」 / 「你能干啥」 is answered as if it were noise — FIXED 2026-09-22 — [HELP-001](TESTMATRIX.yaml) → docs/OPEN_PROBLEMS_ARCHIVE.md
- P29 — Real-drive-only navigation QA is too slow to repeat — FIXED 2026-09-21 — [NAV-SIM-QA-001](TESTMATRIX.yaml) → docs/OPEN_PROBLEMS_ARCHIVE.md
- P30 — Assistant voice sounds like an older woman; prefer a younger cute style — FIXED 2026-09-21 — owner → docs/OPEN_PROBLEMS_ARCHIVE.md
- P31 — Navigation reroutes near the destination instead of arriving — FIXED 2026-09-21 — emulator → docs/OPEN_PROBLEMS_ARCHIVE.md
- P32 — 「导航已开始」 said after a local destination pick, while routes still wait for a choice — FIXED 2026-09-27 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P33 — Assistant overlay drawn over Amap's turn card; speed chip over Amap's bottom bar — FIXED 2026-09-28 — emulator → docs/OPEN_PROBLEMS_ARCHIVE.md
- P34 — 「播放音乐」 plays the music, then 小诺 says 没成功 — FIXED 2026-09-28 — unit-tested → docs/OPEN_PROBLEMS_ARCHIVE.md
- P35 — An `error` event after 「闭嘴」 — FIXED 2026-09-28 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P36 — A chat reply dropped as an "unverified claim"; the driver heard nothing — FIXED 2026-09-28 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P37 — Host audio bridge: bursty uplink, and the script died when the app stopped reading — FIXED 2026-09-28 — measured → docs/OPEN_PROBLEMS_ARCHIVE.md
- P38 — `response_busy` then `internal error` ended the session in DEEP_IDLE — FIXED 2026-09-28 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P39 — After a local pick, every later command got an empty reply (no conversation reset any more) — FIXED 2026-09-28 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P40 — 「开始导航」 after 「最快的」: 小诺 said 没有指定目的地，无法开始导航 while guiding — FIXED 2026-09-28 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P41 — R0 protected an utterance that had ended when the session went to sleep — FIXED 2026-09-28 — unit-tested; → docs/OPEN_PROBLEMS_ARCHIVE.md
- P44 — The CONFIG card stays on the map after the setting it asks for is fixed — FIXED 2026-10-01 — unit-tested → docs/OPEN_PROBLEMS_ARCHIVE.md
- P45 — A conversation reply was held for 9.4 s and never spoken; the next question got no reply — FIXED at L1 2026-10-02 → docs/OPEN_PROBLEMS_ARCHIVE.md
- P49 — With the local gate on, Gemini never ends the turn, and the emulator never ran the gate — FIXED on the emulator → docs/OPEN_PROBLEMS_ARCHIVE.md
- P50 — Qwen Maia on the emulator: replies held whole, a reset after every tool turn, stalls, and false claims about heard sentences — FIXED on the emulator → docs/OPEN_PROBLEMS_ARCHIVE.md
- P57 — 「把我这边的窗户开一点」 opened all four windows — FIXED on the JVM → docs/OPEN_PROBLEMS_ARCHIVE.md
- P58 — The navigation map stays north-up while the car turns (car-up not applied on screen) — CLOSED 2026-10-15 - not → docs/OPEN_PROBLEMS_ARCHIVE.md
- P59 — After 「算了」 cancels the route choice, the route stays drawn on the map — FIXED 2026-10-19 (device-confirmed, run → docs/OPEN_PROBLEMS_ARCHIVE.md
- P60 — After a dropped claim, the correction ordered an action the driver had forbidden — FIXED 2026-10-15 (JVM), live → docs/OPEN_PROBLEMS_ARCHIVE.md
- P61 — Mid-drive 「附近有加油站吗」 replaced the active route with a gas-station list — FIXED 2026-10-16, both causes → docs/OPEN_PROBLEMS_ARCHIVE.md
- P63 — No spoken warning when the car is over the speed limit — FIXED 2026-10-16, verified on → docs/OPEN_PROBLEMS_ARCHIVE.md
