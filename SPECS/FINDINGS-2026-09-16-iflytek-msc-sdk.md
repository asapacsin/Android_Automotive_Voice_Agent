# FINDINGS — the iFlytek SDK was replaced: AIKit → MSC v1140

Recorded: 2026-09-16, from reading `D:\桌面\SDK` after the product owner said "i have update the 訊飛 setting".
Affects: [ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md), [SPEC-001](SPEC-001-wake-word.md), [B-003](../BACKLOG.md), and ~7 MB of code and assets already staged in `app/`.
**Does not affect M2 / Phase 1** — the embedded Amap work touches none of this.

> *(evidence)* = read from the delivered SDK on 2026-09-16. *(unverified)* = not proven; must be tested before being relied on.

## 1. What changed

The folder now holds a **different SDK**, not an update of the previous one. The AIKit bundle is **gone from disk** *(evidence: no `*AIKit*` directory remains under `D:\桌面`)*, while `app/libs/AIKit.aar` is still staged in the repository and still compiled in.

| | Previous (2026-09-16 am) | Now |
| --- | --- | --- |
| SDK | AIKit `AIKit_AEE_Android_IVW_e867a88f2_1.0.44_SDK2.2.17_rc6` | **MSC v1140** *(evidence: `release.txt`)* |
| Library | `AIKit.aar` (4.8 MB) | `libs/Msc.jar` (510,818 B) + `.so` files |
| Native | `libAIKIT.so`, `libef7d69542_v10260_aee.so` | `libmsc.so` **and** `libw_ivw.so`, for `arm64-v8a` and `armeabi-v7a` *(evidence)* |
| Java package | `com.iflytek.aikit.core` | **`com.iflytek.cloud`** *(evidence)* |
| Entry types | `AiHelper`, `AiRequest`, `AiAudio`, `AiStatus` | **`SpeechUtility`, `VoiceWakeuper`, `WakeuperListener`, `WakeuperResult`, `ResourceUtil`** |
| Credentials | appId **+ apiKey + apiSecret** | **appId only** *(evidence: `SpeechApp.java` builds `"appid=" + R.string.app_id`)* |
| Wake resource | `IVW_FILLER_1`, `IVW_GRAM_1`, `IVW_KEYWORD_1`, `IVW_MLP_1` | **one file: `res/ivw/<APPID>.jet`** (987,361 B) *(evidence)* |

**The `.jet` filename is the APPID.** This download is bound to the APPID the product owner supplied, and the demo's `app_id` string is 8 characters and not a placeholder *(evidence; the value is deliberately **not** written here)*. `SpeechApp.java` warns: *"appid 必须和下载的SDK保持一致，否则会出现10407错误"*.

> **Redacted 2026-09-16.** This file previously spelled the APPID out twice. The APPID is a
> credential and must never enter this repository — which is also why the staged asset is
> renamed to `ivw/wakeword.jet` rather than keeping its original `<APPID>.jet` filename, and
> why `IVW_RES_PATH` is a constant instead of being rebuilt from the credential. The live
> value lives only in the Android Keystore store on the device.

**The wake phrase is confirmed:** `wordlist.txt` contains `你好小诺` *(evidence)*.

### The credential blocker is gone

SPEC-001 and every status line since have listed `apiKey` + `apiSecret` as the thing blocking the wake word. **MSC does not use them.** The APPID was supplied on 2026-09-16 and matches this bundle. **B-003 is no longer credential-blocked.**

## 2. Integration facts extracted *(evidence: `WakeDemo.java`, `OneShotDemo.java`, `SpeechApp.java`, `readme.txt`, `release.txt`)*

- **Init:** `SpeechUtility.createUtility(ctx, "appid=<id>," + SpeechConstant.ENGINE_MODE + "=" + SpeechConstant.MODE_MSC)`, once, at application entry.
- **Privacy:** `readme.txt` is explicit — `createUtility` may be called **only after** the user has agreed to a privacy policy. MSC collects the Android ID for licensing. Compliance text and a real consent gate are required before release, and a dev stand-in must be marked as such.
- **Both `.so` files are needed for wake.** v1.1139: *"libmsc.so库中唤醒能力单独分出，需要唤醒能力时需加入libw_ivw.so"*. And `readme.txt`: the `.so` must match its `Msc.jar` drop — **do not mix versions**.
- **Resource path is generated, and read from `assets`:**
  `ResourceUtil.generateResourcePath(ctx, RESOURCE_TYPE.assets, "ivw/" + appId + ".jet")`.
  **This is strictly better than AIKit**, which required copying resources to `/sdcard/iflytek/` and pulled in storage permissions. MSC needs no storage permission and probably **no resource installer at all** — `IflytekResourceInstaller.kt` may become unnecessary.
