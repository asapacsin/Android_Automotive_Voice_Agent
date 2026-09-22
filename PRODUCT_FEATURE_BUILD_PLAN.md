# Product feature build plan

**Branch:** `cursor/2026-09-22` · **Harness baseline:** `d11cfd9` (evidence-bound PASS)  
**Authoritative evidence:** [config/capabilities.yaml](config/capabilities.yaml), [TEST_MATRIX.yaml](TEST_MATRIX.yaml), [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md)  
**Updated:** 2026-09-22

This document is the executable product roadmap. It does **not** replace the capability registry or test matrix — those remain machine-readable truth. Harness, model routing, and evidence-binding are **infrastructure**; do not redesign them unless a slice discovers a real blocker.

---

## Management view

| Order | ID | Feature | State | Priority | Dependency | Evidence needed |
| ----- | -- | ------- | ----- | -------- | ---------- | --------------- |
| 1 | F01 | Colloquial capability help (「你能干啥」) | **DONE** — HELP-001 device PASS; P28 FIXED | P1 | — | Bound 2026-09-22 |
| — | **Demo** | Live 3–5 min voice trip (packets A–D) | A–C in progress; D after device | **P0 demo** | A, B, C | Three no-touch rehearsals on `2391ff70` |
| 2 | F02 | Off-route / yaw / jam route recovery | Callbacks log; `reroute()` stub | P2 | **After demo** | Measure first; emulator/device log + video |
| 3 | F03 | GPS-weak driver notice | Log only | P2 | **After demo** | Unit + device log |
| — | H01 | Real-road navigation to arrival | Emulator E2E PASS | Human | Owner drive | NAV-DRIVE-001 |
| — | H02 | Cabin wake-word detection | Synth + false-wake PASS | Human | Owner drive | WAKE-REAL-001 |
| — | H03 | Cabin mic / VAD thresholds | Desk scenarios PASS | Human | Owner drive | MIC-CABIN-001 |
| — | H04 | Guidance loudness vs assistant | Mic gate PASS | Human | Owner drive | AUDIO-QUALITY-001 |
| — | H05 | Voice 4196 ear-check | Default shipped | Human | Owner listen | VOICE-STYLE-001 |
| — | H06 | Confirmed call connects | Unit + no-SIM PASS | Human | SIM + consent | CALL-REAL-001 |
| — | H07 | Cantonese policy | Measured | Decision | Owner | YUE-POLICY-001 |
| — | H08 | Signed release + ABI | Unsigned 227 MB | Decision | Owner | RELEASE-SIGN-001, ABI-POLICY-001 |

**Counts:** executable agent slices now = **Demo packets A–C** + F02–F03 after demo. F01 complete. P0 demo = packets A–C.

**Recommended now:** **Demo readiness** (packets A–C), then rehearsal packet D. **F02/F03 deferred** until after the demo.

---

## Demo readiness (before F02 / F03)

**Goal:** A driver finishes a multi-step trip by voice — search, pick, route, start, one climate command, one 「停止说话」 — without touching the screen and without the assistant claiming an action that did not run. Climate stays simulated. Vision is optional.

### Primary script (3–5 min)

| Step | Phrase | Notes |
| ---- | ------ | ----- |
| 1 | 「你好小诺」 (or status-row tap if wake misses once) | Wake |
| 2 | 「带我去拱北口岸」 | Candidates on screen; navigation not started |
| 3 | 「第二个」 | Ordinal pick until packet B device PASS; then may use place name |
| 4 | 「最快的」 then 「开始导航」 | Driving HUD |
| 5 | 「有点热」 | Simulated climate; confirmation must be **audible** (packet A) |
| 6 | 「停止说话」 | Model calls `set_speech_output(silent)`; playback stops; no ack (packet C) |
| 7 (optional) | 「看看前面有什么」 | Skip if camera/key fails |

**Fallback:** repeat the same phrase once; if still failing, use on-screen control for that step only. Never narrate success the log did not show.

**Exclude from live demo:** real-road arrival, SIM call, Cantonese, music library, mid-sentence barge-in, network kill, long guidance listening (FC-007 / 「一点血」).

### Demo packets

