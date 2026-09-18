# Harness update policy

The harness may evolve. It may not drift.

## May be refreshed automatically, no approval

Anything derived from evidence: build status, test counts, git state, capability *observations*,
open issues, tech-debt status, blockers, generated indexes and metrics. These are outputs of
`scripts/collect_state.py` and `scripts/skill_metrics.py`; refreshing them is not a decision.

## Requires a written proposal

Anything that changes what "correct" means:

- constitution rules or architectural invariants;
- capability **semantics** — what a capability means, or its verification level;
- verification standards, release criteria, or what counts as evidence;
- safety rules and permissions;
- the source-of-truth hierarchy or harness topology;
- automatic enforcement behaviour — what the architecture tests assert.

Write `harness/proposals/HARNESS_PROPOSAL_<id>.md` containing: the observed repeated failure · the
evidence · root cause · proposed change · expected benefit · added complexity · token and latency
cost · risk · how to test it · how to roll it back.

Promote a proposal only when it fixes a demonstrated problem. Sophistication is not a reason.

## Deleting harness components

Ask the maintenance question: **does removing this make outcomes measurably worse?** If not, delete
it. A component that has never caught anything is cost without benefit.

## Self-evaluation

The target is *maximum verified engineering output for minimum harness complexity and human
attention* — not maximum harness. Signals worth watching, all cheap:

- how often a human had to repeat an instruction (`human_reminders` in the skill events);
- how often a completed task later turned out to be wrong (`task_outcome` vs a later FAIL);
- whether the same manual workflow keeps reappearing (`manual_repeated_work`);
- stale-state incidents — the state file disagreeing with the repository.

`scripts/skill_metrics.py` summarises these. If a component shows no signal over many tasks,
[delete it](#deleting-harness-components).
