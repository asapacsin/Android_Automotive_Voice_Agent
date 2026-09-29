# Live conversation — implementation plan

Status: **Proposed plan. Nothing here is authorised yet.** It turns
[LIVE_CONVERSATION_ARCHITECTURE.md](LIVE_CONVERSATION_ARCHITECTURE.md) (the "architecture doc",
cited as `A§n`) into ordered, gated work. The plan does not change the architecture. Where
building it needs a new constraint, that goes back to the owner as a decision (§1).

This plan is not in `BACKLOG.md` or `CURRENT_MILESTONE.md`. Like the architecture doc, it stays out
of the autonomous queue (`scripts/discover_work.py`) until decision D-A is taken. Task T0.1 in §3
then puts it into the queue deliberately.

Written 2026-09-29 on `claude/9-29`. `BASE_COMMIT` for the first wave is the branch HEAD at the
time the plan is started, re-read then. It is not the commit recorded here.

---

## 1. Gates: what must be true before each phase

| Gate | What it is | Who decides or produces it | Unblocks |
| --- | --- | --- | --- |
| **D-A** | Scope and order of the four properties | Mentor / product owner | T0.1 intake, and every phase |
| **D-B** | Acknowledgement cues: earcon, none, or wait for a native provider | Owner | Phase 7 (cues) |
| **D-C** | Background model service, credentials, cost ceiling, search grounding | Owner | Phase 5 (real `DelegationPort`) |
| **D-D** | What may leave the phone: destination name, district, coordinates | Owner | Phases 4–5 (`DelegationContext` content) |
| **D-E** | Deadline, TTL, whether a stale answer is announced | Owner; proposed 20 s / 60 s / announce once | Phase 3 (constants; code can take defaults) |
| **D-F** | SPEC-012 amendment: rows `DEFERRED_ANSWER` and `ACK_CUE` | Owner, recorded as a SPEC-012 amendment | Phase 3 arbiter task |
| **D-G** | Provider: Baidu only (option 1) or GPT-Live adapter as well (option 2) | Owner, recorded in an ADR | Phase 8 only; phases 1–7 are identical under both |
| **M-1** | Duplex evidence (`ECHO-001`, `ASTRA-DOUBLE-TALK-001`, playback-only silence, stop in tail) | Device + human (already queued) | Phase 6 sign-off |
| **M-2** | Flex speaks a result injected as text + `response.create`, reliably, in the persona voice, without making up anything beyond it | Device probe (Phase 1) | Phase 4 delivery design; a FAIL blocks delegation (§6) |
| **M-3** | Does a local earcon trip server VAD / `speech_started`? | Device probe | Phase 7 cues |
| **M-4** | Transcript accuracy with and without WebRTC NS on recorded cabin noise | Recorded audio replay | Phase 7 NS |
| **M-5** | Split-sentence rate and latency at `silence_duration_ms` 200 / 500 / 800 | Device probe + scripted utterances with pauses | Phase 7 pause tuning |

D-A through D-F are not an agent's call. `agent/INTAKE.md` step 4 applies to them. Phases 1 and 2
may start once D-A says delegation is in scope. Nothing past Phase 2 starts before M-2 has a
result.

---

## 2. Dependency graph

```
                      D-A
                       │
                    T0 intake ─────────────┐
                       │                    │
          ┌────────────┼──────────────┐     │  (independent track, may start at T0)
          ▼            ▼              ▼     ▼
      P1 probes    P2 contract   T0.3 stale capability docs
     (M-2,M-3,M-5)     │
          │            │
      M-2 result ──────┤  + D-E (defaults ok), D-F
                       ▼
   ┌─────────────── P3 wave (parallel, ≤4) ───────────────┐
   │ P3a WorkCoordinator  P3b DelegationPort+I-14 rule    │
   │ P3c SpeechArbiter DEFERRED_ANSWER  P3d DriverTurn    │
   └──────────────────────────┬───────────────────────────┘
                              ▼
                P4 integration (sequential, reviewer)
                 P4a tool + dispatcher → P4b controller delivery
                 → P4c Baidu deferred injection → P4d cancel/staleness wiring
                              │
                  D-C, D-D ───┤
                              ▼
                P5 real DelegationPort (Qianfan text or chosen service)
                              ▼
                P6 device verification (L5) + human queue (L6)

   Independent after their gates:  P7a pause tuning (M-5) · P7b NS (M-4) · P7c cues (D-B, M-3, D-F)
   Optional:                       P8 GPT-Live adapter (D-G option 2, new ADR)
```

