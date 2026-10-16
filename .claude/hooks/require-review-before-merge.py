#!/usr/bin/env python3
"""PreToolUse(Bash): an executor branch is merged only after its review (owner 2026-10-16).

`git merge ... exec/<name>` or `git pull <remote> exec/<name>` is refused unless the last valid line in
state/reviews.log for that branch's tip is PASS, or WAIVED on a branch that changes no <module>/src/main
(grammar shared with scripts/delegation_check.py; invalid lines never count). Reviews happen after the executor finishes and before
integration - the Writer/Reviewer pattern and "add an adversarial review step before treating a
task as done" from the official Claude Code best-practices guide. PENDING does not count.
Fails open on anything unexpected, so a broken hook never blocks all work.
"""
import json
import os
import re
import subprocess
import sys


def _gate():
    """Shared record grammar from scripts/delegation_check.py (statuses must agree)."""
    import importlib.util
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "scripts",
                        "delegation_check.py")
    spec = importlib.util.spec_from_file_location("delegation_check", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def decide(command, repo, log_path):
    if not re.search(r"\bgit\b[^;&|]*\b(merge|pull)\b", command):
        return None
    branches = re.findall(r"\b(exec/[A-Za-z0-9._/-]+)", command)
    if not branches:
        return None
    dc = _gate()
    records, _ = dc.parse_log(log_path)  # invalid lines never count
    for branch in branches:
        try:
            tip = subprocess.run(["git", "-C", repo, "rev-parse", branch + "^{commit}"],
                                 capture_output=True, text=True, check=True).stdout.strip()
        except Exception:
            return None
        rec = dc.lookup(records, [tip])  # last valid line wins
        status = rec[1] if rec else None
        if status == "WAIVED":
            try:
                mb = dc.git(repo, "merge-base", "HEAD", tip).strip()
                if dc.touches(dc.changed(repo, mb, tip), "main/"):
                    return ("%s (tip %s) is WAIVED but changes <module>/src/main; it needs a reviewer PASS."
                            % (branch, tip[:12]))
            except Exception:
                return None
            continue
        if status != "PASS":
            return ("%s (tip %s) has no accepted review in state/reviews.log (latest: %s). Run the reviewer "
                    "on the branch first, then record '<tip-sha> PASS <yyyy-mm-dd> <note>' (or WAIVED with "
                    "a reason of 4+ words, not for src/main) and merge." % (branch, tip[:12], status or "none"))
    return None


def selftest():
    import tempfile
    tmp = tempfile.mkdtemp()
    run = lambda *a: subprocess.run(["git", "-C", tmp, "-c", "user.name=t", "-c", "user.email=t@t"] + list(a),
                                    capture_output=True, text=True, check=True).stdout.strip()
    run("init", "-q", "-b", "main"); open(os.path.join(tmp, "a"), "w").write("a")
    run("add", "-A"); run("commit", "-qm", "a"); run("checkout", "-qb", "exec/x")
    os.makedirs(os.path.join(tmp, "app", "src", "main"))
    open(os.path.join(tmp, "app", "src", "main", "b"), "w").write("b"); run("add", "-A"); run("commit", "-qm", "b")
    tip = run("rev-parse", "HEAD"); run("checkout", "-q", "main"); log = os.path.join(tmp, "reviews.log")
    open(log, "w").write("# h\n")
    ok = decide("git merge --no-ff exec/x -m m", tmp, log) is not None
    ok = ok and decide("git pull . exec/x", tmp, log) is not None
    open(log, "a").write(tip[:12] + " PENDING 2026-10-16 x\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is not None
    open(log, "a").write(tip[:12] + " PASSED 2026-10-16 reviewer\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is not None
    open(log, "a").write(tip[:12] + " WAIVED 2026-10-16 four words of reason\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is not None
    open(log, "a").write(tip[:12] + " PASS 2026-10-16 reviewer\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is None
    ok = ok and decide("git pull . exec/x", tmp, log) is None
    ok = ok and decide("git log exec/x", tmp, log) is None and decide("git merge main", tmp, log) is None
    open(log, "a").write(tip[:12] + " REVISE 2026-10-16 reviewer asked changes\n")
    ok = ok and decide("git merge --no-ff exec/x", tmp, log) is not None
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
