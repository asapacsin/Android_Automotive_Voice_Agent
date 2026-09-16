# SPEC-002 — Stop sending Amap's guidance to Baidu

Status: **Approach superseded by [ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md) (2026-09-16). Option C (VAD raise) shipped and remains valid; the precise gate becomes v2 Phase 7 using the embedded SDK's own guidance-speech state, which is the signal Phase 1 proved unobservable from outside.** The measurements below stay true for the external-app model and are kept as evidence.
Raised: 2026-09-16 (from P3, found in the logs of the successful M1 voice test)
Problem: [P3](../OPEN_PROBLEMS.md)

## The problem

During navigation the microphone picks up Amap's spoken guidance, and we **upload it to Baidu**, which transcribes it as if the driver had spoken:

```
transcript=你: 走右侧车道即将在红绿灯右转，我的fuck。
transcript=小诺: 没听清，再说一遍。
```

The navigation mute rule (`NavigationState`) silences 小诺's *replies*, so the driver never hears the consequence. But the audio still goes up the WebSocket, the model still generates responses, and the quota is still spent — on every drive, continuously.

Costs: wasted Baidu quota, wasted battery and bandwidth, and a real risk that a garbled transcript eventually resembles a command and fires a tool.

## The trap — why the obvious fix is wrong

The obvious fix is "gate the microphone while `NavigationState.navigating` is true."

**That would destroy the product.** Navigation is precisely when the driver most needs to talk hands-free. Gating the whole drive means 小诺 cannot hear 「关闭音乐」 or anything else until navigation ends — and nothing currently detects when navigation ends (see P4), so it could stay deaf indefinitely.

The real requirement is narrower:

> Stop the uplink **only while Amap is actually speaking**, not for the whole drive.

Amap's guidance is intermittent — a few seconds at a time, with long gaps. Gating exactly those windows costs the driver almost nothing and removes almost all of the waste.

## What already exists (reuse, do not rebuild)

- `AndroidMicrophonePort.gated` — drops frames while true, keeps the `AudioRecord` open so resuming is instant. Already used to stop 小诺 hearing itself, with a 350 ms release tail.
- `NavigationState` — `navigating`, `allowConfirmation()`, `shouldMuteSpeech()`, `reset()`.
- `AudioFocusController.onFocusChanged` — single owner, already wired to duck/pause/resume playback.

The mechanism is in place. What is missing is a **signal for "another app is speaking right now."**

## Options

### Option A — observe other apps' playback ✅ **CHOSEN 2026-09-16**

`AudioManager.registerAudioPlaybackCallback` reports active `AudioPlaybackConfiguration`s. Gate the uplink whenever a configuration with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` is `state:started`, and ungate after a short tail (~400 ms).

- **Pro:** precise, event-driven, no polling, independent of whether we hold focus, works regardless of which app navigates.
- **Con:** **must be verified** — Android restricts what one app may observe of another's playback, and details have tightened across releases. It is possible only our own configurations are visible, or that `usage` is reported but the owning package is not (we do not need the package — usage alone is enough).
- **Verification before building:** on the test device, register the callback while Amap navigates and log every configuration seen. If `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` from another uid appears, Option A is viable. A ~30-minute experiment; it gates implementation, not the decision (see Decision below).

### Option B — hold audio focus for the session and gate on focus loss (fallback only — see Decision)

Amap requests transient focus when it speaks, so we would receive `AUDIOFOCUS_LOSS_TRANSIENT` / `..._CAN_DUCK` and could gate the uplink until `AUDIOFOCUS_GAIN`.

- **Pro:** uses a callback we already handle; no new visibility concerns.
- **Con, and it is serious:** we deliberately moved to **per-utterance** focus in task20. We only hold focus while 小诺 is speaking, so while merely listening we receive no callbacks at all. Making this work means holding focus for the whole session again — which is exactly the defect that caused P1 ("we request focus but never react to losing it", and a 42-second single hold). Reintroducing a session-long hold to fix P3 risks regressing P1.
- Only pursue if Option A proves impossible.

### Option C — raise the server-VAD threshold while navigating (partial, cheap)

Increase `turn_detection.threshold` above the current 0.62 whenever `navigating` is true.

- **Pro:** one line; no new Android APIs.
- **Con:** does not stop the uplink — audio is still sent and quota still spent. It only reduces how often the model *responds*. Treat as a complement, never as the fix.

### Option D — filter at the transcript level

Discard transcripts that look like navigation phrasing.

- **Rejected.** Pattern-matching another app's speech is brittle, quota is already spent by the time a transcript exists, and it would silently drop genuine driver speech that happens to resemble guidance.

## Decision (2026-09-16)

**Option A is chosen** by the product owner: observe other apps' playback via `AudioManager.registerAudioPlaybackCallback` and gate the microphone uplink whenever a `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` player is active.

Why A over B: Option B would require holding audio focus for the whole session, and a session-long focus hold is precisely the defect that produced P1. Trading a fixed P1 back for a fix to P3 is not acceptable. Option A is independent of focus entirely.

**This decision does not remove Phase 1.** Choosing A commits us to the approach; it does not establish that Android will let us see another app's playback configuration on this device. If the probe shows we cannot, the fallback is Option B *with* explicit care not to regress P1 — and that would be a new decision, not an automatic fall-through.

## Plan

**Phase 1 — RUN 2026-09-16. Result: Option A is falsified in its specced form.**

A debug-only probe (`app/src/debug/.../AudioPlaybackProbe.kt`) registered an `AudioPlaybackCallback`; Amap was driven into live turn-by-turn via the `androidamap://navi` deep link on the test device (`2391ff70`, HyperOS / SDK 36) and its playback state was sampled against `dumpsys audio` ground truth.

