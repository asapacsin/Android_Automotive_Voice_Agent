# Current Task

## Checkpoint
REALTIME PROVIDER RECONCILIATION — corrective lifecycle review

## Objective
Preserve the passing Nova Drive Qwen-first realtime implementation, optional GPT-Live/Baidu/Fake adapters, Checkpoint 1 safety/verification flow, and all existing tests. Correct the lifecycle, reconnect, asynchronous work, and result-delivery gaps below, then re-verify the whole repository.

## Authoritative source-of-truth reconciliation
This task supersedes every older Baidu-only task or checkpoint text. A stopped corrective worker briefly wrote stale Baidu-first instructions into project state; that text is obsolete and must not be treated as an architecture decision. Determine current architecture from the implemented Qwen-first code, the newest integrated reports, and verified tests.

When files disagree, use this priority:
1. This current task.
2. The implemented Qwen-first architecture and newest integrated reports.
3. Latest verified tests and code.
4. Reconciled task/project documentation.
5. Older Baidu-only instructions as historical context only.

Baidu is retained as an optional compatibility/provider-abstraction adapter, not the primary provider. Do not delete it unnecessarily and do not switch the system back to Baidu. The corrective pass must leave provider defaults, implementation, reports, and handoff documents internally consistent so review is not blocked.

## Product and provider requirements
- Default provider is Qwen; default model is `qwen-audio-3.0-realtime-flash`. Qwen Plus, GPT-Live, Baidu, and Fake remain selectable.
- Vendor protocols remain inside backend/adapters. UI/business code consumes normalized events only.
- Android captures/plays native streaming PCM through provider-neutral controller ports.
- Speech barge-in stops assistant output but never implicitly cancels background work.
- Vehicle tool calls retain typed command → task manager → ALLOW/CONFIRM/DENY → adapter → observed-state verification → typed result.
- No live paid calls. No permanent secrets in source/APK/logs. Preserve official protocol fixture tests and honest live/device limitations.

## Reviewer findings to fix
1. Android wrapper `release()` calls `stop()` and immediately cancels the scope, while core `stop()` launches `provider.disconnect()` in that scope. Make shutdown deterministic so disconnect completes or is owned outside the cancelled scope. Test disconnect exactly once and repeated start/stop collector cleanup.
2. Reconnect stops microphone capture but does not reliably start it again after success. Resume capture exactly once after reconnect/session readiness. Test audio forwarding resumes with no duplicate capture or event collectors.
3. `WorkCoordinator` is an in-memory table only. Add a lightweight asynchronous execution path using caller-owned coroutine scope/jobs: submit suspending work, refine/update state, explicit cancellation cancels that work only, progress/result/error are recorded, and realtime audio/event processing continues while work is pending. Preserve compatible state methods where useful.
4. A work result is marked delivered before `provider.injectWorkResult()` succeeds. Acknowledge only after success. Failed injection must leave it pending for a later safe retry; successful injection must never duplicate. Add failure-then-success and exactly-once tests.
5. Do not hold the session mutex across slow provider injection. Preserve state ordering/thread safety without blocking realtime event processing.
6. Harden Android audio cleanup: catch permission/start failures, reject invalid minimum buffer sizes, and terminate capture/playback worker threads on repeated stop/release using bounded cleanup. Keep hardware behavior documented as manual-test-only.

## Required verification
- Full backend pytest suite with live tests disabled.
- Full Gradle tests plus forced ingress/behavior tests using `--no-daemon --no-parallel`.
- Fake provider scenario and benchmark.
- Strict Checkpoint 1 demo.
- Android `:app:assembleDebug`, APK size/path, and static source/APK secret scan.
- Update `agent/WORKER_REPORT.md` and `agent/PROJECT_STATE.md` with exact commands/counts and corrected lifecycle evidence.
- In the builder report, state the primary provider, Qwen's role, Baidu's optional compatibility role, and whether any provider conflict remains. For each of the four core lifecycle findings, record root cause, files changed, resulting behavior, and its regression test.
- End the builder report with exactly: `BUILDER READY FOR REVIEW — Qwen/Baidu source-of-truth conflict reconciled; lifecycle corrective pass completed; reviewer may proceed.`

## Acceptance criteria
1. All existing backend/JVM tests remain passing and APK builds.
2. Deterministic disconnect and repeated lifecycle cleanup are tested.
3. Microphone restarts once after successful reconnect and audio forwarding resumes.
4. Slow work is truly asynchronous and explicitly cancellable without blocking/cancelling voice.
5. Work-result injection retries after failure and acknowledges exactly once after success.
6. Realtime event handling is not blocked by provider work-result injection.
7. Android audio failure/cleanup paths compile and are bounded.
8. Qwen remains default; provider protocols, safety boundary, secret isolation, and prior capabilities remain intact.

Work autonomously through fixes, tests, and documentation. Do not remove provider functionality, change defaults back to Baidu, perform live calls, or stop at ordinary failures.

## Supervisor follow-up findings
The first corrective pass passed its suites, but final review found these remaining acceptance gaps. Fix only these, then rerun the relevant tests and update the evidence:

1. `VoiceSessionController.stop()` / core `release()` do not explicitly cancel outstanding asynchronous work. Add deterministic coordinator teardown (for example, `cancelAll`) so running jobs are actually cancelled and their snapshots become `CANCELLED`; test that the underlying coroutine reaches cancellation and unrelated already-terminal snapshots are handled consistently.
2. Legacy `WorkCoordinator.takeDeliverable()` still sets `delivered=true` before any provider injection. Remove it or change it to safe claim semantics so no public path can acknowledge delivery before successful injection. Update callers/tests to use claim → provider inject → acknowledge, with release-on-failure.
3. Make the final report satisfy the requested handoff exactly and end with: `BUILDER READY FOR REVIEW — Qwen/Baidu source-of-truth conflict reconciled; lifecycle corrective pass completed; reviewer may proceed.`

Preserve all passing Qwen-first provider and lifecycle behavior. This is a narrow correction, not a redesign.

## Final source-of-truth wording sweep
The executable Kotlin/Python defaults and reports are Qwen-first, but a final scan found stale Baidu-default prose in `README.md`, `docs/ARCHITECTURE.md`, `docs/PROVIDER_SETUP.md`, `docs/REALTIME_PROTOCOL_REFERENCES.md`, `docs/DECISIONS.md` D13, `docs/CHECKPOINTS.md`, `docs/BAIDU_E2E_SETUP.md`, and the Android developer-settings explanatory label. Reconcile those exact references so they state:

- Product/default provider: Qwen Flash (`qwen-audio-3.0-realtime-flash`); Qwen Plus selectable.
- Baidu: optional compatibility provider with Lite Near as its provider-family default when Baidu is explicitly selected.
- GPT-Live: optional provider.
- Fake: quota-free local test provider, never the product default.

Preserve useful Baidu setup/protocol documentation and all adapter code. Update setup steps/tables accurately for Qwen default plus optional Baidu/GPT/Fake. Rerun focused scans, relevant tests, and Android compile/build after changing the UI string. Do not change architecture or provider behavior.
