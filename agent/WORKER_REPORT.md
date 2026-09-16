# Implementation report — direct Android Baidu realtime

Date: 2026-09-15

## Status

PASS for offline implementation, automated verification, physical-device install, and live Baidu Flex on Xiaomi 24069RA21C (Test Connection, voice session with transcripts and `navigate_to`, hands-free Amap navi with Web key, foreground keep-alive). Remaining manual items: user ear-test of reply audio; entering an Amap Web-service key on a given install; a full drive test.

## Architecture delivered

```text
MainActivity
  -> BaiduSettingsRepository -> AndroidKeystoreCredentialStore
  -> VoiceSessionService (microphone FGS keep-alive)
  -> VoiceSessionController
     -> PcmAudioCapture (16 kHz mono PCM16; AEC + NS when available)
     -> BaiduRuntimeProvider (defaults to FLEX)
        -> BaiduFlexProvider (default, Function Calling)
           -> BaiduAccessTokenClient (legacy mode)
           -> BaiduFlexClient
           -> BaiduFlexProtocol / FlexFunctionCallAssembler
           -> AndroidToolDispatcher (SafeAndroidActionExecutor)
           -> NavigationAdapter -> AmapPoiClient
           -> BundledMusicPlayer
        -> BaiduDirectRealtimeProvider (Lite/Pro, conversation only)
           -> BaiduAccessTokenClient (legacy mode)
           -> BaiduRealtimeClient
           -> BaiduProtocol
     -> PcmAudioPlayer (>= 320 ms buffer, LinkedBlockingQueue)
```

The selected provider waits for `session.updated` before the shared controller arms the microphone. Connection generations reject stale callbacks. Stop/release cancels capture, playback, socket work, pending delivery, and token HTTP. Retry/error mapping distinguishes auth, quota, DNS, TLS, timeout, invalid model, protocol, and connection failures without logging credentials.

Settings support masked App ID/API Key/Secret Key input, blank-to-keep, replace, and explicit clear. Schema v2 migration preserves Lite for installs that previously had schema 1 or a Lite/Pro model saved. Test Connection uses `BaiduFlexClient` or `BaiduRealtimeClient` according to the selected model, waits for `session.updated`, and never starts the microphone. Both official legacy OAuth token auth and modern Bearer API Key auth are supported.

## Protocol boundary

Flex endpoint: `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime?model=qianfan-realtime-flex-v1`. Auth is access_token query (legacy App ID/API Key/Secret Key OAuth) or `Authorization: Bearer <bce-v3 API key>`. Handshake: server sends `session.created` + `conversation.created`, then client sends `session.update`. Input/output is pcm16. Official doc: https://cloud.baidu.com/doc/SPEECH/s/Wmtlcgi7c

Documented Flex client events used by the app: `session.update`, `input_audio_buffer.append`, `response.cancel`, `conversation.item.create` (`function_call_output`), `response.create`. Function-call server events: `response.output_item.added` (`item.type=function_call`), `response.function_call_arguments.delta` / `.done`. Flex is in PUBLIC BETA (本接口处于公测阶段); access may require Baidu enablement — access denial is surfaced as `BAIDU_FLEX_ACCESS_DENIED`.

Tools declared in `session.update`: `navigate_to(destination: string 1..120)` and `open_app(app: enum maps|music|settings)`, `tool_choice` auto, modalities text+audio, server_vad with `create_response=true` and `interrupt_response=true`. Arguments are validated (bounded size, exact fields, enum) before dispatch; unknown tools return `UNKNOWN_TOOL` without executing; `navigate_to` hands off via `NavigationAdapter` -> `AmapPoiClient` (Amap `navi` with Web-service key, else `keywordNavi` / `geo:`), `open_app` launches maps/settings intents or toggles `BundledMusicPlayer`; results are returned as `function_call_output` followed by `response.create`. Assistant prose is not parsed as commands.

