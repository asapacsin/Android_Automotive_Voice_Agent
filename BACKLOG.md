# Backlog

Recorded demands from the product owner, newest first. See `agent/INTAKE.md` for how these move to shipped work.

Status values: **Recorded** (captured, not specced) · **Specced** (has a SPEC) · **In milestone** · **Done** · **Dropped**

| # | Demand | Raised | Status | Spec |
| --- | --- | --- | --- | --- |
| B-008 | **Complex / contextual voice commands** — the driver speaks naturally (「有点热」「再凉一点」「这个太远了，换个近一点的」) instead of like an API, and the assistant resolves it against what it already did — **bounded by the tools that exist**, never a spoken acknowledgement in place of an execution | 2026-09-19 | **Partly implemented 2026-09-19** — context record, resolver, staleness, ambiguity, clarification and the two execution guards are built and unit-proven (33 tests). Multi-intent decomposition and the navigation-phase cases are not. Live-model behaviour unproven | [SPEC-006](SPECS/SPEC-006-complex-voice-commands.md) |
| B-007 | **Assistant-on-map UI design** — avatar + state indicator + speech bubble upper-left, temporary action-feedback card, compact media/climate bottom bar, and a bottom-right **front-facing camera** button that must not end the assistant session | 2026-09-16 | **Done** 2026-09-19 as a *decision* — the layout is authoritative and folded into [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md) D2. Of its four open questions, **inert bottom-bar controls is closed**: the bar now executes through `ScreenControls`, the same route a spoken command takes (D-3). The rest are tracked where they belong — session entry point with B-003, `openApp(maps)` and camera-vs-§42 in that design | [DEMAND](SPECS/DEMAND-2026-09-16-ui-design.md) → [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md) |
| B-006 | **Embedded Amap navigation MVP** — map-first vehicle UI, `AMapNaviView` inside our Activity, assistant overlay above it, `NavigationController`/`DestinationResolver` abstractions, no external Amap app, no overlay permission. Replacement spec (48 sections) | 2026-09-16 | **Done** 2026-09-19 — M2 closed on device evidence: candidates resolve and render, routes draw, the chosen route is the one driven, arrival auto-stops ([ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)). The key blocker was resolved; the key type is proven by navigation working | [SPEC-005](SPECS/SPEC-005-embedded-amap-mvp.md) |
| B-005 | Automated speech test harness — TTS-simulated user → real pipeline → ASR-verified output; local failure records; regression corpus; latency distributions | 2026-09-16 | **Superseded** by SPEC-005 (three levels A/B/C, UI screenshot assertions, navigation failure stages). Level A is built and running ([docs/EVALUATION.md](docs/EVALUATION.md)); the speech harness drives the phone. The paid-API constraint on audio levels is acknowledged in that document | [SPEC-004](SPECS/SPEC-004-speech-test-harness.md) → SPEC-005 |
| B-004 | Amap coexistence by **voice policy** and generic `ActionExecutor` with a mock | 2026-09-16 | **Superseded** by SPEC-005 Phases 5–6 — both open conflicts resolved (§16 keeps Baidu E2E; §19 supplies the Amap-speaking signal). The guidance mute shipped and is guarded by `GuidanceMicGate` + `NavigationMuteFollowsPhaseTest` | [SPEC-003](SPECS/SPEC-003-amap-coexistence-voice-policy.md) → SPEC-005 |
| B-003 | Wake word to activate the assistant — say 「你好小诺」 instead of pressing a button | 2026-09-15 | **Ready to implement** — [ADR-006](DECISIONS/ADR-006-wake-word-aikit-shared-capture.md); AIKit SDK + 你好小诺 resource received 2026-09-16. Blocked only on `apiKey` + `apiSecret` | [SPEC-001](SPECS/SPEC-001-wake-word.md) |
| B-002 | 小诺 must stay quiet during navigation and speak only short confirmations | 2026-09-15 | **Done** 2026-09-16 | [P1](OPEN_PROBLEMS.md) — verified on device |
| B-001 | 「关闭音乐」 must actually stop the music | 2026-09-15 | **Done** 2026-09-16 | [P2](OPEN_PROBLEMS.md) — verified on device with log evidence |

---

## B-008 — Complex / contextual voice commands

> 「后续可以试试复杂的语音指令」

Received 2026-09-19 with a modern automotive conversational assistant as the reference interaction
style. The demand is **not** "several commands in one sentence" — it is that the driver should not
have to speak like an API, and that context from the conversation and the active task should resolve
what they meant.

Specced as [SPEC-006](SPECS/SPEC-006-complex-voice-commands.md) without implementing anything. Two
findings from writing it are worth reading even before the work starts:

- **The model has no cross-turn memory here.** `ConversationResetPolicy` resets the conversation
  after every tool turn, on measured device evidence. So context must be app-owned and injected —
  the spec extends `VoiceContextHints`, which exists for exactly this reason, rather than adding a
  second mechanism.
- **Three of the reference examples cannot be honoured as given.** The music ones need a library
  this product does not have, and the barge-in one needs an interrupt the product deliberately does
  not support. They are adapted, and the music phrasing becomes an *unsupported* case — today it is
  a plausible false-success path, because a request for a named song is not recognised as
  unsupported and would likely start the one bundled track.

