#!/usr/bin/env python3
"""Audit: module source must arrive through executor merges (AGENTS.md hard rule, owner 2026-10-16).

Lists non-merge commits on HEAD's first-parent chain since the baseline that touch <module>/src/**.
Executor work arrives as the second parent of a merge and is not flagged. Prints `sha subject` only.
Exit 1 on any violation.
"""
import os
import shutil
import subprocess
import sys
import tempfile

BASELINE = "860371129f130852c918d99d49fb79bcb4e7b4bf"
MODULES = ("app", "behavior-test", "contracts", "demo", "evaluation", "feedback", "ingress",
           "orchestration", "safety", "simulator", "vehicle", "verification")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def git(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), capture_output=True, text=True,
                          check=True).stdout


def violations(repo=REPO, baseline=BASELINE):
    out = git(repo, "log", "--first-parent", "--no-merges", "--format=%H",
              "%s..HEAD" % baseline, "--", *["%s/src" % m for m in MODULES])
    result = []
    for sha in out.split():
        subject = git(repo, "log", "-1", "--format=%s", sha).strip()
        result.append("%s %s" % (sha[:12], subject))
    return result


REVIEW_LOG = os.path.join(REPO, "state", "reviews.log")


def review_status(repo=REPO, baseline=BASELINE, log_path=REVIEW_LOG):
    """(missing, pending): executor merges since the baseline with no review line / a PENDING one."""
    out = git(repo, "log", "--first-parent", "--merges", "--format=%H %s", "%s..HEAD" % baseline)
    entries = {}
    if os.path.exists(log_path):
        for line in open(log_path, encoding="utf-8"):
            parts = line.split()
            if parts and not line.startswith("#") and len(parts) >= 2:
                entries[parts[0]] = parts[1]
    missing, pending = [], []
    for line in out.splitlines():
        sha, _, subject = line.partition(" ")
        if not subject.startswith("Merge exec/"):
            continue
        status = next((v for k, v in entries.items() if sha.startswith(k)), None)
        if status is None:
            missing.append("%s %s" % (sha[:12], subject))
        elif status == "PENDING":
            pending.append("%s %s" % (sha[:12], subject))
    return missing, pending


def stop_hook():
    """Stop hook (official docs: decision "block" keeps Claude working): a direct source commit, or an
    executor merge with no review line, blocks the end of the turn. PENDING does not block."""
    try:
        v = violations()
        missing, _ = review_status()
    except Exception:  # never wedge a session on a broken check
        return 0
    if v or missing:
        reason = []
        if v:
            reason.append("module source committed outside an executor merge: " + "; ".join(v))
        if missing:
            reason.append("executor merges with no review record in state/reviews.log (run the reviewer, "
                          "then add PASS, or WAIVED with a reason): " + "; ".join(missing))
        import json
        print(json.dumps({"decision": "block", "reason": " | ".join(reason)}, ensure_ascii=False))
    return 0


def selftest():
    tmp = tempfile.mkdtemp()
    try:
        env = ["-c", "user.name=t", "-c", "user.email=t@t"]

        def g(*a):
            return subprocess.run(["git", "-C", tmp] + env + list(a), capture_output=True,
                                  text=True, check=True).stdout.strip()

        def commit(path, msg):
            full = os.path.join(tmp, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "a") as f:
                f.write(msg + "\n")
            g("add", "-A")
            g("commit", "-q", "-m", msg)

        g("init", "-q", "-b", "main")
        commit("README.md", "base")
        base = g("rev-parse", "HEAD")
        commit("docs/a.md", "docs only")
        g("checkout", "-q", "-b", "exec/t")
        commit("app/src/main/A.kt", "executor work")
        g("checkout", "-q", "main")
        g("merge", "-q", "--no-ff", "-m", "merge exec", "exec/t")
        ok = violations(tmp, base) == []
        commit("safety/src/test/B.kt", "planner wrote source")
        v = violations(tmp, base)
        ok = ok and len(v) == 1 and v[0].endswith("planner wrote source")
        g("checkout", "-q", "-b", "exec/u", base)
        commit("app/src/main/C.kt", "more executor work")
        g("checkout", "-q", "main")
        g("merge", "-q", "--no-ff", "-m", "Merge exec/u: thing", "exec/u")
        log = os.path.join(tmp, "reviews.log")
        open(log, "w").write("# header\n")
        missing, pending = review_status(tmp, base, log)
        ok = ok and len(missing) == 1 and missing[0].endswith("Merge exec/u: thing")
        open(log, "a").write(g("rev-parse", "HEAD")[:12] + " PENDING 2026-10-16 x\n")
        missing, pending = review_status(tmp, base, log)
        ok = ok and missing == [] and len(pending) == 1
        print("delegation_check selftest: %s" % ("ok" if ok else "FAIL %r" % v))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--selftest" in sys.argv:
        return selftest()
    if "--stop-hook" in sys.argv:
        return stop_hook()
    v = violations()
    for line in v:
        print(line)
    if v:
        print("%d commit(s) wrote module source on the working branch outside an executor merge"
              % len(v))
        return 1
    missing, pending = review_status()
    for line in missing:
        print("NO REVIEW " + line)
    for line in pending:
        print("REVIEW PENDING " + line)
    if missing:
        print("%d executor merge(s) without a review record (state/reviews.log)" % len(missing))
        return 1
    print("delegation_check: no direct module-source commits since baseline; every executor merge has a review record")
    return 0


if __name__ == "__main__":
    sys.exit(main())
