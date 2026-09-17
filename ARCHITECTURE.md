# Architecture — Nova Drive / 小诺

> The **currently approved** architecture. This file is authoritative.
> `docs/ARCHITECTURE.md` is the older Checkpoint-1 document and is retained for history.

## Shape of the system

```text
                         ANDROID PHONE
 ┌──────────────────────────────────────────────────────────────┐
 │  MainActivity (UI)          DeveloperSettingsActivity        │
 │        │                            │                        │
 │        │                     BaiduSettingsRepository ──┐      │
 │        │                     AmapSettingsRepository ───┤      │
 │        │                                               ▼      │
 │        │                            AndroidKeystoreCredentialStore
 │        │                                     (AES/GCM, on device)
 │        ▼                                                      │
 │  VoiceSessionController (app)                                 │
 │    ├── PcmAudioCapture ──── 16 kHz mono PCM16 ────┐           │
 │    │     (VOICE_COMMUNICATION source, AEC + NS)   │           │
 │    │     gated while the assistant speaks         │           │
 │    │                                              │           │
 │    ├── BaiduFlexProvider  (DEFAULT)               │           │
 │    │     └── BaiduFlexClient ── WSS ──────────────┼──────────────► Baidu Flex
 │    │           BaiduFlexProtocol                  │           │    aip.baidubce.com
 │    │           BaiduAccessTokenClient ────────────┼──────────────► Baidu OAuth
 │    │                                              │           │
 │    ├── BaiduDirectRealtimeProvider (selectable, no tools)     │
 │    │                                              │           │
 │    └── PcmAudioPlayer ◄── PCM16 reply audio ──────┘           │
 │          (USAGE_ASSISTANT → media stream)                     │
 │                                                                │
 │  VoiceSessionController (ingress, provider-neutral core)       │
 │    state machine · reconnect · work coordinator · diagnostics  │
 │                                                                │
 │  AndroidToolDispatcher  (validates model output)               │
 │    ├── NavigationAdapter ──► AmapPoiClient ───────────────────────► Amap Web API
 │    │        └── androidamap://navi?lat&lon ──────────────────────► Amap app
 │    ├── BundledMusicPlayer (in-app, res/raw)                    │
 │    └── Settings intent                                         │
 │                                                                │
 │  VoiceSessionService — microphone foreground service            │
 └──────────────────────────────────────────────────────────────┘
```

## Components

### MainActivity
The only production entry point. Starts and stops the voice session, renders state and transcripts, and routes tool calls to `AndroidToolDispatcher`. It calls `startBaidu(...)` exclusively — no other provider path is reachable from the UI.

### DeveloperSettingsActivity
Where the user enters credentials and tuning: Baidu auth mode and keys, provider/model, Amap Web key, persona text, voice id, speed, reply-audio sample rate. Also hosts **Test Connection**, which opens the provider WebSocket, waits for `session.updated`, and closes — deliberately **without** starting the microphone.

### Credential storage
`AndroidKeystoreCredentialStore` encrypts each field with AES/GCM under a Keystore key. Fields are independently keep / replace / clear. Non-secret configuration lives in private SharedPreferences.

> **Lifecycle trap:** `BaiduSettingsRepository.loadSettings()` prefers a *saved* `instructions` value over the code default. Changing `PersonaProfiles` does **not** reach a device that has ever pressed Save until **恢复默认人设 → SAVE** is pressed. Any automation that taps that button by text must use this exact label.

