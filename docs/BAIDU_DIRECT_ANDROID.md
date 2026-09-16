# Baidu Direct Android Runtime

The production path is fully on-device. Default runtime provider is Baidu Flex (`qianfan-realtime-flex-v1`); Lite Near / Lite Far / Pro Near / Pro Far remain selectable and use `BaiduDirectRealtimeProvider` with no Function Calling.

```text
MainActivity
  -> VoiceSessionController
     -> PcmAudioCapture (mono PCM16, 16 kHz)
     -> BaiduFlexProvider (default, Function Calling)
        -> BaiduAccessTokenClient (legacy auth only)
        -> BaiduFlexClient (WSS)
        -> BaiduFlexProtocol
        -> AndroidToolDispatcher / NavigationAdapter
     -> BaiduDirectRealtimeProvider (Lite/Pro, conversation only)
        -> BaiduAccessTokenClient (legacy auth only)
        -> BaiduRealtimeClient (WSS)
        -> BaiduProtocol
     -> PcmAudioPlayer
```

`BaiduSettingsRepository` stores non-secret configuration in private preferences and App ID, API Key, and Secret Key as AES/GCM ciphertext protected by Android Keystore. Blank credential fields retain saved values; clearing is explicit. Schema v2 migration preserves Lite for installs that previously had schema 1 or a Lite/Pro model saved. Test Connection uses `BaiduFlexClient` or `BaiduRealtimeClient` according to the selected model, waits for `session.updated`, and closes without starting the microphone.

Default model: `qianfan-realtime-flex-v1` (Flex). `BaiduRuntimeProvider.fromWire` and `VoiceCatalog.DEFAULT_PROVIDER` default to Flex.

## Handshake

Flex waits for the server to send `session.created` and `conversation.created` before the client sends `session.update`. Pro/Lite sends `session.update` on open.

Flex endpoint: `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime?model=qianfan-realtime-flex-v1`. Auth is access_token query (legacy App ID/API Key/Secret Key OAuth) or `Authorization: Bearer <bce-v3 API key>`. Input/output is pcm16. Official Flex doc: https://cloud.baidu.com/doc/SPEECH/s/Wmtlcgi7c

Documented Flex client events used by the app: `session.update`, `input_audio_buffer.append`, `response.cancel`, `conversation.item.create` (`function_call_output`), `response.create`. The Pro/Lite adapter sends only `session.update` and `input_audio_buffer.append`. Server VAD handles response creation and interruption (`create_response=true`, `interrupt_response=true` on Flex). The phone does not use the PC backend, localhost, emulator aliases, LAN addresses, ADB, or Codex at runtime.

## Command boundary

Flex declares tools in `session.update`: `navigate_to(destination: string 1..120)` and `open_app(app: enum maps|music|settings)`, `tool_choice` auto, modalities text+audio. Function-call server events: `response.output_item.added` (`item.type=function_call`), `response.function_call_arguments.delta` / `.done`. Arguments are validated (bounded size, exact fields, enum) before dispatch; unknown tools return `UNKNOWN_TOOL` without executing; `navigate_to` hands off via `NavigationAdapter` (see Navigation handoff), `open_app` launches maps/settings intents or toggles `BundledMusicPlayer` (see open_app); results are returned as `function_call_output` followed by `response.create`. Assistant prose is not parsed as commands.

Pro/Lite has no Function Calling: documented UpdateSession has no tools field, documented client events have no tool-result message, and ConversationItem type is `message`. The Pro/Lite path does not invent Function Call messages.

Flex is in PUBLIC BETA (本接口处于公测阶段); access may require Baidu enablement — access denial is surfaced as `BAIDU_FLEX_ACCESS_DENIED`.

## Foreground service

`app/src/main/kotlin/com/novadrive/app/VoiceSessionService.kt` is a microphone foreground service (`foregroundServiceType="microphone"`, channel `voice_session`, notification "小诺语音会话进行中"). `MainActivity` starts it right after `startBaidu` succeeds and stops it on every session-end path. Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (requested once at startup; denial does not block voice). Verified on Xiaomi 24069RA21C: with another app in front for 15 s the session stayed Listening and MIUI did not destroy the app's sockets (previously `InetDiagMessage: Destroyed live tcp sockets` after ~5 s in background led to `BAIDU_FLEX_DNS_FAILED`).

## Navigation handoff

Verified on the phone that Amap starts turn-by-turn navigation immediately from `androidamap://navi?sourceApplication=NovaDrive&poiname=<name>&lat=<lat>&lon=<lon>&dev=0&style=2`, while `keywordNavi` / `route?dname=` / `geo:` all stop at a destination pick-list or POI page. `NavigationAdapter` now: with an optional Amap Web-service key (Keystore credential `amap_web_key`, `AmapSettingsRepository`, entered in Developer Settings) it resolves the destination via GET `https://restapi.amap.com/v3/place/text` (`AmapPoiClient`, off the main thread, 4 s timeouts, key never logged) and launches the navi link; without the key it launches `keywordNavi` (one tap) and falls back to `geo:`. Nominatim (OSM) is unreachable from the phone, so no keyless geocoder is used.

Hands-free navigation verified on the device (2026-09-15, 17:07): with a user-supplied Amap Web-service key stored as `amap_web_key`, pressing the in-app "测试导航到天安门广场（走真实工具路径）" button (exactly the `navigate_to` tool path) launched `androidamap://navi/...` and Amap entered live turn-by-turn guidance with ZERO taps: turn card "216m 无名道路", route line, speed gauge, ETA "23小时18分 2265公里 明天下午4:26到达". No destination pick-list appeared. Without a key the same path still degrades to `keywordNavi` + pick-list, which is expected.

## open_app

MAPS -> `geo:` intent; SETTINGS -> `Settings.ACTION_SETTINGS`; MUSIC -> `BundledMusicPlayer` toggles an in-app looping `MediaPlayer` of `app/src/main/res/raw/bach_air_usaf.mp3` (J.S. Bach, Air; The United States Air Force Band; public domain; source Wikimedia Commons; credited in `docs/THIRD_PARTY_AUDIO.md`), volume 0.35, tool output status `music_playing` / `music_stopped`; external players (`CATEGORY_APP_MUSIC`, then `com.miui.player` / netease / qqmusic / kugou / kuwo / spotify launch intents) only as fallback. `resolveActivity` pre-checks were removed (Android 11+ package visibility); `<queries>` declares Amap, those music packages and the `geo:` VIEW intent. Verified on the phone: SETTINGS and MAPS intents open; nothing on this phone handles `CATEGORY_APP_MUSIC`, hence the bundled track.

## Output audio rate

Baidu docs do not state the output sample rate; the Flex `session.created` example shows model `qwen3-omni-safe` (Qwen3-Omni backend, whose realtime output is 24 kHz). `BaiduAppSettings.outputSampleRate` is AUTO / 16000 / 24000 (prefs key `output_sample_rate`); AUTO resolves Flex -> 24 kHz, Lite/Pro -> 16 kHz via `resolvedOutputSampleRateHz()`, selectable in Developer Settings ("回复音频采样率"). `PcmAudioPlayer` buffer >= 320 ms with a `LinkedBlockingQueue`; `PcmAudioCapture` attaches `AcousticEchoCanceler` and `NoiseSuppressor` when available. Whether 24 kHz is correct is NOT yet confirmed by listening; it is the default pending the user's ear test.
