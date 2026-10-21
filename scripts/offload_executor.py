#!/usr/bin/env python3
"""Cheap external-model executor: run Aider (OpenRouter model) on a contract's approved files only,
then the contract's required checks, retrying with the failure output.

  offload_executor.py CONTRACT --worktree W [--model M] [--max-rounds N] [--max-cost USD] [--dry-run]

Never commits (the caller commits). The API key is never printed or logged.
"""
import argparse
import os
import posixpath
import re
import shlex
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import task_contract as tc  # noqa: E402

DEFAULT_MODEL = "deepseek/deepseek-chat"
BLOCKED_EXT = (".jar", ".aar", ".so", ".zip", ".apk", ".keystore", ".jks", ".mp3", ".wav")
COST_RE = re.compile(r"Cost:.*?\$\s*([0-9]+(?:\.[0-9]+)?)\s+session", re.I)
TAIL = 60
CHECK_TIMEOUT = 1800
AIDER_TIMEOUT = 1800


def _git(wt, *args, check=True):
    return subprocess.run(["git", "-C", wt] + list(args), capture_output=True, text=True, check=check)


def file_list(c, wt):
    tracked = [p for p in _git(wt, "ls-files", "-z").stdout.split("\0") if p]
    approved = c.get("approved_paths") or []
    files = [p for p in tracked if any(tc.match(p, g) for g in approved)]
    for g in approved:
        if not any(ch in g for ch in "*?") and not g.endswith("/") and g not in files \
                and not os.path.exists(os.path.join(wt, g)):
            files.append(g)
    forbidden = list(tc.PROTECTED) + list(c.get("forbidden_paths") or [])
    return sorted(p for p in files if not any(tc.match(p, g) for g in forbidden))


def refusals(files, wt):
    errs = []
    for p in files:
        n = posixpath.normpath(p.replace("\\", "/"))
        if n.startswith("/") or n == ".." or n.startswith("../"):
            errs.append("%s: not repo-relative" % p)
            continue
        low = n.lower()
        parts = low.split("/")
        if low.endswith(BLOCKED_EXT):
            errs.append("%s: binary/archive" % p)
        elif parts[-1] == "local.properties":
            errs.append("%s: local.properties" % p)
        elif low.startswith("app/libs/") or any("vendor" in d for d in parts[:-1]):
            errs.append("%s: vendor/app-libs path" % p)
    if files:
        r = subprocess.run(["git", "-C", wt, "check-ignore", "--no-index", "--"] + files,
                           capture_output=True, text=True)
        if r.returncode not in (0, 1):
            errs.append("cannot check ignore status (git check-ignore exit %d)" % r.returncode)
        errs += ["%s: gitignored" % p for p in r.stdout.splitlines() if p]
    return errs


def task_message(c):
    def sec(name, key):
        items = c.get(key) or []
        return "%s:\n%s\n" % (name, "\n".join("- %s" % i for i in items)) if items else ""
    return ("Task %s: %s\n\n" % (c.get("task_id"), c.get("title", ""))
            + sec("Allowed changes", "allowed_changes")
            + sec("Architecture constraints", "architecture_constraints")
            + sec("Forbidden changes", "forbidden_changes")
            + sec("Acceptance criteria", "acceptance_criteria")
            + "\nEdit only the files you were given. Do not create, modify or delete any other file.")


def aiderignore_text(files):
    """Ignore everything, then un-ignore exactly each listed file. No directory negations: Aider
    matches full paths with GitWildMatchPattern, so "!/dir/" would re-include the whole subtree."""
    return "\n".join(["*", "**"] + ["!/%s" % posixpath.normpath(f) for f in files]) + "\n"


def run_aider(wt, model, msg, files):
    env = dict(os.environ)
    if not env.get("OPENROUTER_API_KEY"):
        env["OPENROUTER_API_KEY"] = "placeholder-proxy-injects"
    with tempfile.TemporaryDirectory(prefix="offload-") as tmp:
        ignore = os.path.join(tmp, "aiderignore")
        with open(ignore, "w") as f:
            f.write(aiderignore_text(files))
        cmd = [os.environ.get("NOVA_AIDER_BIN", "aider"), "--model", "openrouter/" + model,
               "--yes-always", "--no-auto-commits", "--no-gitignore", "--map-tokens", "0",
               "--no-analytics", "--no-check-update", "--no-show-model-warnings", "--no-pretty",
               "--no-detect-urls", "--aiderignore", ignore,
               "--chat-history-file", os.path.join(tmp, "chat.md"),
               "--input-history-file", os.path.join(tmp, "input"), "--message", msg] + files
        try:
            r = subprocess.run(cmd, cwd=wt, env=env, stdin=subprocess.DEVNULL, capture_output=True,
                               text=True, timeout=AIDER_TIMEOUT)
            out = r.stdout + r.stderr
        except (OSError, subprocess.TimeoutExpired) as e:
            out = "aider failed: %s" % type(e).__name__
    costs = COST_RE.findall(out)
    return float(costs[-1]) if costs else 0.0


