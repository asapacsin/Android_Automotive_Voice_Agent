# Gemini-native voice path — implementation plan

Status: **Plan, 2026-09-30.** Wave 1 needs no owner decision; waves 2–3 wait for N-1/N-2.
Architecture: [GEMINI_NATIVE_ARCHITECTURE.md](GEMINI_NATIVE_ARCHITECTURE.md), **Revision 2 only**
(cited `R2.n`). The original §5.1 clause release and §5.3 correction-by-trait are *not* built.
Decision: [ADR-011](../DECISIONS/ADR-011-gemini-native-voice-path.md) (Proposed) · Requirement:
[SPEC-014](../SPECS/SPEC-014-gemini-native-voice-path.md) · Demand: B-028 · Evidence:
[reports/2026-09-29-gemini-live-probe.md](reports/2026-09-29-gemini-live-probe.md) F23–F33.

`BASE_COMMIT` for each wave is the `claude/9-29` HEAD when that wave starts. It is re-read and
verified in every worktree before dispatch (`git -C <wt> rev-parse HEAD`).

---

## 1. What the build must achieve

| Target | Today | After | Measured by |
| --- | --- | --- | --- |
| 「把空调打开」 → tool runs | 7–31.7 s or never (extended thinking) | ≤ 2.5 s after end of speech, 9 of 10 (L1) | smoke test, `gemini-3.8-live` |
| Clean chat reply → first audio leaves the gate | at `turnComplete` ≈ reply length after the first audio | at `generationComplete` (L2: gate release ≤ 50 ms after `generationComplete`) | smoke test + `GeminiLiveClientTest` |
| An action claim before its `ok=true` result | never heard (I-1) | never heard (L3), including the two D-10 orderings | `DriverTurnTest`, `GeminiLiveClientTest`, smoke test |
| Baidu | device-proven | unchanged except the two D-10 tightenings | every existing Baidu test and golden file |

## 2. What was found while planning, and how it changes the task list

Read against the code at `7dd2524`:

1. **Settling is one call to move.** `GeminiLiveClient.finishGeneration()` already emits the
   subtitle and `AudioDone` at `generationComplete`. The gate verdict
   (`pipeline.takeSuperseded()` + `pipeline.settleResponse(outcome)`) runs in `closeTurn()`, at
   `turnComplete`. R2.1 is therefore: settle in `finishGeneration()`, once. `closeTurn()` keeps
   `afterResponse`, `ResponseDone`, `releaseFallback` and state clearing, and settles only if
   `generationComplete` never came (interrupted, or a model that does not send it).
2. **The repeat rule already exists; R2.5 must extend it, not add a second one.**
   `ToolCallGuards.repeatedInTurn` → `DriverContext.claimDispatch(epoch, tool, args)` answers
   `DUPLICATE_IN_TURN` for a repeat in one driver turn. It covers only `REPEAT_SENSITIVE =
   {control_climate, control_music, query_live_info}`. `place_call`, `navigate_to`, `open_app`,
   `save_place` and `exit_navigation_mode` are not covered. A doubled `place_call` dials twice.
   There is also `DriverTurnPipeline.isDuplicateCall`, a per-response check that both adapters use.
   The plan extends the existing guard's tool set (AGENTS.md "never add a parallel mechanism").
   One semantic difference from R2.5's wording: the existing guard claims at *dispatch*, not at
   *success*. A model's retry after a failed call in the same driver turn is therefore also
   answered `DUPLICATE_IN_TURN`. That is today's behaviour for climate, and the plan keeps it: a
   failed action is reported to the driver (`reportFailure`), not silently retried. The reviewer
   checks this deliberately.
3. **D-10(a) cannot simply be "re-decide the hold in `onToolCall`".** If the hold becomes
   `AWAITING_TOOL_RESULT`, `onResponseDone` answers `hadToolCallInResponse → Release("tool_called")`.
   That would release a done-claim spoken after a *failed* result, which today's
   `AWAITING_EXECUTION_PROOF` drops with `reportFailure`. That is a regression. The required
   behaviour is fixed below as a truth table (§4, P2), so the executor implements semantics
   decided here and does not invent them.