Why this order:
- **The contract (P2) comes before any parallel work.** P3a, P3c and P3d all read the new
  `WorkInjection`/capability fields, and two workers guessing a shared interface is the collision
  planner rule §4 forbids.
- **M-2 gates P3.** If Flex cannot reliably speak a result injected as text, the delivery design in
  A§5.3 is wrong and P3/P4 would build on it. The probe costs one debug hook and one device session.
- **P4 is one sequential chain**, because every step touches `VoiceSessionController` or
  `AndroidToolDispatcher`, and both are core abstractions.
- **P5 comes after P4**, so the whole flow is proven with a fake `DelegationPort` before anyone
  spends credentials or cost on a real one.

---

## 3. Tasks

Each row becomes one executor packet (planner rules §6), in its own worktree
`../nova-wt/<ID>` from the phase's `BASE_COMMIT`, with its own `NOVA_BUILD_DIR`. "Keep" means the
planner does it itself. **R** means an independent review is required before merge.

### Phase 0: intake (planner-owned, after D-A)

| ID | Objective | Owned scope | Who | Acceptance |
| --- | --- | --- | --- | --- |
| T0.1 | Record the demand; draft **SPEC-013 live conversation** from `SPECS/SPEC-TEMPLATE.md`, with A§7 as its evidence section and D-A…D-G answers inline | `BACKLOG.md`, `SPECS/SPEC-013-*.md` | keep | `python scripts/harness_check.py` shows no new failures beyond the pre-existing ones (§7); SPEC names every gate |
| T0.2 | Write the SPEC-012 amendment (D-F) and **ADR-010 background delegation**: `DelegationPort`, I-14, I-15. Add I-14/I-15 to `docs/INVARIANTS.md` and the A§6 owner rows to `docs/ARCHITECTURE.md` | `SPECS/SPEC-012-*.md`, `DECISIONS/ADR-010-*.md`, `docs/INVARIANTS.md`, `docs/ARCHITECTURE.md` | keep | Owner approved the text; `:behavior-test:test` green |
| T0.3 | Fix the stale capability docs found in A§1 (`speech.interrupt_tts_by_voice`, the CAPABILITIES.md barge-in paragraph), matching ARCHITECTURE.md and the actual evidence level | `docs/CAPABILITIES.md`, `config/capabilities.yaml` | executor | `:behavior-test:test --tests '*CapabilityContract*'` green; the level claimed is no higher than the evidence |

T0.3 needs no decision and could run today. It is listed here so the plan stays in one place.

### Phase 1: measurement probes (after D-A)

