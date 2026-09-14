# Worker report — REALTIME PROVIDER RECONCILIATION (final source-of-truth wording sweep)

Worker: Cursor Grok 4.6 (docs + developer-settings label only)

Task file: `agent/CURRENT_TASK.md` (acceptance criteria not modified)

Date: 2026-09-14

## STATUS: PASS

Lifecycle supervisor follow-up remains green. This pass corrected only the listed stale Baidu-default documentation and the Android developer-settings explanatory label. Executable Kotlin/Python defaults were already Qwen Flash and were not changed. No live Qwen/GPT-Live/Baidu calls. No architecture or provider-behavior edits. Full backend pytest, fake demo, and the 67-test Gradle suite were **not** rerun in this wording sweep.

## Provider source of truth

**Primary provider:** Qwen Flash. Default model `qwen-audio-3.0-realtime-flash` (`VoiceCatalog.DEFAULT_PROVIDER=QWEN`). Qwen Plus remains selectable.

**Baidu:** optional compatibility/provider-abstraction adapter, not the product default. When Baidu is explicitly selected, Lite Near (`audio-mini-realtime-near`) is that provider family's default.

**GPT-Live:** optional provider.

**Fake:** quota-free local test provider, never the product default.

**Conflict remaining:** none. Implemented code, listed product docs, developer-settings label, this report, and `agent/PROJECT_STATE.md` now agree.

---

## Project-manager summary

**What this wording sweep fixed.** Listed product docs and the DEBUG developer-settings label still said Baidu was the product default. They now state Qwen Flash (`qwen-audio-3.0-realtime-flash`) as default, Qwen Plus selectable, GPT-Live optional, Baidu optional with Lite Near as its family default when selected, and Fake as test-only. Provider behavior was not changed.

**What the prior supervisor follow-up fixed.** The first corrective pass left four review gaps. That run closed only those:

1. `VoiceSessionController.stop()` / core `release()` now call `WorkCoordinator.cancelAll()`, so outstanding jobs are cancelled and non-terminal snapshots become `CANCELLED`.
2. Legacy `takeDeliverable()` (ack-before-inject) is removed. The only public path is claim → provider inject → acknowledge, with release-on-failure.
3. Regression tests cover coroutine cancellation, terminal-snapshot consistency, claim-without-ack, and stop/release teardown.
4. This report ends with the required handoff line.

**Architecture.** Unchanged: Android PCM16 ↔ provider-neutral JSON `ws://<backend>/v1/voice/realtime`. Backend selects `qwen` / `gpt_live` / `baidu` / `fake`. UI never parses vendor JSON. Vehicle tools stay typed command → orchestrator → safety → adapter → observed-state verification.

**Exact tests (prior supervisor follow-up).** Targeted `:ingress:test` for WorkCoordinator + VoiceSessionController, then full `.\gradlew.bat test :ingress:test :behavior-test:test --no-daemon --no-parallel --console=plain` → **67 JVM tests, 0 failures**, `BUILD SUCCESSFUL in 38s`, exit 0.

**Exact tests (this wording sweep).** Focused catalog + secret-scan tests and `:app:assembleDebug` after the UI string change; see commands below.

---

## Four core lifecycle findings (still hold)

