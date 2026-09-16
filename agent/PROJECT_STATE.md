# Project state — Nova Drive / 小诺

## Milestone

BAIDU FLEX ON-DEVICE, LIVE-VERIFIED (2026-09-15): function calling, navigation handoff, bundled music, foreground keep-alive

## Completed

- Phone settings for legacy App ID/API Key/Secret Key auth or Bearer API Key auth, all credential fields Android-Keystore protected with independent keep/replace/clear semantics. Schema v2 migration preserves Lite for installs that previously had schema 1 or a Lite/Pro model saved.
- Real Test Connection: uses `BaiduFlexClient` or `BaiduRealtimeClient` according to the selected model, waits for `session.updated`, then closes; microphone is not started.
- Foreground service: `app/src/main/kotlin/com/novadrive/app/VoiceSessionService.kt` (`foregroundServiceType="microphone"`, channel `voice_session`, notification "小诺语音会话进行中"), started by `MainActivity` right after `startBaidu` succeeds and stopped on every session-end path. Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (requested once at startup; denial does not block voice).
- Flex provider (default): 16 kHz mono PCM16 capture, documented Flex client events, server-VAD, Function Calling via `AndroidToolDispatcher` (`SafeAndroidActionExecutor`) and `NavigationAdapter`, generation-based stale callback rejection, and close/reconnect cleanup. `sendAudio` / `cancelResponse` no-op on a closed socket (`BAIDU_FLEX_CONNECTION_CLOSED`); `sendFunctionResult` still throws so the core controller can release the delivery claim. Regression: `BaiduFlexClientTest.sendAudioOnClosedSocketEmitsErrorWithoutThrowing`.
- Navigation: with an optional Amap Web-service key (Keystore credential `amap_web_key`, `AmapSettingsRepository`, entered in Developer Settings) `NavigationAdapter` resolves the destination via GET `https://restapi.amap.com/v3/place/text` (`AmapPoiClient`, off the main thread, 4 s timeouts, key never logged) and launches `androidamap://navi?sourceApplication=NovaDrive&poiname=<name>&lat=<lat>&lon=<lon>&dev=0&style=2`; without the key it launches `keywordNavi` (one tap) and falls back to `geo:`. Nominatim (OSM) is unreachable from the phone, so no keyless geocoder is used.
- `open_app`: MAPS -> `geo:` intent; SETTINGS -> `Settings.ACTION_SETTINGS`; MUSIC -> `BundledMusicPlayer` toggles an in-app looping `MediaPlayer` of `app/src/main/res/raw/bach_air_usaf.mp3` (J.S. Bach, Air; The United States Air Force Band; public domain; source Wikimedia Commons; credited in `docs/THIRD_PARTY_AUDIO.md`), volume 0.35, tool output status `music_playing` / `music_stopped`; external players (`CATEGORY_APP_MUSIC`, then `com.miui.player` / netease / qqmusic / kugou / kuwo / spotify launch intents) only as fallback. `resolveActivity` pre-checks were removed (Android 11+ package visibility); `<queries>` declares Amap, those music packages and the `geo:` VIEW intent.
- Transcript display: the core controller now forwards only final (`event.final == true`) user/assistant transcript sentences to the UI. Test: `VoiceSessionControllerTest.transcriptCallbackShowsOnlyFinalUtterances`.
- Reply audio: `BaiduAppSettings.outputSampleRate` (AUTO / 16000 / 24000; prefs key `output_sample_rate`; AUTO resolves Flex -> 24 kHz, Lite/Pro -> 16 kHz via `resolvedOutputSampleRateHz()`), selectable in Developer Settings ("回复音频采样率"). `PcmAudioPlayer` buffer >= 320 ms with a `LinkedBlockingQueue`; `PcmAudioCapture` attaches `AcousticEchoCanceler` and `NoiseSuppressor` when available. Whether 24 kHz is correct is NOT yet confirmed by listening; it is the default pending the user's ear test.
- Lite Near / Lite Far / Pro Near / Pro Far remain selectable in Developer Settings and use `BaiduDirectRealtimeProvider` with no Function Calling.
- Main defaults to Baidu Flex (`qianfan-realtime-flex-v1`; `BaiduRuntimeProvider.fromWire` / `VoiceCatalog.DEFAULT_PROVIDER` = FLEX). Qwen/GPT/backend compatibility remains frozen and is not selected by the production UI.

## Flex boundary

Official Flex doc: https://cloud.baidu.com/doc/SPEECH/s/Wmtlcgi7c — endpoint `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime?model=qianfan-realtime-flex-v1`. Auth is access_token query (legacy App ID/API Key/Secret Key OAuth) or `Authorization: Bearer <bce-v3 API key>`. Handshake: server sends `session.created` + `conversation.created`, then client sends `session.update`. Input/output is pcm16.

Documented client events used by the app: `session.update`, `input_audio_buffer.append`, `response.cancel`, `conversation.item.create` (`function_call_output`), `response.create`. Function-call server events: `response.output_item.added` (`item.type=function_call`), `response.function_call_arguments.delta` / `.done`.

