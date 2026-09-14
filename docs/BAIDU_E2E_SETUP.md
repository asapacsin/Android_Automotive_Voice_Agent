# Baidu E2E realtime setup

Baidu is an **optional compatibility provider**. Product default is Qwen Flash (`qwen-audio-3.0-realtime-flash`); see `docs/PROVIDER_SETUP.md`. This page is the Baidu operational path when `VOICE_PROVIDER=baidu` is selected. Credentials never go in the Android app. When Baidu is selected, Lite Near is that provider family's default.

Official API: [端到端语音语言大模型API](https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei) (updated 2026-09-04)

Official auth: [鉴权认证](https://cloud.baidu.com/doc/SPEECH/s/cm8sn2bii)

## A. Where to enter credentials

1. Copy `backend/.env.example` to `backend/.env`
2. Set `VOICE_PROVIDER=baidu` (Baidu is optional; Qwen Flash remains the product default)
3. Fill **only** these values from the Baidu console application:

```
BAIDU_APP_ID=
BAIDU_API_KEY=
BAIDU_SECRET_KEY=
```

Do not paste keys into chat, Android, `local.properties`, or `strings.xml`.

## B. Which model is used

When Baidu is explicitly selected, the provider-family default is `audio-mini-realtime-near` (Lite Near).

Debug settings can switch to Lite Far / Pro Near / Pro Far. The backend will not silently upgrade Lite to Pro.

## C. How to start the backend

From the repo root in PowerShell:

```powershell
cd backend
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Check: open `http://127.0.0.1:8000/health` → `{"status":"ok"}`.

## D. How to start the Android app

1. `android/local.properties` is `local.properties` at the repo root. Set:

```
NOVA_BACKEND_URL=http://10.0.2.2:8000
```

Emulator `10.0.2.2` reaches the host PC. On a physical device, use the PC LAN IP, for example `http://192.168.1.8:8000`.

2. Android Studio: open this folder, run the `app` debug configuration.

Or:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Install the APK from `C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk`.

## E. How to test

1. Allow microphone permission.
2. Optional DEBUG screen **开发者设置**: product default is Qwen Flash. If Baidu is selected, confirm Backend URL and model Lite Near.
3. Tap the microphone button (session starts; audio is sent only while the session is active).
4. Speak: `你好，我正在测试车载语音助手，请简短回复我。`
5. Tap the microphone button again to end the session.

**麦克风测试** checks the mic locally and does not call Baidu.

## F. What success looks like

`Listening` → model native PCM audio plays → you hear a short Chinese reply. States visible: Disconnected, Connecting, Listening, Thinking, Speaking, Error.

This is true E2E audio (`wss://aip.baidubce.com/ws/2.0/speech/v1/realtime`), not ASR + LLM + TTS.

## G. Troubleshooting

| Symptom | Code / what to do |
| --- | --- |
| credentials not filled | `BAIDU_CREDENTIALS_MISSING` — edit `backend/.env` |
| wrong API Key / Secret Key | `BAIDU_AUTH_FAILED` |
| free quota gone | `BAIDU_QUOTA_EXHAUSTED` |
| mic denied | `MIC_PERMISSION_DENIED` — grant RECORD_AUDIO |
| backend down / emulator network | `BAIDU_WS_FAILED` — start uvicorn; use `10.0.2.2` or LAN IP |
| bad model name | `BAIDU_INVALID_MODEL` |

Function calling / `set_temperature` via this exact E2E WebSocket API is **not documented** (`BLOCKED_BAIDU_FUNCTION_CALLING`). Do not parse assistant text as a tool call.

Normal tests never call Baidu. Live check only if you set `RUN_BAIDU_LIVE_TESTS=true` **and** real keys exist in `backend/.env`.