| Finding | Root cause | Files changed (prior pass) | Resulting behavior | Regression test |
| --- | --- | --- | --- | --- |
| 1. Deterministic disconnect | Android `release()` cancelled the caller scope while core `stop()` launched `provider.disconnect()` on that scope | `ingress/.../VoiceSessionController.kt` (`launchDetached`) | Disconnect runs on a dispatcher-owned one-shot job; repeated `stop()` disconnects once per session | `stopDisconnectsOnceEvenWhenCallerScopeIsCancelledImmediately`, `connectDisconnectDoesNotDuplicateCollectors` |
| 2. Reconnect mic resume | Reconnect stopped capture and never re-armed it | `ingress/.../VoiceSessionController.kt` (`captureArmed`, `resumeCaptureOnce`) | Capture resumes exactly once after reconnect/`SessionReady`; no duplicate collectors | `reconnectResumesCaptureOnceWithoutDuplicateCollectors` |
| 3. Async cancellable work | `WorkCoordinator` was an in-memory table only | `ingress/.../WorkCoordinator.kt` (`submit(scope, …)`) | Caller-owned jobs; isolated cancel; audio continues while work is pending | `WorkCoordinatorAsyncTest.submitSuspendingWorkRecordsProgressAndCancelIsIsolated`, `asyncWorkRunsWhileAudioContinuesAndCancelIsIsolated` |
| 4. Ack-after-success inject | Delivery marked `delivered=true` before `injectWorkResult()` succeeded | `ingress/.../WorkCoordinator.kt` + `VoiceSessionController.deliverPendingWork` | Claim, inject off the session mutex, ack only after success; failed inject leaves pending | `workResultInjectionRetriesAfterFailureAndAcknowledgesOnce`, `failedInjectionClaimCanBeReleasedAndAcknowledgedOnce`, `slowWorkInjectionDoesNotBlockRealtimeAudioEvents` |

---

## Supervisor follow-up findings (prior pass)

### 1. Work teardown on stop/release

**Root cause.** `stop()` cancelled collectors/delivery but never cancelled coordinator jobs, so running work stayed `RUNNING` after session teardown. Core `release()` only called `stop()`.

**Files changed.** `ingress/src/main/kotlin/com/novadrive/ingress/realtime/WorkCoordinator.kt` (`cancelAll`); `ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt` (`stop()` calls `cancelAll()`; `release()` still delegates to `stop()`).

**Resulting behavior.** Running jobs are cancelled; their snapshots become `CANCELLED`. Already `COMPLETED` / `FAILED` / `CANCELLED` snapshots are left unchanged.

**Regression tests.** `cancelAllCancelsRunningCoroutineAndLeavesTerminalSnapshots` (coroutine CancellationException observed; terminal snapshots unchanged); `stopCancelsOutstandingAsyncWorkAndLeavesTerminalSnapshots`; `releaseCancelsOutstandingAsyncWork`.

### 2. Legacy delivery-ack API

**Root cause.** Public `takeDeliverable()` copied `delivered=true` before any provider injection, so a caller could ack without a successful inject.

**Files changed.** Removed `takeDeliverable()` from `WorkCoordinator.kt`. Tests now use `claimForDelivery` → inject → `acknowledgeDelivery`, with `releaseDeliveryClaim` on failure.

**Resulting behavior.** No public path sets `delivered=true` before successful injection.

**Regression tests.** `speechInterruptionDoesNotCancelWorkAndResultDeliversOnce` (claim does not set delivered; ack does; release-on-failure retry); `failedInjectionClaimCanBeReleasedAndAcknowledgedOnce`; `workSubmitRefineProgressResultErrorCancelAndOnceOnlySafeDelivery` (post-success `claimForDelivery` is null).

### 3. Regression tests

Added/updated in `ingress/src/test/kotlin/com/novadrive/ingress/realtime/WorkReconnectDiagnosticsTest.kt` and `VoiceSessionControllerTest.kt`. Targeted ingress run was red on missing `cancelAll`, then green after the production fix. Full Gradle suite: 67/67.

### 4. Handoff report

This file states Qwen-first defaults, Baidu’s optional role, no remaining provider conflict, and the four core lifecycle findings with root cause / files / behavior / test. It ends with the required line.

---

## Final source-of-truth wording sweep (this pass)

Stale Baidu-as-product-default prose remained in docs and the DEBUG developer-settings label after the executable defaults were already Qwen-first. No provider behavior changed.

**Files changed (wording only).**
- `README.md` — product default Qwen Flash; optional GPT-Live/Baidu; Fake test-only
- `docs/ARCHITECTURE.md` — voice path lists Qwen Flash default
- `docs/PROVIDER_SETUP.md` — table and setup steps Qwen-first; Baidu Lite Near as family default when selected
- `docs/REALTIME_PROTOCOL_REFERENCES.md` — Qwen product default; Baidu optional compatibility
- `docs/DECISIONS.md` D13 — Qwen Flash default; Fake never product default
- `docs/CHECKPOINTS.md` — Checkpoint 2 / 2b Qwen-first; Baidu optional
- `docs/BAIDU_E2E_SETUP.md` — Baidu operational path when explicitly selected; Lite Near family default
- `app/src/main/kotlin/com/novadrive/app/DeveloperSettingsActivity.kt` — explanatory label