Lite Near / Lite Far / Pro Near / Pro Far remain selectable and use `BaiduDirectRealtimeProvider` with no Function Calling (documented Pro/Lite client events stay `session.update` and `input_audio_buffer.append`). Default runtime provider is Flex (`BaiduRuntimeProvider.fromWire` / `VoiceCatalog.DEFAULT_PROVIDER` = FLEX; model `qianfan-realtime-flex-v1`).

## Verification

- Command: `.\gradlew.bat test :app:assembleDebug` → BUILD SUCCESSFUL, exit code 0 (2026-09-15)
- Test XML reports: 129 tests total, 0 failures, 0 errors, 0 skipped — app 60 (`testDebugUnitTest`), ingress 44, behavior-test 19, contracts 2, safety 2, simulator 2
- Lifecycle: `BaiduFlexClient.sendAudio` and `cancelResponse` no longer throw on a closed socket (they emit `BAIDU_FLEX_CONNECTION_CLOSED` / no-op), matching `BaiduRealtimeClient`; `sendFunctionResult` still throws so the core controller can release the delivery claim. Regression test: `BaiduFlexClientTest.sendAudioOnClosedSocketEmitsErrorWithoutThrowing`. `BaiduRealtimeClientTest` is pinned to the Lite provider/model because the settings default moved to Flex. Transcript UI: `VoiceSessionControllerTest.transcriptCallbackShowsOnlyFinalUtterances`.
- APK: `C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk` — 13,263,138 bytes; SHA-256 `73F79FC557C2A43DD4395DA54701136A01E317129CEBC4B738029CAAD4DBB697`
- Physical device: Installed on Xiaomi 24069RA21C, serial 2391ff70, Android SDK 36. Persona was reset on-device via 开发者设置 -> 恢复默认傲娇人设 -> SAVE, because `BaiduSettingsRepository` prefers a previously saved `instructions` value over the new code default; the stored persona now contains the new 端庄 text and no longer the earlier 傲娇 wording.
- VERIFIED on device: live Baidu Flex Test Connection with the user's Keystore credentials; a live voice session with transcripts and a `navigate_to` function call; hands-free turn-by-turn navigation through the real tool path with an Amap Web key; `open_app` settings and maps intents.
- NOT VERIFIED (requires the user to listen/speak): reply-audio pitch at the new 24 kHz Flex default, the 端庄傲气 persona tone, whether the realtime model accepts numeric `voice` ids such as 4157 (the app falls back to `"default"` automatically), the bundled-music tool by voice, and location-biased nearby POI search.

## Device evidence

- Live Baidu Flex on Xiaomi 24069RA21C (serial 2391ff70, Android SDK 36): Test Connection succeeded with the user's Keystore-stored credentials ("Connection successful / Authentication successful / Model: qianfan-realtime-flex-v1"); a live voice session produced user/assistant transcripts and a `navigate_to` function call that launched Amap (`com.autonavi.minimap`).
- Foreground service keep-alive: with another app in front for 15 s the session stayed Listening and MIUI did not destroy the app's sockets (previously `InetDiagMessage: Destroyed live tcp sockets` after ~5 s in background led to `BAIDU_FLEX_DNS_FAILED`).
- Navigation: Amap starts turn-by-turn immediately from `androidamap://navi?...`; `keywordNavi` / `route?dname=` / `geo:` stop at a destination pick-list or POI page. Hands-free verified 2026-09-15 17:07 with Keystore credential `amap_web_key`: in-app "测试导航到天安门广场（走真实工具路径）" (exactly the `navigate_to` tool path) launched `androidamap://navi/...` and Amap entered live turn-by-turn with ZERO taps (turn card "216m 无名道路", route line, speed gauge, ETA "23小时18分 2265公里 明天下午4:26到达"). Without a key the same path still degrades to `keywordNavi` + pick-list.
- `open_app`: SETTINGS and MAPS intents open on this phone; nothing on this phone handles `CATEGORY_APP_MUSIC`, hence the bundled Bach track.

No commit or push was performed. No credentials exist in the repository.
