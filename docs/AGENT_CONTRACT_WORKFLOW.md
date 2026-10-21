# Agent contract workflow: architect → executor → independent reviewer

Every task that changes tracked files runs under a **task contract**. The contract names the paths
the executor may change, what it must not change, the architecture constraints, the verification
that must pass and the acceptance criteria. Git and the record files decide whether the work may
merge, not the executor's report. Tool: `scripts/task_contract.py`. Tests:
`scripts/tests/test_task_contract.py`, which CI runs with the other script unittests in
`scripts/ci_tier_a.sh`.

| Step | Who | Command | Writes |
| --- | --- | --- | --- |
| 1. Write the contract | architect/planner | edit `state/contracts/<ID>.yaml` | contract |
| 2. Validate | architect/planner | `task_contract.py validate <c>` | nothing |
| 3. Approve | architect/planner (never the executor) | `task_contract.py approve <c> --role architect --note "..."` | `state/contracts/approvals.log` (sha256 of the contract) |
| 4. Implement | executor, in its own worktree | (edits) + `task_contract.py scope <c> --worktree .` | commits on `exec/<name>` |
| 5. Verify | planner or reviewer | `task_contract.py verify <c> --worktree <wt>` | `state/verification/<ID>.json`; full logs in `~/nova-drive-build/nova-verify/<ID>/` |
| 6. Review | reviewer agent | reads the diff, contract and verification record; planner records the verdict | `state/reviews.log` (`<tip> PASS <date> <note>`) |
| 7. Merge gate | planner | `task_contract.py eligible <c> --ref exec/<name>` | nothing; prints ELIGIBLE or NOT_ELIGIBLE with reasons |
| 8. Merge | planner, only after ELIGIBLE | `git merge --no-ff exec/<name>` | merge commit |
| Handoff | whoever ends the unit | `task_contract.py handoff <c> --worktree <wt> --next "..."` | `state/handoff/<ID>.md` |

All commands are run as `python3 scripts/task_contract.py ...` from the **main checkout**, so the
contract and records are always read from there, never from the executor's worktree.

## Contract

Field list: `python3 scripts/task_contract.py schema`. Full example:
[examples/task_contract.example.yaml](examples/task_contract.example.yaml).

- `approved_paths` are globs relative to the repo: `*` stays within one folder, `**` crosses
  folders, and `dir/` means everything under `dir`.
- `forbidden_paths` win over `approved_paths`.
- These paths are always forbidden to executors, and no contract may approve them:
  - `state/contracts/**`
  - `state/verification/**`
  - `state/reviews.log`
  - `state/handoff/**`
- Each `required_verification` entry has an `id`, a `kind` (compile, regression, integration, device
  or lint) and a `command`. List every check the task really needs: compile, regression and
  integration. Wrap Gradle in `python3 scripts/compact_test.py -- ./gradlew ...`, which gives a short
  summary with the exit code and JUnit counts and keeps the full log on disk. Never swap a required
  suite for a smaller one to save tokens.
- Approval records the contract's sha256. Any later edit, for example weakening
  `acceptance_criteria`, shows as "contract changed after approval" until the architect approves it
  again.

## What is enforced

- **Scope (`scope`):** every path changed since `base_commit` must match `approved_paths` and no
  forbidden glob. That covers committed, staged, unstaged and untracked files; gitignored build
  output is skipped, and a rename counts as a delete plus an add.
- **Verification (`verify`):** refuses a dirty worktree and refuses to run when
  `NOVA_AGENT_ROLE=executor`. It runs each command on the committed tip, records the exit code and a
  sha256 of the log, and records nothing if tracked files or HEAD change during the run.
- **Eligibility (`eligible`):** all of the following must hold:
  - the contract is valid and approved at its current sha256;
  - the worktree is clean and the scope check passes;
  - the verification record is for this exact tip and contract version, and every required id
    passed with the command written in the contract;
  - `state/reviews.log` has a reviewer **PASS** for this tip. WAIVED, PENDING and REVISE do not count.

  `eligible` never merges.
- **Merge hook:** `.claude/hooks/require-review-before-merge.py` refuses `git merge` of any
  `exec/*` branch named by a contract unless it is ELIGIBLE. Branches without a contract keep the
  existing review-record rule.
- **Executor guard:** `.claude/hooks/guard-contract-writes.py` is registered with `--executor` in
  `.claude/agents/executor.md` and in project settings for all agents. It denies:
  - for executors, any edit to the protected records;
  - for executors, `task_contract.py approve|verify`;
  - for every agent, writes to those records inside `/home/user/nova-wt/` worktrees.

## Limits (read this)

Local hooks and these checks are **safeguards against mistakes and drift, not tamper-proof security**:
- Anyone with write access can edit `approvals.log`, `reviews.log` or a verification JSON, and the
  roles are self-declared.
- The Bash guard matches command patterns and can be bypassed.
- The independent layers are these:
  - CI re-runs the unittests and `delegation_check.py --ci`.
  - The `test_committed_contracts` test fails CI when a committed contract is invalid or differs from
    its approval.
  - Branch protection and CODEOWNERS on `state/` and `scripts/` would complete the layer; they are
    repository settings and are not set up here.

## Recovery

- **Session ended mid-task:**
  1. `cat state/handoff/<ID>.md`
  2. `git -C /home/user/nova-wt/<ID> status --short`
  3. `python3 scripts/task_contract.py eligible state/contracts/<ID>.yaml --worktree /home/user/nova-wt/<ID>`

  The reasons it prints are the to-do list.
- **"contract changed after approval":** if the change was intended, re-approve (architect only). If
  not, `git checkout -- state/contracts/<ID>.yaml` in the main checkout.
- **"verification is for X, not the current tip":** the executor committed after verification. Run
  `verify` again, then have the reviewer look at the new tip.
- **Scope FAIL:** the executor moves the change out, or the architect widens `approved_paths` and
  re-approves. Never widen it just to make the gate pass.
- **Near a token or context limit:** stop at the end of a unit, commit, write the handoff and end
  the turn. Never loop retries or full re-runs.