| ID | Objective | Owned scope | Who | Acceptance |
| --- | --- | --- | --- | --- |
| T1.1 | Add a debug-build-only hook to inject a synthetic "deferred result" text through `BaiduFlexClient.sendUserText` (already `userTextMessage` + `responseCreate` behind `ResponseTurnGate`), with a system label. Debug source set only, never in release | `app/src/debug/**` DebugToolReceiver action, a test | executor | Unit test covers the hook; release `assemble` has no hook (checked by the existing debug-surface rule or a new one) |
| T1.2 | Add a debug-only override of `silence_duration_ms` (200/500/800) for M-5 | `app/src/debug/**`, `BaiduFlexProtocol` session config, parameterised | executor | `BaiduFlexProtocolTest` covers the value; default stays 200 |
| T1.3 | Write the probe protocols and register **LIVE-M2-001**, **LIVE-M3-001**, **LIVE-M5-001** (AUTONOMOUS, runnable over ADB with `tools/speech-harness`) and **LIVE-M4-001** (needs recorded cabin noise, so `EXTERNAL_RESOURCE` until audio exists) in `TEST_MATRIX.yaml` | `TEST_MATRIX.yaml`, `tools/speech-harness/` scripts | keep | `python scripts/test_matrix.py --work` lists them |
| T1.4 | Run the probes on the device. M-2: 20 injections × {idle, just after a reply, navigating}; record success rate, latency, persona voice, and any words beyond the payload. M-5: a scripted pause corpus at 3 settings | evidence under `docs/reports/` (ids, durations, counts only; I-8) | device session: the owner's PC, or the agent over ADB where a device is attached | Numbers recorded; **M-2 pass bar proposed: ≥19/20 spoken, nothing beyond the payload, p95 < 2 s**. The owner confirms the bar in T0.1 |

### Phase 2: provider-neutral contract (sequential, after T0.2)

| ID | Objective | Owned scope | Who | Acceptance |
| --- | --- | --- | --- | --- |
| T2.1 | `ProviderCapabilities` gains `deferredResultDelivery`, `nativeDelegation`, `nativeBackchannel`, `turnEndTuning` (A§4). `WorkInjection` gains `deferred: Boolean = false`, `originEpoch: Long? = null`. `DomainVoiceEvent.AckCue(epoch, kind)`. Baidu declares `deferredResultDelivery` = M-2 result, `turnEndTuning` = true, the others false. `MockRealtimeVoiceProvider` supports all four, for tests | `ingress/.../VoiceCatalog.kt`, `RealtimeVoiceProvider.kt`, `DomainVoiceEvent.kt`, `MockRealtimeVoiceProvider.kt`, the Baidu capability declaration, `config/capabilities.yaml` rows | **keep**: the interface everyone depends on | `./gradlew :ingress:test :behavior-test:test` green. `ProviderBoundaryTest` still holds: no provider-name branch |

### Phase 3: the pieces, in parallel (after T2.1, M-2 pass, D-F)

At most 4 executors at a time. Each touches a different file, and none edits `VoiceSessionController`.

