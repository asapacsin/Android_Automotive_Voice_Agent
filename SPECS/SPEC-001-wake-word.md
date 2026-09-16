# SPEC-001 — Wake word

Status: **SUPERSEDED IN ITS TECHNICAL DETAIL (2026-09-16) — the SDK was replaced. Rewrite required before implementation.**

> The product owner swapped the **AIKit** bundle for the **MSC v1140** SDK. Everything below describing `AIKit.aar`, `com.iflytek.aikit.core`, `AiHelper`, ability `e867a88f2`, the four `IVW_*` resource files and the three-credential scheme **no longer matches what was delivered**. Measured replacement facts: [FINDINGS-2026-09-16-iflytek-msc-sdk.md](FINDINGS-2026-09-16-iflytek-msc-sdk.md).
>
> **Two things changed for the better:** MSC needs **only an APPID** — already supplied — so *the `apiKey`/`apiSecret` blocker this file has carried all day no longer exists*; and MSC loads its wake resource straight from `assets`, so no external-storage permission and probably no resource installer.
>
> ~~**One thing got worse:** the microphone-ownership premise behind ADR-006 is unverified again.~~ **RESOLVED 2026-09-16 at API level, without a device.** `javap` on `Msc.jar` shows `VoiceWakeuper` exposes `public int writeAudio(byte[], int, int)` — the *same signature* as `SpeechRecognizer.writeAudio`, which the vendor demo exercises successfully for dictation — and `SpeechConstant.AUDIO_SOURCE` is defined. The demo's commented-out `AUDIO_SOURCE="-1"` / `writeAudio()` lines were therefore a real API, not wishful code. **[ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md)'s shared-capture design survives the SDK swap**; `PcmAudioCapture` stays the single `AudioRecord` owner. Outstanding (L5): that the engine *honours* fed audio and detects reliably — the API existing is necessary, not sufficient.
>
> The demand, the wake phrase 你好小诺, the product rationale, the traps about per-consumer mic gating, and the acceptance levels below all still stand.
Decision: [ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md) (supersedes ADR-005)
Raised: 2026-09-15 by the product owner · Rewritten 2026-09-16 against the real SDK
Backlog: [B-003](../BACKLOG.md)

## Demand (what was actually asked for)

> "you might need to establish things like awake words to awake the system"

Activate 小诺 by speaking 你好小诺 instead of pressing 按住麦克风开始.

## Why it matters

The product is a **driving** assistant. Requiring a screen tap to begin defeats its purpose. Every other capability already works hands-free once a session is running; starting the session is the last manual step.

---

## The SDK that actually arrived

The product owner supplied `AIKit_AEE_Android_IVW_e867a88f2_1.0.44_SDK2.2.17_rc6` on 2026-09-16.

### Corrections to earlier versions of this spec

Earlier revisions described the **MSC** SDK, based on vendor documentation rather than the delivered artifact. Three statements were wrong and are corrected here, because acting on them would have produced a broken design:

| Earlier claim | Reality |
| --- | --- |
| Ships `msc.jar` + `libmsc.so` | Ships **`AIKit.aar`**, containing `classes.jar` + `libAIKIT.so` + `libef7d69542_v10260_aee.so` |
| "The SDK captures device audio itself and exposes no documented API to accept an external PCM stream" — recorded as a **BLOCKER** | **False for AIKit.** The app owns the `AudioRecord` and pushes PCM in via `AiHelper.write(...)`. There is no microphone conflict at all |
| Only an APPID is needed | **Three** values are needed: `appId`, `apiKey`, `apiSecret` |

The mic-ownership "blocker" was the entire justification for ADR-005's handover design. It does not exist, so ADR-005 is superseded by [ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md).

### What the bundle contains