def restore_strays(wt, files, before):
    """Undo changes Aider made outside the file list since `before` (set of dirty paths)."""
    allowed = set(files)
    st = _git(wt, "status", "--porcelain", "-z", "--untracked-files=all").stdout.split("\0")
    n = 0
    for e in st:
        if len(e) < 4:
            continue
        code, p = e[:2], e[3:]
        if p in allowed or p in before:
            continue
        in_head = _git(wt, "cat-file", "-e", "HEAD:" + p, check=False).returncode == 0
        full = os.path.join(wt, p)
        if not in_head:
            if code != "??":
                _git(wt, "rm", "-q", "--cached", "--ignore-unmatch", "--", p, check=False)
            try:
                os.remove(full)
            except FileNotFoundError:
                pass
            except OSError:
                continue
            ok = not os.path.lexists(full)
        else:
            ok = _git(wt, "checkout", "HEAD", "--", p, check=False).returncode == 0
        n += ok
    return n


def _dirty(wt):
    st = _git(wt, "status", "--porcelain", "-z", "--untracked-files=all").stdout.split("\0")
    return {e[3:] for e in st if len(e) >= 4}


def run_checks(c, wt):
    """[(id, ok, output)] for required_verification plus scope."""
    _, errs = tc.scope(c, wt, worktree=wt)
    res = [("scope", not errs, "task_contract scope", "\n".join(errs))]
    env = {k: v for k, v in os.environ.items() if k != "OPENROUTER_API_KEY"}
    for v in c.get("required_verification") or []:
        try:
            r = subprocess.run(shlex.split(v["command"]), cwd=wt, stdin=subprocess.DEVNULL, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               timeout=CHECK_TIMEOUT)
            res.append((v["id"], r.returncode == 0, v["command"], r.stdout))
        except (OSError, subprocess.TimeoutExpired) as e:
            res.append((v["id"], False, v["command"], "cannot run: %s" % e))
    return res


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("contract")
    ap.add_argument("--worktree", required=True)
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--max-rounds", type=int, default=3)
    ap.add_argument("--max-cost", type=float, default=0.20)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args(argv)
    c, _ = tc.load(a.contract)
    tid = c.get("task_id", "?") if isinstance(c, dict) else "?"
    wt = os.path.abspath(a.worktree)
    started = time.monotonic()

    def status(state, rounds=0, cost=0.0, checks="", stray=0):
        print("OFFLOAD %s %s rounds=%d cost_usd=%.4f seconds=%d stray=%d checks=%s"
              % (tid, state, rounds, cost, time.monotonic() - started, stray, checks))
        return 0 if state == "DONE" else (2 if state == "REFUSED" else 1)

    problems = tc.validate(c)
    if problems:
        for e in problems:
            print("REFUSED: contract: " + e)
        return status("REFUSED")
    r = _git(wt, "rev-parse", "--abbrev-ref", "HEAD", check=False)
    if r.returncode != 0:
        print("REFUSED: %s is not a git worktree" % wt)
        return status("REFUSED")
    if r.stdout.strip() != c.get("branch"):
        print("REFUSED: branch %s != contract branch %s" % (r.stdout.strip(), c.get("branch")))
        return status("REFUSED")
    files = file_list(c, wt)
    errs = refusals(files, wt)
    if errs or not files:
        for e in errs or ["no files to edit"]:
            print("REFUSED: " + e)
        return status("REFUSED")
    msg = task_message(c)
    if a.dry_run:
        print("FILES:\n" + "\n".join("  " + f for f in files))
        print("MESSAGE:\n" + msg)
        return 0

    cost, stray, checks, rnd = 0.0, 0, "", 0
    while rnd < a.max_rounds and cost <= a.max_cost:
        rnd += 1
        before = _dirty(wt)
        cost += run_aider(wt, a.model, msg, files)
        stray += restore_strays(wt, files, before)
        res = run_checks(c, wt)
        checks = ",".join("%s:%s" % (i, "PASS" if ok else "FAIL") for i, ok, _, _ in res)
        bad = next((x for x in res if not x[1]), None)
        if not bad:
            return status("DONE", rnd, cost, checks, stray)
        tail = "\n".join(bad[3].splitlines()[-TAIL:])
        msg = ("The check `%s` failed. Fix it, editing only the given files.\n\nOutput (tail):\n%s"
               % (bad[2], tail))
    return status("FAILED", rnd, cost, checks, stray)


if __name__ == "__main__":
    sys.exit(main())