Everything that can be built and proven without the live model is done and, where it touches execution, device-verified. What remains is one row of M3: whether the **live model** acts on the injected context, which is `TEXT_LIVE`/`AUDIO_E2E` work.

BLOCKED_BY: the test phone has no network route to the Baidu provider, so no live session can be established to prove the model acts on the injected context

## B-007 — Assistant-on-map UI design

> "Embedded Amap: occupies the full main screen and remains the primary interface during navigation." / "📷 Bottom-right camera button: opens the device's front-facing camera … Closing the camera view returns to the Amap screen without ending the assistant session."

Received 2026-09-16 with an ASCII layout, stored verbatim at [SPECS/DEMAND-2026-09-16-ui-design.md](SPECS/DEMAND-2026-09-16-ui-design.md). It **resolved decision D2** of the Phase 1 design and **superseded** three earlier proposals of mine: a mic + settings bottom bar, treating the bottom-right control as settings, and (in the same message) the `NAVIGATION_NOT_READY` approach to D1.

Two tensions were recorded rather than smoothed over: v2 §42 lists an "advanced camera/video assistant panel" as an MVP non-goal while the design puts a camera button in the Phase-1 screen (read as: a plain front-preview is not the excluded panel); and removing the widget column removes the only way to start a voice session, since the wake word that was meant to replace it is still blocked on iFlytek credentials. Both are open questions in the design, not decisions I made.

## B-006 — Embedded Amap navigation MVP

> "The assistant app owns the screen. Amap supplies the map/navigation engine and navigation view inside the app." / "The product must not switch to the separately installed 高德地图 application for normal navigation."

Received 2026-09-16 as a **replacement specification** (`Embedded_Amap_AI_Assistant_Spec_v2.md`, copied verbatim to [SPECS/DEMAND-2026-09-16-embedded-amap-v2.md](SPECS/DEMAND-2026-09-16-embedded-amap-v2.md)). It reverses ADR-003's app-handoff model — recorded as [ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md) — and in doing so removes the root cause behind P1, P3, P4 and the MIUI socket-kill: another app owning the screen and the speaker. It also settles B-004's two open conflicts. Opened as **M2** with v2 §44 Phase 1 / §47 as the completion rule.

## B-005 — Automated speech test harness

> "Test the real speech pipeline … This should test behavior using voice rather than only inserting text directly into intent handling." / "Every failed automated test must produce a persistent local failure record."

Received 2026-09-16 as part of a 34-section requirements document, stored verbatim in [SPECS/DEMAND-2026-09-16-amap-coexistence.md](SPECS/DEMAND-2026-09-16-amap-coexistence.md) (§14–32). Split out from B-004 because it is a separate workstream with its own blockers: in an end-to-end architecture "feed audio into the pipeline" means calling Baidu, so the deterministic level costs quota and needs the owner's say-so; and it needs TTS/ASR engines the product deliberately does not have.

## B-004 — Amap coexistence: voice policy and action abstraction

> "Normal assistant conversation must remain silent. Action commands such as turn on/off are an explicit exception and SHOULD produce a short voice confirmation." / "The decision must use semantic metadata." / "Device commands should use an abstract action layer with mock implementations."

Same document, §1–13 and §33–34. This is P1 ("quiet during navigation") restated as a policy rather than a time window, plus a new class of mock device actions (空调/蓝牙/灯). **It contains one real conflict:** §2 draws an ASR → intent → TTS cascade, and this product is end-to-end speech-to-speech by ADR-002. SPEC-003 shows the requirements are satisfiable on the E2E stack (categories from turn provenance, policy at the playback boundary) and recommends keeping ADR-002 — but that is the owner's decision, not the spec's. §13's "queue the confirmation until Amap stops speaking" is blocked on the same unknown SPEC-002 measured: there is no observable Amap-is-speaking signal on this device.

## B-003 — Wake word

> "you might need to establish things like awake words to awake the system"

Today the driver must press 按住麦克风开始 to start a session. In a car that is the wrong interaction: hands should stay on the wheel. The persona and wake phrase 你好小诺 already exist in the product identity but are not implemented as an actual trigger.

Specced in [SPEC-001](SPECS/SPEC-001-wake-word.md). It raises genuine architectural conflicts — always-on listening versus the microphone gating added for echo suppression, battery, and Baidu quota — so it needs a decision before any implementation.

BLOCKED_BY: the iFlytek AIKit `apiKey` and `apiSecret` for this application, which are issued from the vendor console and are not on this machine

## B-002 — Quiet during navigation

> "the app voice would occur when amap voice also occur probably you should terminate the voice during the car navigation and only give voice when something like already close music or something else"

Root cause confirmed: we request audio focus but never react to losing it. Recorded in full as **P1** in [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md). Fix in progress.

## B-001 — Stop music by voice

> "currently say close the music also dont work"

Root cause confirmed from logs: the model calls `control_music` for 「播放」 but not for 「关闭」/「关掉」, because the tool declaration was English-only with no binding to the Chinese stop verbs. Recorded in full as **P2** in [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md). Fix in progress.