### Measured facts

| # | Measurement | Result |
| --- | --- | --- |
| 1 | Amap identity | uid **10228**, pid 30221, `com.autonavi.minimap` |
| 2 | Usage reported for Amap's guidance track | **`USAGE_MEDIA`** — *not* `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` |
| 3 | `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` anywhere on the device | **Never appears.** Grepping the whole `dumpsys audio` for `NAVIGATION_GUIDANCE` returns nothing |
| 4 | `AudioPlaybackConfiguration.getClientUid()` from our app | **Redacted — always `-1`.** It is `@SystemApi`; reflective access is blocked on SDK 36, so `ours` is always `unknown` |
| 5 | Amap's track over 45 s (15 samples, 3 s apart) | `piid:1663` **`state:started` continuously, never toggled** — a persistent `AudioTrack` (24 kHz mono, `CONTENT_TYPE_UNKNOWN`) |
| 6 | Our own playback is observable | Yes — bundled music produced change events (`usage=1`, `contentType=2`) |
| 7 | **Is another app's playback visible to us at all?** | **YES.** With Amap navigating and our own audio silent, our snapshot returned `total=1`, reporting `usage=USAGE_MEDIA contentType=0` — matching `dumpsys` (`piid:1911 uid=10228`) exactly. `usage` and `contentType` are reported **truthfully** for a foreign app |

**Visibility is therefore NOT the blocker — discrimination is.** This is the opposite of what the original Option A risk assessment feared ("it is possible only our own configurations are visible"). We can see Amap fine; we just cannot tell *when it is speaking*.

### Why this falsifies Option A

Option A's gate condition is "a configuration with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` is `state:started`". Per measurement 3 **that condition can never become true on this device**, so the gate would never fire and the defect would remain.

The obvious repairs are each independently blocked:

- **Gate on Amap's uid instead** — impossible: `getClientUid()` is redacted to `-1` (measurement 4).
- **Gate on `USAGE_MEDIA`** — the identification problem is *solvable*, but the gate still fails. A `contentType` discriminator does exist, and does not need uid:

  | Source | usage | contentType |
  | --- | --- | --- |
  | Amap guidance | `USAGE_MEDIA` (1) | `CONTENT_TYPE_UNKNOWN` (0) |
  | Our bundled music | `USAGE_MEDIA` (1) | `CONTENT_TYPE_MUSIC` (2) |
  | 小诺's own speech | `USAGE_ASSISTANT` (16) | — |

  So `(usage == MEDIA && contentType == UNKNOWN)` identifies Amap's track uniquely among the audio we produce. **This is not what kills the gate.** What kills it is measurement 5: that track is `started` for the entire navigation, so gating on its *presence* gates the whole drive. Identification was never the binding constraint.
- **Gate on "any started player"** — this is *the trap this spec exists to prevent*: measurement 5 shows Amap's track stays `started` for the entire navigation, silent or not, so 小诺 would be deaf for the whole drive.

