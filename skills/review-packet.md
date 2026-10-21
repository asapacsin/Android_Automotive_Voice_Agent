# /review-packet — from "executor finished" to a recorded review and a safe merge

The planner does this after every executor branch, before `git merge exec/*`. The fixed steps
are in `scripts/review_packet.py`; this procedure is the judgement around them. An executor's own
report is never the evidence (AGENTS.md: an implementation agent may not certify its own work).

## Steps

1. **Print the packet.**
   ```bash
   python scripts/review_packet.py packet --branch exec/<task> --build-dir <the executor's NOVA_BUILD_DIR>
   ```
   It shows the range, the changed files (production source marked), whether review is required,
   candidate SPEC lines, and JUnit counts per module and variant (STALE = older than the tip).
2. **Check the scope** against the task packet's OWNED_SCOPE and DO_NOT_TOUCH. A file outside it
   means a correction packet, not a merge.
3. **Re-run the acceptance commands yourself** in the executor's worktree, with `--rerun-tasks`
   and its own `NOVA_BUILD_DIR`. Then print the packet again: no line may say STALE, and no count
   may be failed.
4. **Decide review.**
   - Production source changed, a behaviour or state machine changed, or the automated coverage is
     incomplete: send `reviewer`.
   - Otherwise WAIVED is allowed, with a reason of 4+ words saying what you checked yourself.
   - Give the reviewer the task packet, the range, the acceptance evidence and the SPEC rows this
     branch claims. Do not include the executor's narrative.
5. **Pick the SPEC rows.** The script lists every line that names a changed symbol. Keep only the
   rows this branch earns, and mark each with its evidence (test class, run, commit).
6. **Act on the verdict.**
   - **REVISE:** write a new bounded correction packet naming the exact issues. Never forward "fix
     the review comments". After the fix, record `REVISED <fix-tip> ...` and review again.
   - **PASS / WAIVED:** record it in its own command:
     ```bash
     python scripts/review_packet.py record --sha <tip> --status PASS --note "reviewer PASS: <what>"
     ```
     Then merge in a separate command (`git merge --no-ff exec/<task>`), rerun the regression checks
     on the integrated result, and push.
7. **Clean up after the merge.** Remove the worktree (`git worktree remove`, then `git branch -d`),
   and delete every build directory used for the branch: the executor's and your own rerun's
   `NOVA_BUILD_DIR`. Each one holds hundreds of MB. On 2026-10-17, leftover build directories filled
   the session's disk allowance and an APK build failed with "No space left on device".

## Gotchas
- **Never run your rerun while the reviewer builds in the same worktree.** A separate
  `NOVA_BUILD_DIR` is not enough: on 2026-10-19 a planner rerun and a reviewer's Gradle run in
  `../nova-wt/P45-U2` at the same time gave 161 `NoClassDefFoundError` failures (test classes
  removed mid-run), a false red that cost a full rerun. Rerun first and then send the reviewer, or
  tell the reviewer to read your JUnit XML instead of building.

- **Never add debug and release counts together.** Report one variant (debug) and say which. The
  summed figure went into SPEC cells on 2026-10-17 and had to be reworded.
- **The record and the merge must be separate commands.** The merge hook reads the whole command
  before any part runs, so a record written in the same command does not count yet.
- **The record keys on the branch tip you merge.** After a REVISE round the tip changes; record the
  new tip.
- **Old JUnit XML can make a red build look green.** Leftover build directories (`/root/nova-verify/*`,
  `/root/nova-build-review`) hold old failures and passes. Read only the executor's `--build-dir`,
  and trust it only when it is not STALE.
- **A worktree without the gitignored vendor files cannot compile `:app`.** Create worktrees with
  `scripts/new_worktree.sh`; with no vendor zip it installs the documented stand-in
  (`tools/vendor-stub/README.md`, decided by the decider 2026-10-19). Never write another stub. On a
  stand-in tree, JVM counts are valid as "stub-iFlytek classpath" evidence; the packaged
  wake-engine check fails by design; no APK, bind or wake-word claim comes from it.
