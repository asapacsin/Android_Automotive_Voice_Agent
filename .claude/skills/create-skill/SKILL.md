---
name: create-skill
description: Turn a repeated Nova Drive workflow into a script or a skill, verified against a no-skill baseline before it lands. Use whenever discover_work.py or skill_metrics.py reports "NEW_SKILL or SCRIPT", "SKILL_MODIFICATION" or "TIME_IMPROVEMENT", when the owner has had to repeat the same instruction twice, or when anyone says "make this a skill", "automate this", "we keep doing this by hand".
---

# Create (or change) a skill from evidence

Adapted from Anthropic's skill-creator and the Agent Skills guides, with the community's
observe-by-machine idea and Voyager's verify-before-adding rule (sources at the end). The
repository's own thresholds and classification stay in
[harness/SKILL_POLICY.md](../../../harness/SKILL_POLICY.md), which wins on any conflict.

## 1. Start from the evidence, not from an idea

```bash
python scripts/skill_metrics.py          # suggested_actions, observed_sequences, time_efficiency
python scripts/skill_observe.py          # repeated command sequences since the last handoff record
```

Collect the real material for the candidate: the observed command sequences, the handoff records
naming it (`harness/skill-events.jsonl`: `manual_repeated_work`, `human_reminders`, `waste`), the
commits that did the work (`git log --stat`), and the OPEN_PROBLEMS or owner corrections behind
it. A skill written from general knowledge comes out generic ("handle errors appropriately"); one
extracted from these traces carries this project's real steps and gotchas.

## 2. Classify: script first

Follow SKILL_POLICY's order. If every step is deterministic, write a script under `scripts/` with
a `--selftest`, and point the relevant skill or AGENTS.md line at it. Example: provisioning worktrees
became `scripts/new_worktree.sh`, not a checklist. Write a skill only when the work still needs
judgement. A rule that must never break goes in an invariant or an architecture test, and project
truth goes in state. Neither belongs in a skill.

## 3. Write it in the Agent Skills format

- The canonical procedure is `skills/<name>.md`, shared with Codex and Cursor.
- `.claude/skills/<name>/SKILL.md` is the Claude Code entry. Copy one of the existing entries:
  frontmatter `name` and `description`, then a body that points at the canonical file.
- **The description decides whether the skill is ever used.** It is listed every turn; the body
  loads only on use. Put what it does and when to use it first, name the concrete triggers
  (phrases, script outputs, file types), and keep it under 1,536 characters. Agents tend to
  under-trigger skills, so make it a little pushy.
- **Body:** imperative steps; one default per step, with the alternative in a line; the *why*
  behind each rule instead of capital-letter MUSTs; a **Gotchas** list of real failures from step 1.
  Keep it under 500 lines, move detail to reference files, and say *when* to read each one.
  Bundle repeated deterministic parts as scripts.
- No project truth: no capability status, test counts or current bugs. Read them from state.

## 4. Verify against a baseline before it lands

Store 2–3 realistic prompts in `.claude/skills/<name>/evals/evals.json`:
`{"skill_name", "evals": [{"id", "prompt", "expected_output", "assertions": [...]}]}`.
Write them the way the owner or the planner really asks, including one edge case.

For each prompt, run two fresh-context subagents (`reviewer`, read-only) and ask each for the exact
commands and decisions it would take:
- **with skill:** the run is told to read the skill first;
- **baseline:** the run gets no mention of the skill.

Grade every assertion PASS or FAIL with evidence, and note the duration and tokens of each run.
The skill lands only if it raises the pass rate without a disproportionate cost. If it does not, revise
it from the transcripts (cut what the runs wasted time on) and rerun, at most twice. Then drop it
and record why. Fresh context matters: authoring context hides the gaps in the written steps.

**Trigger check:** write 3 prompts that should load the skill and 3 near misses that should not.
Give a fresh subagent only the list of skill names and descriptions, and ask which skill it would
load for each prompt. Fix the description until all 6 come out right.

## 5. Land and record

Commit the skill, its evals and a `harness/CHANGELOG.md` line, then append the handoff record with
`--candidate NEW_SKILL` (or `SKILL_MODIFICATION`). Low-risk procedural edits are self-applied.
Anything that changes a hard rule, an invariant or an agent's permissions becomes a proposal under
`harness/proposals/`; per SKILL_POLICY, that is the owner's decision.

## 6. Prune

A skill that never triggers costs context on every turn. `skill_metrics.py` lists `unused_skills`,
and `/skill-doctor` (Claude Code) shows each skill's cost and use. Merge or delete per SKILL_POLICY.

## Gotchas

- Changing `.claude/settings.json` or hooks is self-modification: the session classifier refuses
  it, and it is the owner's call. Observation therefore reads the transcripts at handoff
  (`skill_observe.py`) instead of adding a hook.
- `skills/*.md` paths are pinned by behavior-test architecture tests. Never move or rename them;
  add the `.claude/skills/` entry instead.
- Do not reuse `harness/skill-events.jsonl` fields for new meanings: `skill_metrics.py` counts them.

## Sources (fetched 2026-10-17)

- Anthropic, skill-creator: github.com/anthropics/skills, `skills/skill-creator/SKILL.md`. Capture
  intent from the conversation; a pushy description; evals run with and without the skill;
  improvements generalised from transcripts; repeated helper code bundled as scripts;
  description optimisation with should- and should-not-trigger queries.
- Claude Code docs, "Extend Claude with skills": code.claude.com/docs/en/skills. `.claude/skills/<name>/SKILL.md`,
  the frontmatter fields, the 1,536-character listing cap, baseline evaluation in a fresh session,
  `/skill-doctor`.
- Agent Skills: agentskills.io, the specification and the guides "Best practices for skill creators"
  and "Evaluating skill output quality". Extract from real tasks and project artifacts; spend
  context on what the agent lacks; gotchas; defaults, not menus; under 500 lines; `evals/evals.json`.
- Community: everything-claude-code `continuous-learning-v2` (after homunculus). Observe
  deterministically rather than through the model; small evidence-backed units promoted into
  skills once they recur.
- Voyager (MineDojo): a skill library that grows from executed programs and is added to only
  after self-verification succeeds.