### What is still genuinely unknown

The rescue hypothesis was: each spoken prompt opens a **separate, short-lived `piid`** while the persistent track stays open. If so, a gate keyed on *a new piid appearing* could still work.

### A′ — the rescue hypothesis was tested 2026-09-16, and is unsupported

Movement was simulated from adb, so Amap navigated a live route while the phone sat still:

```
appops set com.android.shell android:mock_location allow
cmd location providers add-test-provider gps
cmd location providers set-test-provider-enabled gps true
cmd location providers set-test-provider-location gps --location <lat>,<lon>
```

(The first `appops` grant is required — without it `cmd location` fails with `SecurityException: uid 2000 not allowed to perform MOCK_LOCATION`. All of it is reverted with `remove-test-provider` and `appops … default`.)

**The simulation demonstrably worked.** Amap recalculated from a 2263 km route to a **2.4 km / 29 分钟** one and rendered live 3D turn-by-turn, 26 m from a turn onto 无名道路 — so it was tracking the injected positions, not the real GPS.

**Result: no per-utterance configuration ever appeared.** Over 22 samples (~30 s) of `dumpsys audio` — system ground truth, *not* redacted — Amap held exactly one player, `piid:1975`, `state:started`, for every single sample. Our app's probe logged zero `probe_playback` change events across the same window.

So the per-piid gate has no signal to key on: Amap appears to write its guidance TTS into **one persistent `AudioTrack`** that is `started` for the whole navigation, whether or not it is speaking.

**Residual uncertainty, stated honestly.** Positions were teleported without realistic speed or bearing, and Amap's HUD still showed `-- km/h` with 手机卫星信号弱. It is therefore possible Amap never actually vocalised, and that a real drive would behave differently. This is the one thing a real drive could still overturn — but note it would have to overturn the *system-level* observation, not merely our app's view.

### Escalation

Per this spec's own rule ("It is not visible → **stop and escalate**"), Phase 2 as written **must not be implemented**. The decision now needed from the product owner is recorded under *Decision required* below.

**Phase 2 — Implement the gate (Option A), once Phase 1 confirms visibility.**
- Add `ExternalSpeechMonitor` (app module): registers the callback, exposes `@Volatile val otherAppSpeaking: Boolean`, with a ~400 ms release tail so the gate does not flap between guidance phrases.
- Extend the existing gating decision so the mic is gated when *either* 小诺 is speaking (existing) *or* `otherAppSpeaking` is true.
- Register only while a session is active; unregister on stop/release to avoid a leak.
- Keep it entirely in the app module — `ingress` must stay Android-free (`DependencyBoundaryTest` enforces this).

**Phase 3 — Complement with VAD (Option C).**
Raise the threshold while `navigating`. Cheap, and it catches whatever leaks past the gate.

**Phase 4 — Long-term, once the wake word ships ([ADR-005](../DECISIONS/ADR-005-wake-word-mic-handover.md)).**
The clean end state: during navigation the uplink stays gated by default and opens only when 你好小诺 fires. That removes essentially all idle uplink. This spec's Phase 2 remains useful regardless, since the wake word cannot be heard during an active session under ADR-005.

## Explicitly out of scope

- Gating for the entire navigation session (breaks the product — see The trap).
- Cancelling Amap's audio acoustically; we cannot AEC another app's output.
- Any change to `ingress` or the provider-neutral core.
- P4 (`stop_navigation` tool) — related but separate.

## Verification

| Item | Level |
| --- | --- |
| `AudioPlaybackCallback` visibility of another app's guidance | **L5** — device probe, Phase 1 gate |
| Gate open/close logic and release tail | L2 — pure JVM, mirroring `NavigationStateTest` |
| No Amap-originated transcripts over a sustained drive | **L5** |
| Driver can still be heard between guidance prompts | **L5** — the regression that matters most |
| Mic gate releases correctly; no deafness after navigation | **L5** |
| Callback unregistered on session stop (no leak) | L5 — `dumpsys` |

**The critical test is "driver can still be heard."** A fix that stops the waste but makes 小诺 deaf while driving is worse than the defect.

## Open questions

