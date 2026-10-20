#!/usr/bin/env python3
"""PreToolUse hook for Bash: refuse hand-made `git worktree add`.

Worktrees must come from scripts/new_worktree.sh, which also provisions the vendor jars and the
build config; a bare worktree builds without them. Fails open: any other command, malformed input
or an internal error exits 0 with no output. NOVA_GUARD_WORKTREE=0 disables the guard.
"""
import json
import os
import re
import shlex
import sys

REASON = ("Hand-made worktrees are refused: use `bash scripts/new_worktree.sh <TASK_ID> <branch> "
          "<BASE_COMMIT>` instead. It provisions the vendor jars and build config; a bare "
          "`git worktree add` produces a worktree without them, so builds and tests there are "
          "not evidence.")

# git options that take a separate value argument
_VALUE_OPTS = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path",
               "--config-env", "--super-prefix"}


def _segments(command):
    for seg in re.split(r"&&|\|\||[;|&\n]", command):
        try:
            yield shlex.split(seg)
        except ValueError:
            yield seg.split()


def is_worktree_add(command):
    for words in _segments(command):
        for i, w in enumerate(words):
            if os.path.basename(w) != "git":
                continue
            j = i + 1
            while j < len(words) and words[j].startswith("-"):
                j += 2 if words[j] in _VALUE_OPTS else 1
            if j + 1 < len(words) and words[j] == "worktree" and words[j + 1] == "add":
                return True
    return False


def main():
    if os.environ.get("NOVA_GUARD_WORKTREE") == "0":
        return
    data = json.loads(sys.stdin.read())
    command = data.get("tool_input", {}).get("command", "")
    if isinstance(command, str) and is_worktree_add(command):
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": REASON,
        }}))


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
    sys.exit(0)
