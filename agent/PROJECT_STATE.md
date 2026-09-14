# Project state — Nova Drive / 小诺

## Milestone
REALTIME PROVIDER RECONCILIATION — **final source-of-truth wording sweep** (2026-09-14). Qwen Flash is the default realtime provider. Qwen Plus is selectable. GPT-Live is optional. Baidu is an optional compatibility adapter (Lite Near is the Baidu-family default when Baidu is selected). Fake is quota-free and test-only, never the product default. Live provider microphone/playback is **not** done. No Qwen/Baidu source-of-truth conflict remains.

## Completed work (this checkpoint)
- Official protocol from QwenCloud/DashScope and OpenAI Live docs (retrieved 2026-09-14); Baidu docs preserved as optional compatibility.
- Kotlin provider-independent contract, `VoiceSessionController`, async `WorkCoordinator`, bounded reconnect, fake clock, latency diagnostics, Fake provider.
- Corrective lifecycle (prior pass): deterministic disconnect outside cancelled caller scope; mic resume-once after reconnect; work inject ack-after-success without holding the session mutex; Android audio invalid-buffer / permission / bounded thread cleanup.
- Supervisor follow-up: `WorkCoordinator.cancelAll()` on `stop()`/`release()` cancels running jobs and marks non-terminal snapshots `CANCELLED` without rewriting already-terminal snapshots; `takeDeliverable()` removed so no public path acks before successful inject.
- Final wording sweep (this pass): listed docs and the Android developer-settings explanatory label now state Qwen Flash as product default; Baidu remains optional with Lite Near as its provider-family default when selected; Fake is explicitly test-only. No architecture or provider-behavior change.
- Backend `QwenRealtimeProvider`, `GPTLiveProvider`, `FakeRealtimeVoiceProvider`; Baidu adapter kept.
- Central catalog: `QWEN` / `GPT_LIVE` / `BAIDU` / `FAKE`. Default `qwen-audio-3.0-realtime-flash` (Kotlin + Python).
- Android debug shell delegates to the JVM controller. Developer settings select provider+model. No provider secrets in the app.
- Docs: `README.md`, `docs/ARCHITECTURE.md`, `docs/PROVIDER_SETUP.md`, `docs/REALTIME_PROTOCOL_REFERENCES.md`, `docs/DECISIONS.md` D13, `docs/CHECKPOINTS.md`, `docs/BAIDU_E2E_SETUP.md`.
- Secrets: `.env.example` placeholders, gitignored `.env`.

## Blockers
- **WAITING_FOR_LIVE_PROVIDER_CREDENTIALS** — live Qwen needs `DASHSCOPE_API_KEY`; GPT-Live needs `OPENAI_API_KEY`; Baidu still needs AppID/API Key/Secret Key. Fill `backend/.env` then resume. Do not paste keys into chat.
- **BLOCKED_BAIDU_FUNCTION_CALLING** — unchanged. Qwen/GPT-Live tools follow their official events; typed orchestrator path still used.
- Emulator/device UI, RECORD_AUDIO grant, Bluetooth SCO, and hardware AEC were not exercised.

Non-fatal: AGP SDK XML v4 vs v3 warning; `android.overridePathCheck=true`.

## Key decisions
See `docs/DECISIONS.md` (D1–D13). Pins unchanged: JDK Temurin 17.0.20.1+1, Gradle 8.11.1, Kotlin 2.0.21, AGP 8.7.3, compileSdk 34. Default realtime provider Qwen Flash. Credentials backend-only. True E2E audio; no ASR+LLM+TTS fallback.

## Toolchain evidence (wording sweep session)
| Item | Path / command | Evidence |
| --- | --- | --- |
| Stale Baidu-default rg | listed docs + `DeveloperSettingsActivity.kt` | 0 matches (rg exit 1) |
| Catalog + secret tests + APK | `.\gradlew.bat :ingress:test --tests MockRealtimeVoiceProviderTest … :behavior-test:test --tests SecretScanTest :app:assembleDebug --no-daemon --no-parallel --console=plain` | exit 0; BUILD SUCCESSFUL in 26s; MockRealtimeVoiceProviderTest 3/3; SecretScanTest 2/2 |
| Catalog method (correct name) | `.\gradlew.bat :ingress:test --tests …VoiceSessionControllerTest.catalogDefaultsAreQwenFlashAndAllProvidersSelectable --no-daemon --no-parallel --console=plain` | exit 0; BUILD SUCCESSFUL in 14s; 1/1 |
| APK | `C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk` | **5256995 bytes**, SHA-256 `789CDF7D6A1C73F03FD609802F1C5E5CC2819D464F6E86B047CBDAD608F2D20F`, version `0.3.1-qwen-realtime` |
| Assignment scans | source + APK `BAIDU_API_KEY=` / `DASHSCOPE_API_KEY=` / `OPENAI_API_KEY=` / `client_secret=` | 0 hits |

This wording sweep did not rerun backend pytest, fake demo, the full 67-test Gradle suite, or the strict demo. Prior supervisor follow-up evidence: targeted ingress BUILD SUCCESSFUL in 24s; full Gradle **67** tests, 0 failures, 38s; pytest **55 passed, 2 skipped**; fake 28 / 9000 events; strict demo `VERIFIED`.

## Exact commands (verified this session)

```powershell
cd D:\桌面\Android_Automotive_Voice_Agent
.\gradlew.bat :ingress:test --tests "com.novadrive.ingress.realtime.MockRealtimeVoiceProviderTest" :behavior-test:test --tests "com.novadrive.architecture.SecretScanTest" :app:assembleDebug --no-daemon --no-parallel --console=plain
.\gradlew.bat :ingress:test --tests "com.novadrive.ingress.realtime.VoiceSessionControllerTest.catalogDefaultsAreQwenFlashAndAllProvidersSelectable" --no-daemon --no-parallel --console=plain
```

Start backend after choosing a provider in `backend/.env`:

```powershell
cd D:\桌面\Android_Automotive_Voice_Agent\backend
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Empty-environ default is Qwen Flash (`VOICE_PROVIDER=qwen`). Optional: `VOICE_PROVIDER=gpt_live`, `baidu`, or `fake` (test-only).

## Next
Live audio only after `backend/.env` has the selected provider’s real key(s) and free quota. Then one short controlled phrase test (no long loops, no paid upgrade). Device Bluetooth/AEC remains a later manual test. Baidu function calling stays blocked unless official E2E docs add custom tools.