Flex is in PUBLIC BETA (本接口处于公测阶段); access may require Baidu enablement — access denial is surfaced as `BAIDU_FLEX_ACCESS_DENIED`.

Tools declared in `session.update`: `navigate_to(destination: string 1..120)` and `open_app(app: enum maps|music|settings)`, `tool_choice` auto, modalities text+audio, server_vad with `create_response=true` and `interrupt_response=true`. Arguments are validated (bounded size, exact fields, enum) before dispatch; unknown tools return `UNKNOWN_TOOL` without executing; `navigate_to` hands off via `NavigationAdapter` (Amap `navi` with Web-service key, else `keywordNavi` / `geo:`), `open_app` launches maps/settings intents or toggles `BundledMusicPlayer`; results are returned as `function_call_output` followed by `response.create`. Assistant prose is not parsed as commands.

Baidu docs do not state the output sample rate; the Flex `session.created` example shows model `qwen3-omni-safe` (Qwen3-Omni backend, whose realtime output is 24 kHz). AUTO therefore maps Flex -> 24 kHz. Whether 24 kHz is correct is NOT yet confirmed by listening.

## Verified on device

Xiaomi 24069RA21C, serial 2391ff70, Android SDK 36 (2026-09-15).

- Live Baidu Flex Test Connection succeeded with the user's Keystore-stored credentials ("Connection successful / Authentication successful / Model: qianfan-realtime-flex-v1"); a live voice session produced user/assistant transcripts and a `navigate_to` function call that launched Amap (`com.autonavi.minimap`).
- Foreground keep-alive: with another app in front for 15 s the session stayed Listening and MIUI did not destroy the app's sockets (previously `InetDiagMessage: Destroyed live tcp sockets` after ~5 s in background led to `BAIDU_FLEX_DNS_FAILED`).
- Hands-free navigation (2026-09-15, 17:07): with a user-supplied Amap Web-service key stored as Keystore credential `amap_web_key`, pressing the in-app "测试导航到天安门广场（走真实工具路径）" button (exactly the `navigate_to` tool path) launched `androidamap://navi/...` and Amap entered live turn-by-turn guidance with ZERO taps: turn card "216m 无名道路", route line, speed gauge, ETA "23小时18分 2265公里 明天下午4:26到达". No destination pick-list appeared. Without a key the same path still degrades to `keywordNavi` + pick-list.
- `open_app` SETTINGS and MAPS intents open on this phone; nothing on this phone handles `CATEGORY_APP_MUSIC`, hence the bundled track.

Still pending: user ear-test of reply audio at the new 24 kHz Flex default (flip the sample-rate switch if wrong); entering an Amap Web-service key for hands-free navigation on a given install; a full drive test. Also not yet verified by listening/speaking: the 端庄傲气 persona tone, whether the realtime model accepts numeric `voice` ids such as 4157 (the app falls back to `"default"` automatically), the bundled-music tool by voice, and location-biased nearby POI search.

## Evidence

| Check | Result |
| --- | --- |
| Final Gradle | `.\gradlew.bat test :app:assembleDebug` — BUILD SUCCESSFUL, exit code 0 (2026-09-15) |
| Test XML reports | 129 tests total, 0 failures, 0 errors, 0 skipped — app 60 (`testDebugUnitTest`), ingress 44, behavior-test 19, contracts 2, safety 2, simulator 2 |
| APK | `C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk` — 13,263,138 bytes; SHA-256 `73F79FC557C2A43DD4395DA54701136A01E317129CEBC4B738029CAAD4DBB697` |
| Physical device | Installed on Xiaomi 24069RA21C, serial 2391ff70, Android SDK 36. Persona was reset on-device via 开发者设置 -> 恢复默认傲娇人设 -> SAVE, because `BaiduSettingsRepository` prefers a previously saved `instructions` value over the new code default; the stored persona now contains the new 端庄 text and no longer the earlier 傲娇 wording. |
| VERIFIED on device | live Baidu Flex Test Connection with the user's Keystore credentials; a live voice session with transcripts and a `navigate_to` function call; hands-free turn-by-turn navigation through the real tool path with an Amap Web key (see Verified on device); `open_app` settings and maps intents |
| NOT VERIFIED | reply-audio pitch at the new 24 kHz Flex default, the 端庄傲气 persona tone, whether the realtime model accepts numeric `voice` ids such as 4157 (the app falls back to `"default"` automatically), the bundled-music tool by voice, and location-biased nearby POI search — requires the user to listen/speak |

## Notes

- Frozen backend compatibility string `http://10.0.2.2:8000` and an UNREFERENCED `res/xml/network_security_config.xml` (localhost/10.0.2.2 only) remain packaged; Qwen/GPT/backend code remains frozen compatibility code, not selectable from the production UI.
- `BaiduRealtimeClientTest` is pinned to the Lite provider/model because the settings default moved to Flex. Regression: `BaiduFlexClientTest.sendAudioOnClosedSocketEmitsErrorWithoutThrowing`.
- No commit or push was performed. No credentials exist in the repository.
