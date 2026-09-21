# HARNESS_PROPOSAL_002 — required_scope and evidence provenance

Status: **accepted by product owner 2026-09-21** (direct instruction; implemented same day)

Follows [HARNESS_PROPOSAL_001](HARNESS_PROPOSAL_001-verdict-scope.md), which stopped intermediate
evidence from claiming END_TO_END. Two loopholes remained, both closed here.

## Loophole 1 — scope laundering

A test author could satisfy an END_TO_END requirement by declaring an INTERMEDIATE_FLOW test:
nothing tied a requirement to the scope its cover must reach.

Fix: `config/capabilities.yaml` owns each requirement's `required_scope` (absent = COMPONENT);
matrix entries link requirements via `covers:`; validation rejects `test_scope <
required_scope`. `test_matrix.py --coverage` reports per-requirement cover, and uncovered
flow/E2E requirements without queued human validation hold the human gate shut. Seeded with
`navigation.arrival_lifecycle` (END_TO_END, new), `navigation.guidance_voice` (INTERMEDIATE_FLOW)
and `phone.place_call` (END_TO_END).

## Loophole 2 — prose as evidence

"Arrival callback observed" as an evidence string would have earned E2E PASS: a claim about
evidence passing as a reference to evidence.

Fix: device flow/E2E acceptance rows with `observed: true` must cite an artifact —
`log:<tag>/<pattern>[@range]`, `video:<file>[@range]`, `xml:<file>#<test>` or
`artifact:<name>`. Bare prose earns INCOMPLETE, never PASS. Absence claims (`observed: false`)
may use prose — there is no line to cite for what never happened. Component/sim verdicts keep
the plain evidence rule.

## Benefit, cost, risk, test, rollback

Same shape as proposal 001: the 0.6.4 verdict shape plus these two launderings fail validation
instead of reaching reports. Added complexity is one registry field, one matrix link field and
one evidence grammar, all covered by selftests. Risk is over-strictness on future verdicts;
mitigated by keeping COMPONENT and sim verdicts on the light rule. Test with both selftests,
`--validate`, `--coverage`, `--verdict` and `harness_check.py`. Rollback is one revert; unknown
fields are ignored by the old validator.