| ID | Objective | Owned scope | Acceptance | R |
| --- | --- | --- | --- | --- |
| T3a | **`WorkCoordinator`**: a job carries `kind`, `originEpoch`, `createdAtMs`, `deadlineMs`, `ttlMs`, `deferred`. It supersedes the running job of the same kind (extend `refine`, don't add a second path). Deadline expiry becomes `FAILED("deadline")`. A TTL-expired result is never returned by `claimForDelivery`. `cancel(kind)` is added. Injected clock | `ingress/.../WorkCoordinator.kt` + tests | New unit tests for supersede, deadline, TTL, cancel-by-kind, once-only delivery; existing `WorkCoordinator*`/`WorkReconnect*` tests unchanged and green | R |
| T3b | **`DelegationPort`** interface, `DelegatedJob`, `DelegationContext`, `DelegatedResult`, and a `FakeDelegationPort` for tests (A§5.3). **I-14 rule** in `behavior-test`: no `DelegationPort` implementation depends on actuating executors or `AndroidToolDispatcher` | new `ingress/.../delegation/*.kt`, `behavior-test/.../DependencyBoundaryTest.kt` (a new test method only) | The rule fails on a planted violation (proved once, then removed) and passes on the tree | R |
| T3c | **`SpeechArbiter` row `DEFERRED_ANSWER`**, exactly as amended in D-F: R4 DROP (kept, re-offered once), R1 HOLD, R5 HOLD, R6a HOLD, navigating PLAY once per `work_id` outside the P1 window, otherwise PLAY. No existing row changes | `app/.../voice/SpeechArbiter.kt` + a new `SpeechArbiterDeferredTest.kt` | `SpeechRulesCharacterizationTest` and all `SpeechArbiter*Test` unchanged and green; one test per row | R |
| T3d | **`DriverTurn`**: `status=accepted` from `delegate_task` counts as acceptance proof only. A reply that claims an answer before a deferred result exists is held, then dropped (I-1/I-5), and a deferred answer is released only against its `DelegatedResult` | `app/.../voice/DriverTurn.kt` (+ `PhantomTurnGate` if needed) + tests | New `DriverTurnTest` cases: filler passes, claim-before-result dropped, claim-after-result passes; all existing cases green | R |

Collision check: T3a and T3b are both in `ingress`, but in separate files, and T3b only reads the
T2.1 types. T3c and T3d are separate `app/voice` files. If T3d finds it must edit `SpeechArbiter`, it
returns BLOCKED and runs after T3c instead.

### Phase 4: integration (sequential, one worktree, reviewer after P4d)

| ID | Objective | Owned scope | Acceptance |
| --- | --- | --- | --- |
| T4a | Tool `delegate_task(task, kind)`: schema, `FlexFunctionCallAssembler` validation (closed `kind` enum, bounded `task` length). `AndroidToolDispatcher` returns `{ok:true,status:"accepted",work_id}` **at once**, so the tool call is closed and `ConversationResetPolicy` is not blocked. It then submits a separate job `dlg-<n>` to `WorkCoordinator` through `VoiceSessionController.submit(scope, …)`, running the `DelegationPort`. The existing `deferredOutput` path stays for tools that must hold their call open (LiveInfo); it is not reused here | tool schema, `BaiduFlexProtocol.kt` assembler, `AndroidToolDispatcher.kt`, persona text (tone only, I-11) | `AndroidToolDispatcherTest`, `BaiduFlexProtocolTest` new cases; `RealtimeToolDispatchBehaviorTest` green |
| T4b | `VoiceSessionController.deliverPendingWork` branches on `deferred`: tool output as today, or `WorkInjection(deferred=true, originEpoch)`. Tighten `isSafeWorkDeliveryPoint` for deferred jobs: no open driver utterance, no reply playing or held, lifecycle ACTIVE. In SILENT_WAIT, subtitle only; in SLEEP/DEEP_IDLE, not delivered (kept until TTL). The arbiter's `DEFERRED_ANSWER` decision gates the audio | `ingress/.../VoiceSessionController.kt`, `VoiceSessionStateMachine.kt`, their tests | `VoiceSessionControllerTest` new cases for each safe-point clause; `FullDuplexBargeInTest` green |
| T4c | Baidu adapter: `injectWorkResult(deferred=true)` sends a system-labelled `userTextMessage` carrying the result, then `responseCreate`, through `ResponseTurnGate` **in the current conversation epoch**, never as `function_call_output` (after a reset that call id no longer exists, as `sendFunctionResult` already notes). It counts as a plain turn for `ConversationResetPolicy`. Content never logged: only id, sizes, durations (I-8) | `BaiduFlexProvider.kt`, `BaiduFlexClient.kt`, tests | `BaiduFlexClient*`/`ConversationResetPolicyTest` green; new test: delivery after a reset lands in the new epoch; a log assertion shows no payload text |
| T4d | Cancel and staleness wiring: 「算了」 through `VoiceCommandRouter` → `WorkCoordinator.cancel(kind)`. A navigation-state change (arrived, new destination) expires pending deferred answers. A barge-in over a deferred answer re-queues it once, and never cancels the work (A§5.1) | `VoiceCommandRouter.kt`, nav-state hook at its existing owner, tests | `VoiceCommandRouterTest` new cases; `:behavior-test:test` green |

After T4d: full `./gradlew test --rerun-tasks :app:assembleDebug`. The planner reads the JUnit XML,
then hands the whole P3+P4 range to the reviewer (`agent/REVIEWER.md`). Merge only on PASS.

### Phase 5: the real background model (after D-C, D-D)

| ID | Objective | Owned scope | Acceptance |
| --- | --- | --- | --- |
| T5.1 | One `DelegationPort` implementation for the service chosen in D-C: vendor JSON confined to `app` (I-9); credential through the existing Keystore settings owner in its own namespace (I-7); `DelegationContext` built only from what D-D allows; read-only tools at most (I-14 rule enforces). Timeouts map to `Failed`; ungrounded `kind=search` maps to the honest refusal (I-3) | `app/.../delegation/<Vendor>DelegationPort.kt`, settings wiring, tests with a recorded-response fake | Unit tests; `SecretScanTest`, `DependencyBoundaryTest` green; **R** |
| T5.2 | Register **LIVE-CRED-001** (HUMAN_CREDENTIAL: the owner enters the key on the device) | `TEST_MATRIX.yaml` | queued, not a stop |

### Phase 6: device verification

Rows added to `TEST_MATRIX.yaml` by the planner, then run AUTONOMOUS over ADB where a device is
attached:

| Id | Scenario | Level |
| --- | --- | --- |
| LIVE-DLG-001 | "查一下…" → filler within 1.5 s → answer spoken once, from the result | L5 |
| LIVE-DLG-002 | Driver keeps talking about something else while the job runs; the answer waits for a safe point | L5 |
| LIVE-DLG-003 | Answer arrives during Amap guidance / inside a manoeuvre zone → held, then played | L5 |
| LIVE-DLG-004 | 「算了」 cancels it; nothing is spoken later | L5 |
| LIVE-DLG-005 | Deadline expiry → one honest sentence; TTL expiry → silence (or the D-E announcement) | L5 |
| LIVE-DLG-006 | A new destination while a job is pending → the old answer is never spoken | L5 |
| LIVE-DLG-007 | Real drive, mixed conversation (HUMAN_PHYSICAL, safety) | L6 |

Together with M-1 (already queued), these are the phase's evidence. `HUMAN_VALIDATION.md` asks the
person once, at the phase boundary (`python scripts/test_matrix.py --gate`).

### Phase 7: independent tracks (each behind its own gate)

| ID | Gate | Objective | Owned scope |
| --- | --- | --- | --- |
| T7a | M-5 | Set `silence_duration_ms` from the data (a single constant, or per navigation phase if the data shows a clear split), and record the number in SPEC-013 | `BaiduFlexProtocol` config + test |
| T7b | M-4 pass | Enable WebRTC NS in the **existing** APM instance, after AEC and before gain; a single native backend (Astra D1) | native APM config, `NativeProvenanceTest` still green; **R** |
| T7c | D-B = earcon, M-3 pass, D-F | `ACK_CUE` arbiter row. One earcon asset at two moments only: delegation accepted, deferred answer about to play. Routed through `PcmAudioPlayer` (AEC render reference). Off by default behind a setting | `SpeechArbiter.kt` (**after** T3c, not in parallel), a cue player at the playback owner; **R** |

### Phase 8: optional GPT-Live adapter (D-G option 2 only)

A separate ADR (**ADR-011**) first: availability from the target market, latency, cost, zh-CN voice,
and re-verification of the 2026-09-14 protocol notes. Only then comes one new
`RealtimeVoiceProvider`, written fresh (not revived from git history, ADR-008). Its
`delegation.type=client` maps onto the same `WorkCoordinator`/`DelegationPort`, and
`session.commentary.append` is its `deferredResultDelivery`. That is planned as its own milestone
at the time, not here.

---

## 4. Waves and parallelism

| Wave | Tasks | Concurrency | Notes |
| --- | --- | --- | --- |
| W0 | T0.1, T0.2 (keep) ∥ T0.3 (executor) | 1 executor | T0.2 waits for the owner's approval of the text |
| W1 | T1.1 ∥ T1.2 (executors), T1.3 (keep) | 2 | Then T1.4 on a device session |
| W2 | T2.1 (keep) | 0 | Can overlap with W1's device time |
| W3 | T3a ∥ T3b ∥ T3c ∥ T3d | 4 | Replan here if M-2 changed the delivery design |
| W4 | T4a → T4b → T4c → T4d | 1 | One worktree, a commit per step, review at the end |
| W5 | T5.1 | 1 | After D-C/D-D |
| W6 | Phase 6 rows | — | Device and human |
| W7 | T7a / T7b / T7c as their gates open | ≤2 | T7c is serialised after T3c |

After every wave: objective checks (build, JUnit XML read by the planner, `:behavior-test`,
`harness_check`), then merge into the feature branch with a merge commit, then
`python scripts/collect_state.py`, then replan.

---

## 5. Planner-owned state to reconcile as work lands

- `CURRENT_MILESTONE.md`: becomes "M5 live conversation (delegation first)" only after T0.1/T0.2.
- `TEST_MATRIX.yaml`: the LIVE-* rows above; generated `TEST_STATUS.md`/`HUMAN_VALIDATION.md`
  are regenerated, never edited by hand.
- `config/capabilities.yaml` / `docs/CAPABILITIES.md`: a new capability row per shipped feature, at
  the level its evidence reached.
- `docs/ARCHITECTURE.md`: the owner rows from A§6, added in T0.2 before code exists, so every
  packet can cite them.
- `docs/LIVE_CONVERSATION_ARCHITECTURE.md`: status line updated when D-A…D-G are answered.

---

## 6. Risks and replan triggers

| Trigger | Response |
| --- | --- |
| M-2 fails: Flex drops, rephrases beyond, or re-voices the injected result | Stop the P3/P4 delivery path. `ARCHITECTURE_REVIEW_REQUIRED` with the options: deliver as a subtitle only; hold the tool call open with the existing `deferredOutput` (blocks the conversation and fights `ConversationResetPolicy`); or D-G option 2 |
| The model does not call `delegate_task` reliably, or calls it for things a tool already answers | Measure the call rate on the live-scenario suite (SPEC-008). A persona change is tone only (I-11); if the rate is unusable, escalate: a deterministic router would be a new owner |
| T3d shows the claim rule needs `SpeechArbiter` changes | Serialise it after T3c and replan W3 |
| `ConversationResetPolicy` interacts badly with deferred turns (the model degrades) | Count a deferred turn as a tool turn instead; this is a change in the policy's owner, with its own test |
| The deadline or TTL is wrong in practice | D-E values are constants in one place; retune from LIVE-DLG-005 data |
| A delegated vendor needs data D-D forbids | Refuse that `kind`; never widen `DelegationContext` silently |
| Pre-existing `harness_check` failures on `demo-1.0` hide new ones | Record the baseline failure list before W0 (§7) and compare against it every wave |

---

## 7. Baseline facts to carry in

- `scripts/harness_check.py` already fails on `demo-1.0` (`7ac69f5`), in registry validation and the
  gate self-tests. Record the failure list as the baseline at W0. Fixing it is a separate task, not
  part of this plan.
- `.claude/worktrees/agent-*` exists inside the repository from an earlier Agent-tool isolation
  run. Planner rule §5 says worktrees go under `../nova-wt/`; do not create more in the repo.
- Cloud containers may lack the gitignored iFlytek files, so `:app` may not compile there. `:app`
  acceptance then runs on the owner's PC and is recorded as an external blocker, never stubbed.
- The existing `deferredOutput` path keeps a tool call open until its result (LiveInfo uses it). The
  delegation design deliberately closes the call at once and delivers the answer as a new turn.
  Mixing the two is the most likely implementation mistake; T4a/T4b packets must say so.
