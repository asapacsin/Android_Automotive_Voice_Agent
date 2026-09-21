# HARNESS_PROPOSAL_EVIDENCE_CLOSURE_2026_09_21

Status: **Implemented on proposal branch; independent review required before merge**

## Observed failure

A navigation run was reported as a stable baseline after launch, route calculation, guidance, speed-limit
HUD and traffic-light icons worked for several minutes. The recording did **not** reach the claimed
end state. Near the destination the remaining distance fell to roughly 15 m, then jumped back to
kilometres, navigation continued, and the session was eventually stopped manually.

The lower-level observations were real. The broad conclusion was not. Prefix evidence was promoted
to a lifecycle claim.

## Evidence

- The run's own summary ended with a manual stop rather than a natural arrival/completion event.
- Review of the recording showed no natural terminal arrival state.
- `TEST_MATRIX.yaml` already contains `NAV-DRIVE-001`, whose purpose explicitly says that route
  navigation must be driven **to arrival** and whose status remains `HUMAN_REQUIRED`.
- Existing Constitution rules 1-3 require runtime evidence, but do not require the evidence window
  to cover the complete scope of the claim.

## Root cause

The harness checks that evidence exists, but not that its **boundary matches the claim**. A test can
therefore accumulate many true intermediate observations and still be mislabeled PASS for a larger
flow. There is no mandatory terminal oracle for lifecycle/end-to-end/baseline tests, and no explicit
classification of manual stop, timeout, recording end or unexpected reroute as an abort of the parent
claim.

## Proposed change

Add a generic rule: **CLAIM_SCOPE_MUST_MATCH_EVIDENCE**.

For any lifecycle, end-to-end, baseline, arrival, or full-route claim:

1. declare the claim scope;
2. declare an observable terminal oracle;
3. declare abort conditions;
4. record whether the terminal oracle was actually observed;
5. allow PASS only when the terminal result is `OBSERVED` and terminal evidence is recorded.

Evidence before the terminal oracle proves only the observed prefix. A manual stop, timeout, recording
end, crash, environment reset, or unexpected transition before the terminal oracle makes the parent
claim PARTIAL/FAIL (or leaves it unearned), even when lower-level subcriteria pass. Manual stop may be
a success only when manual stop itself is the behaviour under test.

Mechanically enforce this in `scripts/test_matrix.py` for explicitly lifecycle-scoped tests and for
test IDs/names that advertise lifecycle semantics such as baseline, E2E, lifecycle, arrival, or a full
route. Update the SPEC template and verification procedure so future work declares the boundary before
execution. Add an architecture test so removing the mechanism fails the suite.

## Expected benefit

Prevents the exact class of false closure seen here: "many useful things worked" can no longer become
"the whole flow passed" without evidence of the promised end state. Subsystem progress remains
recordable without overstating the parent result.

## Added complexity

Small: five optional lifecycle fields in the test registry and one validation helper. Existing point
tests are unchanged. Only tests that explicitly claim lifecycle semantics pay the extra annotation.

## Token and latency cost

Negligible. Registry validation is local string/schema work. Agent prompts gain a short boundary check
before verification, which should save time by preventing invalid handoffs and retests.

## Risk

- Keyword inference could classify a test as lifecycle when its name uses "baseline" in another sense.
  The inference is intentionally limited to test ID/name, not free-form purpose/evidence text.
- A model can still falsely write `terminal_result: OBSERVED`; this proposal cannot replace review of
  runtime evidence. It makes the claim explicit and mechanically reviewable rather than implicit.

## Test

1. A lifecycle-like test with no `claim_scope: LIFECYCLE` must fail registry validation.
2. A lifecycle PASS with `terminal_result: ABORTED` or `NOT_OBSERVED` must fail validation.
3. A lifecycle PASS with no `terminal_evidence` must fail validation.
4. A correctly annotated lifecycle PASS with an observed terminal oracle must validate.
5. `NAV-DRIVE-001` remains unearned until natural arrival is observed; manual stop cannot close it.
6. Architecture policy tests must fail if the Constitution, SPEC template, verify procedure, or matrix
   enforcement is removed.

## Rollback

Revert the proposal commit(s), remove rule 20, remove the lifecycle validator and policy test, and
remove the added lifecycle fields from `NAV-DRIVE-001`. No production code or persisted user data is
affected.
