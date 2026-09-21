# HARNESS_PROPOSAL_003 — Cursor model-routing hard gate

Status: **accepted by product owner 2026-09-21** (direct instruction; implemented same day)

The 2026-09-21 Cursor split put Composer on mechanical work and Grok High on H1–H8, but the
gate was prose. A DEFAULT executor could still take the gated reasoning itself, and a missing
Grok pin could be ignored. That is how a "must route" policy becomes a suggestion.

## Observed failure

A recommendation-only rule cannot stop `--action continue` through architecture, two failed
fixes, or a release-critical contract change. It also cannot fail closed when
`.cursor/agents/grok-high.md` is absent or unpinned.

## Change

One classifier, [scripts/model_route.py](../../scripts/model_route.py), is the enforcement.
Routes are `DEFAULT` | `GROK_REQUIRED` | `BLOCKED_GROK_UNAVAILABLE`. Trigger letters A–F are
the hard-gate names; H1–H8 remain the same conditions (not a second list). The always-apply
rule and the `grok-high` / `implementer` / `repo-explorer` pins stay; they now require the
script.

Fail-closed: Grok unavailable → `BLOCKED_GROK_UNAVAILABLE`, never DEFAULT.
Anti-loop: gitignored ledger under `.local-agent-memory/`; `--role grok-high` cannot
re-delegate.

## Benefit, cost, risk, test, rollback

DEFAULT can no longer implement a gated portion without a non-zero exit. Added complexity is
one script, one ledger path (gitignored), and architecture-test coverage of the eight
required scenarios. Risk is over-escalation on mechanical bulk work; mitigated by explicit
suppressors (mechanical rename, docs-only, obvious local fix) that the selftest locks.
Rollback is one revert of the script, rule, agent files, and `ModelRoutingPolicyTest`.

## Amendment 2026-09-21 — Termination hard gate

DEFAULT could still self-authorize ending a run when `discover_work` said the frontier was
exhausted or blocked. That is closed: `--action terminate-request|terminate-review|terminate-consume`,
MAX_GROK-only review, fingerprint-bound single-use `TERMINAL_APPROVED`, fail closed on
`REVIEW_UNAVAILABLE` / stale / unavailable Grok. `discover_work` always prints
`TERMINATION_AUTHORIZED = NO`.