- **Session parameters** (`WakeDemo.java:128–148`):
  `PARAMS`→null (clear), `IVW_THRESHOLD`→`"0:<n>"`, `IVW_SST`→`"wakeup"` (or `"oneshot"` for wake+recognise), `KEEP_ALIVE`→`"1"` for continuous, `IVW_NET_MODE`, `IVW_RES_PATH`, then `startListening(WakeuperListener)`.
- **Threshold is the false-accept control.** Demo default **1450**, range 0–3000, *lower = easier to wake*. Replaces AIKit's `wdec_param_nCmThreshold`.
- **Two settings we must deliberately NOT copy from the demo:**
  1. `IVW_NET_MODE` — modes 1 and 2 enable "闭环优化", which **uploads audio optimisation data**. Set **`"0"`** to keep the feature offline, consistent with [ADR-001](../DECISIONS/ADR-001-direct-provider-connection.md).
  2. `IVW_AUDIO_PATH` + `AUDIO_FORMAT` — the demo writes the **last minute of microphone audio to disk**. Do not set these in the product.
- **Result JSON:** `sst`, `id`, `score`, `bos`, `eos` — score is the match confidence.
- **Lifecycle:** `VoiceWakeuper.createWakeuper(ctx, null)` creates a singleton; `getWakeuper()` retrieves; `stopListening()`; `destroy()` in `onDestroy`. Unlike AIKit's `unInit`, **no evidence of a one-way-destroy trap** — but *(unverified)*.

## 3. The architecture question — an ADR decision, not an executor's

[ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md) chose "share our existing PCM stream" **specifically because AIKit accepts external audio**. That premise must be re-established for MSC, or the decision changes.

| Option | Evidence | Assessment |
| --- | --- | --- |
| **A — app-fed PCM (keeps ADR-006 intact).** `AUDIO_SOURCE = "-1"` + `mIvw.writeAudio(...)`, fed from `PcmAudioCapture` | **Both calls appear in `WakeDemo.java` — at lines 146 and 155 — but are commented out.** `IatDemo.java:140,154` uses exactly this pattern successfully for dictation | The API surface plainly exists; the wake engine's support is *(unverified)* because the demo never runs it. **Test this first** — it preserves ADR-006 unchanged |
| **B — engine owns the mic, streams audio back.** `NOTIFY_RECORD_DATA = "1"` → `onEvent(EVENT_RECORD_DATA)` delivers raw bytes *(evidence: `WakeDemo.java:143–144, 261–264`)* | An inversion AIKit never offered | Viable fallback, but the detector then owns capture and must hand over at wake — closer to the superseded ADR-005, with the same handover gap |
| **C — detector owns mic while idle, hands over on wake** | — | The original ADR-005 design. Only if A and B both fail |

**Recommended next step:** one small on-device experiment proving or disproving Option A. It is ~30 minutes and decides whether ADR-006 stands as written or is superseded. Do not write the integration before it returns — that is the same discipline that saved SPEC-002 from being built on a false premise.

## 4. What is now obsolete in the repository

Staged on 2026-09-16 against the **wrong** SDK, and currently compiled into the app:

| Path | Size | Status |
| --- | --- | --- |
| `app/libs/AIKit.aar` | 4,805,210 B | **Obsolete** — referenced by `implementation(files("libs/AIKit.aar"))` in `app/build.gradle.kts` |
| `app/src/main/assets/iflytek/ivw/` (`IVW_FILLER_1`, `IVW_GRAM_1`, `IVW_KEYWORD_1`, `IVW_MLP_1`, `keyword1.txt`, `keyword1.txt.bin`) | ~2.3 MB | **Obsolete** — MSC uses a single `.jet` |
| `app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt` | — | **Rewrite** — built on `com.iflytek.aikit.core` |
| `.../wake/IflytekResourceInstaller.kt` | — | **Probably delete** — MSC reads the `.jet` from assets directly |
| `.../wake/WakeWordCredentials.kt` | — | **Simplify** — `apiKey`/`apiSecret` are unused by MSC; keep the Keystore mechanism for `appId` |
| `.../wake/WakeWordSettings.kt` | — | Keep; trim credentials |

Roughly **7 MB of a ~26 MB APK is dead weight** for an SDK we no longer use. Removing it partly offsets the Amap SDK's growth and is relevant to the open ABI decision.

**Not actioned yet, deliberately:** the Phase 1 worker holds the repository and is editing `app/build.gradle.kts` right now. Touching `app/libs/` or `wake/**` concurrently would collide. This cleanup is a separate task, after Phase 1 lands and after the Option A experiment decides the architecture.

## 5. Corrections to the record

- SPEC-001 previously stated, as a correction of an earlier error, that the SDK "**Ships `AIKit.aar`**, not `msc.jar`/`libmsc.so`". That was true of the bundle then on disk. **The delivered SDK has since been replaced**, and the original MSC description now matches what exists. Neither statement was wrong when written; the input changed.
- Every status line since 2026-09-16 morning listing "blocked on iFlytek `apiKey`/`apiSecret`" is **superseded** — MSC needs only the APPID, which is already supplied.
