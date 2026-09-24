# Android_Automotive_Voice_Agent — Nova Drive / 小诺

> Coding agents start at **[AGENTS.md](AGENTS.md)**.

China-first Android / Android Automotive voice assistant foundation.

Checkpoint 1 establishes a buildable Kotlin/Gradle tree, typed structured-command contracts, deterministic safety, a provider/vehicle adapter boundary, an in-memory simulator, observed-state verification, and concise `zh-CN` feedback. Later checkpoints add microphone ingress, realtime S2S, production skills, and optional AAOS/VHAL.

Default persona / wake phrase: **小诺** / **你好小诺**. Locale: `zh-CN`. Units: metric.

## Non-negotiable boundaries

- AI / S2S decides **what** action is intended (typed `StructuredCommand` only).
- Deterministic Kotlin decides **how** an action executes (`VoiceSessionOrchestrator` + `SafetyPolicy`).
- Observed vehicle state decides **whether** execution succeeded (`ObservedStateVerifier`).
- LLM/S2S output never imports or drives the simulator, provider SDKs, AAOS, or VHAL.

## Modules

| Module | Role | May depend on |
| --- | --- | --- |
| `contracts` | Typed commands, results, location metadata, policy enums | nothing |
| `ingress` | Provider-neutral S2S / structured-command port | `contracts` |
| `safety` | Deterministic `ALLOW` / `CONFIRM` / `DENY` | `contracts` |
| `vehicle` | Navigation / media / phone / HVAC adapter interfaces | `contracts` |
| `verification` | Read-back comparison | `contracts`, `vehicle` |
| `feedback` | Driving-appropriate Simplified Chinese copy | `contracts` |
| `orchestration` | Task manager, policy gate, skill router | contracts + safety + vehicle + verification + feedback + ingress port |
| `simulator` | In-memory Fake adapters | `contracts`, `vehicle` |
| `behavior-test` | Cross-module behavior and dependency-boundary tests | all of the above |
| `demo` | JVM demonstration | runtime wiring including simulator |
| `app` | Android Automotive-capable shell (included only when SDK platform 34 is installed) | same as demo |

`ingress`, `safety`, `orchestration`, and other core modules do **not** depend on `simulator`.

## Local toolchain (this workspace)

Pinned because they are what this machine actually has or can extract:

- JDK: Eclipse Temurin **17.0.20.1+1** at `C:\Users\Administrator\tools\jdk-17`
- Gradle: **8.11.1** zip at `C:\Users\Administrator\tools\gradle-8.11.1-bin.zip`
- Android SDK root: `C:\Users\Administrator\Android\Sdk` (cmdline-tools 22.0; platform 34 is installed by `scripts/dev.ps1` when missing)
- Kotlin **2.0.21**, Android Gradle Plugin **8.7.3**, `compileSdk` / `targetSdk` **34**, `minSdk` **28**

**The product runs Baidu Qianfan Flex end-to-end speech-to-speech on the phone** (`qianfan-realtime-flex-v1`), with credentials in the Android Keystore. Qwen, GPT-Live and the PC backend below are dormant compatibility code and are not reachable from the production UI — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/TECH_DEBT.md](docs/TECH_DEBT.md) D-5.

## Realtime voice credentials — **the dormant PC backend only**

The phone does not read any of this. Its credentials live in the Android Keystore
(`AndroidKeystoreCredentialStore`) and its provider is fixed to Baidu Qianfan Flex by
[ADR-002](DECISIONS/ADR-002-baidu-flex-default-provider.md). The steps below configure
`backend/`, which is dormant compatibility code ([TECH_DEBT.md](docs/TECH_DEBT.md) D-5) —
`VOICE_PROVIDER=qwen` is that backend's default, never the product's.

1. Copy `backend/.env.example` to `backend/.env`
2. Keep `VOICE_PROVIDER=qwen` (default) and fill `DASHSCOPE_API_KEY`, **or** use `fake` with no keys
3. Optionally set `VOICE_PROVIDER=gpt_live` or `baidu` only when using those adapters. Selecting Baidu uses Lite Near (`audio-mini-realtime-near`) as that provider family's default
4. Placeholder Qwen credentials fail as `QWEN_CREDENTIALS_MISSING`; placeholder Baidu credentials fail as `BAIDU_CREDENTIALS_MISSING`
5. Start backend:

```powershell
cd backend
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Android emulator backend URL in `local.properties`:

```
NOVA_BACKEND_URL=http://10.0.2.2:8000
```

Never put AppID / API Key / Secret Key in the Android app.

## Commands

From the repository root in PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat :demo:run --args="--strict-exit"
.\gradlew.bat :app:assembleDebug
```

`gradlew.bat` sets `JAVA_HOME` when unset and extracts Gradle 8.11.1 from the local zip on first use. Gradle is configured `--no-daemon` with 20s HTTP timeouts. Compiled outputs (including `app-debug.apk`) go to `C:\Users\Administrator\tools\nova-drive-build\` so Windows test workers can load classes from this `D:\桌面\...` source path.

Equivalent helper:

```powershell
.\scripts\dev.ps1 test
.\scripts\dev.ps1 :demo:run --args="--strict-exit"
.\scripts\dev.ps1 :app:assembleDebug
```

`:app:assembleDebug` exists only after `platforms\android-34` is present. `scripts/dev.ps1` installs that package through `sdkmanager` when missing.

## Demonstration

The JVM demo submits a typed `StartNavigation` to 人民广场 (GCJ-02), runs policy → Fake navigation adapter → read-back verification, and prints a short `zh-CN` line such as `已开始前往人民广场。`

## License / deployment

Not published. Checkpoint 1 is a local foundation only.

## Local binaries (not in git)

Large third-party files are kept out of the repository. A fresh clone needs them copied in before `:app` builds:

| Path | What | Source |
| --- | --- | --- |
| `app/libs/Msc.jar` | iFlytek MSC SDK | iFlytek console, SDK download for your App ID |
| `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libmsc.so`, `libw_ivw.so` | iFlytek native libraries (wake word) | same SDK package |
| `app/src/main/assets/ivw/wakeword.jet` | 「你好小诺」 wake-word resource | iFlytek console, bound to the App ID |
| `app/src/main/res/raw/bach_air_usaf.mp3` | Bundled public-domain music track | US Air Force Band public-domain recording |

Cloud sessions (Claude Code on the web) install the toolchain and fetch these from environment
variables at session start: see [docs/CLOUD_BUILD.md](docs/CLOUD_BUILD.md).
