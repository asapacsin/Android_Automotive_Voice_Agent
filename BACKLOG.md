# Backlog

Recorded demands from the product owner, newest first. See `agent/INTAKE.md` for how these move to shipped work.

Status values: **Recorded** (captured, not specced) · **Specced** (has a SPEC) · **In milestone** · **Done** · **Dropped**

| # | Demand | Raised | Status | Spec |
| --- | --- | --- | --- | --- |
| B-034 | **Gemini voice sounds like an old woman** — owner wants a 符玄-like voice; owner ear test rejected every Gemini prebuilt voice, options A–D in §B-034 | 2026-10-02 | Open — **owner decision** | §B-034 below |
| B-033 | **Status cards fade out** — an error/status card on the map disappears by itself after a while instead of staying forever | 2026-10-02 | **Done** (emulator check queued) | §B-033 below |
| B-032 | **Car agent with domain servers** — "a more clever agent system that has several car MCP"; Gemini stays the one agent, car functions become MCP-shaped domain servers | 2026-09-30 | **Specced** | [SPEC-016](SPECS/SPEC-016-gemini-default-and-domain-servers.md) · [ADR-015](DECISIONS/ADR-015-car-domain-servers.md) |
| B-031 | **Guidance in 小诺's voice** — mute Amap, the model speaks every guidance sentence; Amap only when the model cannot; Gemini everywhere (one voice) | 2026-09-30 | **Specced** | [SPEC-018](SPECS/SPEC-018-guidance-in-assistant-voice.md) · [ADR-013](DECISIONS/ADR-013-gemini-default-provider.md) · [ADR-014](DECISIONS/ADR-014-guidance-spoken-by-assistant.md) |
| B-030 | **Music from a vague description** — 「梶浦的、空之境界里很燃的 OP」 → the AI picks and plays it, via the driver's music app | 2026-09-30 | **Specced** | [SPEC-017](SPECS/SPEC-017-music-from-description.md) |
| B-029 | **Fuzzy driving requests** — 嗲一点 (voice style), 座位高/低 (seat), 有蚊子 (windows), and every action announced from its result; plus more phrases of the same kind | 2026-09-30 | **Specced**; all decisions taken | [SPEC-015](SPECS/SPEC-015-fuzzy-driving-requests.md) · [architecture](docs/FUZZY_DRIVING_ARCHITECTURE.md) · [ADR-012](DECISIONS/ADR-012-fuzzy-driving-requests.md) |
| B-028 | **A Gemini-native architecture** — "design a new architecture that should optimise for the new Gemini API, because the last test said the old design is slow and poorly suited to it". Measured: the extended-thinking model is the cause of slow actions; the whole-reply claim hold is the cause of slow conversation | 2026-09-29 | **Specced**; proposal awaiting the owner (N-1, N-2) | [SPEC-014](SPECS/SPEC-014-gemini-native-voice-path.md) · [ADR-011](DECISIONS/ADR-011-gemini-native-voice-path.md) (Proposed) |
| B-027 | **Gemini Live as a second voice provider** — the owner chose Gemini 3.8 Live Extended Thinking; build it behind the provider seam, opt-in, Baidu stays default | 2026-09-29 | **Built (code, cloud-verified)**; device rows open | [SPEC-013](SPECS/SPEC-013-gemini-live-provider.md) · [ADR-010](DECISIONS/ADR-010-gemini-live-second-provider.md) |
| B-026 | **One owner for who may speak** — a single arbiter decides between Amap guidance, other apps' audio and 小诺's replies (calls later), using a driver-workload signal (distance to the next manoeuvre), replacing today's separate special cases. *Not* the assistant speaking first | 2026-09-24 | **In milestone** M4 | [SPEC-012](SPECS/SPEC-012-speech-arbiter.md) |
| B-025 | **Live information from Amap** — weather at the destination, traffic on the route, along-route search (fuel, charging, service areas, toilets), place details (hours, parking); one tool each with an honest failure result | 2026-09-24 | **In milestone** M4 | [SPEC-011](SPECS/SPEC-011-amap-live-info.md) |
| B-024 | **Say anything on screen (可见即可说)** — every visible control publishes id, label, aliases, position and action; a deterministic matcher resolves the driver's words without the model | 2026-09-24 | **In milestone** M4 | [SPEC-010](SPECS/SPEC-010-screen-affordances.md) |
| B-023 | **Voice style** — prefer a younger cute female voice (符玄-like), not an older-woman timbre | 2026-09-20 | **Done** (code) 2026-09-21 — default **4196** 度清影; picker in developer settings; device ear-check [VOICE-STYLE-001](TEST_MATRIX.yaml) | [P30](OPEN_PROBLEMS.md) |
| B-022 | **Repeatable navigation QA without a real drive** — **screen-recording movie** of nav UI under `D:\桌面\android_doc\` | 2026-09-20 | **Done** 2026-09-21 — [NAV-SIM-QA-001](TEST_MATRIX.yaml) PASS | [P29](OPEN_PROBLEMS.md) |
| B-021 | **「你能做什么」 / 「你能干啥」 must list real capabilities** | 2026-09-20 | **Done** 2026-09-22 — [HELP-001](TEST_MATRIX.yaml) PASS (colloquial 干啥 on 2391ff70) | [P28](OPEN_PROBLEMS.md) |
| B-020 | **No phantom 「没听清」 after 小诺 speaks (esp. navigation start)** | 2026-09-20 | **Done** 2026-09-21 — [ECHO-001](TEST_MATRIX.yaml) PASS | [P27](OPEN_PROBLEMS.md) |
| B-009 | **Provider-neutral realtime layer** — a second realtime provider must be addable by writing one adapter, without provider-name branches or vendor protocol vocabulary reaching voice/session/tool logic | 2026-09-19 | **Done** 2026-09-19 — breach fixed (`ResponseOutcome`), contract and capability model recorded, `RealtimeProviderContractTest` (25 cases across both in-repo providers), `ProviderBoundaryTest` checked against a reintroduced breach, harness rules and I-13 written | [ADR-009](DECISIONS/ADR-009-provider-neutral-realtime-contract.md) · [SPEC-007](SPECS/SPEC-007-provider-neutral-realtime.md) |
| B-008 | **Complex / contextual voice commands** — the driver speaks naturally (「有点热」「再凉一点」「这个太远了，换个近一点的」) instead of like an API, and the assistant resolves it against what it already did — **bounded by the tools that exist**, never a spoken acknowledgement in place of an execution | 2026-09-19 | **Done** 2026-09-19 — context record, resolver, staleness, ambiguity, clarification and three execution guards, all device-verified, and the live model verified acting on them (CVC-04/09/27 + the named-song refusal). Multi-intent decomposition is deliberately the model's (SPEC-006 §On multi-intent). A human voice in a cabin remains a standing gap |
| B-007 | **Assistant-on-map UI design** — avatar + state indicator + speech bubble upper-left, temporary action-feedback card, compact media/climate bottom bar, and a bottom-right **front-facing camera** button that must not end the assistant session | 2026-09-16 | **Done** 2026-09-19 as a *decision* — the layout is authoritative and folded into [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md) D2. Of its four open questions, **inert bottom-bar controls is closed**: the bar now executes through `ScreenControls`, the same route a spoken command takes (D-3). The rest are tracked where they belong — session entry point with B-003, `openApp(maps)` and camera-vs-§42 in that design | [DEMAND](SPECS/DEMAND-2026-09-16-ui-design.md) → [SPEC-005-P1-design](SPECS/SPEC-005-P1-design.md) |
| B-006 | **Embedded Amap navigation MVP** — map-first vehicle UI, `AMapNaviView` inside our Activity, assistant overlay above it, `NavigationController`/`DestinationResolver` abstractions, no external Amap app, no overlay permission. Replacement spec (48 sections) | 2026-09-16 | **Done** 2026-09-19 — M2 closed on device evidence: candidates resolve and render, routes draw, the chosen route is the one driven, arrival auto-stops ([ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)). The key blocker was resolved; the key type is proven by navigation working | [SPEC-005](SPECS/SPEC-005-embedded-amap-mvp.md) |
| B-005 | Automated speech test harness — TTS-simulated user → real pipeline → ASR-verified output; local failure records; regression corpus; latency distributions | 2026-09-16 | **Superseded** by SPEC-005 (three levels A/B/C, UI screenshot assertions, navigation failure stages). Level A is built and running ([docs/EVALUATION.md](docs/EVALUATION.md)); the speech harness drives the phone. The paid-API constraint on audio levels is acknowledged in that document | [SPEC-004](SPECS/SPEC-004-speech-test-harness.md) → SPEC-005 |
| B-004 | Amap coexistence by **voice policy** and generic `ActionExecutor` with a mock | 2026-09-16 | **Superseded** by SPEC-005 Phases 5–6 — both open conflicts resolved (§16 keeps Baidu E2E; §19 supplies the Amap-speaking signal). The guidance mute shipped and is guarded by `GuidanceMicGate` + `NavigationMuteFollowsPhaseTest` | [SPEC-003](SPECS/SPEC-003-amap-coexistence-voice-policy.md) → SPEC-005 |
| B-003 | Wake word to activate the assistant — say 「你好小诺」 instead of pressing a button | 2026-09-15 | **Done** 2026-09-19 — spoken by the product owner, the session opens. Human-verified end to end | [SPEC-001](SPECS/SPEC-001-wake-word.md) |
| B-010 | **Saved places** — 「回家」「去公司」 must navigate, and an unset slot must be admitted rather than guessed | 2026-09-19 | **Done** 2026-09-19 — device-verified both ways. Driving to arrival is blocked by a physical GPS condition, not by this | [CAPABILITIES](docs/CAPABILITIES.md) |
| B-011 | **Spoken vague and contextual requests reach the right tool** — the scenario set, through the live model | 2026-09-19 | **Done** 2026-09-20 — 19/19, every §scenario row this hardware can reach; 「回家」 measured at 4/5 | [SPEC-008](SPECS/SPEC-008-live-scenario-suite.md) |
| B-012 | **Wake-word reliability is uncharacterised** — false accepts over a long drive, and detection at distance with road noise | 2026-09-19 | **Half done** 2026-09-20 — 0 false wakes in 16 min with music, engine proven alive. Detection by a human voice still needs a person | [SPEC-001](SPECS/SPEC-001-wake-word.md) |
| B-013 | **`BaiduFlexClientTest` readiness timeout is load-sensitive** — it fails under a full parallel suite and passes alone | 2026-09-19 | **Done** 2026-09-20 — the wait no longer encodes machine speed, and the timeout has its own test | §B-013 below |
| B-014 | **A false sentence is spoken before it is corrected** — the correction follows; the driver still heard the claim | 2026-09-19 | **Done** 2026-09-20 — held and dropped; measured cost 93–515 ms | [P23](OPEN_PROBLEMS.md) |
| B-015 | **Cantonese is not understood** — 「返屋企啦」 transcribes as 「发诺克拉」; the product owner speaks Cantonese | 2026-09-19 | Open | §B-015 below |
| B-016 | **A rejected session still looked like it was listening** — `state=ERROR` with `listening=ACTIVE` and zero frames, for 30 s | 2026-09-19 | **Done** 2026-09-19 — device-verified by reproducing the rejection | §B-016 below |
| B-017 | **A duplicate correction is device-observed but not unit-covered** — the fix is in; the regression test is not | 2026-09-20 | **Done** 2026-09-20 — covered, after three attempts and one real bug found on the way | §B-017 below |
| B-018 | **Calling** — resolve a contact, confirm, then dial; and admit when the car cannot call at all | 2026-09-20 | **Partly done** 2026-09-20 — the refusal and the confirmation contract are verified; dialling needs a SIM | §B-018 below |
| B-019 | **Release readiness** — the release APK is unsigned and 227 MB; neither is fixable without the owner | 2026-09-20 | Open | §B-019 below |
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

**CLOSED 2026-09-19.** The product owner said 「你好小诺」 to the build on `2391ff70` and the
session opened. Every level of the evidence hierarchy this item can reach has been reached.

What is *not* claimed by closing it: a measured false-accept rate over a long drive, and detection
at distance with road noise and passengers. Those are reliability characteristics, tracked as
[B-010](#b-010--wake-word-reliability-is-uncharacterised), not preconditions for the feature
existing.

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

## B-010 — Saved places

**CLOSED 2026-09-19.** Evidence in [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md).

## B-011 — Spoken vague and contextual requests

The scenario set this product is aimed at, stated as the driver would say it:

| # | Utterance | What must happen | State |
| --- | --- | --- | --- |
| S1 | 「把空調調到22度」 | `control_climate{set_temperature,22}` | device-verified |
| S2 | 「有啲熱，幫我舒服啲」 | a real climate change, not a sentence about one | unit + live (SPEC-006 CVC-04) |
| S3 | 「返屋企啦」 | `navigate_to{回家}` → the saved home | **the tool path is proven; the spoken path is not** |
| S4 | 「播啲精神啲嘅歌」 | refused honestly — there is no music library | unit |
| S5 | 「搵間附近仲開緊嘅餐廳，帶我去，順便打電話問下有冇位」 | decomposition, then a call | **no calling capability exists** |
| S6 | context resolves an otherwise ambiguous request | no clarification asked | SPEC-006 |
| S7 | genuinely ambiguous request | clarification asked, nothing executed | SPEC-006 CVC-09 |
| S8 | a tool fails | recovery or an honest failure, never a claim | partial |
| S9 | an action needs confirmation or refusal | safety decision reaches execution | partial |
| S10 | the driver interrupts mid-execution | the task stops | 「闭嘴」「休眠」 device-verified |
| S11 | wake / listening / executing audio ownership | no conflict | device-verified 2026-09-19 |

**Why it matters:** every row above that is not device-verified is a claim about the product that
rests on a test double. The Cantonese phrasing is deliberate and not decoration — the product owner
speaks it, and a Mandarin-only system would pass every test here and fail in the car.

**Acceptance:** each row driven through the live model on `2391ff70`, with the tool call and the
resulting state in the log, not the reply text.

**Verification:** the speech harness injects synthesized utterances into a live session; assert on
`flex_event`/dispatch logs.

## B-012 — Wake-word reliability

Detection exists and a human has confirmed it. Unmeasured: the false-accept rate with music and
guidance playing over a sustained period, and detection at arm's length with road noise. Threshold
is 1450 of 0–3000, lower being easier to wake.

Wake-word reliability has two halves, and only one of them needs a person.

**The half a machine can measure — false accepts.** Leave the engine listening with the car's own
music playing and count how many times it wakes. A false accept is worse than a missed wake: the
assistant interrupts a driver who did not ask for it. `tools/speech-harness/measure_false_wake.py`
does this, and checks the engine was still alive at the end — an engine that quietly stopped
listening would also report zero.

**The half that needs a person — detection.** A human voice, at arm's length, over road noise, in
a moving car. Synthesized speech through the injection path proves the model matches the phrase; it
says nothing about a real cabin, and no amount of it will.

**Acceptance:** (a) a measured false-accept count over ≥15 minutes of playback, with the engine
proven alive; (b) a detection rate for a human voice at a stated distance with the car moving.
**Verification:** (a) autonomous, recorded below; (b) device, with a person driving.

**(a) is done, 2026-09-20: 0 false wakes in 16 minutes with music playing**, 0 engine errors, 0
restarts, engine alive at the end. Evidence in [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md).

BLOCKED_BY: a person saying 「你好小诺」 at a stated distance with the car actually moving, which is
the only way to measure detection in a real cabin

## B-013 — A load-sensitive test

`BaiduFlexClientTest.completedToolTurnStartsAFreshConversationAndHeldAudioReachesIt` failed with
`Baidu Flex session readiness timed out` during a full parallel suite on 2026-09-19 and passed on
its own immediately after. It is a real timing dependency in a readiness wait, not a product defect
yet — but a suite that fails for reasons unrelated to the change under test destroys the value of
running it, and that is the whole basis of this project's evidence.

**CLOSED 2026-09-20.** Not by making the wait longer everywhere, but by noticing that none of
those tests is *about* how long readiness may take — they are about what happens once it arrives.
A 3-second wait in such a test encodes how busy the machine is, and nothing else. They now share a
named 30-second constant that says so.

What was actually missing is now present: **a test that the timeout fires at all.** A socket that
upgrades and then says nothing must fail with `BAIDU_FLEX_TIMEOUT`, and that test uses 300 ms of
its own. So the behaviour gained coverage while the suite lost a dependency on machine speed.

The original acceptance asked for 20 consecutive clean runs. That was the wrong bar — it measures
luck, not the change. Three consecutive `--rerun-tasks` full-suite runs are recorded, and the
argument that carries the rest is structural: no test now waits on a window a loaded machine can
miss.

## B-014 — The claim is spoken before the correction

`ActionClaimGuard` sends a follow-up *after* a response completes, so the driver hears the false
sentence and then hears it retracted. Better than the sentence standing, which is what P23 fixed,
but not right.

The honest fix is to hold reply audio until the response is done and its outcome is known —
`DriverTurn` already holds audio pending execution proof, so the machinery exists. The cost is
latency on every reply, which in a car is not free.

**CLOSED 2026-09-20.** The cost was measured before the design was chosen, which turned a judgement
call into arithmetic. `reply_timing` instrumentation on device gave **93–515 ms** between the driver
first hearing a reply and the app first knowing whether it was true — and **zero** for a turn that
called a tool, because the spoken result is a separate response that happens after the tool has
already run. So the whole cost falls on replies that called nothing, which are exactly the ones
that can be false.

`DriverTurn` now holds `CONVERSATION` and `UNKNOWN` turns until the response resolves, then drops
the reply if it claims a car action nothing performed. The `contextAwaitingAnswer` exemption was
closed for the same reason it was closed for `Kind.ACTION` in 2026-09-18: a picker on screen means
a *prompt* is wanted, not that an action happened. Doubtful audio with a picker open stays exempt —
a repair must reach a driver who is mid-choice.

## B-015 — Cantonese

Measured 2026-09-19 against the live model, synthesized `zh-HK`:

| Said | Heard | Result |
| --- | --- | --- |
| 「返屋企啦」 | 「发诺克拉。」 | nothing usable |
| 「有啲熱，幫我舒服啲」 | 「有的人帮我舒服的。」 | wrong, but close enough that the model guessed 「有点热」 |
| 「播啲精神啲嘅歌」 | 「波低精神的k歌。」 | `control_music{play}` ran — the bundled track, for a request that named a *style*. A false capability claim that `isSpecificMediaRequest` would have caught had the transcript survived |

So it is not a clean failure: it is **plausible mis-transcription**, which is worse, because the
model then acts confidently on a sentence the driver never said. P23 catches the case where nothing
runs; it cannot catch a wrong tool running on a wrong transcript.

This matters more than a localisation nicety: the product owner speaks Cantonese, and every
scenario in the target set was written in it.

### Answered 2026-09-19: there is no dialect hint, and that is not the whole story

The session already carries `input_audio_transcription.language`. Setting it to `yue` was rejected
by the server in as many words:

```
BAIDU_FLEX_API_REJECTED Invalid value: 'yue'. Value must be null or 'zh'.
```

So the transcriber is Mandarin-only and no configuration changes that. **But the transcript is not
what the model hears.** This is an end-to-end speech model: it consumes the audio. And it plainly
understood the Cantonese — 「有啲熱，幫我舒服啲」 produced 「有点热啊，我帮你调低一点温度」, which is a
correct reading of an utterance the transcriber turned into 「有的人帮我舒服的。」, and 「播啲精神啲嘅歌」
produced an actual `control_music` call.

So Cantonese comprehension is **partly there**. What degrades is **tool calling**: the same request
in Mandarin calls `control_climate` (SPEC-006 CVC-04, device-verified), and in Cantonese it
answered with words and called nothing.

That reframes the decision. It is not "support Cantonese or do not"; it is whether to invest in
making tool calls reliable for non-Mandarin input, against a model whose transcriber cannot be told
what language it is hearing.

**Acceptance:** either the scenario set passes in Cantonese, or ADR-002 records the limit and
`capabilities.yaml` says the product is Mandarin-only, so nothing downstream claims otherwise.
**Verification:** the same harness clips, re-run.

### Measured 2026-09-20, five runs each

| Said | Must reach | Rate |
| --- | --- | --- |
| 「返屋企啦」 | `navigate_to` | **2/5** |
| 「有啲熱，幫我舒服啲」 | `control_climate` | **0/5** |
| 「播啲精神啲嘅歌」 | an honest refusal | **0/5 before the P25 fix, 3/3 after** |

`tools/speech-harness/scenarios-cantonese.json`, deliberately outside the default suite: it is
expected to fail some of the time, and a suite that is red by design stops being read.

### Then one sentence was added to the persona, and re-measured

The instructions never told the model it might hear Cantonese — only that it must *reply* in
Mandarin. Adding that the driver may speak it, that the transcript may look strange, and that a
recognisable instruction should still be executed:

| Said | Must reach | Before | After |
| --- | --- | --- | --- |
| 「返屋企啦」 | `navigate_to` | 2/5 | **4/5** |
| 「有啲熱，幫我舒服啲」 | `control_climate` | 0/5 | **1/5** |
| 「播啲精神啲嘅歌」 | an honest refusal | 0/5 | **5/5** (P25) |

Mandarin re-measured straight after: **20/20**, no regression.

So the navigation case roughly works now, and the implicit-comfort case still does not — the
phrasing that has no keyword in it at all is the one the transcriber destroys most completely.
The third row was never a recognition problem: it was [P25](OPEN_PROBLEMS.md), the app *acting* on
a mis-transcription, and it is now refused honestly whatever the transcript says.

BLOCKED_BY: the owner choosing between three options that are not comparable on technical grounds
— accept Mandarin-only and say so in ADR-002 and the capability registry; keep the current partial
support and document the measured rates as the expected behaviour; or reopen ADR-008 and evaluate a
provider whose transcriber accepts Cantonese

**Needs a product decision, not more engineering.** The evidence is in; the alternatives are real
and not comparable on technical grounds: accept Mandarin-only and say so, spend effort on prompt
work aimed at tool calling for accented or non-Mandarin input, or change provider — which
[ADR-008](DECISIONS/ADR-008-single-active-realtime-provider.md) settled and would be reopening.
Until then, [P23](OPEN_PROBLEMS.md) makes the failure honest: the driver is told nothing happened
rather than told it did.

## B-016 — A rejected session still looked like it was listening

Measured while testing the above. When the server rejected the configuration, the app logged
`state=ERROR err=BAIDU_FLEX_API_REJECTED` and then sat for ~30 s with
`session_diag listening=ACTIVE captureSuspended=false captured=0`.

The session was dead and the listening lifecycle did not know. A driver watching the screen would
see the assistant listening while every word fell on the floor — and the failure that produced it
was a *configuration* error, the kind that survives a reconnect, so waiting does not help.

**CLOSED 2026-09-19.** `onConnectionLost` is for a session that is coming back - staying ACTIVE
through a reconnect is right, or every network wobble would cost the driver their turn. A terminal
failure now takes the separate `onSessionFailed` path to DEEP_IDLE and closes the session.

Proven by reproducing the exact failure on `2391ff70`, with the same rejected configuration:

| | |
| --- | --- |
| Before | `state=ERROR` → 30 s of `listening=ACTIVE captureSuspended=false captured=0` |
| After | `state=ERROR` → `state=DISCONNECTED` → `listening=DEEP_IDLE`, immediately |

Baseline re-verified after reverting the injected fault: 「关闭空调。」 → `control_climate` →
`✓ 空调关 · 24°C · 风2` → 「空调已关闭。」

## B-017 — The duplicate correction has no regression test

Measured on `2391ff70`, 2026-09-19: 「算了」 produced two identical follow-ups
(`flex_user_text chars=127` twice, 2 ms apart) and `exit_navigation_mode` ran twice. Two components
correct the same response independently — `DriverTurn` when it drops a reply, and
`ActionClaimGuard` on its own judgement. `exit_navigation_mode` is idempotent so nothing broke;
`control_climate{adjust_temperature,-2}` twice is −4 °C, and is only saved by the dispatcher's
`DUPLICATE_IN_TURN` guard — a second line of defence doing a first line's job.

**Fixed** by making corrections single-owner: when `DriverTurn` sends one, `ActionClaimGuard` does
not.

**Not covered by a test, and this is the honest part.** A scripted-server test was written and
deleted: it passed with *and* without the fix. The reason is diagnosable — `ConversationResetPolicy`
resets after the tool turn, and the second correction lands in `heldOutbound` and never reaches the
wire in the scripted flow, so only one is ever observable there. On the device the reset completes
and both go out. A test that passes either way is worse than no test, because it claims coverage
that does not exist.

**CLOSED 2026-09-20.** It took three attempts, and the first two passing was the useful part.

| Attempt | Why it passed without the fix |
| --- | --- |
| 1 | The mock answered only the opening handshake, so the follow-up turn hung in `ResponseTurnGate` |
| 2 | The mock still never answered `response.create`, so the second correction never left the client |
| 3 | The transcript arrived *after* `response.created`, so the turn stayed `UNCLASSIFIED_CLAIM` instead of being re-classified as `ACTION` — and only the `ACTION` path sends a correction of its own |

The third one was not a test bug. `onUserTranscript` re-decides a hold taken before the kind was
known, but it only did so for `PHANTOM_AUDIO` and `NONE`. The new `UNCLASSIFIED_CLAIM` hold was
left in place, so a turn known to be an ACTION kept being treated as unclassified, and an action
claim that execution proof would have released mid-response waited for the response to end instead.
Fixed, and the test now fails without the single-owner guard (**n=2**) and passes with it.

Found in the same pass and fixed: with a *known* request, the fallback was telling the driver
「刚才没有听清楚」 for a sentence the app had understood. Measured on device — 「算了」 answered
「退出导航中…」 with no tool call. When the request is known the follow-up must make the model perform
it; `describesCarAction` now feeds the classified branch too. Device-verified: 「算了」 with a picker
open cancels for real (`nav_flow_cancelled`, `exit_navigation_mode`, 「已取消选择。」).

## B-018 — Calling

The last capability named in the product objective that did not exist. It is the one tool here that
reaches a **person who did not ask to be reached**, so it is built to refuse rather than to guess.

**Two turns, on purpose.** The first call resolves the contact and returns `confirm_required` with
the display name; only a second call carrying `confirmed=true` dials. Every other tool in this
product can be undone by saying the opposite — a wrong temperature is wrong for ten seconds. A
wrong call has already rung someone. And the risk is measured, not theoretical:
[P23](OPEN_PROBLEMS.md) recorded the model acting confidently on a sentence the driver never said.

**The number never reaches the model.** It would end up in a transcript, and the driver knows their
own contacts. Names and numbers are never logged; the log says how many matched.

**What is proven** (device, `2391ff70`, `gsm.sim.state=ABSENT,ABSENT`):

| | |
| --- | --- |
| A car that cannot call says so | `NO_TELEPHONY`, with wording that forbids claiming otherwise |
| No dialler is started on refusal | no intent, nothing launched |
| One match is never dialled on the first turn | unit |
| Two matches are offered, never chosen between — even with `confirmed=true` | unit |
| An unknown name is not substituted for a real one | unit |

**What is not proven, and cannot be here: a call connecting.** The test phone has no SIM. That is a
physical dependency, not a code one.

BLOCKED_BY: a SIM in the test phone (or another Android device with an active SIM), and a number
its owner is willing to have this app ring, before a call can be dialled and heard to connect

Until then no call has been placed by this project, and none will be.

**Acceptance for the rest:** a confirmed call reaches a real handset, and cancelling before
confirmation dials nothing. **Verification:** device, with a person, on a phone that has service.

## B-019 — Release readiness

`:app:assembleRelease` succeeds, which is worth knowing. What it produces is not shippable:

**Unsigned.** `app-release-unsigned.apk`. Signing needs a keystore and its passwords, which are the
owner's credentials and are not going anywhere near this repository ([I-7](docs/INVARIANTS.md)).

**227 MB**, against 229 MB for debug — so essentially nothing is being stripped. The reason is that
almost none of it is code:

| | |
| --- | --- |
| `libAMapSDK_NAVI` arm64 + armeabi-v7a | **108 MB** |
| `libw_ivw` + `libmsc` (wake, both ABIs) | 28 MB |
| `libneonui_shared` + `libnui` (both ABIs) | 25 MB |
| `classes.dex` | 9.6 MB |
| bundled track, Amap resources | 15 MB |

`isMinifyEnabled = false` for release. Turning R8 on would shrink `classes.dex` and nothing else —
a few MB of 227 — and it carries a real risk with three SDKs that use reflection, which could only
be discharged by installing and exercising a *signed* release build. So it waits on the same thing.

**The lever that would actually work is dropping `armeabi-v7a`**, which would remove roughly 70 MB.
That is a compatibility decision: no 32-bit device could install it. Modern Automotive head units
are arm64, but "modern" is the owner's call, not mine. ABI *splits* are the middle path — separate
per-ABI APKs, nobody loses support — and are worth doing if the distribution channel supports them.

BLOCKED_BY: a signing keystore and its credentials, which only the owner can provide, plus a
decision on whether armeabi-v7a must keep working

**Acceptance:** a signed release APK installs and passes the scenario suite; a stated size and the
ABI decision recorded. **Verification:** device, with the signed build.


## B-020 — No phantom 没听清 after 小诺 speaks

Code done 2026-09-20. Device [ECHO-001](TEST_MATRIX.yaml) PASS 2026-09-21.

## B-021 — Capability help answer

**Done 2026-09-22.** Device [HELP-001](TEST_MATRIX.yaml) PASS on `2391ff70`: `help_gan_sha` clip →
transcript=你能干啥 → reply names 导航 and other supported groups; `TURN_RELEASE reason=capability_help`.
Unit [HELP-UNIT-001](TEST_MATRIX.yaml) PASS. [P28](OPEN_PROBLEMS.md) closed. S21 uses `help_gan_sha`.

## B-022 — Repeatable navigation QA clip

**Done 2026-09-21.** [NAV-SIM-QA-001](TEST_MATRIX.yaml) PASS — `D:\桌面\android_doc\nav_qa_to_shizimen.mp4` exists and shows nav UI.

## B-023 — Voice style

**Done (code) 2026-09-21.** Owner chose Flex voice **4196** (度清影-甜美女声). Default + developer picker ship; cabin ear-check remains [VOICE-STYLE-001](TEST_MATRIX.yaml).

## B-024 — Say anything on screen

Raised 2026-09-24 as the most reliable of six directions reviewed that day (the others are not
recorded: they depend on unmeasured provider behaviour or cannot work on a real car — see below).

Generalise `NavigationChoiceResolver` into a registry of screen affordances. Each visible control
publishes its id, label, aliases, position number and action; the driver's transcript
(`conversation.item.input_audio_transcription.completed`, already received) is matched
deterministically, and the current list goes into `VoiceContextHints`. Execution goes through the
existing owners (`ScreenControls`, `AndroidToolDispatcher`) — no second route.

Why reliable: no model decision is involved, so it is unit-testable end to end. Limit: it covers only
what is on screen, and a transcription error still misses.

Specced in [SPEC-010](SPECS/SPEC-010-screen-affordances.md). Status 2026-09-25: steps 1–4a built (L2); step 4b and A7 wait for the phone.

## B-025 — Live information from Amap

Raised 2026-09-24. New tools on the Amap web-service API that `AmapPoiClient` already calls with the
separate web-service key (`AmapSettings.loadWebKey`, `AMAP_WEB_KEY_MISSING` when absent): weather,
route traffic, along-route search, place details.

Why reliable: each call is a deterministic lookup with a checkable failure. Limit, to measure before
claiming the feature: every new tool still depends on Flex *choosing* to call it — the failure class of
[P2](OPEN_PROBLEMS.md) — and adding tools may lower selection accuracy for existing ones. Personal
Amap keys have daily quotas.

Specced in [SPEC-011](SPECS/SPEC-011-amap-live-info.md). Status 2026-09-25: steps 1–3 and 5 built (L2); device steps 0, 4 and 6 wait for the phone.

## B-026 — One owner for who may speak

Raised 2026-09-24. Consolidate the guidance/assistant/call speech rules (today `GuidanceMicGate` and
the P1/P3 fixes) into one arbiter, modelled on Android Automotive's audio-focus interaction table, with
a workload input from the navigation state. Per AGENTS.md, extend the existing owner rather than add a
parallel mechanism, and delete what it replaces.

**Status 2026-09-25:** built at L2 as [SPEC-012](SPECS/SPEC-012-speech-arbiter.md) steps 1–4 — `SpeechArbiter` via `SpeechAuthority` replaces `GuidanceMicGate`, `VoicePolicy` and the `NavigationState` window (deleted); the workload hold is wired. Device rows A6/A7 open.

Deliberately excluded: proactive prompts (「前方拥堵，换路线吗？」). There is no TTS, so the
assistant speaking first would mean asking Flex to say a line, which it may reword; that needs its own
measurement first. Amap's voice is its built-in one (`setUseInnerVoice`) — it can be muted or deferred,
not re-spoken by us.

### Not recorded, and why (2026-09-24)

- **Talker/planner split** — unknown whether Flex honours `create_response: false`; without it the
  hand-off itself rests on function calling. Needs a one-day spike before any ADR.
- **Offline mode** — on-device ASR must share the microphone with the iFlytek wake word and AEC3, and
  phone TTS quality varies by vendor; reopens the no-separate-ASR/TTS rule.
- **Real vehicle control** — HVAC and window properties need signature/privileged permissions a normal
  app cannot hold on a real car; emulator-only demo.

## B-029 — Fuzzy driving requests (模糊意图)

Raised 2026-09-30 by the owner (`D:\桌面\android_doc\fuzzy logic.docx`). Status: **Recorded** — needs
architecture (seat/window tools do not exist) and a SPEC before building.

What the owner asked for:

| Driver says | Wanted | Today |
| --- | --- | --- |
| 说话能不能嗲一点 | 小诺 switches to a sweeter, more coquettish speaking style | Voice is fixed per session (B-023, 4196); no spoken style switch |
| 座位有点高 / 有点低 | Seat height goes down / up | `windows_seats_doors_lights_wipers` was `unsupported` (2026-09-30: windows and seat height became `body.*`, the rest is `unsupported.sunroof_doors_lights_wipers`) — no tool |
| 有蚊子 | Open the windows to let it out | Same — no window tool |
| (every action) | 小诺 says what it actually did, e.g. 「已把车窗打开一半」 | Mostly true via the claim gate (I-11), not a stated rule for multi-action turns |
| "and more like these" | Other fuzzy driving phrases | B-008/SPEC-006 covers 有点热/再凉一点 against existing tools |

Review notes (2026-09-30):

1. **These are B-008 extended to new actuators.** The resolver pattern (UtteranceIntentResolver →
   tool, context record, ambiguity policy) already exists; extend it, don't add a second one.
2. **Seats and windows must be simulated vehicle state**, like climate: a car model + bottom-bar/overlay
   display + `control_seat` / `control_window` tools with honest results. Real actuation needs
   privileged permissions (see "Not recorded" above). `capabilities.yaml` flips from `unsupported`
   only for what is built, and TRUTH-BAIT-001 must be updated in the same change.
3. **有蚊子 is multi-action by nature** (open windows; optionally fan up, then close windows later).
   Decide: one composite "scenario" tool owned by deterministic code, or the model chaining tools
   (SPEC-006 left multi-intent to the model). Each executed step must be announced from its result.
4. **嗲一点 is a voice/persona change, not a car action.** Options: switch Flex voice id at the next
   session reset, or a style instruction in the session prompt. Keep it playful/sweet (撒娇 tone);
   the product will not produce sexualised speech. Persona stays out of code (voice-persona memory).
   Must persist until 正常一点 / 恢复.
5. **"Announce every action"** becomes an invariant candidate: the spoken confirmation is built from
   the executor's result, one short sentence listing each step (I-11, voice-replies-short).
6. Candidate extra phrases (same tools, no new hardware): 有点冷, 起雾了/看不清 (defrost, fresh air),
   有异味/空气不好 (outside air), 好困 (cooler + fresh air + music), 太吵了 (music down/pause),
   太晒/刺眼 (sunshade — needs another simulated actuator), 腰不舒服 (lumbar/seat recline — seat tool).

Architecture proposed 2026-09-30: [docs/FUZZY_DRIVING_ARCHITECTURE.md](docs/FUZZY_DRIVING_ARCHITECTURE.md),
[ADR-012](DECISIONS/ADR-012-fuzzy-driving-requests.md). Next: the owner decides O-1…O-5, then SPEC-015
(fuzzy-phrase table with expected actions, test rows), then implementation in waves.

## B-033 — Status cards fade out over time

**Asked by the owner 2026-10-02** during the emulator test run on `claude/10-1`: "those log should disappear over time rather than stay that forever".

**What happened:** after an idle disconnect while asleep (15:37:39, `connection_lost_in_sleep`), the card `GEMINI_LIVE_CONNECTION_FAILED` stayed on the map. Nothing new happened after it was shown, and the owner read it as a current failure. P44 covers the same thing for CONFIG cards: they are cleared when the cause is fixed.

**Wanted:**
- A transient error/status card (connection lost, provider error) disappears by itself after a short time. The proposal is about 10–15 s; the exact time is still to be chosen.
- When it disappears, the state line still shows the real state (休眠中（已断开）), so nothing is hidden. The detail stays in the log.
- A card the driver must act on (CONFIG: missing key, no consent) stays while the problem is still there, and goes away when it is fixed (P44).
- A newer card is not cleared by an older card's timer.

**Related:** an ordinary idle disconnect while asleep may not deserve an error card at all; it could show only 已断开. Decide this together with B-033.

**Owner in code:** `AssistantOverlayView.showError` / `clearError` and `ShownErrorCard`. Test it with `ShownErrorCardTest`.

**Done 2026-10-02:** a non-CONFIG card fades after `ERROR_CARD_FADE_MS` = 12 s and the state line
shows the real state again; CONFIG cards never fade and leave only when fixed (P44). Each card has a
token, so an older card's timer never clears a newer card or a transcript line. The related question
(no card at all for an ordinary idle disconnect while asleep) is settled by the fade: the card shows
briefly and goes, and the state line keeps 已断开. Tests: `ShownErrorCardTest` 10/0. Device check:
`CONFIG-CARD-EMU-001` (fade step).


## B-034 — A 符玄-like voice on Gemini

**Owner, 2026-10-02**, during the emulator test run on `claude/10-1` with the Gemini provider: "the current voice is terrible old woman voice, i prefer 符玄 like voice".

**Current state:** Gemini uses `GeminiAppSettings.DEFAULT_VOICE = "Kore"`, unless the voice field in 开发者设置 overrides it. Baidu is not affected: its default is `BaiduFlexVoices.PREFERRED_YOUTHFUL_FEMALE`.

**Target:** 符玄 (Honkai: Star Rail):
- a young, bright, clear female voice;
- confident and slightly proud; crisp, not breathy;
- never old, deep or matronly.

The persona notes are kept out of the code on purpose. Only the voice choice and the style instructions change.

**Wanted:**
1. A/B the Gemini prebuilt voices with the same Chinese test sentences. Candidates: Leda, Aoede, Zephyr, Puck, Kore and others. Save short 24 kHz clips in `D:\桌面\android_doc\` for the owner to pick. This is the same method as the 2026-09-30 Baidu voice A/B.
2. Make the owner's pick the Gemini default. Check how it sounds together with the speaking styles (B-029: 傲娇 fits 符玄 well).
3. The owner judges it by ear. A green test is not evidence that the voice sounds right.

**Constraint:** this is a voice name change, not a cloned or imitated voice of a real voice actor.

**Done 2026-10-02 (cloud):**
- `tools/gemini-live-probe/voice_ab.py` records the same Chinese lines with each candidate prebuilt
  voice (model `gemini-3.8-live`) and measures the median pitch. Three runs: Erinome 235–250 Hz,
  Leda 222–235, Autonoe 211, Aoede 205, Zephyr 200, Despina 200, Callirrhoe 195, **Kore 186**,
  Laomedeia 178, Pulcherrima 154–170. Kore is among the lowest, which matches "old woman".
- Default is now **Leda** (Google calls it "youthful"; second-highest, reads the lines verbatim).
  Erinome ("clear") is the runner-up.
- A stored `Kore` was the prefilled value saved with the settings form, not a choice, so it switches
  once to the new default (same pattern as the ADR-011 model switch). Any voice saved after that is
  kept. Tests: `GeminiSettingsTest`.
- Still the owner's: listen and pick (`VOICE-EAR-001`). Run the tool on the PC with
  `OUT=D:\桌面\android_doc\voice_ab` to get the WAVs; clips are not committed.


**Owner ear test, 2026-10-02 (emulator, `claude/10-1` at 34d629d):** I recorded 小诺 in 10 voices through the app itself. Each voice answered 「你能做什么」 and 「有点热」, and the clips are the exact bytes the app played, 24 kHz. They are in `D:\桌面\android_doc\voice_pick_2026-10-02\` and are not committed.

Pitch of these app recordings: Erinome 250 Hz, Aoede 233, Leda 231, Callirrhoe 226, Zephyr 220, Autonoe 218, Despina 207, Kore 207, Laomedeia 202, Pulcherrima 178.

The owner rejected Leda ("your default just shit") and then the whole set: "gemini voice are all old woman compare to chinese model and japanese". The emulator is set to Erinome for now; this is not a pick.

### Problem: no Gemini prebuilt voice can be 符玄-like

- **Why.** Gemini Live has only a fixed set of prebuilt voices. Google built them mainly for English and uses the same voice in every language, so in Mandarin they sound like a calm adult woman. There is no custom voice, no cloning, no training and no age setting. The persona prompt and the speaking styles (B-029) change wording and attitude, not how old the voice sounds.
- **Result.** Picking a different Gemini voice cannot meet B-034. Picking a voice (`VOICE-EAR-001`) is blocked on the decision below.

### Options (owner decision pending)

| Option | Voice | Cost / risk |
| --- | --- | --- |
| A. Baidu Flex speaks (owner selects Baidu) | Youthful female 4196; younger than any Gemini voice. In the 9-30 run the owner complained about noise, not age. | ADR-013 makes Gemini the default and plans to delete Baidu. Baidu's tool calling is weaker. |
| B. Gemini text + our own Chinese TTS | Any voice: a commercial voice library, a "voice design" voice made from a text description, or a voice trained (CosyVoice, GPT-SoVITS, …) on recordings from a voice actor who has agreed to it. | Breaks "end to end, no separate TTS" (ADR-002/013), so it **needs a new ADR**. Each reply starts about 0.3–0.8 s later. Barge-in and the playout claim gate (DriverTurn) have to be re-done for TTS audio. A few days to integrate. |
| C. A Chinese end-to-end realtime model (e.g. Doubao realtime) | Chinese-first voice catalogue, including young voices. | A new provider behind the seam (ADR-010 shape). Tool-calling quality is unknown. ADR-008 says do not revive deleted providers, but this one is new. |
| D. Pitch-shift Gemini audio on the device | A small gain only. | Shifts beyond +2 to 3 semitones sound artificial. Does not produce 符玄. |

**Recommendation given to the owner:** try B with a voice-design service first. Generate a few sample sentences in a young, clear, confident, slightly proud voice and let the owner judge them before any ADR. The owner enters the service key; agents never do. For the demo, A is the quickest fix.

**Hard constraint:** never train on, clone or imitate the real 符玄 game voice or any real voice actor without their consent. A new synthetic voice in the same *style* is fine.