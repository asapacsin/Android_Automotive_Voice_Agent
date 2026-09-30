# Architecture — Nova Drive / 小诺

**This file is the canonical architecture.** `/ARCHITECTURE.md` at the repository root points here.
Rules that must never be broken are in [INVARIANTS.md](INVARIANTS.md); what the product can and
cannot do is in [CAPABILITIES.md](CAPABILITIES.md); known problems are in [TECH_DEBT.md](TECH_DEBT.md).

## What this is

An Android voice assistant for driving. Speech goes to the realtime model — Gemini Live by default
since 2026-09-30 ([ADR-013](../DECISIONS/ADR-013-gemini-default-provider.md)), Baidu Qianfan Flex only
by the owner's explicit choice — as **end-to-end speech-to-speech with function calling**; there is
no separate ASR or TTS in this product, by decision
([ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md)). The map and turn-by-turn
navigation are the Amap Navigation SDK embedded in our own Activity
([ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md)).

## The one trace that matters

```
microphone
  → PcmAudioCapture (16 kHz PCM16, WebRTC AEC3 before gain/gate)
  → AndroidMicrophonePort         gates: muted / guidance / sleep (model playback stays uplinked)
  → SpeechUplinkGate              200 ms of voice before anything is uploaded
  → BaiduFlexClient (WSS)         input_audio_buffer.append
        ↑ server VAD decides where a turn starts and ends
  → response.created / function_call / audio deltas
  → FlexFunctionCallAssembler     validates name, fields, bounds, enums
  → AndroidToolDispatcher         the ONLY bridge from model output to device action
  → SafeAndroidActionExecutor / EmbeddedNavigationController / ClimateToolHandler / …
  → ToolDispatchResult            {ok, status|error, next}  ← the truth about what happened
  → BaiduFlexClient.sendFunctionResult → the model answers from the result
  → PhantomTurnGate               may hold or drop the reply audio + subtitle
  → AndroidPlaybackPort → PcmAudioPlayer (USAGE_ASSISTANT, media stream)
  → AssistantOverlayView          subtitle
```

Read that top to bottom before changing anything in the voice path.

## Who owns what

One behaviour, one owner. If you need to change one of these, change it **here** and nowhere else.

