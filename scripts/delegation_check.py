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
        print("delegation_check selftest: %s" % ("ok" if ok else "FAIL %r" % v))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    if "--selftest" in sys.argv:
        return selftest()
    v = violations()
    for line in v:
        print(line)
    if v:
        print("%d commit(s) wrote module source on the working branch outside an executor merge"
              % len(v))
        return 1
    print("delegation_check: no direct module-source commits since baseline")
    return 0


if __name__ == "__main__":
    sys.exit(main())