| Packet | Scope | Owner | Acceptance |
| ------ | ----- | ----- | ---------- |
| **A** | Audible tool confirmations during navigation; restore duck volume | `AndroidPlaybackPort`, `NavigationState` | 「有点热」 heard at normal loudness during emulator drive |
| **B** | Name on open list → `choose_navigation_option`, not `navigate_to` | `NavigationPickerIntercept`, `VoiceSessionController`, `AndroidToolDispatcher` | List showing 拱北口岸 → say name → `destination_selected` |
| **C** | 「停止说话」 intent → `set_speech_output(silent)` | `BaiduFlexProtocol`, `AndroidToolDispatcher`, `VoiceSessionGateway.shutUp` | Model calls tool; playback flushes; no ack; next command works. Not a local phrase list. |
| **D** | Rehearse script | No product code | Three consecutive no-touch runs; checklist D01–D04, D07–D12, D14, D18–D20 |

**Sequence:** A and C parallel; B independent; D waits until A and C on device (B may land before or after first rehearsal; D05 out until B passes).

### New field failures (spreadsheet IDs 5–8 → FC-006…FC-009)

| FC | Symptom | Demo impact |
| -- | ------- | ------------- |
| FC-006 | Cannot cut speech; 「停止说话」 not routed to `set_speech_output` | Packet C (model intent, not phrase list) |
| FC-007 | Guidance nonsense (Amap, not Flex) | Log-only; not demo-blocking |
| FC-008 | Assistant extremely quiet during nav | Packet A — highest demo risk |
| FC-009 | Saying listed place starts new search | Packet B; D05 |

---

## Current product architecture (one paragraph)

