# HARNESS_PROPOSAL_004 — Best-practice design-basis gate

Status: **accepted by product owner 2026-09-22** (direct instruction; implemented same day)

Agents could invent a locally plausible mechanism, pass some tests, and only later discover the
architecture was wrong — for example muting the microphone during playback when barge-in requires
duplex capture and playback interruption.

## Observed failure

The harness tracked features, failures, tests, and evidence well, but had no upstream design-
quality control. Prose in `skills/continue.md` said "compile the demand first"; nothing executable
blocked implementation when the design basis was missing or suppressed required behaviour.

## Change

One registry, [harness/design_basis.yaml](../design_basis.yaml), and one checker,
[scripts/design_basis.py](../../scripts/design_basis.py), integrated into:

- `python scripts/harness_check.py` (`--selftest` + `--validate`)
- `scripts/discover_work.py` `SOURCES` (uncleared entries → `needs_compilation` at `P_SPEC`)

Statuses: `NOT_REQUIRED` | `PASS` | `NEEDS_RESEARCH` | `NOVEL_REVIEW`. Clearance is mechanical;
`NOVEL_REVIEW` requires explicit rationale fields, not a human queue.

Fail-closed: gated entry without clearance → non-zero `--check`, validate problem, frontier work.

## Migration

The gate applies only to entries in `design_basis.yaml`. Historical capabilities, test rows, and
PASS evidence are not backfilled. Absence of a record is not a violation.

## Benefit, cost, risk, test, rollback

Prevents premature invention without a web-search engine or second task framework. Cost: one YAML
registry, one script, one architecture test, thin Cursor rule. Risk: over-gating trivial edits;
mitigated by trigger booleans defaulting false and `NOT_REQUIRED` when no trigger is set.
Rollback: revert the script, registry, wiring, rule, and `DesignBasisPolicyTest`.