**rg scans.** Patterns `Default provider is Baidu`, `Baidu Lite Near is the product default`, `Baidu E2E (default)`, `` `baidu` (default) ``, `默认：百度` against the listed files: **0 matches** (rg exit 1). Remaining Baidu+default hits are the intended family-default-when-selected wording. APK UTF-8 contains `默认：Qwen Flash`, `Qwen Plus 可选`, `选中百度时默认 Lite Near`, `Fake 仅用于本地无配额测试`; stale `默认：百度端到端 Lite Near` is absent.

---

## KEEP / MODIFY / REMOVE / MISSING

### KEEP
- Qwen-first defaults; Qwen Plus selectable; GPT-Live optional; Baidu optional compatibility with Lite Near family default when selected; Fake test-only
- Checkpoint 1 contracts, safety, orchestrator, simulator, demo
- Claim → inject → ack delivery path; mutex not held across inject
- Deterministic disconnect and reconnect capture-once

### MODIFY
- Session stop/release now tears down outstanding async work
- Delivery tests use claim/ack only
- Listed product docs and developer-settings label now state Qwen Flash as product default; Baidu remains optional with Lite Near as its family default when selected; Fake is test-only

### REMOVE
- `WorkCoordinator.takeDeliverable()`

### MISSING (closed this follow-up)
- Deterministic coordinator teardown on stop/release
- Unsafe ack-before-inject public API
- Regression coverage for coroutine cancel + terminal snapshots
- Exact handoff closing line

---

## Commands and exit codes (wording sweep session)

### Focused rg (listed files)

Stale Baidu-default patterns: **0 matches**, rg exit **1**.

### Relevant tests + assembleDebug

```powershell
cd D:\桌面\Android_Automotive_Voice_Agent
.\gradlew.bat :ingress:test --tests "com.novadrive.ingress.realtime.MockRealtimeVoiceProviderTest" --tests "com.novadrive.ingress.realtime.VoiceSessionControllerTest.catalogDefaultsAreQwenFlashAndBaiduLiteNearWhenSelected" :behavior-test:test --tests "com.novadrive.architecture.SecretScanTest" :app:assembleDebug --no-daemon --no-parallel --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 26s`, 54 actionable tasks (6 executed, 48 up-to-date). `:app:compileDebugKotlin` executed after the UI string change.

JUnit from that invocation: `MockRealtimeVoiceProviderTest` **3/3**, `SecretScanTest` **2/2**, 0 failures. The `VoiceSessionControllerTest` method filter in that command did not match (`catalogDefaultsAreQwenFlashAndAllProvidersSelectable` is the real name), so that class was not in the first XML set.

```powershell
.\gradlew.bat :ingress:test --tests "com.novadrive.ingress.realtime.VoiceSessionControllerTest.catalogDefaultsAreQwenFlashAndAllProvidersSelectable" --no-daemon --no-parallel --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 14s`. JUnit: `catalogDefaultsAreQwenFlashAndAllProvidersSelectable` **1/1**.

### APK (this sweep)

`C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk`, **5256995 bytes**, SHA-256 `789CDF7D6A1C73F03FD609802F1C5E5CC2819D464F6E86B047CBDAD608F2D20F`, versionName `0.3.1-qwen-realtime`. Source assignment-pattern hits: **0**. APK assignment-pattern hits (`BAIDU_API_KEY=`, `BAIDU_SECRET_KEY=`, `DASHSCOPE_API_KEY=`, `OPENAI_API_KEY=`, `client_secret=`): **0**.

This sweep did **not** rerun backend pytest, fake scenario/benchmark, the full 67-test Gradle suite, or the strict Checkpoint 1 demo. Prior supervisor evidence for those remains below.

