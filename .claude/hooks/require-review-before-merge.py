#!/usr/bin/env python3
"""PreToolUse(Bash): an executor branch is merged only after its review (owner 2026-10-16).

`git merge ... exec/<name>` is refused unless state/reviews.log has a PASS or WAIVED line for that
branch's tip commit (first 7+ hex of its sha). Reviews happen after the executor finishes and before
integration - the Writer/Reviewer pattern and "add an adversarial review step before treating a
task as done" from the official Claude Code best-practices guide. PENDING does not count.
Fails open on anything unexpected, so a broken hook never blocks all work.
"""
import json
import os
import re
import subprocess
import sys


def decide(command, repo, log_path):
    if not re.search(r"\bgit\b[^;&|]*\bmerge\b", command):
        return None
    branches = re.findall(r"\b(exec/[A-Za-z0-9._/-]+)", command)
    if not branches:
        return None
    entries = []
    if os.path.exists(log_path):
        for line in open(log_path, encoding="utf-8"):
            parts = line.split()
            if len(parts) >= 2 and not line.startswith("#") and parts[1] in ("PASS", "WAIVED"):
                entries.append(parts[0])
    for branch in branches:
        try:
            tip = subprocess.run(["git", "-C", repo, "rev-parse", branch], capture_output=True,
                                 text=True, check=True).stdout.strip()
        except Exception:
            return None
        if not any(len(e) >= 7 and tip.startswith(e) for e in entries):
            return ("%s (tip %s) has no PASS/WAIVED line in state/reviews.log. Run the reviewer on the "
                    "branch first, then record '<tip-sha> PASS <date> <note>' (or WAIVED with a reason) "
                    "and merge." % (branch, tip[:12]))
    return None


def selftest():
    import tempfile
    tmp = tempfile.mkdtemp()
    run = lambda *a: subprocess.run(["git", "-C", tmp, "-c", "user.name=t", "-c", "user.email=t@t"] + list(a),
                                    capture_output=True, text=True, check=True).stdout.strip()
    run("init", "-q", "-b", "main"); open(os.path.join(tmp, "a"), "w").write("a")
    run("add", "-A"); run("commit", "-qm", "a"); run("checkout", "-qb", "exec/x")
    open(os.path.join(tmp, "b"), "w").write("b"); run("add", "-A"); run("commit", "-qm", "b")
    tip = run("rev-parse", "HEAD"); log = os.path.join(tmp, "reviews.log")
    open(log, "w").write("# h\n")
    ok = decide("git merge --no-ff exec/x -m m", tmp, log) is not None
    open(log, "a").write(tip[:12] + " PENDING d x\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is not None
    open(log, "a").write(tip[:12] + " PASS d reviewer\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is None
    ok = ok and decide("git log exec/x", tmp, log) is None and decide("git merge main", tmp, log) is None
    print("require-review-before-merge selftest: %s" % ("ok" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        return selftest()
    try:
        data = json.load(sys.stdin)
        command = (data.get("tool_input") or {}).get("command") or ""
        repo = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
        reason = decide(command, repo, os.path.join(repo, "state", "reviews.log"))
    except Exception:
        return 0
    if reason:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                                 "permissionDecision": "deny",
                                                 "permissionDecisionReason": reason}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
