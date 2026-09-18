# /handoff — leave the next session able to continue

Durable state lives in git, the canonical documents and generated files — never in a transcript.

## Steps

1. `git status --porcelain` and `git diff --stat`: nothing unexplained should be uncommitted.
2. Refresh generated state:
   ```bash
   python scripts/collect_state.py
   ```
3. Record outcomes where they belong — not in a new status file:
   - a defect and its measured root cause -> `OPEN_PROBLEMS.md`
   - device evidence -> `ACCEPTANCE_TESTS.md`
   - a capability or its verification level -> `config/capabilities.yaml` + `docs/CAPABILITIES.md`
   - debt discovered or resolved -> `docs/TECH_DEBT.md`
4. Commit verified work locally with a message that says what changed and what verified it.
5. **Skill review** — the loop that lets the procedures improve. Answer briefly:
   1. which skills were used?
   2. was any deviated from?
   3. was an important step missing?
   4. did the human have to repeat an instruction?
   5. was a multi-step workflow done by hand that has appeared before?
   6. did a skill name a stale path, command or assumption?
   7. could a repeated operation become a script?
   8. does this match earlier entries in `harness/skill-events.jsonl`?

   Then append exactly one record:
   ```bash
   python scripts/skill_event.py --task <id> --outcome PASS --skills start,fix,verify        --candidate NONE --note "one line"
   ```
   Most tasks are `NONE`. Do not manufacture churn because the review exists.
6. If the review crosses a threshold in [SKILL_POLICY.md](../harness/SKILL_POLICY.md), act:
   self-apply a low-risk procedural edit, or write a proposal under `harness/proposals/`.

## Output

Completed · still failing · approaches already tried · blocked on · next 3 actions. Structured
conclusions, never a transcript.