### Audio capture — `PcmAudioCapture` / `AndroidMicrophonePort`
16 kHz mono PCM16 from `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, with `AcousticEchoCanceler` and `NoiseSuppressor` attached when available. Exposes `gated`: while true, frames are dropped but the recorder keeps running, so resuming is instant.

### Realtime provider — `BaiduFlexProvider` → `BaiduFlexClient` → `BaiduFlexProtocol`
Owns the vendor protocol. Handshake: the server sends `session.created`, then the client sends `session.update` carrying persona instructions, voice, speed, server-VAD settings and the tool declarations. Audio goes up as base64 `input_audio_buffer.append`.

Key behaviours:
- **Voice fallback** — if the server errors before readiness and a non-default voice was sent, `session.update` is resent once with `"default"`, so a bad voice id cannot cost the whole session.
- **Benign cancel** — a refused `response.cancel` ("no active response") is swallowed, never escalated to a session error. `response.cancel` is only sent while the assistant is actually speaking.
- **Generation guarding** — callbacks from a superseded connection are rejected.
- `sendAudio` and `cancelResponse` never throw on a closed socket; `sendFunctionResult` still does, so the core can release its delivery claim and retry.

`BaiduDirectRealtimeProvider` / `BaiduRealtimeClient` / `BaiduProtocol` serve Pro/Lite: same transport, different handshake (`session.update` on open), and **no** function calling.

### Provider-neutral core — `ingress`
`VoiceSessionController` in `ingress` holds the state machine, reconnect policy, work coordinator, latency diagnostics and audio ports. It knows nothing about any vendor. Vendor JSON never escapes the Android adapters. Enforced by `behavior-test/DependencyBoundaryTest`.

### Audio playback — `PcmAudioPlayer` / `AndroidPlaybackPort`
AudioTrack with `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`, so replies land on the **media stream** and obey the volume rocker. Buffer ≥ 320 ms, blocking queue worker. Audio focus is requested when speech begins and abandoned when it ends — per utterance, not per session.

Focus changes are acted on, not merely requested: `LOSS_TRANSIENT_CAN_DUCK` ducks, `LOSS_TRANSIENT` pauses, `LOSS` pauses and flushes, `GAIN` unducks and resumes. `AudioFocusController.onFocusChanged` has exactly one owner (`AndroidPlaybackPort`); it is a single slot, not a listener list.

> **Known trade-off:** moving off `USAGE_VOICE_COMMUNICATION` made replies audible but removed the platform echo-cancellation reference for our own output. The mic is therefore gated while the assistant speaks (350 ms release delay). **This disables barge-in** — the user cannot interrupt mid-reply.

### Navigation mute rule — `NavigationState`

A product rule, not an audio-policy trick: **while navigation is running, 小诺's reply audio is muted**, except for a short window after a tool action succeeded.

- `navigate_to` succeeding calls `NavigationState.begin()`.
- Any Accepted tool dispatch calls `allowConfirmation()`, opening a 10 s window in which speech is permitted — so "music stopped" is still spoken.
- **Update 2026-09-17:** the driver's own question opens the same window (`allowReply()`, from `VoiceCommandRouter` on a meaningful, non-control utterance), and every played frame extends it (`extendWhileSpeaking()`), so an answer the driver asked for is always spoken in full. Only unprompted speech stays muted. Previously the answer to a question asked more than 10 s after the last tool was dropped silently.
- While Amap guidance is playing, 小诺's reply is paused (held in the queue, not dropped) and resumes when guidance ends.
- `AndroidPlaybackPort.enqueue` drops frames while `shouldMuteSpeech()` is true and logs `reply_audio_not_played reason=navigation_unprompted` once.
- The flag is cleared by `reset()` on session stop and release.

The audio-focus ducking described in the previous section remains underneath, but this rule is the primary mechanism for not talking over Amap.

> **Known limitation:** nothing detects when navigation *ends*. The flag clears when the voice session stops, so a driver who finishes navigating but keeps the session open stays muted until the session ends. A `stop_navigation` tool would resolve this; not yet specced.

### Tool dispatch — `AndroidToolDispatcher`
The only bridge from model output to device action. Every call is validated before execution: exact field sets, length bounds, enum membership. Unknown tools return `UNKNOWN_TOOL` without executing. Tools: `navigate_to`, `open_app(maps|settings)`, `control_music(play|stop)`.

### Navigation — `NavigationAdapter` + `AmapPoiClient`
With an Amap Web key: resolve the destination to coordinates (`place/around` biased by coarse location, falling back to `place/text`), then launch `androidamap://navi?...&lat=&lon=&dev=0` for zero-tap turn-by-turn. Without a key: `keywordNavi` (one tap), then `geo:`. See `ADR-003`.

### Background behaviour — `VoiceSessionService`
A `foregroundServiceType="microphone"` service started when a session starts and stopped on every session-end path. It is what keeps the session alive when another app takes the screen, and it also earns the background-activity-start exemption (`BAL_ALLOW_FOREGROUND`) that lets tools launch apps from the background.

### Network boundary
Outbound only, to three hosts: `aip.baidubce.com` (WSS + OAuth) and `restapi.amap.com`. Release builds set `usesCleartextTraffic="false"`; the debug source set overrides it for local experimentation. The packaged backend URL resource is empty.

## Status of every path

