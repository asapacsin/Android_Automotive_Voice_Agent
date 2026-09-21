# HARNESS_PROPOSAL_001 — acceptance scopes and verdict validation

Status: **accepted by product owner 2026-09-21** (direct instruction; implemented same day)

## Observed failure

The 0.6.4 navigation run was reported as a stable navigation baseline because launch, route
resolution, guidance, HUD, traffic-light icons, callbacks and a clean manual stop all worked.
The recording never reached the destination: near the end, remaining distance jumped from
~15 m back to ~3.7 km and navigation continued; the run ended by manual stop. Intermediate
health was allowed to stand in for end-to-end success.

## Evidence

- `nav_baseline_0_6_4.mp4` (local `D:\桌面\android_doc\`): no arrival; terminal regression
  observed by the product owner
- `TEST_MATRIX.yaml` `NAV-BASELINE-001` (since renamed `NAV-MID-ROUTE-001`): stored PASS with
  `ended_by: manual` and no arrival evidence, and nothing in the harness objected

## Root cause

The registry validated *shape* (fields present, owner/status agreement, PASS-has-evidence) but
never *scope*: what a verdict is allowed to claim. No rule tied PASS to terminal-state
observation, to how the run ended, or to the words used in the report.

## Proposed change (implemented)

- `scripts/acceptance.py`: reusable scope/verdict framework — COMPONENT / INTERMEDIATE_FLOW /
  END_TO_END, terminal states, manual-stop rule, evidence-completeness matrix, verdict
  vocabulary, near-terminal regression detector. Product-neutral; navigation supplies only a
  minimum-criteria row.
- `scripts/test_matrix.py`: `scope` on every entry; acceptance matrix + `ended_by` on device
  flow/E2E verdicts; `terminal_success` on E2E entries; `INCOMPLETE` / `PARTIAL_PASS`
  statuses; unscoped `baseline` / `e2e` wording rejected below END_TO_END; `--verdict ID`
  self-check; extended `--selftest`.
- `scripts/harness_check.py`: runs both selftests, so the false-PASS shape fails the gate.
- Records: `NAV-MID-ROUTE-001` PASS (intermediate) + `NAV-E2E-ARRIVAL-001` FAIL (terminal
  regression, P31); constitution rule 20; `ACCEPTANCE_TESTS.md` scopes section.

## Expected benefit

The 0.6.4 verdict shape — intermediate evidence + manual stop, no terminal observation — can
no longer validate, let alone reach PASS. Same protection extends to every flow (voice,
calling, music, AC) through the shared framework.

## Added complexity

One small module (~200 lines), three matrix fields, two statuses. No app code touched.

## Token and latency cost

Negligible: two selftests run in seconds inside the existing harness check.

## Risk

Over-strict validation could block legitimate verdicts. Mitigated: COMPONENT and sim verdicts
keep the plain evidence rule; only device flows/E2E carry the matrix, which is where the
failure mode lives.

## How to test it

`python scripts/acceptance.py --selftest`, `python scripts/test_matrix.py --selftest`,
`python scripts/test_matrix.py --validate`, `python scripts/harness_check.py` — all must pass,
including the 0.6.4-reconstruction cases that must NOT earn PASS.

## How to roll it back

Revert the scripts and strip the added matrix fields; the old validator ignores unknown
fields, so rollback is one commit with no migration.
