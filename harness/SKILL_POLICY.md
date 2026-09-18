# Skill policy

A skill is a **procedure**. It must never contain project truth — no capability status, no current
bug, no test counts. Those come from [config/capabilities.yaml](../config/capabilities.yaml),
[state/PROJECT_STATE.json](../state/PROJECT_STATE.json) and
[OPEN_PROBLEMS.md](../OPEN_PROBLEMS.md), which the skill *reads*.

```
Bad:  "Navigation is currently unsupported."
Good: "Read config/capabilities.yaml and confirm the capability's verified level."
```

## Classification — decide in this order

```
Can deterministic code do it reliably?        -> SCRIPT  (prefer this)
Is it a rule that must never be violated?     -> INVARIANT / architecture test
Is it a recurring workflow needing judgement? -> SKILL
Is it volatile project truth?                 -> STATE or CONTRACT, never a skill
```

## Evidence thresholds

| Action | Threshold |
| --- | --- |
| **New skill** | the same meaningful workflow observed **≥ 3** times, sequence reasonably stable, judgement still required, and deterministic automation is insufficient |
| **Modify** | the same meaningful deviation **≥ 2** times. Immediate if a skill names an obsolete path or command, or a missing step caused a deterministic verification failure |
| **Merge** | two skills substantially duplicate steps, or are nearly always invoked together |
| **Delete** | repeated evidence that the model does it reliably unaided, or a script/invariant now covers it, or it costs more than it returns. Never on one good task |

## What an agent may change by itself

Procedural, low-risk edits only: adding a missing verification step, correcting an obsolete command
or path, removing a provably redundant step, reordering, parallelising independent steps, reducing
unnecessary reads, making output more concise **without** reducing evidence.

Procedure for a self-applied change:

1. copy the current skill to `skills/.history/<name>.<timestamp>.md`;
2. make the edit;
3. run the checks the skill itself prescribes;
4. compare the outcome against the previous behaviour;
5. keep it only if correctness did not decrease — otherwise restore from `.history`;
6. add one line to [CHANGELOG.md](CHANGELOG.md).

## What needs a proposal

Anything that would weaken verification, change capability semantics or safety boundaries, alter
permissions or pass criteria, remove required evidence, or change an invariant. Write
`harness/proposals/SKILL_PROPOSAL_<id>.md` with the evidence, affected tasks, current behaviour,
proposed change, expected gain, risk, validation method and rollback.

Before proposing a skill, ask the cheaper question first: **could a script do this?** Fixed
operations belong in `scripts/`. Skills are for work that needs judgement.
