# Provider setup — Nova Drive / 小诺

Default provider is **Qwen Flash** (`qwen-audio-3.0-realtime-flash`). Qwen Plus is selectable. GPT-Live and Baidu remain optional adapters. When Baidu is explicitly selected, Lite Near (`audio-mini-realtime-near`) is that provider family's default. Fake is a quota-free local test provider and is never the product default.

## 1. Copy env

```powershell
cd backend
copy .env.example .env
```

Edit `backend/.env` only. Never put keys in the APK, `local.properties`, or chat.

## 2. Choose provider

| `VOICE_PROVIDER` | Model | Secret |
| --- | --- | --- |
| `qwen` (default) | `qwen-audio-3.0-realtime-flash` (Flash, product default) or `qwen-audio-3.0-realtime-plus` (Plus) | `DASHSCOPE_API_KEY` |
| `gpt_live` (optional) | `gpt-live-1` | `OPENAI_API_KEY` |
| `baidu` (optional compatibility) | `audio-mini-realtime-near` (Lite Near, Baidu-family default when selected), also Lite Far / Pro Near / Pro Far | `BAIDU_APP_ID` + `BAIDU_API_KEY` + `BAIDU_SECRET_KEY` |
| `fake` / `mock` (test-only) | any catalog id | none |

Missing Baidu or GPT-Live credentials **must not** block Qwen or Fake startup. Select `baidu` / `gpt_live` only when you intend to use them. Placeholder Qwen keys fail as `QWEN_CREDENTIALS_MISSING`. Placeholder Baidu keys fail as `BAIDU_CREDENTIALS_MISSING`. Fake needs no credentials and is never the product default.

## 3. Start backend

```powershell
cd backend
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Android emulator: `NOVA_BACKEND_URL=http://10.0.2.2:8000` in `local.properties`.
Physical device: use the PC LAN IP.

DEBUG developer settings expose Backend URL + Qwen Flash/Plus (product default Flash), plus optional GPT-Live, Baidu Lite Near/Far and Pro Near/Far, and Fake (test-only). No credential fields.

## 4. Quota-free checks

```powershell
cd backend
python -m pytest -p no:warnings -q
python scripts/fake_provider_demo.py
python scripts/fake_provider_demo.py --benchmark
```

Do not set `RUN_BAIDU_LIVE_TESTS`, `RUN_QWEN_LIVE_TESTS`, or `RUN_GPT_LIVE_TESTS` for normal work.
