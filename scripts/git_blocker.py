#!/usr/bin/env python3
"""Classify a git blocker from read-only evidence and name the safe, deterministic recovery.

    python3 scripts/git_blocker.py              # diagnose this checkout (read-only git commands only)
    python3 scripts/git_blocker.py --text "<stderr of a failed git command>"
    python3 scripts/git_blocker.py --json

Owner 2026-10-23: a session reported itself "blocked on a git pull" for many turns; the pull had in
fact succeeded. This script turns "blocked on git" into one of a fixed set of kinds, each with a
recovery that never resets, force-pushes, discards changes or skips a review gate. Kinds whose
recovery is mechanical are marked deterministic, so the loop guard (scripts/loop_guard.py) does not
spend an architecture escalation on them.

Read-only: only `git status`, `rev-parse`, `rev-list`, `ls-files`, `worktree list` are run.
"""
import json
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# kind -> (deterministic, recovery). Recoveries are deliberately non-destructive.
RECOVERY = {
    "clean": (True, "Nothing blocks git. Do not report a git blocker; continue the task."),
    "uncommitted_changes": (True, "Commit the work if it is verified, or `git stash push -u -m <why>` and "
                            "`git stash pop` after the pull. Never discard the changes (no checkout-dash-dash, no hard reset)."),
    "merge_conflict": (False, "Resolve each unmerged path keeping both sides' intent, run the tests, then "
                       "`git add` + `git commit`. If intent is ambiguous, `git merge --abort` (restores the "
                       "pre-merge tree, keeps local commits) and escalate. Never `checkout --theirs/--ours` "
                       "wholesale."),
    "diverged": (True, "Integrate with `git pull --no-rebase` (a merge commit), never rebase or force-push a "
                 "shared branch."),
    "behind": (True, "`git pull --ff-only`."),
    "ahead": (True, "`git push -u origin <branch>` (retry 2/4/8/16 s on network errors)."),
    "no_upstream": (True, "`git push -u origin <branch>` to create it, or `git branch --set-upstream-to "
                    "origin/<branch>` if the remote branch exists."),
    "detached_head": (True, "`git switch <branch>` (or `git switch -c <new-branch>` to keep commits)."),
    "auth_failure": (False, "Credentials are the owner's: queue it as an owner item and continue other work. "
                     "Never paste or print a token."),
    "permission_denied": (False, "The remote or a hook refused this: do not retry or work around it; record "
                          "it and continue independent work, or ask the owner once."),
    "network": (True, "Retry with backoff 2/4/8/16 s; after that record it and continue offline work."),
    "worktree_mismatch": (True, "`git worktree list`; work in the checkout that owns the branch, or "
                          "`git worktree prune` for a deleted path. Never `worktree remove --force` over work."),
    "hook_rejection": (False, "A repository hook refused the command: read its message and satisfy the "
                       "gate (review record, executor merge). Never bypass it with --no-verify."),
    "unknown": (False, "Unrecognised: collect `git status`, the exact stderr, and escalate."),
}

TEXT_RULES = [
    ("merge_conflict", r"CONFLICT \(|Automatic merge failed|unmerged files|you need to resolve your current index"),
    ("uncommitted_changes", r"would be overwritten by (merge|checkout)|commit your changes or stash them|"
                            r"cannot pull with rebase: You have unstaged changes"),
    ("diverged", r"have diverged|Need to specify how to reconcile divergent branches|Not possible to fast-forward"),
    ("no_upstream", r"no tracking information|has no upstream branch|no such ref was fetched|"
                    r"couldn't find remote ref"),
    ("auth_failure", r"Authentication failed|could not read Username|HTTP 401|invalid credentials"),
    ("permission_denied", r"HTTP 403|Permission to .* denied|Permission denied \(publickey\)|"
                          r"protected branch|denied by the permission"),
    ("hook_rejection", r"pre-receive hook declined|hook declined|PreToolUse.*(block|denied)|"
                       r"blocked by hook|refused by .*hook"),
    ("network", r"Could not resolve host|Connection (timed out|reset|refused)|RPC failed|"
                r"unexpected disconnect|early EOF|Failed to connect|HTTP 50[234]"),
    ("worktree_mismatch", r"is already checked out at|already used by worktree|is not a working tree"),
    ("detached_head", r"You are not currently on a branch|HEAD detached"),
]


def classify_text(text):
    for kind, pattern in TEXT_RULES:
        if re.search(pattern, text or "", re.IGNORECASE):
            return kind
    return "unknown"


def _git(repo, *args):
    p = subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, timeout=30)
    return p.returncode, p.stdout


def diagnose(repo=REPO):
    """Read-only state of a checkout -> list of (kind, detail). Never modifies the repository."""
    found = []
    rc, porcelain = _git(repo, "status", "--porcelain=v1", "--branch")
    if rc != 0:
        return [("worktree_mismatch", "git status failed: not a usable working tree")]
    lines = porcelain.splitlines()
    head = lines[0] if lines else ""
    body = lines[1:]
    unmerged = [l[3:] for l in body if l[:2] in ("UU", "AA", "DD", "AU", "UA", "DU", "UD")]
    if unmerged:
        found.append(("merge_conflict", "%d unmerged path(s)" % len(unmerged)))
    elif any(l and not l.startswith("??") for l in body):
        found.append(("uncommitted_changes", "%d changed path(s)" % sum(1 for l in body if not l.startswith("??"))))
    if "HEAD (no branch)" in head or "no branch" in head:
        found.append(("detached_head", head))
    elif "..." not in head and "No commits yet" not in head:
        found.append(("no_upstream", head.replace("## ", "")))
    else:
        m = re.search(r"\[ahead (\d+), behind (\d+)\]", head)
        if m:
            found.append(("diverged", "ahead %s, behind %s" % m.groups()))
        elif "[behind" in head:
            found.append(("behind", head.split("[", 1)[1].rstrip("]")))
        elif "[ahead" in head:
            found.append(("ahead", head.split("[", 1)[1].rstrip("]")))
    return found or [("clean", head.replace("## ", ""))]


def report(kinds):
    return [{"kind": k, "detail": d, "deterministic": RECOVERY[k][0], "recovery": RECOVERY[k][1]}
            for k, d in kinds]


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if "--text" in argv:
        text = argv[argv.index("--text") + 1]
        kinds = [(classify_text(text), text[:200])]
    else:
        kinds = diagnose()
    rows = report(kinds)
    if "--json" in argv:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    else:
        for r in rows:
            print("%-20s %s\n  recovery: %s" % (r["kind"], r["detail"], r["recovery"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
