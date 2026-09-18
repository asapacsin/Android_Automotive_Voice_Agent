# Architecture — Nova Drive / 小诺

**This file is the canonical architecture.** `/ARCHITECTURE.md` at the repository root points here.
Rules that must never be broken are in [INVARIANTS.md](INVARIANTS.md); what the product can and
cannot do is in [CAPABILITIES.md](CAPABILITIES.md); known problems are in [TECH_DEBT.md](TECH_DEBT.md).

## What this is

An Android voice assistant for driving. Speech goes to Baidu Qianfan Flex as **end-to-end
speech-to-speech with function calling** — there is no separate ASR or TTS in this product, by
decision ([ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md)). The map and turn-by-turn
navigation are the Amap Navigation SDK embedded in our own Activity
([ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md)).

## The one trace that matters

```
microphone
  → PcmAudioCapture (16 kHz PCM16, AEC + NS)
  → AndroidMicrophonePort         gates: muted / assistant speaking / guidance / sleep
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
| Whether a capability exists at all | the tool list in `BaiduFlexProtocol.sessionUpdate` + [CAPABILITIES.md](CAPABILITIES.md) | the persona prompt, the UI |
| Whether a tool call is well-formed | `FlexFunctionCallAssembler` (schema, bounds, enums) | the model, the dispatcher |
| Whether an action may execute | `AndroidToolDispatcher` (+ `SafetyPolicy` in `orchestration` for the JVM path) | the model |
| Whether an action **did** execute | the `ToolDispatchResult` / `AndroidActionResult` returned by the executor | any sentence the model produced |
| What may be claimed to the driver | `DriverTurn` — holds reply audio+subtitle until execution proof exists; `PhantomTurnGate` judges phantom turns; `ActionClaimGuard` classifies requests and writes corrections | the persona prompt, the model's wording |
| Per-utterance state (phase, kind, proof) | `DriverTurn`, one instance per driver turn, epoch-guarded | loose flags anywhere else |
| **Cross-turn** context (what was adjusted, what is pending, what is stale) | `DriverContext`, built only from `ok=true` tool results; resolved by `ContextResolver`; carried to the model by `VoiceContextHints` | the model's memory — there is none, the conversation resets after every tool turn |
| Navigation execution | `EmbeddedNavigationController` → `AmapNaviViewHost` (the only file that may import `com.amap`) | `NavigationAdapter` (legacy deep link, dormant) |
| Which candidate the driver picked | `NavigationChoiceResolver` | the model |
| Turn-taking / interruption | server VAD for turn ends; `ListeningLifecycle` for ACTIVE / SILENT_WAIT / SLEEP / DEEP_IDLE; `VoiceCommandRouter` for 「闭嘴」「休眠」 | ad-hoc checks in the client |
| Whether reply audio is heard | `AndroidPlaybackPort` (navigation mute, lifecycle) + `PhantomTurnGate` (phantom/false-claim holds) | the UI |
| Conversation lifetime | `ConversationResetPolicy` (reset after tool turns) + `ResponseTurnGate` (one reply at a time) | the model |
| Credentials | `AndroidKeystoreCredentialStore` | source, Gradle files, logs |

## Modules and allowed dependencies

```
contracts ← safety, vehicle, verification, feedback, ingress, orchestration
ingress   ← app            (provider-neutral realtime core: state machine, reconnect, ports)
vehicle   ← app, simulator (VehicleControlPort: the one climate abstraction)
app       → everything above; nothing depends on app
simulator → contracts, vehicle          TEST/SIM ONLY
evaluation→ nothing product-facing      Level A simulation harness
```

**Forbidden, and enforced by `behavior-test/DependencyBoundaryTest`:** `contracts`, `ingress`,
`safety`, `vehicle`, `verification`, `feedback` and `orchestration` must not import
`com.novadrive.simulator`, `android.car`, `com.amap`, `com.baidu`, GMS, OpenAI or DashScope. Vendor
JSON never escapes the Android adapters.

**Also forbidden** (enforced by `ArchitectureRulesTest`): the UI package must not execute vehicle or
navigation actions directly, and `com.amap` may be imported by exactly one file.

## Subsystems

### Audio capture — `app/voice/PcmAudioCapture.kt`
`VOICE_COMMUNICATION` at 16 kHz with AEC and noise suppression. `AndroidMicrophonePort` owns every
reason a frame may not be sent: `muted`, `gated` (the assistant is speaking), `guidanceGated` (Amap
is speaking), `suppressLive` (the debug harness is injecting), and the `SpeechUplinkGate`.
`MicInputGain` lifts quiet speech above the server's VAD floor (max 3×, measured).

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

### Tool dispatch — `app/AndroidToolDispatcher.kt`
The only bridge from model output to device action. Every call is validated before execution: exact
field sets, length bounds, enum membership. Unknown tools return `UNKNOWN_TOOL` without executing.
Failures carry `ToolFailureAdvice` so the model is told what to say without relying on the tool
description surviving a conversation reset.

### Navigation — `app/nav/`
`EmbeddedNavigationController` owns the flow (resolve → candidates → route list → start → end) and
`NavigationPhase`/`NavigationStateStore` the state. `AmapNaviViewHost` is the only file permitted to
import `com.amap`; it also owns the map camera, including the startup recentre
(`InitialLocationRecenter`). `DestinationQuery` turns what the driver said into a POI keyword;
`NavigationChoiceResolver` turns 「第二个」/「就去某某」 into a candidate.

### Vehicle control — `vehicle` module
`VehicleControlPort` is the one climate abstraction. `VehicleControlProvider` is the only production
file naming a concrete backend; today that is `SimulatedVehicleControl`. Swapping to a real vehicle
is one new implementation selected there.

### Background survival — `app/VoiceSessionService.kt`
A `foregroundServiceType="microphone"` service, started when a session starts and stopped on every
session-end path. It is what keeps the socket alive when another app takes the screen, and what
earns the background-activity-start exemption that lets tools launch apps.

### Dormant, deliberately kept
Qwen, GPT-Live, the PC backend (`backend/`, `BackendRealtimeProvider`), `NavigationAdapter`'s deep
link and `AmapAutoPickService` all still compile and are **not** part of the product. Do not infer
the architecture from their existence — see [TECH_DEBT.md](TECH_DEBT.md) D-4.