| Behaviour | Canonical owner | Not owned by |
| --- | --- | --- |
| Whether a capability exists at all | `ProductCapabilities` / `config/capabilities.yaml` (kept in step by `CapabilityContractTest`) | the persona prompt, the UI, `ActionClaimGuard` keyword lists |
| What an utterance names (intent → capability id) | `UtteranceIntentResolver` — parsing only; bounded grammar for `speech.capability_help` (not synonym lists) | availability, Android APIs, the model |
| Spoken capability-help copy | `ProductCapabilities.spokenHelpSummary` — supported catalog groups only | `ActionClaimGuard` keyword lists, the persona prompt |
| Capability-help turn handling | `DriverTurn.Kind.CAPABILITY_HELP` + `HoldReason.CAPABILITY_HELP` — release when the reply names ≥2 supported groups; else `help_incomplete` with catalog scripted speak | `unverified_claim` / `UNVERIFIED_ACTION_CLAIM` for help utterances |
| Calling a contact | `PhoneCallTool` via `PhonePort`; `PhoneProvider` selects `AndroidContacts` | NLU, the catalog, the UI |
| Which tools are declared, and whether a tool call is well-formed | the owning car domain (`app/tools/*Domain.kt`: names, descriptions, JSON Schema, `validate`, `repeatSensitive`) assembled by `ToolRegistry` (ADR-015); `RealtimeToolCatalog` serves the registry's list to every realtime adapter; `FlexFunctionCallAssembler` only assembles Baidu's streamed calls | the model, the dispatcher, a per-provider copy of the list |
| Whether an action may execute | `AndroidToolDispatcher` — validation errors, the guard chain, `UNKNOWN_TOOL` — before routing the call to its domain's `ToolServer` (+ `SafetyPolicy` in `orchestration` for the JVM path) | the model, a server (servers execute; they do not decide whether) |
| Whether an action **did** execute | the `ToolDispatchResult` / `AndroidActionResult` returned by the executor | any sentence the model produced |
| What may be claimed to the driver | `DriverTurn`, driven for every provider by `DriverTurnPipeline` (ADR-010) — holds reply audio+subtitle until execution proof exists; `PhantomTurnGate` judges phantom turns; `ActionClaimGuard` classifies requests and writes corrections | the persona prompt, the model's wording |
| Per-utterance state (phase, kind, proof) | `DriverTurn`, one instance per driver turn, epoch-guarded | loose flags anywhere else |
| **Cross-turn** context (what was adjusted, what is pending, what is stale) | `DriverContext`, built only from `ok=true` tool results; resolved by `ContextResolver`; carried to the model by `VoiceContextHints` | the model's memory — there is none, the conversation resets after every tool turn |
| Navigation execution | `EmbeddedNavigationController` → `AmapNaviViewHost` | `NavigationAdapter` (legacy deep link, dormant) |
| Navigation camera while driving | `AmapDrivingPresentation` (`AMapNaviView` lock-car, traffic line, native HUD) | idle `moveCamera(newLatLngZoom)`, a homemade tilt, `MapView` |
| Current speed / posted limit while driving | `DrivingSpeedHud` in `AmapNaviViewHost` (Amap location + cameras) | assistant overlay, a second speed source |
| Which candidate the driver picked | `NavigationChoiceResolver` | the model |
| Turn-taking / interruption | server VAD for turn ends; `ListeningLifecycle` for ACTIVE / SILENT_WAIT / SLEEP / DEEP_IDLE; `VoiceCommandRouter` for 「闭嘴」「休眠」 | ad-hoc checks in the client |
| Whether 小诺 may speak / whether the mic reaches the model | `SpeechArbiter` via `SpeechAuthority` (P1 window, guidance hold, focus, guidance uplink gate, SPEC-018 assistant-guidance rows `guidanceChunk`/`abandon` — guidance chunks exempt from P1/R6a and from the listening-state gate, ordinary replies held while guidance is open, workload hold R6a fed the next-manoeuvre distance by `NavigationTraceListener` → `NavigationState`; `SpeechAuthority.syncPlaybackHold` is the one place the player is paused or resumed for a hold), applied by `AndroidPlaybackPort` (+ lifecycle) + `PhantomTurnGate` (phantom/false-claim holds) | the UI |
| Simulated windows and seat height, and their limits | `VehicleControlPort` (`cabinState`, `setWindows`/`changeWindows`, `setSeatHeight`/`changeSeatHeight`) implemented by `SimulatedVehicleControl`, selected only in `VehicleControlProvider` | a second port, the UI, the handler |
| `control_window` / `control_seat` → port, and what may be said about the result | `WindowToolHandler` / `SeatToolHandler` in the `body` domain (`BodyDomain` + `BodyServer`, ADR-015); the spoken clause is `ActionAnnouncement` (pure, from the read-back state), carried as `announce` | the model's wording, the dispatcher |
| Speaking style (tone only; the voice never changes) and how long it lasts | `SpeakingStyleState` (sticky; persisted by `SpeakingStyleStore`) + `PersonaProfiles.compose`, used by both clients when they build instructions; changed only by `set_speaking_style` | a provider adapter, the voice id, anything mid-turn |
| Playing a described song, and what may be said about it | `play_music` in the `media` domain → `media/` hand-off (`MEDIA_PLAY_FROM_SEARCH` to the driver's music app) → `NowPlayingVerifier` readback (MediaSession); only `now_playing` may be claimed | the model's identification of the song |
| Who speaks each guidance sentence (assistant or Amap), per prompt | `GuidanceRelay` (SPEC-018; behind the developer toggle, off by default), with `GuidanceClaims` hooks from `AndroidPlaybackPort`; a guidance prompt is correlated to its response by `AppPromptTurn` (provider) and is never a driver turn | the model, `AmapGuidanceVoice` (it is the SDK edge only) |
| Live information (weather, route traffic, along-route, place details) | `LiveInfoTool` (`query_live_info`) — REST kinds via `AmapPoiClient` / `AmapLiveInfoParser`; SDK kinds via the `RouteLiveInfo` port / `nav/amap/AmapRouteLiveInfo` (SPEC-011) | the model's own knowledge |
| Conversation lifetime | `ConversationResetPolicy` (reset after tool turns) + `ResponseTurnGate` (one reply at a time) | the model |
| Credentials | `AndroidKeystoreCredentialStore` (`baidu_*`, `gemini_*`, `iflytek_*`, `amap_*`) | source, Gradle files, logs |
| Which realtime provider a session uses | `VoiceProviderChoice` (Gemini Live unless the owner's stored preference is Baidu; a missing key/consent fails the start with its code, never a fallback), applied once in `VoiceSessionController.openSession` (ADR-010, ADR-013) | the adapters, the UI, anything mid-session |
| Gemini Live wire format | `GeminiLiveProtocol` / `GeminiLiveClient` / `GeminiLiveProvider` | `ingress`, policy code |
| "Driver speaking" when the provider has no speech events | the local `SpeechUplinkGate` onset/offset, fed to the session core's `onLocalSpeechActivity` and honoured only when `ProviderCapabilities.serverSpeechActivityEvents` is false | the adapter, a provider-name branch |

## Modules and allowed dependencies

```
contracts ← safety, vehicle, verification, feedback, ingress, orchestration
ingress   ← app            (provider-neutral realtime core: state machine, reconnect, ports)
vehicle   ← app, simulator (VehicleControlPort: the one climate abstraction)
app       → everything above; nothing depends on app
simulator → contracts, vehicle          TEST/SIM ONLY
evaluation→ nothing product-facing      Level A simulation harness
```

`PhonePort` lives in `contracts`, parallel to `VehicleControlPort`: tests inject `FakePhonePort`; production selects `AndroidContacts` only in `PhoneProvider`.

**Forbidden, and enforced by `behavior-test/DependencyBoundaryTest`:** `contracts`, `ingress`,
`safety`, `vehicle`, `verification`, `feedback` and `orchestration` must not import
`com.novadrive.simulator`, `android.car`, `com.amap`, `com.baidu`, GMS, OpenAI or DashScope. Vendor
JSON never escapes the Android adapters.

**Also forbidden** (enforced by `ArchitectureRulesTest`): the UI package must not execute vehicle or
navigation actions directly, and `com.amap` may be imported by exactly one file.

## Subsystems

### Audio capture — `app/voice/PcmAudioCapture.kt`
`VOICE_COMMUNICATION` at 16 kHz. Speaker echo is cancelled by **WebRTC AEC3** (`WebRtcAcousticEcho`):
render PCM is fed immediately before `AudioTrack.write` (including 24 kHz → 16 kHz resample for the
far-end reference); capture PCM is cleaned on the capture thread before `SpeechUplinkGate` and
`MicInputGain`. Platform `AcousticEchoCanceler` / `NoiseSuppressor` stay off when the native backend
loads; otherwise the app falls back to the legacy platform path and logs `aec_backend=unavailable`.
A debug build on an x86 emulator (ARM libraries under translation, `TranslatedAbi`) skips AEC3 and
logs `reason=translated_abi`: translated AEC3 took ~80% of a core and starved both audio threads.
`AndroidMicrophonePort` owns every reason a
frame may not be sent: `muted`, `guidanceGated` (the `SpeechArbiter` uplink answer via `SpeechAuthority`: Amap is speaking, plus tail/cap), `suppressLive` (the debug
harness is injecting), and the `SpeechUplinkGate`. Model reply playback does **not** gate the mic:
the uplink stays open for full-duplex barge-in. `MicInputGain` lifts quiet speech above the server's
VAD floor (max 3×, measured).

### Open-mic defence — `SpeechUplinkGate`, `PhantomTurnGate`
Because the server decides what a turn is, every sustained cabin sound is a candidate turn. The
uplink gate refuses to upload anything shorter than 200 ms of voice-like energy; the phantom gate
holds a doubtful turn's reply and drops it when the model asked for no action, nothing on screen was
waiting, the audio produced no words, and the reply carries no content. See
[INVARIANTS.md](INVARIANTS.md) I-4 and I-5.

### Cross-turn context — `app/voice/DriverContext.kt`, `ContextResolver.kt`

The conversation resets after every tool turn, so 「再凉一点」 reaches a model that does not know anything
was adjusted. `DriverContext` is the app's record of what actually happened — written from tool
results with `ok=true`, never from the model's wording — and `ContextResolver` turns a
context-dependent sentence into one concrete action or into a decision to ask. `VoiceContextHints`
puts that single resolved instruction into the fresh conversation.

Navigation phase and candidates are **not** copied in: the hint reads them live from the navigation
owner. Two copies of that state is the defect [TECH_DEBT.md](TECH_DEBT.md) D-4 already records.

The rules are in [SPEC-006](../SPECS/SPEC-006-complex-voice-commands.md); the part that has a real
oracle is proven by `ContextResolverTest`.

### Realtime provider — `app/voice/BaiduFlexClient.kt`
Owns the vendor protocol and wires the per-turn machinery: `ResponseTurnGate` (one reply at a time),
`ConversationResetPolicy` (a fresh conversation after tool turns), `EmptyResponseRetryPolicy`, and
`ActionClaimGuard`.

**Per-utterance state lives in `DriverTurn`, not in the client.** One object per driver utterance
carries the phase (LISTENING → RESPONDING → SETTLED, or CANCELLED), the request kind (ACTION /
NO_TOOL_ACTION / REALTIME_INFO / CONVERSATION), whether the driver was transcribed, whether a tool
was called, and whether execution *proved* success. It is the single authority on whether the reply
may be heard, and it is why a stale event from a superseded turn cannot mutate a newer one — every
turn carries an epoch and a cancelled turn accepts nothing.

The three reasons a reply is held are one mechanism: `PHANTOM_AUDIO` (the audio looked like noise),
`NO_TOOL_REQUEST` (nothing can ever prove it), `AWAITING_EXECUTION_PROOF` (I-1). Diagnostics:
`TURN_HOLD`, `TURN_RELEASE`, `TURN_DROP`, each with the epoch and reason.

### Provider-neutral core — `ingress`
`VoiceSessionController` holds the state machine, reconnect policy, work coordinator and the audio
ports. It knows nothing about any vendor and must stay that way.

### Tool dispatch — `app/AndroidToolDispatcher.kt` and `app/tools/` (ADR-015)
The only bridge from model output to device action. Every call is validated before execution: exact
field sets, length bounds, enum membership (by the owning domain's `validate`). Unknown tools return
`UNKNOWN_TOOL` without executing. The dispatcher then runs the guard chain and routes the call to the
`ToolServer` of the domain that declares it (navigation, apps, media, climate, vision, phone,
live_info, speech); it contains no tool-name branch (`ArchitectureRulesTest.dispatcherDoesNotBranchOnToolNames`).
A new car function is a new `ToolDomain` + `ToolServer` pair registered in `ToolRegistry.PRODUCT`.
Failures carry `ToolFailureAdvice` so the model is told what to say without relying on the tool
description surviving a conversation reset.

### Navigation — `app/nav/`
`EmbeddedNavigationController` owns the flow (resolve → candidates → route list → start → end) and
`NavigationPhase`/`NavigationStateStore` the state. `AmapNaviViewHost` is the only module permitted to
import `com.amap`; `AmapDrivingPresentation` is the native driving HUD (lock-car, traffic line, 3D
arrows, lanes). Idle browse still uses `InitialLocationRecenter`. After `startNavi` the idle 2D
camera must not run. `DestinationQuery` turns what the driver said into a POI keyword;
`NavigationChoiceResolver` turns 「第二个」/「就去某某」 into a candidate.

### Vehicle control — `vehicle` module
`VehicleControlPort` is the one climate abstraction. `VehicleControlProvider` is the only production
file naming a concrete backend; today that is `SimulatedVehicleControl`. Swapping to a real vehicle
is one new implementation selected there.

### Background survival — `app/VoiceSessionService.kt`
A `foregroundServiceType="microphone"` service, started when a session starts and stopped on every
session-end path. It is what keeps the socket alive when another app takes the screen, and what
earns the background-activity-start exemption that lets tools launch apps.

### One active provider, and the seam that keeps it replaceable

There is exactly one realtime provider: Baidu Qianfan Flex. The dormant Qwen, GPT-Live and PC-backend
implementations were **deleted** on 2026-09-19 by
[ADR-008](../DECISIONS/ADR-008-single-active-realtime-provider.md), along with `NavigationAdapter`'s
deep link and `AmapAutoPickService`. Do not infer a second provider from git history: none of it was
finished, and it was written against an older session model.

What remains, and is the point: `RealtimeVoiceProvider` and the provider-neutral `ingress` core.
Adding a provider means writing one implementation of that interface and registering it — not
changing voice logic.