#!/usr/bin/env python3
"""Task contracts: architect -> executor -> independent reviewer, checked from git, not from self-report.

    python scripts/task_contract.py schema
    python scripts/task_contract.py validate state/contracts/<ID>.yaml
    python scripts/task_contract.py approve  state/contracts/<ID>.yaml --role architect --note "..."
    python scripts/task_contract.py scope    state/contracts/<ID>.yaml --worktree /home/user/nova-wt/<ID>
    python scripts/task_contract.py verify   state/contracts/<ID>.yaml --worktree /home/user/nova-wt/<ID>
    python scripts/task_contract.py eligible state/contracts/<ID>.yaml (--worktree W | --ref exec/<branch>)
    python scripts/task_contract.py handoff  state/contracts/<ID>.yaml (--worktree W | --ref R) --next "..."

Flow (docs/AGENT_CONTRACT_WORKFLOW.md): the architect/planner writes the contract in the main checkout
and approves it (a separate step that records the contract's sha256 in state/contracts/approvals.log;
any later edit invalidates the approval). The executor implements it in its own worktree. `scope`
compares every committed, staged, unstaged and untracked path since base_commit with approved_paths.
The planner or reviewer runs `verify`, which executes every required_verification command on the
clean executor tip and writes state/verification/<ID>.json (full logs stay outside the repo). The
reviewer records PASS for the tip in state/reviews.log. `eligible` says ELIGIBLE only when all of that
holds for the current tip; it never merges.

These are safeguards, not tamper-proof security: anyone with write access can edit the records. The
executor-side hook (.claude/hooks/guard-contract-writes.py) and these checks catch mistakes and
drift; branch protection, CODEOWNERS and the CI unittests are the independent layer.
Exit codes: 0 ok / ELIGIBLE, 1 usage or invalid input, 2 check failed / NOT_ELIGIBLE.
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
import re
import shlex
import subprocess
import sys
import time

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_ID = "nova-task-contract/1"
APPROVER_ROLES = ("architect", "planner", "owner")
VERIFY_KINDS = ("compile", "regression", "integration", "device", "lint")
# Executors may never change these, whatever a contract approves (records and the contracts themselves).
PROTECTED = ("state/contracts/**", "state/verification/**", "state/reviews.log", "state/handoff/**")
_TASK_ID = re.compile(r"[A-Z][A-Z0-9]*(?:-[A-Za-z0-9]+)+", re.ASCII)
_SHA = re.compile(r"[0-9a-f]{40}", re.ASCII)

SCHEMA = {
    "schema": "const %s" % SCHEMA_ID,
    "task_id": "string, e.g. TC-1 or P67-D (letters/digits, dash-separated)",
    "title": "string",
    "branch": "string, the executor branch, exec/<name>",
    "base_commit": "40-hex sha the executor branch starts from",
    "approved_paths": "non-empty list of repo-relative globs (* within a segment, ** across, dir/ = dir/**)",
    "allowed_changes": "non-empty list of strings: what may change",
    "architecture_constraints": "non-empty list of strings: owners, ADRs, invariants that bind",
    "forbidden_changes": "list of strings: what must not change",
    "forbidden_paths": "list of globs; wins over approved_paths (PROTECTED paths are always forbidden)",
    "required_verification": "non-empty list of {id, kind in %s, command}" % "|".join(VERIFY_KINDS),
    "acceptance_criteria": "non-empty list of strings",
}
_LISTS = ("approved_paths", "allowed_changes", "architecture_constraints", "acceptance_criteria")


# ---------------------------------------------------------------- contract

def load(path):
    with open(path, "rb") as f:
        raw = f.read()
    return yaml.safe_load(raw.decode("utf-8")), hashlib.sha256(raw).hexdigest()


def _bad_glob(g):
    return (not isinstance(g, str) or not g.strip() or g.startswith(("/", "\\")) or re.match(r"[A-Za-z]:", g)
            or ".." in g.replace("\\", "/").split("/"))


def validate(c):
    """List of problems; empty means the contract is well-formed."""
    if not isinstance(c, dict):
        return ["contract must be a mapping"]
    errs = []
    unknown = sorted(set(c) - set(SCHEMA))
    if unknown:
        errs.append("unknown keys: %s" % ", ".join(unknown))
    if c.get("schema") != SCHEMA_ID:
        errs.append("schema must be %s" % SCHEMA_ID)
    if not isinstance(c.get("task_id"), str) or not _TASK_ID.fullmatch(c["task_id"]):
        errs.append("task_id missing or malformed")
    for key in ("title", "branch"):
        if not isinstance(c.get(key), str) or not c[key].strip():
            errs.append("%s missing" % key)
    if isinstance(c.get("branch"), str) and not c["branch"].startswith("exec/"):
        errs.append("branch must be exec/<name>")
    if not isinstance(c.get("base_commit"), str) or not _SHA.fullmatch(c["base_commit"]):
        errs.append("base_commit must be a full 40-hex sha")
    for key in _LISTS:
        v = c.get(key)
        if not isinstance(v, list) or not v or not all(isinstance(x, str) and x.strip() for x in v):
            errs.append("%s must be a non-empty list of strings" % key)
    for key in ("forbidden_changes", "forbidden_paths"):
        v = c.get(key, [])
        if not isinstance(v, list) or not all(isinstance(x, str) and x.strip() for x in v):
            errs.append("%s must be a list of strings" % key)
    for key in ("approved_paths", "forbidden_paths"):
        for g in c.get(key) or []:
            if _bad_glob(g):
                errs.append("%s entry %r must be repo-relative without '..'" % (key, g))
    for g in c.get("approved_paths") or []:
        if isinstance(g, str) and any(match(p.replace("**", "x"), g) or match(g.rstrip("*/") + "/x", p)
                                      for p in PROTECTED):
            errs.append("approved_paths entry %r covers a protected record path" % g)
    ver = c.get("required_verification")
    if not isinstance(ver, list) or not ver:
        errs.append("required_verification must be a non-empty list")
    else:
        ids = []
        for i, v in enumerate(ver):
            if not isinstance(v, dict) or set(v) != {"id", "kind", "command"}:
                errs.append("required_verification[%d] needs exactly id, kind, command" % i)
                continue
            if not isinstance(v["id"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", v["id"]):
                errs.append("required_verification[%d].id malformed" % i)
            if v["kind"] not in VERIFY_KINDS:
                errs.append("required_verification[%d].kind must be one of %s" % (i, ", ".join(VERIFY_KINDS)))
            if not isinstance(v["command"], str) or not v["command"].strip():
                errs.append("required_verification[%d].command missing" % i)
            ids.append(v["id"])
        if len(ids) != len(set(ids)):
            errs.append("required_verification ids must be unique")
    return errs


# ---------------------------------------------------------------- globs and git

def _glob_re(g):
    g = g.replace("\\", "/")
    if g.endswith("/"):
        g += "**"
    out, i = "", 0
    while i < len(g):
        if g.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif g.startswith("**", i):
            out, i = out + ".*", i + 2
        elif g[i] == "*":
            out, i = out + "[^/]*", i + 1
        elif g[i] == "?":
            out, i = out + "[^/]", i + 1
        else:
            out, i = out + re.escape(g[i]), i + 1
    return re.compile(out + r"\Z")


def match(path, glob):
    return bool(_glob_re(glob).match(path))


def git(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), capture_output=True, text=True,
                          check=True).stdout


def changed_paths(repo, base, tip=None, worktree=None):
    """Paths changed since base: committed up to tip (ref mode), or committed + staged + unstaged +
    untracked (non-ignored) in a worktree. Renames count as delete + add."""
    if worktree:
        out = git(worktree, "diff", "--name-only", "--no-renames", base)
        out += git(worktree, "diff", "--cached", "--name-only", "--no-renames", base)
        out += git(worktree, "ls-files", "--others", "--exclude-standard")
    else:
        out = git(repo, "diff", "--name-only", "--no-renames", base, tip)
    return sorted({p for p in out.splitlines() if p})


def scope(c, repo, tip=None, worktree=None):
    """(changed paths, problems)."""
    where = worktree or repo
    head = git(where, "rev-parse", "HEAD").strip() if worktree else tip
    if subprocess.run(["git", "-C", where, "merge-base", "--is-ancestor", c["base_commit"], head],
                      capture_output=True).returncode != 0:
        return [], ["base_commit %s is not an ancestor of %s" % (c["base_commit"][:12], head[:12])]
    paths = changed_paths(repo, c["base_commit"], head, worktree)
    forbidden = list(PROTECTED) + list(c.get("forbidden_paths") or [])
    errs = []
    for p in paths:
        hit = next((g for g in forbidden if match(p, g)), None)
        if hit:
            errs.append("%s: forbidden (%s)" % (p, hit))
        elif not any(match(p, g) for g in c["approved_paths"]):
            errs.append("%s: not in approved_paths" % p)
    return paths, errs


# ---------------------------------------------------------------- records

def approvals_path(repo):
    return os.path.join(repo, "state", "contracts", "approvals.log")


def latest_approval(repo, task_id):
    """(sha256, role, date) of the last valid approval line for task_id, or None."""
    found = None
    path = approvals_path(repo)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            lines = f.readlines()
        for line in lines:
            parts = line.split()
            if (len(parts) >= 6 and not line.startswith("#") and parts[0] == task_id
                    and parts[2] == "APPROVED" and re.fullmatch(r"[0-9a-f]{64}", parts[1])
                    and parts[4] in APPROVER_ROLES):
                found = (parts[1], parts[4], parts[3])
    return found


def _read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def verification_path(repo, task_id):
    return os.path.join(repo, "state", "verification", task_id + ".json")


def logs_dir(task_id):
    root = os.environ.get("NOVA_BUILD_DIR") or os.path.join(os.path.expanduser("~"), "nova-drive-build")
    return os.path.join(root, "nova-verify", task_id)


def _review_gate():
    spec = importlib.util.spec_from_file_location("delegation_check",
                                                  os.path.join(REPO, "scripts", "delegation_check.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def review_status(repo, tip):
    dc = _review_gate()
    records, _ = dc.parse_log(os.path.join(repo, "state", "reviews.log"))
    rec, _ = dc.lookup(records, [tip], repo)
    return rec[1] if rec else None


# ---------------------------------------------------------------- commands

def _target(c, repo, args):
    """(tip, worktree or None)."""
    if args.worktree:
        return git(args.worktree, "rev-parse", "HEAD").strip(), os.path.abspath(args.worktree)
    ref = args.ref or c["branch"]
    return git(repo, "rev-parse", "--verify", ref + "^{commit}").strip(), None


def cmd_approve(c, digest, repo, args):
    if os.environ.get("NOVA_AGENT_ROLE", "").lower() == "executor" or args.role not in APPROVER_ROLES:
        print("REFUSED: an executor cannot approve a contract (role must be %s)" % "|".join(APPROVER_ROLES))
        return 2
    if len(args.note.split()) < 2:
        print("REFUSED: --note needs at least 2 words")
        return 1
    os.makedirs(os.path.dirname(approvals_path(repo)), exist_ok=True)
    with open(approvals_path(repo), "a", encoding="utf-8") as f:
        f.write("%s %s APPROVED %s %s %s\n" % (c["task_id"], digest, datetime.date.today().isoformat(),
                                              args.role, " ".join(args.note.split())))
    print("APPROVED %s sha256=%s" % (c["task_id"], digest[:16]))
    return 0


def cmd_scope(c, repo, args):
    tip, wt = _target(c, repo, args)
    paths, errs = scope(c, repo, tip, wt)
    print("SCOPE %s %s: %d changed path(s)" % (c["task_id"], "FAIL" if errs else "PASS", len(paths)))
    for e in errs:
        print("  " + e)
    return 2 if errs else 0


def cmd_verify(c, digest, repo, args):
    if os.environ.get("NOVA_AGENT_ROLE", "").lower() == "executor":
        print("REFUSED: verification records are written by the planner or reviewer, not the executor")
        return 2
    tip, wt = _target(c, repo, args)
    if not wt:
        print("verify needs --worktree (commands run on the executor's checkout)")
        return 1
    if git(wt, "status", "--porcelain", "--untracked-files=normal").strip():
        print("REFUSED: worktree has uncommitted changes; verification must run on the committed tip")
        return 2
    os.makedirs(logs_dir(c["task_id"]), exist_ok=True)
    results, failed = [], 0
    for v in c["required_verification"]:
        log = os.path.join(logs_dir(c["task_id"]), "%s-%s.log" % (tip[:12], v["id"]))
        started = time.monotonic()
        with open(log, "wb") as out:
            try:
                code = subprocess.run(shlex.split(v["command"]), cwd=wt, stdout=out,
                                      stderr=subprocess.STDOUT).returncode
            except OSError as e:
                out.write(("cannot start: %s\n" % e).encode())
                code = 127
        with open(log, "rb") as f:
            data = f.read()
        tail = data.decode("utf-8", "replace").splitlines()[-5:]
        results.append({"id": v["id"], "kind": v["kind"], "command": v["command"], "exit_code": code,
                        "seconds": round(time.monotonic() - started, 1), "log": log,
                        "log_sha256": hashlib.sha256(data).hexdigest()})
        failed += code != 0
        print("VERIFY %-12s %-11s exit=%d %s" % (v["id"], v["kind"], code, "" if code == 0 else "| " + " / ".join(tail)[-300:]))
    # Untracked test output (build dirs, __pycache__) does not change what was verified; tracked edits do.
    if git(wt, "rev-parse", "HEAD").strip() != tip or git(wt, "status", "--porcelain", "--untracked-files=no").strip():
        print("REFUSED: the worktree changed while verifying; nothing recorded")
        return 2
    record = {"task_id": c["task_id"], "contract_sha256": digest, "tip": tip,
              "verified_by": os.environ.get("NOVA_AGENT_ROLE") or "planner",
              "at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
              "results": results}
    path = verification_path(repo, c["task_id"])
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(record, f, indent=1)
        f.write("\n")
    print("VERIFICATION %s %s tip=%s -> %s" % (c["task_id"], "FAIL" if failed else "PASS", tip[:12],
                                               os.path.relpath(path, repo)))
    return 2 if failed else 0


def eligibility(c, digest, repo, tip, worktree=None):
    """List of blocking reasons; empty means merge-eligible. Never merges."""
    why = ["contract invalid: " + e for e in validate(c)]
    if why:
        return why
    appr = latest_approval(repo, c["task_id"])
    if not appr:
        why.append("contract not approved (scripts/task_contract.py approve)")
    elif appr[0] != digest:
        why.append("contract changed after approval (approved %s, now %s)" % (appr[0][:12], digest[:12]))
    if worktree and git(worktree, "status", "--porcelain").strip():
        why.append("worktree has uncommitted changes")
    why += ["scope: " + e for e in scope(c, repo, tip, worktree)[1]]
    path = verification_path(repo, c["task_id"])
    try:
        rec = _read_json(path)
    except (OSError, ValueError):
        rec = None
    if not rec:
        why.append("no verification record (scripts/task_contract.py verify)")
    else:
        if rec.get("tip") != tip:
            why.append("verification is for %s, not the current tip %s" % (str(rec.get("tip"))[:12], tip[:12]))
        if rec.get("contract_sha256") != digest:
            why.append("verification ran against a different contract version")
        if str(rec.get("verified_by", "")).lower() == "executor":
            why.append("verification was recorded by the executor")
        got = {r.get("id"): r for r in rec.get("results") or []}
        for v in c["required_verification"]:
            r = got.get(v["id"])
            if r is None:
                why.append("required verification %r missing" % v["id"])
            elif r.get("command") != v["command"] or r.get("exit_code") != 0:
                why.append("required verification %r did not pass as specified" % v["id"])
    status = review_status(repo, tip)
    if status != "PASS":
        why.append("no independent reviewer PASS for %s in state/reviews.log (latest: %s)"
                   % (tip[:12], status or "none"))
    return why


def cmd_eligible(c, digest, repo, args):
    tip, wt = _target(c, repo, args)
    why = eligibility(c, digest, repo, tip, wt)
    print("%s %s tip=%s" % ("NOT_ELIGIBLE" if why else "ELIGIBLE", c["task_id"], tip[:12]))
    for w in why:
        print("  " + w)
    if not why:
        print("  merge is not automatic: the planner merges with `git merge --no-ff %s`" % c["branch"])
    return 2 if why else 0


def cmd_handoff(c, digest, repo, args):
    tip, wt = _target(c, repo, args)
    paths, scope_errs = scope(c, repo, tip, wt)
    why = eligibility(c, digest, repo, tip, wt)
    try:
        rec = _read_json(verification_path(repo, c["task_id"]))
    except (OSError, ValueError):
        rec = {}
    lines = ["# Handoff %s: %s" % (c["task_id"], c["title"]), "",
             "- tip: %s (%s), base %s, contract sha256 %s" % (tip[:12], c["branch"], c["base_commit"][:12], digest[:12]),
             "- changed (%d): %s" % (len(paths), ", ".join(paths[:20]) + (" ..." if len(paths) > 20 else "")),
             "- scope: %s" % ("PASS" if not scope_errs else "FAIL: " + "; ".join(scope_errs[:5])),
             "- verification: %s" % (", ".join("%s=%s" % (r["id"], r["exit_code"]) for r in rec.get("results", []))
                                     + " (tip %s)" % str(rec.get("tip"))[:12] if rec else "none"),
             "- review: %s" % (review_status(repo, tip) or "none"),
             "- merge: %s" % ("ELIGIBLE (not merged)" if not why else "NOT_ELIGIBLE: " + "; ".join(why[:6])),
             "- next: %s" % (args.next or "-"), ""]
    path = os.path.join(repo, "state", "handoff", c["task_id"] + ".md")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n".join(lines))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=("schema", "validate", "approve", "scope", "verify", "eligible", "handoff"))
    p.add_argument("contract", nargs="?")
    p.add_argument("--repo", default=REPO, help="main checkout holding contracts and records")
    p.add_argument("--worktree")
    p.add_argument("--ref")
    p.add_argument("--role", default="")
    p.add_argument("--note", default="")
    p.add_argument("--next", default="")
    args = p.parse_args(argv)
    if args.command == "schema":
        print(json.dumps(SCHEMA, indent=1))
        return 0
    if not args.contract:
        p.error("contract path required")
    try:
        c, digest = load(args.contract)
    except (OSError, yaml.YAMLError, UnicodeDecodeError) as e:
        print("INVALID: cannot read contract: %s" % type(e).__name__)
        return 1
    errs = validate(c)
    if args.command == "validate" or errs:
        print("%s %s" % ("INVALID" if errs else "VALID", (c or {}).get("task_id", "?") if isinstance(c, dict) else "?"))
        for e in errs:
            print("  " + e)
        return 1 if errs else 0
    repo = os.path.abspath(args.repo)
    try:
        if args.command == "approve":
            return cmd_approve(c, digest, repo, args)
        if args.command == "scope":
            return cmd_scope(c, repo, args)
        if args.command == "verify":
            return cmd_verify(c, digest, repo, args)
        if args.command == "eligible":
            return cmd_eligible(c, digest, repo, args)
        return cmd_handoff(c, digest, repo, args)
    except subprocess.CalledProcessError as e:
        print("git failed: %s" % " ".join(e.cmd[3:]))
        return 1


if __name__ == "__main__":
    sys.exit(main())