1. ~~Is another app's `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` configuration visible to us on Android 36 / HyperOS?~~ **ANSWERED 2026-09-16 — no. That usage never appears on this device at all.**
2. ~~Does Amap use that usage consistently, or sometimes `USAGE_MEDIA`?~~ **ANSWERED 2026-09-16 — Amap uses `USAGE_MEDIA`.** And the uid-based exclusion this question anticipated is **also unavailable**, because `getClientUid()` is redacted to `-1`.
3. **NEW, and now the decisive one:** does each spoken prompt open a **separate short-lived `piid`** while the persistent track stays open? Untestable while stationary; needs Amap actually speaking.
4. What release tail avoids flapping between consecutive guidance phrases without swallowing the driver's reply? (Moot unless Q3 is favourable.)
5. Should the gate apply outside navigation too (any other app speaking), or only while `navigating`? Narrower is safer to start.

## Decision required (raised 2026-09-16)

Option A cannot be built as specced, and **A′ has since been tested and did not rescue it** (see Phase 1 above). The stakes also changed on the same day:

> **P3 was reclassified from Medium to HIGH on 2026-09-16.** Device evidence shows it does not merely waste quota — it **degrades the model until tool calling stops**. In a real session `navigate_to` fired correctly, then ~90 s of Amap guidance transcribed as driver speech filled the context, and a subsequent 「帮我播一下音乐」 produced a fabricated 「已为你播放音乐」 with no tool call. Full evidence in [P3](../OPEN_PROBLEMS.md).

**Therefore "accept P3" is withdrawn as an option.** It was justified only by the belief that P3 did not break the product. It does.

Remaining paths:

- **C — raise the VAD threshold while `navigating`.** Previously dismissed as "not a fix, quota is still spent". **That judgement was cost-based and is now outdated.** On *correctness* grounds C attacks the actual failure directly: fewer of Amap's utterances cross server VAD means fewer bogus driver turns entering the context, which is precisely what poisons the model. One line, no new Android APIs, no P1 risk. **Partial, but immediately available.**
- **Wake-word gating (was Phase 4, now near-term).** During navigation keep the uplink closed by default and open it only on 你好小诺. This removes essentially all idle uplink and is the only approach that fully solves P3. It was deferred as "long-term" — but the AIKit SDK and the 你好小诺 resource **arrived on 2026-09-16** ([ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md)), so it is now blocked only on two credential values. Note ADR-006 also removes the old objection that the wake word could not be heard during a session.
- **B — gate on audio-focus loss.** Unchanged, and still carries the session-long focus hold that caused P1. Only if the two above prove insufficient.

**Recommendation: ship C now as mitigation, and treat wake-word gating as the real fix.** C is cheap and reduces the poisoning today; the wake word is the only path that actually closes P3, and it is far closer to hand than when this spec was written.

### Option C — IMPLEMENTED 2026-09-16 (mitigation only)

| What | Detail |
| --- | --- |
| Threshold | `DEFAULT_VAD_THRESHOLD = 0.62` → `NAVIGATION_VAD_THRESHOLD = 0.75` while `navigating` |
| Mechanism | `NavigationState.onNavigatingChanged` fires only on a real flip; `BaiduFlexClient` re-sends `session.update` mid-session, reusing `sentVoice` so a voice fallback is not undone |
| Files | `NavigationState.kt`, `BaiduFlexProtocol.kt`, `BaiduFlexClient.kt` (+3 test files). No audio-path, `ingress`, or dispatcher changes |
| Tests | 90 pass / 0 fail, `assembleDebug` clean |

**Ownership hazard handled.** `DeveloperSettingsActivity` builds a throwaway `BaiduFlexClient` for Test Connection, so two clients can exist at once. `disconnect()` clears the listener slot only on an identity match, so the throwaway cannot wipe a live session's listener. This is the same class of defect as the historic duplicate `onFocusChanged` assignment, and is covered by `releasingFirstOwnerDoesNotClearSecondOwner`.

**Verification status: L2 only.** No device run, no live Baidu or Amap session. The two L5 claims that matter are **unproven**:

1. Fewer Amap-originated transcripts over a sustained drive.
2. **The driver is still heard while navigating.** 0.75 is a judgement, not a tuned value — raising the threshold also makes a quiet driver harder to hear over road noise. If the driver has to repeat themselves during navigation, lower this constant first.

**This does not close P3** and must not be recorded as doing so.
