# Product — Nova Drive / 小诺

> What we are building **right now**. Durable product facts only; no history, no aspirations.
> Architecture detail lives in `ARCHITECTURE.md`. The current work item lives in `CURRENT_MILESTONE.md`.

## Purpose

A Chinese-language voice assistant for driving, running on an Android phone. The driver speaks naturally; the assistant answers in speech and performs a small set of real actions — navigation, opening a supported app, music — without the driver looking at or touching the screen.

## Primary user experience

1. The user enters their provider credentials once, on the phone, in 开发者设置.
2. They press 按住麦克风开始 and speak Mandarin.
3. The assistant replies in speech, and when the request is an action it calls a tool and confirms briefly.
4. Navigation hands off to the Amap app and enters turn-by-turn guidance.
5. The assistant keeps running while the map is in the foreground.

Persona: **小诺**, wake phrase 你好小诺. Locale `zh-CN`, Mandarin only, metric units. The persona is a configurable session instruction; the default is a composed, slightly proud character that must never claim an action succeeded unless a tool actually returned success.

## Supported platform

- Android phone, `minSdk 28`, `targetSdk 34`, currently `0.6.0-baidu-flex` (versionCode 7).
- Verified on Xiaomi 24069RA21C (HyperOS, Android SDK 36).
- `android.hardware.type.automotive` is declared `required="false"`. Android Automotive is a future target, not the current one.

## External services

| Service | Used for | Credential |
| --- | --- | --- |
| Baidu 端到端语音语言大模型 Flex — `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime` | Realtime speech-to-speech and function calling | App ID + API Key + Secret Key, or Bearer API Key |
| Baidu OAuth — `https://aip.baidubce.com/oauth/2.0/token` | Access token for legacy auth mode | same |
| Amap Web Service — `https://restapi.amap.com/v3/place/text`, `/place/around` | Resolving a spoken destination to coordinates | Amap Web service key (optional) |
| Amap app (`com.autonavi.minimap`) | Turn-by-turn navigation | none |

All credentials are entered on the device and stored as AES/GCM ciphertext under Android Keystore. None exist in the repository or the APK.

## Feature set

- **Speech-to-speech conversation** — the model consumes microphone audio directly and returns speech. There is no speech-recognition or text-to-speech stage in this app; on-screen text is a caption side-channel from the provider.
- **Function calling** — `navigate_to(destination)`, `open_app(maps|settings)`, `control_music(play|stop)`. Model output is validated against a whitelist before anything executes.
- **Navigation** — destination resolved to coordinates, then handed to Amap for hands-free turn-by-turn.
- **Music** — a bundled public-domain track played in-app, so the feature does not depend on which player is installed.
- **Background survival** — a microphone-type foreground service keeps the session alive when another app takes the screen.
- **Configurable persona, voice and speed**, plus reply-audio sample rate, in 开发者设置.

## Active runtime architecture

**Phone → Baidu Flex WebSocket, directly.** No PC backend, no LAN, no ADB, no localhost in normal operation. See `DECISIONS/ADR-001` and `ADR-002`.

> **Decided direction, not yet shipped (2026-09-16, `ADR-007`):** navigation moves from handing off to the installed Amap app to the **Amap Navigation SDK embedded in our own Activity**, with the assistant layered above the map. Items 4–5 under *Primary user experience* and the *Navigation* feature line describe the currently installed build and will be rewritten when SPEC-005 Phase 4 ships. Until then, do not add new work to the deep-link path.

Default provider: **Baidu Flex** (`qianfan-realtime-flex-v1`), with function calling.
Selectable alternative: **Baidu Pro/Lite** (`audio-mini-realtime-near` and siblings) — conversation only, no function calling.

## Intentionally dormant compatibility code

Present in the tree, compiles, **not reachable from the production UI**, and must not be revived without an ADR:

| Component | State |
| --- | --- |
| `QwenSettings.kt`, `QwenDirectRealtimeProvider`, `QwenRealtimeClient`, `QwenProtocol` | DORMANT — no production caller; `VoiceAppSettings` is constructed only inside `QwenSettings.kt` |
| `BackendRealtimeProvider`, `BackendVoiceClient`, `LocalConnectivity` | DORMANT — packaged backend URL is empty |
| `backend/` (Python) | DORMANT — not a Gradle module, not required at runtime |
| GPT-Live entries in `VoiceCatalog` | DORMANT — catalog metadata only, no Android adapter |
| `AmapAutoPickService` (accessibility auto-tap) | OPTIONAL FALLBACK — disabled by default, only useful without an Amap key |
| `simulator`, `FakeRealtimeVoiceProvider`, `MockRealtimeVoiceProvider` | TEST ONLY — never a product default |

Dormant code is kept deliberately. Do not delete it for cleanliness, and do not treat its presence as permission to use it.

## Non-goals

- Building a navigation engine, map rendering, or routing. Navigation is delegated (`ADR-003`).
- Speech recognition or speech synthesis of our own.
- Android Automotive / VHAL integration at this stage.
- Windows, sunroof, charging, parking, cameras, video, seats, smart scenes.
- Natural-language parsing of assistant prose as a substitute for typed tool calls.
- Cloud accounts, user profiles, or telemetry.
