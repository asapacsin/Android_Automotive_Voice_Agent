# /start — resume work accurately

Use at the beginning of a session. Goal: know where things stand in under a minute, without reading
chat history or re-deriving state from `git log`.

## Steps

1. Refresh generated state — never trust a stale file:
   ```bash
   python scripts/collect_state.py
   ```
2. Read `state/PROJECT_STATE.json`: commit, whether the tree is clean, test totals, open issues,
   tech debt.
3. Read [AGENTS.md](../AGENTS.md) for the map, and
   [harness/CONSTITUTION.md](../harness/CONSTITUTION.md) for how work is done here.
4. Read only the capability entries relevant to the task from
   [config/capabilities.yaml](../config/capabilities.yaml) — not the whole file.
5. If a defect is in play, read its section in [OPEN_PROBLEMS.md](../OPEN_PROBLEMS.md). Root causes
   are recorded there; do not re-diagnose what has already been measured.
6. Check the last device evidence in `ACCEPTANCE_TESTS.md` if the task touches audio, the map or
   lifecycle.

## Output — short, four parts

- **Objective** — one sentence.
- **State** — commit, tree clean or not, test totals and failures, and any open issue, all read from the state file.
- **Blocker** — the one thing in the way, or "none".
- **Next 3 actions** — concrete and executable, not "investigate X".

Do not dump file contents into the session. Read what the task needs.