| Item | Detail |
| --- | --- |
| Library | `SDK/AIKit.aar` (4.8 MB) |
| Native ABIs | `arm64-v8a`, `armeabi-v7a` — both present, matching the test device. No x86 (irrelevant) |
| Ability ID | **`e867a88f2`** (offline wake, IVW70) |
| Java package | `com.iflytek.aikit.core` — `AiHelper`, `AiRequest`, `AiResponse`, `AiListener`, `AiHandle`, `AiStatus`, `AiAudio`, `BaseLibrary`, `CoreListener`, `ErrType` |
| Wake-word resource | `resource/ivw/` — `IVW_FILLER_1`, `IVW_GRAM_1` (1.5 MB), `IVW_KEYWORD_1`, `IVW_MLP_1` (728 KB) |
| **Wake phrase, already built** | `resource/ivw/keyword1.txt` contains **`你好小诺;`** — exactly our phrase. Nothing needs generating |
| minSdk | 21 (demo targets 30; ours is higher, which is fine) |
| Not needed | `resource/ivw/AudioCache/test.pcm` (20 MB demo audio) and the `xcrash` dependency |

---

## Integration design

### 1. Initialise once, on a background thread

```java
BaseLibrary.Params params = BaseLibrary.Params.builder()
        .appId(APPID).apiKey(APIKEY).apiSecret(APISECRET)
        .workDir(WORK_DIR)
        .build();
AiHelper.getInst().initEntry(appContext, params);   // background thread
AiHelper.getInst().registerListener(coreListener);  // auth state
```

Auth arrives asynchronously on `CoreListener.onAuthStateChange(ErrType.AUTH, code)`; **`code == 0` means authorised**. Nothing may start before that.

**Network is required on first run only.** The SDK fetches its protocol once, then runs offline — the vendor states this explicitly, and it is what makes the feature acceptable under [ADR-001](../DECISIONS/ADR-001-direct-provider-connection.md).

### 2. Session lifecycle

Documented order — `SDKinit → registerListener → start → write → end → engineUninit → SDKUninit`:

```java
AiHelper.getInst().registerListener(ABILITY_ID, aiListener);

AiRequest.Builder custom = AiRequest.builder();
custom.customText("key_word", RES_DIR + "/keyword.txt", 0);
AiHelper.getInst().loadData(ABILITY_ID, custom.build());
AiHelper.getInst().specifyDataSet(ABILITY_ID, "key_word", new int[]{0});

AiRequest.Builder p = AiRequest.builder();
p.param("wdec_param_nCmThreshold", "0 0:800");  // confidence threshold
p.param("gramLoad", true);
aiHandle = AiHelper.getInst().start(ABILITY_ID, p.build(), null);
```

Then feed audio continuously, and read results from `AiListener.onResult`, where the keys that matter are **`func_wake_up`** and **`func_pre_wakeup`**.

### 3. Audio contract

- **16 kHz, 16-bit, mono PCM.**
- 1280-byte buffers, sent roughly every 40 ms (1280 bytes = exactly 40 ms at that format).
- Each frame is tagged `AiStatus.BEGIN` / `CONTINUE` / `END`.

Per [ADR-006](../DECISIONS/ADR-006-wake-word-aikit-shared-capture.md), these frames come from the **existing `PcmAudioCapture`**, not a second `AudioRecord`.

**Verify before building:** our capture runs at `VOICE_COMMUNICATION` with AEC/NS attached. Confirm its sample rate is already 16 kHz mono; if it is not, resample rather than opening a second recorder.

### 4. Resource packaging — do NOT copy the demo

The demo sets `workDir = /sdcard/iflytek/` and requests `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE` and **`MANAGE_EXTERNAL_STORAGE`**. Shipping that in a voice assistant is both a poor look and unnecessary.

Instead: ship `resource/ivw/*` in **`assets/iflytek/ivw/`**, copy once on first run into app-private storage (`context.filesDir`), and set `workDir` there. No external-storage permission is required. The only permission the feature genuinely adds is `RECORD_AUDIO`, which the app already holds.

### 5. Credentials

`appId`, `apiKey` and `apiSecret` go in `AndroidKeystoreCredentialStore` beside the Baidu and Amap keys, entered on-device — **never in source, never committed, never logged**. The SDK's `strings.xml` approach is explicitly rejected for this reason.

---

## Traps — read before implementing

