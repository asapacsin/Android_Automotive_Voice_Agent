# Installing agent-routing into another repository

1. From a checkout that has the tool:
   `python3 tools/agent-routing/agent_routing_cli.py install --target /path/to/repo [--executor poolside]`
   This copies `tools/agent-routing/` and creates `.agent-routing/project.json` (from
   `templates/project.json`) and `.agent-routing/.gitignore` only if they do not exist. No other file is
   modified.
2. Edit `/path/to/repo/.agent-routing/project.json`: set `tests.command` (argv list), add `never_send`
   globs for licensed or vendor content, optional `risk_rules`, and `providers.<id>.auth`
   (`env` or `proxy-injected`).
3. Provide keys as environment variables (`GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `NVIDIA_API_KEY`); never
   put keys in the config.
4. Check: `python3 tools/agent-routing/agent_routing_cli.py doctor --project . --live`.
5. Commit `tools/agent-routing/` and `.agent-routing/project.json` + `.agent-routing/.gitignore`.
6. Add the Stop hook to `.claude/settings.json` yourself (install never edits it):
   ```json
   {"hooks": {"Stop": [{"hooks": [{"type": "command",
     "command": "python3 \"$CLAUDE_PROJECT_DIR/tools/agent-routing/agent_routing_cli.py\" stop-hook",
     "timeout": 600}]}]}}
   ```
   On Windows use `py -3` or `python` instead of `python3`.

Workflow: `execute` -> fix until tests pass -> `review` -> address actionable findings (HIGH: `gate resolve
--id F --evidence "..."`) -> `review` again -> `gate check` passes -> commit -> `gate clear` (refused unless PASS/CLEAN;
`--force --reason` is audited and leaves the change UNVERIFIED).
