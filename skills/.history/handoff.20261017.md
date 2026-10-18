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
   9. **time** (owner, 2026-10-17): how many minutes did it take from start to verified push, and
      how many attempts (dispatches, builds, runs repeated before it was right)? In hindsight, was
      this the time-efficient way? If not, name each cause of lost time and fix it now (a script,
      a skill step, a settings or environment change), or write the proposal if only the owner can.

   Then append exactly one record:
   ```bash
   python scripts/skill_event.py --task <id> --outcome PASS --skills start,fix,verify \
       --candidate NONE --minutes 40 --attempts 2 --efficient no \
       --waste vendor-files-missing --improvement "what now prevents it" --note "one line"
   ```
   The record also stores the command sequences `scripts/skill_observe.py` finds repeated in
   this work's transcripts. `python scripts/skill_metrics.py` (run by the record) totals the time,
   flags a cause of lost time seen twice as `TIME_IMPROVEMENT`, and flags a sequence seen in 3
   records as a script or skill candidate. Each of these is due work, not a note: build it with
   `.claude/skills/create-skill/SKILL.md`.
   Most tasks are `NONE`. Do not manufacture churn because the review exists.
6. If the review crosses a threshold in [SKILL_POLICY.md](../harness/SKILL_POLICY.md), act:
   self-apply a low-risk procedural edit, or write a proposal under `harness/proposals/`.

## Output

Completed · still failing · approaches already tried · blocked on · next 3 actions. Structured
conclusions, never a transcript.
