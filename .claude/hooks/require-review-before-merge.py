#!/usr/bin/env python3
"""PreToolUse(Bash): an executor branch is merged only after its review (owner 2026-10-16).

`git merge ... <ref containing exec/>` or `git pull . exec/<name>` is refused unless the last valid
line in state/reviews.log for the tip of the ref actually being merged (e.g. origin/exec/x, not the
local exec/x) is PASS, or WAIVED on a branch that changes no production module source (grammar shared
with scripts/delegation_check.py; invalid lines and ambiguous keys never count). `git pull <remote>
exec/<name>` from a real remote is always refused: fetch, review the fetched tip, then merge it.
Advisory only: `delegation_check.py --ci` is the enforcement. Reviews happen after the executor finishes and before
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


_VALUE_OPTS = {"-m", "-F", "-s", "-X", "--message", "--file", "--strategy", "--strategy-option",
               "--cleanup", "-S", "--gpg-sign", "--into-name"}


def _invocations(command):
    """(verb, operands) for each `git ... merge|pull ...` segment of a shell command."""
    import shlex
    for seg in re.split(r"[;&|\n]+", command):
        try:
            toks = shlex.split(seg)
        except ValueError:
            toks = seg.split()
        if "git" not in toks:
            continue
        toks = toks[toks.index("git") + 1:]
        verb = next((t for t in toks if t in ("merge", "pull")), None)
        if verb is None:
            continue
        rest, operands, skip = toks[toks.index(verb) + 1:], [], False
        for t in rest:
            if skip:
                skip = False
            elif t.startswith("-"):
                skip = t in _VALUE_OPTS
            else:
                operands.append(t)
        yield verb, operands


def decide(command, repo, log_path):
    if not re.search(r"\bgit\b[^;&|]*\b(merge|pull)\b", command) or "exec/" not in command:
        return None
    refs = []
    for verb, operands in _invocations(command):
        if verb == "pull":
            remote, specs = (operands[0], operands[1:]) if operands else (None, [])
            specs = [x for x in specs if "exec/" in x]
            if specs and remote != ".":
                return ("git pull %s %s: fetch, review the fetched tip, then merge it."
                        % (remote, " ".join(specs)))
            refs += [x.split(":")[0].lstrip("+") for x in specs]
        else:
            refs += [x for x in operands if "exec/" in x]
    if not refs:
        return None
    dc = _gate()
    records, _ = dc.parse_log(log_path)  # invalid lines never count
    for ref in refs:
        tip = dc.resolve(repo, ref)
        if tip is None:
            return None
        rec, _ = dc.lookup(records, [tip], repo)  # last valid line wins; ambiguous keys refused
        status = rec[1] if rec else None
        if status == "WAIVED":
            try:
                mb = dc.git(repo, "merge-base", "HEAD", tip).strip()
                if dc.touches(dc.changed(repo, mb, tip), "main/"):
                    return ("%s (tip %s) is WAIVED but changes production module source; it needs a "
                            "reviewer PASS." % (ref, tip[:12]))
            except Exception:
                return None
            continue
        if status != "PASS":
            return ("%s (tip %s) has no accepted review in state/reviews.log (latest: %s). Run the reviewer "
                    "on the branch first, then record '<tip-sha> PASS <yyyy-mm-dd> <note>' (or WAIVED with "
                    "a reason of 4+ words, non-production source only) and merge."
                    % (ref, tip[:12], status or "none"))
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
    ok = ok and decide("git pull origin exec/x", tmp, log) is not None
    run("update-ref", "refs/remotes/origin/exec/x", tip)
    ok = ok and decide("git merge refs/remotes/origin/exec/x", tmp, log) is not None
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
