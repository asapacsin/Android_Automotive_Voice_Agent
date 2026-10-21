#!/usr/bin/env python3
"""PreToolUse(Edit|Write|MultiEdit|NotebookEdit|Bash): executors never change their own contract or records.

Protected: state/contracts/** (contracts and approvals), state/verification/**, state/reviews.log,
state/handoff/** - see PROTECTED in scripts/task_contract.py. With --executor (registered in the
executor agent's frontmatter) any write to them is denied, and so are `task_contract.py approve|verify`.
Without it (project settings, every agent) only writes inside an executor worktree are denied.
A safeguard against mistakes, not security: `task_contract.py scope|eligible` re-check from git, and the
CI unittests and branch protection are the independent layer. Fails open on unparsable input.
"""
import json
import re
import sys

WORKTREE_ROOT = "/home/user/nova-wt/"
PROTECTED_RE = re.compile(r"(?:^|[\\/\s'\"=])state[\\/](?:contracts[\\/]|verification[\\/]|handoff[\\/]|reviews\.log)")
WRITE_RE = re.compile(r"\bsed\s+-\w*i|(?<![0-9&<>-])>>?(?!&)|\btee\b|\bcp\s|\bmv\s|\brm\s|\btruncate\b|"
                      r"\bgit\s+(?:apply|restore|checkout|rm|mv)\b|\bpatch\b|open\([^)]*['\"][wa]|"
                      r"write_text|write_bytes|\bperl\s+-\w*i")
ROLE_CMD_RE = re.compile(r"task_contract\.py['\"]?\s+(?:approve|verify)\b")


def decide(tool, tool_input, executor):
    if tool == "Bash":
        cmd = tool_input.get("command") or ""
        if executor and ROLE_CMD_RE.search(cmd):
            return "Executors cannot approve contracts or record verification; return to the planner."
        if PROTECTED_RE.search(cmd) and WRITE_RE.search(cmd) and (executor or WORKTREE_ROOT in cmd):
            return "Contracts, approvals, verification and review records are not executor-editable."
        return None
    path = (tool_input.get("file_path") or tool_input.get("notebook_path") or "").replace("\\", "/")
    if PROTECTED_RE.search("/" + path) and (executor or path.startswith(WORKTREE_ROOT)):
        return "Contracts, approvals, verification and review records are not executor-editable."
    return None


def selftest():
    cases = [
        ("Edit", {"file_path": "/home/user/nova-wt/T/state/contracts/T-1.yaml"}, False, True),
        ("Write", {"file_path": "/home/user/Android_Automotive_Voice_Agent/state/reviews.log"}, True, True),
        ("Write", {"file_path": "/home/user/Android_Automotive_Voice_Agent/state/reviews.log"}, False, False),
        ("Edit", {"file_path": "/home/user/nova-wt/T/scripts/x.py"}, True, False),
        ("Bash", {"command": "python3 scripts/task_contract.py approve c.yaml --role architect"}, True, True),
        ("Bash", {"command": "python3 scripts/task_contract.py scope c.yaml --worktree ."}, True, False),
        ("Bash", {"command": "echo x >> state/verification/T-1.json"}, True, True),
        ("Bash", {"command": "cat state/contracts/T-1.yaml"}, True, False),
        ("Bash", {"command": "sed -i s/a/b/ /home/user/nova-wt/T/state/contracts/T-1.yaml"}, False, True),
    ]
    bad = [c for c in cases if (decide(c[0], c[1], c[2]) is not None) != c[3]]
    print("guard-contract-writes selftest: %s" % ("ok" if not bad else "FAIL %r" % bad))
    return 1 if bad else 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    try:
        data = json.load(sys.stdin)
        reason = decide(data.get("tool_name") or "", data.get("tool_input") or {}, "--executor" in sys.argv)
    except Exception:
        return 0
    if reason:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                                 "permissionDecisionReason": reason}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