4. **D-10(b) has a simple, strictly tighter fix.** When `PHANTOM_AUDIO` sees reply text with
   content, the hold becomes `UNCLASSIFIED_CLAIM` instead of `NONE`. The rest of the reply is then
   judged at the response end by `carActionClaimMatch`, as every chat reply already is.
   `onUserTranscript` already takes this path for a transcribed turn. The cost is the early release
   of phantom-looking replies: 93–515 ms on Baidu, and the generation time on Gemini after P4.
5. **SPEC-014 is stale.** Its Behaviour 3–7 and A2/A3 describe clause release, which R2.2
   deferred. It is rewritten in W0 before any code is written.
6. **The saved model survives a default change.** `GeminiSettings` persists `model` on every
   save, so N-1 changes the default only for a phone that never saved Gemini settings. The owner's
   phone keeps extended thinking until he picks the new model. The plan does not migrate a saved
   choice silently (ADR-010 opt-in). The settings screen lists the fast model first, marked 推荐.
   **Amended 2026-09-30 by the owner ("switch the live one"):** a saved extended-thinking model is
   switched to `gemini-3.8-live` once, on the first settings load after the update; later choices,
   extended thinking included, are kept (`geminiModelAfterOneTimeSwitch`).

## 3. Dependency graph and waves

```
W0  planner: SPEC-014 rewrite to R2, D-10 truth table (this doc), base commit
      │
      ├──────────────────────┬──────────────────────┐          no owner decision needed
      ▼                      ▼                      ▼
W1  P1 model registry     P2 D-10 gate fixes     P3 repeat guard coverage
    (ingress, Gemini       (DriverTurn, both      (ToolCallGuards,
     protocol/settings)     providers)             DriverContext tests)
      │                      │  └── reviewer (state machine)
      └──────────┬───────────┘
                 ▼                                              gated on N-2
W2  P4 settle at generationComplete (GeminiLiveClient) ── reviewer
                 ▼                                              gated on N-1
W3  P5 default model = gemini-3.8-live + docs/ADR/ownership reconcile (planner)
                 ▼
W4  P6 smoke test L1–L3, full build, final review, state, TEST_MATRIX device row
                 ▼
    device (owner): GEMINI-DEVICE-LATENCY-001, G-M2 self-interruption on gemini-3.8-live
```

W1 runs 3 executors in parallel. Their files are disjoint (§4), and none of them depends on
another's interface. P4 waits for P1, because both edit `GeminiLiveClient`, and for P2, because it
settles through the gate P2 changes. P4 also waits for N-2. P5 waits for N-1. If N-1/N-2 are still
open when W1 is integrated, the run continues with other `discover_work.py` items. The two
decisions are queued in `HUMAN_VALIDATION.md`, not treated as a stop.

## 4. Task packets (summaries; the planner expands each to the full §6 packet at dispatch)

### P1 — Model registry and per-model setup (W1)

- **Objective:** `gemini-3.8-live` is a declared, selectable Gemini model. Its `setup` carries no
  `thinkingConfig`; extended thinking's still does. The default is unchanged until N-1.
- **Owned scope:** `ingress/.../realtime/VoiceCatalog.kt` (+ its tests); `GeminiLiveProtocol.kt`
  (`setup`); the `setup` call site in `GeminiLiveClient.kt` (≈ line 136 only);
  `GeminiSettings.kt`; the Gemini block of `DeveloperSettingsActivity.kt`;
  `behavior-test/.../ProviderBoundaryTest.kt`; `GeminiLiveProtocolTest`, `GeminiSettingsTest`.
- **Requirements:**
  - One registry (R2.6). `VoiceCatalog.geminiLiveModels` gains the model. A neutral per-model
    trait (e.g. `acceptsThinkingLevel: Boolean`) lives beside it in `ingress`. There is no second
    model list in `app`.
  - `GeminiLiveProtocol.setup` takes the trait, never a model name. When the trait is false,
    `generationConfig` has no `thinkingConfig` key at all.
  - The settings screen shows the thinking-level radio only for a model with the trait.
  - `ProviderBoundaryTest`: no Gemini model-id literal outside `VoiceCatalog` and tests.
- **Acceptance:** protocol tests that assert the setup JSON for each model (F27: a
  `thinkingLevel` sent to `gemini-3.8-live` must be impossible); settings round-trip for both
  models; `:behavior-test:test` green.