| Path | Status |
| --- | --- |
| Baidu Flex direct (function calling) | **ACTIVE — default** |
| Baidu Pro/Lite direct (conversation only) | **ACTIVE — user-selectable** |
| Amap coordinate deep link navigation | **ACTIVE** |
| Bundled in-app music | **ACTIVE** |
| Microphone foreground service | **ACTIVE** |
| Navigation mute rule (`NavigationState`) | **ACTIVE** — unprompted speech muted while navigating; post-tool confirmations and answers to the driver's questions are spoken, never over Amap guidance |
| `AmapAutoPickService` accessibility auto-tap | OPTIONAL FALLBACK — off by default |
| Qwen direct (`QwenSettings`, providers, protocol) | DORMANT — unreachable from production UI |
| PC backend (`BackendRealtimeProvider`, `backend/`) | DORMANT — empty packaged URL |
| GPT-Live | DORMANT — catalog metadata only, no adapter |
| Camera question (`describe_camera_view`; automatic look when the camera opens) | **ACTIVE** — vision call verified on device 2026-09-17 with a Qianfan API key (paid) |
| Cabin climate (`control_climate` tool → `VehicleControlPort`) | **ACTIVE — SIMULATED BACKEND.** `SimulatedVehicleControl` on the phone; not real vehicle control |
| `simulator` `InMemoryVehicleSimulator`, Fake/Mock providers | TEST ONLY (its climate state is the shared `SimulatedVehicleControl`) |
| `demo` module | TEST/DEMO ONLY — JVM structured-command demo |

## Vehicle control (cabin climate)

```text
Baidu Flex tool call: control_climate {action, value}
      ↓  FlexFunctionCallAssembler (schema validation)
AndroidToolDispatcher
      ↓
ClimateToolHandler            ← depends only on the interface
      ↓
VehicleControlPort            ← module `vehicle`; the ONE climate abstraction
      ↓
VehicleControlProvider        ← the only production file naming a backend
      ├── SimulatedVehicleControl        ← phone build (module `simulator`)
      └── (later) AndroidAutomotive / CAN / OEM / remote adapter
```

- `ClimateState(powerOn, targetTemperatureCelsius, fanLevel)`; limits 16–32 °C and fan 0–7 from `CommandBounds` (docs/MVP_SPEC.md).
- Absolute out-of-range values are **rejected**; relative changes are **clamped** and report `limitReached`.
- Power off keeps setpoints. Setpoints may change while off; every result carries the power state.
- `VehicleActionResult`: `Success(state, limitReached)`, `InvalidArgument`, `Unsupported`, `Unavailable`, `PermissionDenied`, `Failure`. Every non-success reaches the model as `ok=false` with an instruction not to claim completion.
- The legacy synchronous `VehiclePort` (orchestration) routes its HVAC commands through the same port; `HvacController` was removed.
- The bottom bar reads and changes climate through the port too.
- **Swapping to a real vehicle** = one new `VehicleControlPort` implementation selected in `VehicleControlProvider`. A guard test (`DependencyBoundaryTest`) fails if another production file names a concrete backend.

## Camera question (看图)

```text
「看看前面有什么」 → describe_camera_view {question}
      ↓  AndroidToolDispatcher returns deferredOutput (never blocks the session event loop)
VoiceSessionController → WorkCoordinator (async) → result delivered to the model once, at a safe point
      ↓
CameraQuestionHandler         ← depends on CameraVisionSurface + VisionPort only
      ├── CameraVisionGateway → CameraPreviewView (opens camera, waits 8 frames, JPEG ≤768 px)
      └── VisionProvider → QianfanVisionClient (Qianfan v2 chat/completions, image as data URL)
```

- Opening the camera (📷) looks once automatically; the answer shows in the speech bubble and the assistant reads it aloud via a session text turn (the phone has no default system TTS). There is no ask button.
- The camera is a small 9:16 picture-in-picture window, bottom right.
- The image leaves the phone only when the camera is opened or the driver asks about it; never continuously. Neither the image nor the answer is logged.
- Credential: an optional dedicated vision API key (Keystore, 开发者设置); otherwise the Baidu voice credential. **Measured 2026-09-17: Qianfan v2 rejects the legacy OAuth access token (`HTTP 401 invalid_iam_token`)** — with legacy voice credentials a dedicated Qianfan API key is required. Camera capture itself verified on device (18–26 KB frames sent).
- Every failure (no camera, no permission, no frame, not configured, auth, request) reaches the model as `ok=false` with an instruction never to describe the image.

## Test boundaries

| Suite | Proves |
| --- | --- |
| `app` unit tests | Protocol JSON shape, settings semantics, URI construction, tool validation. MockWebServer proves **protocol shape, not connectivity**. |
| `ingress` tests | Provider-neutral state machine, reconnect, work coordination, protocol fixtures |
| `behavior-test` | Module dependency boundaries, secret scanning, orchestration behaviour |
| Not covered by any automated test | Audio routing, mic gating, foreground-service survival, real provider connectivity, anything requiring a phone |

See `ACCEPTANCE_TESTS.md` for what each level is allowed to claim.
