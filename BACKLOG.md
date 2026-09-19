# Backlog

Recorded demands from the product owner, newest first. See `agent/INTAKE.md` for how these move to shipped work.

Status values: **Recorded** (captured, not specced) · **Specced** (has a SPEC) · **In milestone** · **Done** · **Dropped**

| # | Demand | Raised | Status | Spec |
| --- | --- | --- | --- | --- |
| B-009 | **Provider-neutral realtime layer** — a second realtime provider must be addable by writing one adapter, without provider-name branches or vendor protocol vocabulary reaching voice/session/tool logic | 2026-09-19 | **Done** 2026-09-19 — breach fixed (`ResponseOutcome`), contract and capability model recorded, `RealtimeProviderContractTest` (25 cases across both in-repo providers), `ProviderBoundaryTest` checked against a reintroduced breach, harness rules and I-13 written | [ADR-009](DECISIONS/ADR-009-provider-neutral-realtime-contract.md) · [SPEC-007](SPECS/SPEC-007-provider-neutral-realtime.md) |
| B-008 | **Complex / contextual voice commands** — the driver speaks naturally (「有点热」「再凉一点」「这个太远了，换个近一点的」) instead of like an API, and the assistant resolves it against what it already did — **bounded by the tools that exist**, never a spoken acknowledgement in place of an execution | 2026-09-19 | **Done** 2026-09-19 — context record, resolver, staleness, ambiguity, clarification and three execution guards, all device-verified, and the live model verified acting on them (CVC-04/09/27 + the named-song refusal). Multi-intent decomposition is deliberately the model's (SPEC-006 §On multi-intent). A human voice in a cabin remains a standing gap |
| B-007 | **Assistant-on-map UI design** — avatar + state indicator + speech bubble upper-left, temporary action-feedback card, compact media/climate bottom bar, and a bottom-right **front-facing camera** button that must not end the assistant session | 2026-09-16 | **Done** 2026-09-19 as a *decision* — the layout is authoritative and folded into [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md) D2. Of its four open questions, **inert bottom-bar controls is closed**: the bar now executes through `ScreenControls`, the same route a spoken command takes (D-3). The rest are tracked where they belong — session entry point with B-003, `openApp(maps)` and camera-vs-§42 in that design | [DEMAND](SPECS/DEMAND-2026-09-16-ui-design.md) → [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md) |
| B-006 | **Embedded Amap navigation MVP** — map-first vehicle UI, `AMapNaviView` inside our Activity, assistant overlay above it, `NavigationController`/`DestinationResolver` abstractions, no external Amap app, no overlay permission. Replacement spec (48 sections) | 2026-09-16 | **Done** 2026-09-19 — M2 closed on device evidence: candidates resolve and render, routes draw, the chosen route is the one driven, arrival auto-stops ([ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)). The key blocker was resolved; the key type is proven by navigation working | [SPEC-005](SPECS/SPEC-005-embedded-amap-mvp.md) |
| B-005 | Automated speech test harness — TTS-simulated user → real pipeline → ASR-verified output; local failure records; regression corpus; latency distributions | 2026-09-16 | **Superseded** by SPEC-005 (three levels A/B/C, UI screenshot assertions, navigation failure stages). Level A is built and running ([docs/EVALUATION.md](docs/EVALUATION.md)); the speech harness drives the phone. The paid-API constraint on audio levels is acknowledged in that document | [SPEC-004](SPECS/SPEC-004-speech-test-harness.md) → SPEC-005 |
| B-004 | Amap coexistence by **voice policy** and generic `ActionExecutor` with a mock | 2026-09-16 | **Superseded** by SPEC-005 Phases 5–6 — both open conflicts resolved (§16 keeps Baidu E2E; §19 supplies the Amap-speaking signal). The guidance mute shipped and is guarded by `GuidanceMicGate` + `NavigationMuteFollowsPhaseTest` | [SPEC-003](SPECS/SPEC-003-amap-coexistence-voice-policy.md) → SPEC-005 |
| B-003 | Wake word to activate the assistant — say 「你好小诺」 instead of pressing a button | 2026-09-15 | **Working, one human check left** 2026-09-19 — 「你好小诺」 fires and opens a session on `2391ff70`, proven with synthesized speech through the real audio path. Unproven: a person's voice in a cabin | [SPEC-001](SPECS/SPEC-001-wake-word.md) |
| B-002 | 小诺 must stay quiet during navigation and speak only short confirmations | 2026-09-15 | **Done** 2026-09-16 | [P1](OPEN_PROBLEMS.md) — verified on device |
| B-001 | 「关闭音乐」 must actually stop the music | 2026-09-15 | **Done** 2026-09-16 | [P2](OPEN_PROBLEMS.md) — verified on device with log evidence |

---

## B-009 — Provider-neutral realtime layer

> "Application-level voice/session/tool/business logic must depend on a provider-neutral realtime
> voice contract, not on Baidu/iFlytek/Qwen/GPT protocol details."

