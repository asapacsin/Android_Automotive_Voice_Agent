---
name: token-efficient-task
description: Plan and execute a bounded Nova Drive task with targeted repository retrieval, an explicit task contract, and compact verification evidence.
---

# Token-efficient task protocol (TC-2, TC-5, TC-6)

This procedure changes *how much text agents receive*, not project truth, the
current model routing policy, release gates, or safety/review ownership rules.
The canonical AGENTS.md, SPECS, ADRs and harness/CONSTITUTION.md remain binding.

## 1. Retrieve the smallest necessary working set

- Obtain branch/commit, status and active task from git and discover_work.py.
- Start with the "Where things are" index in AGENTS.md, the task's SPEC and
  relevant owner rows in docs/ARCHITECTURE.md (Grep then bounded Read).
- For test evidence, use:
  - python scripts/matrix_row.py --rows-for <capability>
  - python scripts/matrix_row.py --row <ID>
  - python scripts/test_matrix.py --gate (gate summary)
- For defects, locate IDs/headings in OPEN_PROBLEMS.md using Grep, then read the
  surrounding bounded lines. A RESOLVED heading may still describe an active
  limitation; never infer closure from the label alone.
- Never Read TEST_MATRIX.yaml or OPEN_PROBLEMS.md end-to-end as a routine step.
  Do not use full-file cat/sed/Bash to evade this rule.
- Do not preload every SPEC, ADR, architecture chapter, and acceptance document.
  Retrieve more when the specific task needs it. A source owner or invariant
  must still be verified before behavior/source changes.

## 2. Produce the executor contract BEFORE dispatch

Use this small packet rather than forwarding the planning transcript:

TASK_ID:
BASE_STATE: exact commit, branch, worktree
GOAL: observable product outcome
OWNER: architecture owner and affected interfaces
INPUT_POINTERS: exact relative file paths, required sections, test row IDs
OWNED_SCOPE: files or paths the executor may modify
DO_NOT_TOUCH: conflicting or critical files
INVARIANTS: applicable constraints and ADR identifiers
ACCEPTANCE_COMMANDS: targeted tests plus required final gates
EVIDENCE_REQUIRED: XML, CI verdicts, device evidence if applicable
ESCALATION: conditions for BLOCKED / ARCHITECTURE_REVIEW_REQUIRED

Do not copy entire documents into the packet. Point to the exact section or
test ID. The executor must read actual source at the relevant integration
boundary; pointers do not replace source inspection.

## 3. Delegate only when isolation is worth the overhead

- Default to one executor. Use 2–4 only when separate components, independent
  base states, and non-overlapping ownership make concurrency worthwhile.
- Never add a second planner/reviewer merely to restate another agent's output.
- Decider runs only for a genuine unresolved decision; it is not a default pass.
- Existing independent review requirements remain mandatory. Reviewers inspect
  the diff, applicable contracts, and evidence rather than reloading unrelated
  project materials.

## 4. Verify without flooding context

During implementation, run the affected unit classes/modules:
  python scripts/compact_test.py -- ./gradlew :behavior-test:test
  python scripts/compact_test.py -- .\gradlew.bat :behavior-test:test

The wrapper stores the complete log in a temporary path and reports command
exit code, duration, *fresh* JUnit XML counts and failing test identities.
Output is intentionally not a substitute for source-level root-cause analysis.

- Run the required full suite and build gate once at final validation as the
  project policy requires; never reduce safety or device evidence.
- A clean targeted test is not proof of a release or end-to-end behavior.
- If the command fails, inspect the saved log in bounded segments as needed;
  do not paste an entire log into agent context.
- The wrapper never edits TEST_MATRIX.yaml, generated evidence, or canonical
  state. Continue to maintain those through their owning scripts.

## 5. Short handoff

Report: TASK_ID, changed files, test verdicts with commands and proof level,
unverified behavior, blockers and the next specific step. Durable truth lives
in canonical repository state, not a long agent transcript.

## 6. Measure, do not assume

Compare at least five similar task pairs or ten successive tasks per variant.
Log total model tokens/cost (all roles, cache and review), tool calls, retries,
wall time, pass rate, and regressions. Keep model assignments unchanged until
the benchmark supports a change. Do not claim percent savings from context
size alone.