- **Escalate if:** the trait cannot be expressed without `app` knowing a model name.

### P2 — D-10: the two orderings the gate misses (W1, state machine, reviewer required)

- **Objective:** close both D-10 gaps in `DriverTurn`, for both providers, without weakening any
  existing verdict.
- **Owned scope:** `DriverTurn.kt`, `DriverTurnTest.kt`; `DriverTurnPipeline.kt` only if a hook is
  missing (and then only that hook), `DriverTurnPipelineTest.kt`.
- **D-10(a), required behaviour.** A call registered while `phase == RESPONDING`, after the hold
  was decided, in a response that did not start with that call awaited:

  | # | Order inside the response | Required verdict |
  | --- | --- | --- |
  | a1 | call → `ok=true` → reply claims done | released (`execution_proved`), as today |
  | a2 | call → `ok=false` → reply claims done | dropped with `reportFailure(...)`, as today for ACTION. Must **not** become `tool_called` |
  | a3 | call → words stating an outcome (claim, refusal, 「摄像头暂时无法使用」) → result | those words never heard (`reply_before_tool_result`) |
  | a4 | call → words that state no outcome (「好的，稍等」) → result | held until the result, then judged as a1/a2 by the kind's own hold |
  | a5 | call → no words → result → reply in a later response | unchanged from today |
  | a6 | a response that *started* with the call awaited (the camera case, 2026-09-28) | unchanged: every existing `AWAITING_TOOL_RESULT` test passes unmodified |

  Mechanism, within those bounds: on such a call, move to `AWAITING_TOOL_RESULT` and remember the
  hold it replaced. When the last awaited result arrives, judge the text held so far with
  `speaksBeforeResult`. If it states an outcome, mark the response for a drop. If not, restore the
  kind's hold via `decideHold`, which now sees `proven`/`executionFailed`. At the response end,
  the restored hold's own verdict applies.
- **D-10(b), required behaviour.** In `onAssistantText`, `PHANTOM_AUDIO` with contentful text →
  `UNCLASSIFIED_CLAIM`, not `NONE`. A phantom-then-claim reply (「好的。」 then 「已为您打开空调」,
  with no call) is dropped at the end as `unverified_claim_*`. A phantom-then-chat reply is
  released as `no_claim_made`.
- **Deliberately changed tests:** any `DriverTurnTest` case that pins `real_reply`. List each one,
  with its old and new verdict, in the return block. No other existing test may change.
- **Acceptance:** `./gradlew :app:testDebugUnitTest --tests '*DriverTurn*'`, then the whole `test`
  (Baidu golden files unmodified).
- **Escalate if:** a1–a6 cannot all hold at once, or a Baidu golden file changes.

### P3 — Repeat guard covers every actuating tool (W1)

- **Objective:** R2.5 through the existing owner. A repeated identical actuating call in one
  driver turn is answered `DUPLICATE_IN_TURN` and does not run.
- **Owned scope:** `ToolCallGuards.kt` (`REPEAT_SENSITIVE`), its tests, `DriverContext` tests.
- **Requirements:** add `place_call`, `navigate_to`, `open_app`, `save_place` and
  `exit_navigation_mode`. Leave `describe_camera_view` out, because it is a read. Test the Gemini
  ordering: the call arrives before the transcript, and the epoch comes from the speech onset
  (`capabilityEpoch`). A repeat after a new utterance still runs.
- **Acceptance:** guard and dispatcher tests; the full `test`.
- **Escalate if:** under the Gemini ordering the epoch is 0, so the guard cannot apply. That would
  mean Gemini does not call `DriverContext.onSpeechStarted`, which is a wiring question for the
  planner.

### P4 — Settle the gate at `generationComplete` (W2, gated on N-2, reviewer required)

- **Objective:** R2.1 as §2.1 describes it.
- **Owned scope:** `GeminiLiveClient.kt` (`finishGeneration`, `closeTurn`, one `settled` flag);
  `GeminiLiveClientTest.kt`.