Raised 2026-09-19. The abstraction largely existed already — `RealtimeVoiceProvider`,
`ProviderCapabilities`, `DomainVoiceEvent`, `ErrorClass` — and provider selection was already at the
composition boundary. Measurement found one real breach: `ActionClaimGuard` and
`ConversationResetPolicy` read Baidu's own `output[].type` strings. **Fixed** — they take a neutral
`ResponseOutcome` and the adapter translates at its boundary.

What remains, and is independent of [B-003](#b-003--wake-word):

- [ADR-009](DECISIONS/ADR-009-provider-neutral-realtime-contract.md) — the dependency direction;
- [SPEC-007](SPECS/SPEC-007-provider-neutral-realtime.md) — the contract, the capability model, the
  normalized events and errors, and what a new adapter must satisfy;
- a **shared provider-contract test suite** every adapter runs against;
- an **architecture guard** that fails when vendor vocabulary appears in core;
- harness rules for future provider integrations.

Deliberately **not** in scope: introducing a second concrete provider. [ADR-008](DECISIONS/ADR-008-single-active-realtime-provider.md)
settled that dormant vendor implementations are a liability, and nothing here revives one.

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

Pressing 按住麦克风开始 is the wrong interaction in a car: hands should stay on the wheel. Saying
「你好小诺」 now opens a session on the device, which is what this item asked for.

Specced in [SPEC-001](SPECS/SPEC-001-wake-word.md), decided in
[ADR-006](DECISIONS/ADR-006-wake-word-aikit-shared-capture.md). The architectural conflict it raised
— always-on listening against the microphone gating added for echo suppression — is resolved the way
ADR-006 chose: one capture, owned by the app, handed to whichever consumer is active.

BLOCKED_BY: a person saying 「你好小诺」 out loud to this build on `2391ff70` — the acoustic path
(a human voice at distance, over road noise, against threshold 1450) is the last unproven step and
no amount of synthesized audio can stand in for it

### Diagnosed and fixed on device 2026-09-19

The APPID and the `.jet` were both correct all along. The engine failed because it had opened a
**second microphone recorder**: `cannot get record permission, get invalid audio data` from
`com.iflytek.cloud.record.PcmRecorder`, reported to the app as `code=200061`, a network code.

`IflytekWakeWordDetector` never set `AUDIO_SOURCE`, and nothing in `app/src/main` ever called
`writeFrame` — so the app was configured for neither the engine-owns-the-mic design nor the app-fed
one that [ADR-006](DECISIONS/ADR-006-wake-word-aikit-shared-capture.md) chose. Fixed by setting
`AUDIO_SOURCE=-1` and giving `WakeWordController` an idle capture that stands down while a session
owns the mic. Full evidence in [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md); regression-protected by
`WakeAudioPathTest`.

### What is actually required, corrected 2026-09-19

The earlier blocker said "AIKit `apiKey` and `apiSecret`". That was true of the **AIKit** SDK and is
no longer true of this repository: [FINDINGS-2026-09-16](SPECS/FINDINGS-2026-09-16-iflytek-msc-sdk.md)
records that the delivered SDK was replaced by **MSC v1140** (`com.iflytek.cloud`, `Msc.jar`,
`libmsc.so` + `libw_ivw.so`), and `AIKit.aar` is gone from `app/libs/`. No code references AIKit.

| Needed | State |
| --- | --- |
| iFlytek **APPID** | **present** — entered on device 2026-09-19, stored in the Android Keystore |
| APIKey / APISecret | **not used by MSC.** `DeveloperSettingsActivity` says so in a comment, and the UI has no field for them. Storing them would be storing a secret with no consumer |
| Wake resource `assets/ivw/wakeword.jet` | **present and correctly paired** — it is bound to the same APPID the iFlytek console shows for this app, verified 2026-09-19 |
| AIKit ability `e867a88f2` | an **AIKit** concept. Irrelevant while MSC is integrated; returning to AIKit would need the SDK back and an ADR-006 revision |
| Device activation quota / first-run network | MSC activates on first init; the phone has network again as of 2026-09-19 |

So nothing is missing from the credential path, which exists end to end:
`DeveloperSettingsActivity` → `WakeWordSettings` → `AndroidKeystoreCredentialStore`
(keys `iflytek_app_id`, `iflytek_api_key`, `iflytek_api_secret`).

**If the product owner intends to return to AIKit** — which is what an APIKey, an APISecret and an
ability id imply — that is a product decision, not a credential entry: it needs the AIKit SDK
restaged, ADR-006 revised against the FINDINGS, and the MSC integration removed or made selectable.

## B-002 — Quiet during navigation

> "the app voice would occur when amap voice also occur probably you should terminate the voice during the car navigation and only give voice when something like already close music or something else"

Root cause confirmed: we request audio focus but never react to losing it. Recorded in full as **P1** in [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md). Fix in progress.

## B-001 — Stop music by voice

> "currently say close the music also dont work"

Root cause confirmed from logs: the model calls `control_music` for 「播放」 but not for 「关闭」/「关掉」, because the tool declaration was English-only with no binding to the Chinese stop verbs. Recorded in full as **P2** in [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md). Fix in progress.