Baidu Qianfan Flex is **end-to-end speech-to-speech** with function calling — no separate ASR or TTS engine ([docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [ADR-002](DECISIONS/ADR-002-baidu-flex-default-provider.md)). Reply audio arrives as `response.audio.delta` PCM frames and is queued into `PcmAudioPlayer` as they stream; `DriverTurn` may **hold** frames until a tool result proves an action — that delay is truthfulness enforcement, not whole-utterance buffering. Navigation is the **embedded Amap Navigation SDK** in our Activity ([ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md)). Climate uses `SimulatedVehicleControl`. Music is one bundled track (play/stop). Phone calls are confirm-then-dial.

```
microphone → PcmAudioCapture → gates (sleep / guidance) → Baidu Flex WSS (full-duplex; AEC)
  → FlexFunctionCallAssembler → AndroidToolDispatcher → executors
  → tool result → model reply → PhantomTurnGate / DriverTurn → PcmAudioPlayer → cabin
```

---

## Product capability map (by user workflow)

Legend for **product state** (separate from test status and harness):

| Label | Meaning |
| ----- | ------- |
| **WORKING + EVIDENCE** | Code exists; device or human evidence recorded in registry/matrix |
| **IMPLEMENTED, NOT E2E VERIFIED** | Code + lower-scope tests; required device/human row not PASS |
| **PARTIAL** | Some paths work; known gap in behaviour or feedback |
| **BROKEN / REGRESSION** | OPEN_PROBLEMS entry or FAIL matrix row |
| **SPEC ONLY** | Written requirement; no implementation |
| **MISSING** | Deliberately unsupported or not specced |
| **UNKNOWN — NEEDS TESTING** | Code may exist; behaviour not measured |

### Voice interaction

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| Wake word (engine + synth) | WORKING + EVIDENCE | WAKE-ENGINE-001, WAKE-SYNTH-001 PASS |
| Wake word (real cabin) | IMPLEMENTED, NOT E2E VERIFIED | One owner voice 2026-09-19; WAKE-REAL-001 HUMAN_REQUIRED |
| False-wake rate | WORKING + EVIDENCE | WAKE-FALSE-001: 0 in 16 min with music |
| Open-mic / realtime session | WORKING + EVIDENCE | Flex session; NET-RECOVER-001, LIFECYCLE-ERROR-001 |
| Push-to-talk (separate) | MISSING | Status-row tap = same `VoiceSessionGateway.start` as wake; no PTT product |
| 「闭嘴」 → silent wait (session continues) | WORKING + EVIDENCE | `ListeningLifecycle.SILENT_WAIT`; BARGEIN-001 PASS |
| 「休眠」 / end conversation | WORKING + EVIDENCE | SLEEP-001 PASS |
| Resume speaking after silent wait | WORKING + EVIDENCE | Next command spoken; [docs/LISTENING_LIFECYCLE.md](docs/LISTENING_LIFECYCLE.md) |
| Voice barge-in during model speech | IMPLEMENTED, NOT E2E VERIFIED | Flex `interrupt_response`; mic stays uplinked; shared-session AEC |
| Streaming vs buffered TTS | WORKING + EVIDENCE | Streaming deltas → `PcmAudioPlayer.enqueue`; hold is per-turn truth gate |
| Recovery when not understood | WORKING + EVIDENCE | Nudges; NOISE-001; not confused with help (P28) |
| Conversational context (climate, nav phase) | WORKING + EVIDENCE | SPEC-006; CTX-CHAIN-001, CTX-AMBIG-001 PASS |
| Capability help (colloquial 干啥) | WORKING + EVIDENCE | HELP-001 device PASS 2026-09-22; P28 FIXED |
| Audible confirmations during navigation | IMPLEMENTED, NOT E2E VERIFIED | Packet A — `unduck` on permitted reply |
| Picker name local intercept | IMPLEMENTED, NOT E2E VERIFIED | Packet B — `NavigationPickerIntercept` |
| 「停止说话」 stop-talking intent | IMPLEMENTED, NOT E2E VERIFIED | Packet C — model calls `set_speech_output(silent)`; not local phrase list |
| Post-speech echo / phantom 没听清 | WORKING + EVIDENCE | ECHO-001 PASS (P27) |
| Assistant voice style (4196) | IMPLEMENTED, NOT E2E VERIFIED | Code default; VOICE-STYLE-001 HUMAN_REQUIRED |

### Navigation

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| Destination search | WORKING + EVIDENCE | NAV-SEARCH-001 |
| Ordinal / name / route preference pick | WORKING + EVIDENCE | NAV-PICK-001; NAV-UI-010 unit |
| Start navigation (tap + voice) | WORKING + EVIDENCE | NAV-UI-001, NAV-UI-008 |
| Driving presentation (lock-car, HUD, traffic) | WORKING + EVIDENCE | NAV-UI-002–007; P26 resolved |
| Guidance voice + mic gate | WORKING + EVIDENCE | NAV-MID-ROUTE-001; P3 fixed |
| Saved home/work | WORKING + EVIDENCE | PLACE-*, NAV-CORRECT-001 |
| Voice cancel / 算了 | WORKING + EVIDENCE | NAV-CANCEL-001 |
| Arrival + auto session end (emulator) | WORKING + EVIDENCE | NAV-E2E-ARRIVAL-001; P31 fixed |
| Real GPS drive to arrival | IMPLEMENTED, NOT E2E VERIFIED | NAV-DRIVE-001 HUMAN_REQUIRED |
| Yaw / traffic reroute | PARTIAL | SDK callbacks log; `reroute()` returns `REROUTE_NOT_IMPLEMENTED` |
| GPS weak signal UX | PARTIAL | `nav_gps_weak` log only |
| Manual reroute voice command | UNKNOWN — NEEDS TESTING | Only if SDK does not auto-recalc (F02) |

### Vehicle controls (simulated)

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| AC on/off | WORKING + EVIDENCE | CLIMATE-OFF-001 |
| Set temperature / fan | WORKING + EVIDENCE | CLIMATE-SET-001, CLIMATE-FAN-001 |
| Implicit / relative (有点热, 再凉一点) | WORKING + EVIDENCE | CLIMATE-IMPLICIT-001; live model 2026-09-19 |
| Real vehicle HVAC | MISSING | `VehicleControlPort` seam; SimulatedVehicleControl only |

### Media

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| Play / stop bundled music | WORKING + EVIDENCE | MUSIC-PLAY-001; P2 resolved |
| Named song / library | MISSING (unsupported) | MEDIA_LIBRARY_UNSUPPORTED; MUSIC-NAMED-001 |
| Next / previous track | MISSING (unsupported) | `media.next_track` unsupported |
| Pause / resume (voice) | MISSING | Voice tools are play/stop only |
| Pause / resume (UI) | PARTIAL | Bottom-bar “pause” calls `stopMusic()` (stop/play toggle), not `MediaPlayer.pause` |

### Phone

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| Contact lookup + confirm-before-dial | WORKING + EVIDENCE | Unit: CALL-CONFIRM-001, CALL-AMBIG-001, CALL-PRIVACY-001 |
| No-SIM honest refusal | WORKING + EVIDENCE | CALL-NOSIM-001 |
| Call classification (not unsupported) | WORKING + EVIDENCE | CALL-CLASSIFY-001 |
| Confirmed call rings handset | IMPLEMENTED, NOT E2E VERIFIED | CALL-REAL-001 HUMAN_REQUIRED (SIM) |

### Vision

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| 「看看前面有什么」 | WORKING + EVIDENCE | VISION-001; permission + failure paths |
| Camera release on background | WORKING + EVIDENCE | CAMERA-RELEASE-001 |

### Apps

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| Open maps / settings | WORKING + EVIDENCE | APPS-001 |

### Safety / truthfulness

| Capability | Product state | Evidence / notes |
| --- | --- | --- |
| Action-claim guard | WORKING + EVIDENCE | TRUTH-CLAIM-001; D-7 resolved |
| Misheard → no false action | WORKING + EVIDENCE | TRUTH-MISHEARD-001 |
| Unsupported controls refused | WORKING + EVIDENCE | TRUTH-BAIT-001, UNSUPPORTED-001 |
| Weather / realtime fabrication blocked | WORKING + EVIDENCE | TRUTH-WEATHER-001 |
| Duplicate correction in one turn | WORKING + EVIDENCE | TRUTH-DUP-001 |

### Product robustness

| Area | Product state | Evidence / notes |
| --- | --- | --- |
| Network loss recovery | WORKING + EVIDENCE | NET-RECOVER-001; P19 |
| API / session errors | WORKING + EVIDENCE | LIFECYCLE-ERROR-001 |
| Permission denial (mic, camera, contacts) | WORKING + EVIDENCE | PERM-DENY-001; camera/contacts handlers |
| Process death / restart | WORKING + EVIDENCE | PROCDEATH-001 |
| Guidance vs assistant audio conflict | WORKING + EVIDENCE | P1 resolved; navigation mute + guidance gate |
| Long conversation / context reset | WORKING + EVIDENCE | ConversationResetPolicy; measured 8/8 per turn |
| Bluetooth / audio route changes | UNKNOWN — NEEDS TESTING | Focus duck/pause in `PcmAudioPlayer`; no drive matrix |
| Idle wake battery / CPU | UNKNOWN — NEEDS TESTING | ADR-006 notes cost unmeasured; P4 only if drive shows issue |
| Cantonese | SPEC ONLY | YUE-POLICY-001 decision pending |
| Release signing / APK size | SPEC ONLY | B-019; RELEASE-SIGN-001 |

### Harness (infrastructure — not product gaps)

| Item | State |
| --- | --- |
| Capability registry + protection audit | WORKING — 39 supported, 0 unprotected at `d11cfd9` |
| Evidence-bound device/flow PASS | WORKING — 43 runtime binds; `validate()` clean |
| Model routing / termination gate | WORKING — do not change for product slices |

---

## Gap analysis (evidence-based)

### P0 — broken core flows

**None.** Wake, session, navigation, climate, music, vision, and truthfulness paths are device-proven on `2391ff70`.

### P1 — incomplete core capabilities

1. **F01 — Colloquial capability help** — `speech.capability_help` is `verified: unit`. HELP-001 explicitly requires 「你能干啥」 on device. Formal 「你能做什么」 passed device once; colloquial re-run is outstanding. Do not close P28 with HELP-UNIT-001 alone. This is the only **OPEN** problem ([P28](OPEN_PROBLEMS.md)).

### P2 — reliability and recovery

2. **F02 — Off-route recovery** — Separate from P31 (arrival lifecycle, FIXED). Yaw/jam callbacks log only; `EmbeddedNavigationController.reroute()` stubs. Not an OPEN_PROBLEMS entry — **measure first** before implementing; escalate to P1 only if maneuver proves missing recovery.
3. **F03 — GPS-weak notice** — `onGpsSignalWeak` logs only; driver gets no feedback in tunnel/parking structures. Independent of F02 at product level (coordination only if one agent owns `NavigationTraceListener`).

### Human / decision queue (not agent implementation)

- Real-road arrival (H01), cabin wake (H02), cabin mic (H03), guidance loudness (H04), voice ear-check (H05), SIM call (H06), Cantonese (H07), release (H08).

### Explicitly deferred (do not schedule as bugs)

- Music library, next/previous, volume, windows, weather, voice barge-in, separate ASR, real HVAC — registry `unsupported` or SPEC-006 non-goals.
- Battery/CPU instrumentation — P4 after F01–F03 and only if a drive shows a problem.

### False assumptions to avoid

| Assumption | Reality |
| --- | --- |
| Missing E2E test = missing feature | CALL-REAL-001 blocked on SIM; code exists |
| HELP-UNIT-001 PASS = P28 closed | P28 and HELP-001 require colloquial device evidence |
| P28 = product broken | Unit fixed; gap is colloquial device verification only |
| F03 requires F02 | Independent callbacks; ownership coordination only |
| `reroute()` stub = navigation broken | SDK may recalc via callbacks; measure first (F02) |
| P31 = yaw/jam defect | P31 was arrival proximity; F02 is a separate measure-first gap |
| Emulator arrival = no arrival proof | NAV-E2E-ARRIVAL-001 PASS with video |
| Need separate PTT button | Tap status row = `VoiceSessionGateway.start("ui")` / sleep when ACTIVE (MainActivity kdoc says “push-to-talk” but behaviour is wake/sleep toggle) |
| Silent mode kills session | SILENT_WAIT keeps connection and context |

---

## Build sequence

```
CURRENT PRODUCT STATE (desk device 2391ff70, harness d11cfd9)
        │
        ├──────────────────┬──────────────────┐
        ▼                  ▼                  ▼
       F01                 F02                F03
   colloquial help    yaw/jam measure    GPS-weak notice
        │                  │                  │
        │                  │    (parallel; one writer on NavigationTraceListener if F02+F03 overlap)
        └────────┬─────────┴──────────────────┘
                 ▼
        DESK RELEASE GATE
   (F01–F03 done; matrix binds current; no OPEN P* for desk scope)
                 │
                 ▼
        HUMAN / DECISION QUEUE (H01–H08 in parallel where independent)
                 │
                 ▼
        PRODUCT RELEASE GATE (signed APK + owner ABI/Cantonese choices)
```

**Parallel workstreams**

| Stream | Owner components | May parallel with |
| --- | --- | --- |
| F01 | `UtteranceIntentResolver`, `DriverTurn`, `ProductCapabilities` | F02 |
| F02 | `NavigationTraceListener`, `EmbeddedNavigationController`, `AmapNaviViewHost` | F01, F03 (if different writers) |
| F03 | `NavigationTraceListener`, overlay UI | F01, F02 (if different writers) |

Do not assign two agents to the same subsystem without one being reviewer-only.

---

## Feature slices (detailed)

### F01 — Colloquial capability help

| Field | Value |
| --- | --- |
| **ID** | F01 |
| **Status** | **Complete** 2026-09-22 |
| **Feature / user problem** | Driver asks 「你能干啥」 and gets a real capability answer, not 「没听清」 / 「不理解」 |
| **Current state** | HELP-001 device PASS; P28 FIXED |
| **Expected behaviour** | Short spoken answer naming **导航** and at least one other supported group from `ProductCapabilities.spokenHelpSummary`; never 音量/天气; never `TURN_DROP unverified_claim` for recognised help |
| **Why it matters** | Only OPEN problem (P28) on the desk device; blocks honest "what can you do?" in colloquial speech |
| **Dependencies** | None |
| **Affected components** | `UtteranceIntentResolver`, `DriverTurn`, `ProductCapabilities`, `BaiduFlexClient` (hold/release only if device fails) |
| **Implementation scope** | Run HELP-001 procedure first. Fix only if device fails — do not expand catalog beyond registry |
| **Acceptance criteria** | HELP-001 PASS; P28 may close only after that PASS; `speech.capability_help` may upgrade to `device` in registry |
| **Required evidence** | Log: `TURN_RELEASE reason=capability_help` or scripted speak path; transcript cites 导航 + other group |
| **Required test** | [HELP-001](TEST_MATRIX.yaml) — `python tools/speech-harness/run_scenarios.py --only S21 --wait 8` with 你能干啥 clip or live mic |
| **Regression risks** | Truthfulness holds, unsupported refusals, action-claim guard |
| **change_impact** | `utterance_truth` (+ shared contracts) |
| **Model routing** | **Composer** — run procedure, fix if red. **Grok** only if two reasonable fixes fail (C/H3) |
| **Parallel** | Yes, with F02 |
| **Completion definition** | HELP-001 bind-current PASS; evidence_bind refreshed; P28 updated to FIXED in OPEN_PROBLEMS |

### F02 — Driven route follows yaw or jam recalc

| Field | Value |
| --- | --- |
| **ID** | F02 |
| **Priority** | P2 — **deferred until after demo** |
| **Feature / user problem** | After leaving the route (yaw or traffic jam), the active route updates or the driver is told recalc failed |
| **Current state** | `onReCalculateRouteForYaw` / `onReCalculateRouteForTrafficJam` log in [NavigationTraceListener.kt](app/src/main/kotlin/com/novadrive/app/nav/amap/NavigationTraceListener.kt); [EmbeddedNavigationController.reroute](app/src/main/kotlin/com/novadrive/app/nav/EmbeddedNavigationController.kt) returns `REROUTE_NOT_IMPLEMENTED`. **Not** an OPEN_PROBLEMS entry |
| **Expected behaviour** | During emulator or desk drive, intentional off-route maneuver produces `nav_recalc_*` log and changed `nav_active_route` / on-screen route; OR documented proof SDK already handles it without `reroute()` |
| **Why it matters** | P31 closed **arrival** lifecycle (proximity completion). F02 is a separate code gap: recalc is logged but driver-visible recovery and the `reroute()` API are unproven |
| **Dependencies** | None |
| **Affected components** | `NavigationTraceListener`, `EmbeddedNavigationController`, `AmapNaviViewHost`, `NavigationProgressTrace` |
| **Implementation scope** | **Measure first.** One maneuver test. Implement `reroute()` only if SDK does not update route. Voice 「重新规划」 only if measure fails **and** product wants a voice entry. Do not duplicate Amap recalc |
| **Acceptance criteria** | Video + log showing route change after recalc event; or minimal `reroute()` calling SDK with success path |
| **Required evidence** | `nav_recalc_yaw` or `nav_recalc_jam` + `nav_active_route` change; optional new/extended TEST_MATRIX row |
| **Required test** | New or extended device/emulator flow row (not unit-only) |
| **Regression risks** | Arrival lifecycle (NAV-E2E-ARRIVAL-001), guidance voice gate, driving presentation |
| **change_impact** | `navigation_lifecycle` |
| **Model routing** | **Composer** if SDK already recalcs and only test/doc missing. **Grok** if route does not change and root cause unclear (B/C) |
| **Parallel** | Yes with F01; with F03 only if one writer on `NavigationTraceListener` |
| **Completion definition** | Documented behaviour + matrix evidence; `reroute()` implemented or explicitly N/A with SDK proof |

### F03 — GPS-weak is visible

| Field | Value |
| --- | --- |
| **ID** | F03 |
| **Priority** | P2 — **deferred until after demo** |
| **Feature / user problem** | Driver knows GPS is weak during navigation instead of silent degradation |
| **Current state** | `onGpsSignalWeak` → `nav_gps_weak` log only. **Not** an OPEN_PROBLEMS entry |
| **Expected behaviour** | Overlay or status shows weak-GPS while `weak=true` during active guidance; clears when strong; assistant does not claim healthy fix |
| **Why it matters** | Tunnel/parking structures — common real-drive failure mode with no user feedback |
| **Dependencies** | None (product). **Coordination:** avoid concurrent edits to `NavigationTraceListener` with F02 |
| **Affected components** | `NavigationTraceListener`, `AssistantOverlayView` / `AssistantUiState`, optional `NavigationStateStore` |
| **Implementation scope** | Surface existing callback to UI; unit test state transitions; one device log |
| **Acceptance criteria** | Weak shows notice; strong clears; NAV-E2E-ARRIVAL-001 still passes |
| **Required evidence** | Device log `nav_gps_weak=true` with visible UI state; unit test |
| **Required test** | Unit + one device observation (may use debug/sim weak flag if available) |
| **Regression risks** | Navigation presentation rows NAV-UI-* |
| **change_impact** | `navigation_lifecycle` |
| **Model routing** | **Composer** |
| **Parallel** | Yes with F01; with F02 only if one writer on trace listener |
| **Completion definition** | UI wired + test; matrix row if needed; binds refreshed for touched nav rows |

---

## Human / decision queue (H01–H08)

These are **validation or owner decisions**, not the next autonomous implementation slices. Agents may prepare procedures; agents must not invent cabin results, dial real numbers, or sign APKs.

| ID | Matrix / backlog | Blocker |
| --- | --- | --- |
| H01 | NAV-DRIVE-001 | Real drive with GPS to arrival |
| H02 | WAKE-REAL-001 | Human voice at distance in moving cabin |
| H03 | MIC-CABIN-001 | Road noise, passengers, speed |
| H04 | AUDIO-QUALITY-001 | Subjective loudness / collision |
| H05 | VOICE-STYLE-001 | Ear-check voice 4196 |
| H06 | CALL-REAL-001 | SIM + consented number |
| H07 | YUE-POLICY-001 | Owner policy choice |
| H08 | RELEASE-SIGN-001, ABI-POLICY-001 | Keystore + ABI decision |

---

## Agent roles and model routing

| Work type | Model |
| --- | --- |
| F01 device run, straightforward Kotlin fix, unit tests, `--bind` evidence | **Composer** |
| F02 SDK maneuver interpretation, ambiguous nav lifecycle | **Grok** if first measurement inconclusive |
| F03 overlay wiring | **Composer** |
| Architecture change (new subsystem, provider, nav state machine merge) | **Grok** then Composer |
| Review of completed slice against registry | **Grok** or `reviewer` subagent |

Do not spend Grok tokens on routine Gradle, matrix regeneration, or scripted harness runs.

---

## Regression protection (every slice)

1. Name affected capabilities via [change_impact](config/capabilities.yaml) (`voice_session_lifecycle`, `navigation_lifecycle`, `utterance_truth`).
2. Run `.\gradlew.bat test` and targeted behavior-test rules if contracts touched.
3. After device evidence, `python scripts/test_matrix.py --bind <id>` for affected rows only.
4. `python scripts/test_matrix.py --validate` and `python scripts/harness_check.py` must stay clean.
5. Do not weaken tests to preserve stale PASS; use `--apply-stale` if code invalidates bind.

---

## PRODUCT_STATE summary

| Category | Items |
| --- | --- |
| **Working + evidence** | Wake engine/synth, session lifecycle, silent/sleep, navigation search→arrival (emulator E2E), driving HUD, guidance gate, climate (sim), music play/stop, open maps/settings (APPS-001), vision, truthfulness guards, network recovery, echo protection, no-SIM call refusal |
| **Partial** | Yaw/jam recalc (logged, unproven UX), GPS-weak (log only), pause music (no tool) |
| **Unverified (demo)** | Packets A–C device proof; demo rehearsal D |
| **Missing (by design)** | PTT, barge-in, music library, next track, volume, weather, real HVAC, Cantonese until decided |
| **Unverified** | Real-road arrival, cabin wake/mic/audio, SIM call, voice ear-check, release build |

---

## BUILD_PLAN summary

| Metric | Value |
| --- | --- |
| Planned executable slices | Demo A–C now; F02–F03 after demo; 8 human/decision items |
| P0 / P1 / P2 / P3 / P4 | 1 demo / 0 / 2 deferred / 0 / 0 |
| Critical chain | Demo A–C → rehearsal D → F02 measure → F03 |
| Parallel | A ∥ C; B independent |
| First slice | **Demo packet A** (audible confirmations) |

---

## Related documents

| Document | Role |
| --- | --- |
| [docs/CAPABILITIES.md](docs/CAPABILITIES.md) | Human-readable capability prose |
| [config/capabilities.yaml](config/capabilities.yaml) | Machine registry + change_impact |
| [TEST_MATRIX.yaml](TEST_MATRIX.yaml) | Verification status + evidence_bind |
| [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md) | Active defects |
| [BACKLOG.md](BACKLOG.md) | Demand history |
| [SPECS/SPEC-006-complex-voice-commands.md](SPECS/SPEC-006-complex-voice-commands.md) | Contextual commands scope |
| [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md) | Evidence levels L1–L5 |

---

*Reviewed against repository 2026-09-22 (grok-high read-only pass): P28 severity, F02/F03 dependency, P31 framing, apps row, media pause, F02 priority demoted to P2 measure-first.*