1. **`engineUninit` is one-way.** The SDK's own comment: *"engineuninit仅当最终退出不使用唤醒的时候才调用，调用engineuninit后不能再重新start唤醒能力，否则引擎会报错崩溃."* Call it only at final teardown. Calling it on every session stop will crash the engine on the next start.
2. **The mic gate must become per-consumer.** Frames are currently dropped globally while 小诺 speaks. If that gate also starves the detector, the wake word goes deaf precisely when a user would barge in. Gate the **provider uplink**, not the detector.
3. **Filename mismatch.** The shipped resource is `keyword1.txt`; the demo writes and loads `keyword.txt`. Whatever path is passed to `customText("key_word", …)` must be the file that actually exists.
4. **Auth is asynchronous.** `initEntry` returns before authorisation completes. Starting the ability before `ErrType.AUTH` reports `0` will fail.
5. **The threshold is tunable.** `wdec_param_nCmThreshold = "0 0:800"` is the demo's value, not a tuned one. It is the primary control for false accepts.

### Review findings on the shipped skeleton (2026-09-16, independent review — not self-reported)

The detector in `app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt` compiles and handles the one-way `unInit` trap correctly (`release()` is the only caller; `stopListening()` only ends the session handle). Two issues remain for the **wiring** task:

6. **`IflytekWakeWordDetector` is single-use after `release()`.** `release()` calls `initExecutor.shutdownNow()` and never recreates the executor, so a subsequent `initialize()` silently fails to run. This is *consistent* with the one-way `unInit` trap, but it means the detector must be treated as process-lifetime, not per-session. Do not call `release()` on session stop — only on app teardown.
7. **`registerListener(ABILITY_ID, aiListener)` is called on every `startListening()`.** If the SDK accumulates listeners rather than replacing them, repeated start/stop cycles will register duplicates and fire `onWake` more than once per utterance. Verify against the SDK on device; register once after auth if accumulation is observed.

Also note for wiring: `writeFrame(first, last)` maps `last = true` to `AiStatus.END`, which **ends the engine's utterance**. For continuous idle listening the caller must keep sending `CONTINUE` and never pass `last = true` until it genuinely wants to stop.

---

## Open questions

| # | Question | Status |
| --- | --- | --- |
| Q1 | Which detector? | **ANSWERED** — iFlytek AIKit, ability `e867a88f2` |
| Q2 | Offline Chinese wake word on acceptable terms? | **ANSWERED** — offline after first auth; commercial pricing still unobtained |
| Q3 | Microphone coexistence | **DISSOLVED** — no conflict; see ADR-006 |
| Q4 | False-accept rate with Amap guidance and music playing | OPEN — device-only; tune via `wdec_param_nCmThreshold` |
| Q5 | Battery cost per hour of idle listening | OPEN — device-only |
| Q6 | Does a `VOICE_COMMUNICATION` stream with AEC/NS degrade detection vs the demo's plain `MIC`? | OPEN — the main technical risk in ADR-006 |

## Remaining blockers

1. **`apiKey` and `apiSecret`** from the iFlytek console. The APPID was supplied on 2026-09-16; the SDK's own `strings.xml` ships all three as placeholders, so none of them can be recovered from the bundle. **Without these the SDK cannot authorise and nothing can be tested.**
2. **Commercial pricing** (trial is 10 installs / 90 days — enough to build and verify, not to ship).

## Acceptance

Per `ACCEPTANCE_TESTS.md`:

| Item | Level |
| --- | --- |
| SDK authorises on device (`ErrType.AUTH` code 0) | L5 |
| Detector recognises 你好小诺 and starts a session | **L5** — device, human voice |
| Works with Amap foreground | **L5** |
| No false trigger on navigation guidance or music over a sustained period | **L5** |
| Battery cost measured over ≥ 30 minutes | **L5** |
| Enable/disable setting persists | L2 |
| Detector still hears the phrase while the provider uplink is gated | **L5** — trap 2 |

A wake word cannot be accepted on automated tests. It is a live-audio feature and only device evidence counts.