- **Requirements and tests:**
  - A clean reply is released at `generationComplete`, not at `turnComplete`.
  - `settleResponse` runs exactly once per response. With `generationComplete` absent,
    `turnComplete` settles, as today.
  - A call arriving after `generationComplete` but before `turnComplete` dispatches once. The
    response that answers its result is gated normally (probe: 0 of 8, but it must be safe).
  - `interrupted` after `generationComplete` gives one `ResponseDone("cancelled")` and no
    second settle.
  - `afterResponse` (the correction) stays at `turnComplete`. The 20 s correction grace is
    unchanged (R2.5).
- **Acceptance:** `--tests '*GeminiLive*'`, then the whole `test`. Baidu tests unmodified.

### P5 — Default model (W3, gated on N-1; planner)

`VoiceCatalog`'s Gemini default becomes `gemini-3.8-live`, and the settings list it first, marked
推荐. Planner-owned documents follow in the same change: ADR-010 decision 1 amended, ADR-011
`Accepted`, [ARCHITECTURE.md](ARCHITECTURE.md) ownership rows (R2.6 registry, settle point),
`config/capabilities.yaml`, SPEC-013/014 states, and the D-10 entry in TECH_DEBT marked resolved
(after P2).

### P6 — Evidence (W4)

- `GeminiLiveSmokeTest` (opt-in `NOVA_GEMINI_LIVE_SMOKE=1`, key from the environment, never
  printed) measures, through the app's own client:
  - L1: 10 spoken commands, a call ≤ 2.5 s after end of speech, in ≥ 9.
  - L2: gate release − `generationComplete` ≤ 50 ms for clean replies.
  - L3: no claim released before its result.
  Both models run; extended thinking is expected to fail L1 and is reported, not hidden.
- `./gradlew test --rerun-tasks :app:assembleDebug`. The counts come from the JUnit XML.
  `python scripts/harness_check.py`, `collect_state.py`.
- `TEST_MATRIX.yaml`: `GEMINI-DEVICE-LATENCY-001` (end of speech → first audio heard, on the phone)
  as `HUMAN_REQUIRED`, with what the cloud already established.

## 5. Parallelism, worktrees, builds

- W1: 3 executors, each in `../nova-wt/<P>` on branch `gn/<P>`, created by the planner from
  `BASE_COMMIT`. Each has its own `NOVA_BUILD_DIR`, and `local.properties` is copied in, never
  committed. W2–W4: 1 writer each.
- Integration: merge commits into `claude/9-29` in the order P3 → P1 → P2 (the smallest blast
  radius first), with the full `test` after each merge. Worktrees are removed after integration.
- Nothing is pushed unless the owner says so.

## 6. Review

A reviewer is required for P2 and P4 (state machine, I-1) and for the W4 integration. P1 and P3
are accepted on objective checks unless their diff leaves the owned scope. The reviewer receives
the packet, the commit range and the JUnit XML, never the executor's narrative. On REVISE, a new
bounded correction packet goes out. After two failed attempts on one path, the planner takes it
back.

## 7. Decisions this plan waits for, and what does not wait

| Decision | Blocks | Recommended |
| --- | --- | --- |
| **N-1** default Gemini model | P5 | `gemini-3.8-live` (20/20 calls, 0.8–2.4 s) |
| **N-2** settle at `generationComplete` (no invariant change) | P4 | yes |
| N-3 keep extended thinking selectable | nothing | yes, until the phone has tried both |
| D-9 hold budget releases unjudged words | nothing in this plan | owner decision; stays in TECH_DEBT |

P1–P3 (W1) wait for none of these. They add a selectable model without changing the default, and
tighten the gate without changing an invariant.

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| D-10(b) makes phantom-looking genuine replies wait | cost bounded (§2.4); the transcript arriving clears `PHANTOM_AUDIO` as today |
| Extending `REPEAT_SENSITIVE` swallows a legitimate model retry after a failure | failure is reported, not retried (I-2); the reviewer checks this deliberately |
| Settling before `turnComplete` while the server still paces playback | the audio is already all delivered at `generationComplete` (R2 measurement); interruption is covered by a P4 test |
| `gemini-3.8-live` self-interrupts or answers poorly in the cabin | G-M2 on the phone before P5 ships as the default |
| The cloud container lacks vendor artifacts | recorded as an external blocker, never stubbed into a pass |