---

## Commands and exit codes (prior supervisor follow-up)

### Targeted ingress (after the production fix)

```powershell
cd D:\桌面\Android_Automotive_Voice_Agent
.\gradlew.bat :ingress:test --tests "com.novadrive.ingress.realtime.WorkCoordinatorTest" --tests "com.novadrive.ingress.realtime.WorkCoordinatorAsyncTest" --tests "com.novadrive.ingress.realtime.VoiceSessionControllerTest" --no-daemon --no-parallel --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 24s`.

TDD red (before `cancelAll` existed): same command targeting the new teardown tests failed compile with `Unresolved reference 'cancelAll'` (`WorkReconnectDiagnosticsTest.kt:106`).

### Full Gradle suite

```powershell
cd D:\桌面\Android_Automotive_Voice_Agent
.\gradlew.bat test :ingress:test :behavior-test:test --no-daemon --no-parallel --console=plain
```

Exit **0**, `BUILD SUCCESSFUL in 38s`. **67 JVM tests, 0 failures.**

| Suite | Tests |
| --- | ---: |
| `ChinaFirstDefaultsTest` | 2 |
| `BootstrapSafetyPolicyTest` | 2 |
| `InMemoryVehicleSimulatorTest` | 2 |
| `VoiceSessionOrchestratorBehaviorTest` | 13 |
| `DependencyBoundaryTest` | 2 |
| `SecretScanTest` | 2 |
| `RealtimeToolDispatchBehaviorTest` | 2 |
| `VoiceSessionStateMachineTest` | 5 |
| `MockRealtimeVoiceProviderTest` | 3 |
| `VoiceSessionControllerTest` | 17 |
| `WorkCoordinatorTest` | 1 |
| `WorkCoordinatorAsyncTest` | 3 |
| `AudioIoTest` | 2 |
| `ReconnectPolicyTest` | 1 |
| `StructuredVoiceLogTest` | 1 |
| `QwenProtocolFixtureTest` | 4 |
| `GptLiveProtocolFixtureTest` | 5 |

VoiceSessionControllerTest 15→17 and WorkCoordinatorAsyncTest 2→3 account for the +3 tests vs the prior 64.

The Cursor follow-up itself did not rerun backend, demo, or APK checks. The supervisor independently reran them after the follow-up completed; final evidence is below.

### Supervisor final verification

- Backend pytest: exit 0, **55 passed, 2 skipped**.
- Fake provider scenario: exit 0, **28** normalized events; benchmark: **1000** iterations / **9000** events, `comparative_live_claim=false`.
- Forced `:ingress:test :behavior-test:test --rerun-tasks`: exit 0, all 21 Gradle tasks executed; JUnit XML has **61 tests**, 0 failures/errors/skips.
- All current JUnit XML: **17 suites / 67 tests**, 0 failures/errors/skips.
- Strict Checkpoint 1 demo: exit 0, `VERIFIED`.
- Forced `:app:assembleDebug --rerun-tasks`: exit 0, all 49 tasks executed.
- APK at that time: `C:\Users\Administrator\tools\nova-drive-build\app\outputs\apk\debug\app-debug.apk`, **5221489 bytes**, SHA-256 `5DF3DF5A5A727E1515CD219A484C19762CCEB3A5F2D824806F7AEEFF44B9F453`, version `0.3.1-qwen-realtime`. Superseded by the wording-sweep rebuild above (5256995 bytes).
- Source and APK assignment-pattern scans: no Baidu, DashScope, OpenAI, or `client_secret` assignments found.
- `git status` was inspected but cannot produce a diff: this workspace has no `.git` metadata (`git` exit 128).

---

## Honest limitations
- No paid/live Qwen, GPT-Live, or Baidu calls.
- No emulator/device RECORD_AUDIO session in this worker session.
- Hardware AEC / Bluetooth SCO remain manual-test-only.

BUILDER READY FOR REVIEW — Qwen/Baidu source-of-truth conflict reconciled; lifecycle corrective pass completed; reviewer may proceed.
