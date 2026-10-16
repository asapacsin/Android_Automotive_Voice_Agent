#!/usr/bin/env python3
"""Audit: module source must arrive through reviewed executor merges (AGENTS.md hard rule, owner 2026-10-16).

Checks HEAD's first-parent chain since the baseline:
- a non-merge commit touching <module>/src/** is a violation (direct commit, fast-forward, cherry-pick);
- a merge touching <module>/src/** whose subject does not start with "Merge exec/" is a violation,
  unless its second parent is reachable from an upstream ref (a sync from the base branch);
- every "Merge exec/" merge needs an accepted record in state/reviews.log (grammar: parse_log).

Modes: default audit (exit 1 on violation/missing/invalid; unresolved only reported), --ci (exit 1 on
anything, never fails open), --stop-hook (fails open, never blocks on unresolved), --selftest.
Prints `sha subject` only.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

BASELINE = os.environ.get("NOVA_DELEGATION_BASELINE") or "860371129f130852c918d99d49fb79bcb4e7b4bf"
# WAIVED on a merge touching <module>/src/main/** is grandfathered only for merges in this commit's history.
REVIEW_CUTOFF = os.environ.get("NOVA_REVIEW_CUTOFF") or "1fb1ed2185777865b4f6d2fa21321efaab01d552"
DEFAULT_UPSTREAMS = ("origin/main", "origin/HEAD", "origin/claude/10-13")
MODULES = ("app", "behavior-test", "contracts", "demo", "evaluation", "feedback", "ingress",
           "orchestration", "safety", "simulator", "vehicle", "verification")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REVIEW_LOG = os.path.join(REPO, "state", "reviews.log")

STATUSES = ("PASS", "WAIVED", "PENDING", "REVISE", "REVISED")
_SHA = re.compile(r"^[0-9a-f]{7,40}$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def git(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), capture_output=True, text=True,
                          check=True).stdout


def git_ok(repo, *args):
    return subprocess.run(["git", "-C", repo] + list(args), capture_output=True, text=True).returncode == 0


def parse_line(line):
    """(sha, status, note_tokens) for a valid record, None for comment/blank, raises ValueError if invalid."""
    s = line.strip()
    if not s or s.startswith("#"):
        return None
    parts = s.split()
    if len(parts) < 4:
        raise ValueError("needs '<sha> <STATUS> <YYYY-MM-DD> <note>'")
    sha, status, date, note = parts[0], parts[1], parts[2], parts[3:]
    if not _SHA.match(sha):
        raise ValueError("bad sha %r" % sha)
    if status not in STATUSES:
        raise ValueError("unknown status %r" % status)
    if not _DATE.match(date):
        raise ValueError("bad date %r" % date)
    if status == "WAIVED" and len(note) < 4:
        raise ValueError("WAIVED needs a reason of at least 4 words")
    if status == "REVISED" and not _SHA.match(note[0]):
        raise ValueError("REVISED note must start with the fix branch tip sha")
    return sha, status, note


def parse_log(log_path):
    """(records in file order, invalid lines). The log is append-only: the last matching record wins."""
    records, invalid = [], []
    if os.path.exists(log_path):
        with open(log_path, encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                try:
                    r = parse_line(line)
                except ValueError as e:
                    invalid.append("line %d: %s: %s" % (n, e, line.strip()[:80]))
                    continue
                if r:
                    records.append(r)
    return records, invalid


def lookup(records, candidates):
    """Last valid record whose sha is a prefix of any candidate full sha."""
    found = None
    for r in records:
        if any(c.startswith(r[0]) for c in candidates):
            found = r
    return found


def changed(repo, parent, sha):
    return git(repo, "diff", "--name-only", parent, sha).split()


def touches(paths, sub=""):
    return any(p.startswith("%s/src/%s" % (m, sub)) for p in paths for m in MODULES)


def existing_refs(repo, refs):
    return [r for r in refs if git_ok(repo, "rev-parse", "--verify", "--quiet", r + "^{commit}")]


def violations(repo=REPO, baseline=BASELINE, upstreams=None):
    if upstreams is None:
        upstreams = existing_refs(repo, DEFAULT_UPSTREAMS)
    out = git(repo, "log", "--first-parent", "--no-merges", "--format=%H",
              "%s..HEAD" % baseline, "--", *["%s/src" % m for m in MODULES])
    result = []
    for sha in out.split():
        subject = git(repo, "log", "-1", "--format=%s", sha).strip()
        result.append("%s %s" % (sha[:12], subject))
    for line in git(repo, "log", "--first-parent", "--merges", "--format=%H %P|%s",
                    "%s..HEAD" % baseline).splitlines():
        shas, _, subject = line.partition("|")
        sha, *parents = shas.split()
        if subject.startswith("Merge exec/") or not touches(changed(repo, parents[0], sha)):
            continue
        if any(git_ok(repo, "merge-base", "--is-ancestor", p, u) for p in parents[1:] for u in upstreams):
            continue
        result.append("%s %s (non-exec merge of module source)" % (sha[:12], subject))
    return result


def review_findings(repo=REPO, baseline=BASELINE, log_path=REVIEW_LOG, cutoff=REVIEW_CUTOFF):
    """(missing, unresolved, invalid) for executor merges since the baseline."""
    records, invalid = parse_log(log_path)
    invalid = ["INVALID RECORD " + i for i in invalid]
    missing, unresolved = [], []
    out = git(repo, "log", "--first-parent", "--merges", "--format=%H %P|%s", "%s..HEAD" % baseline)
    for line in out.splitlines():
        shas, _, subject = line.partition("|")
        sha, *parents = shas.split()
        if not subject.startswith("Merge exec/"):
            continue
        label = "%s %s" % (sha[:12], subject)
        rec = lookup(records, [sha] + parents[1:])
        if rec is None:
            missing.append(label)
            continue
        status = rec[1]
        if status == "PASS":
            continue
        if status == "WAIVED":
            if touches(changed(repo, parents[0], sha), "main/") and not git_ok(
                    repo, "merge-base", "--is-ancestor", sha, cutoff):
                invalid.append("WAIVED on <module>/src/main after the cutoff: " + label)
            continue
        if status == "REVISED":
            fix = rec[2][0]
            fix_rec = None
            try:
                full = git(repo, "rev-parse", "--verify", "--quiet", fix + "^{commit}").strip()
                fix_rec = lookup(records, [full])
                merged = git_ok(repo, "merge-base", "--is-ancestor", full, "HEAD")
            except subprocess.CalledProcessError:
                merged = False
            if fix_rec and fix_rec[1] == "PASS" and merged:
                continue
            unresolved.append("%s (REVISED fix %s has no PASS or is not merged)" % (label, fix))
            continue
        unresolved.append("%s (%s)" % (label, status))
    return missing, unresolved, invalid


def review_status(repo=REPO, baseline=BASELINE, log_path=REVIEW_LOG, cutoff=REVIEW_CUTOFF):
    """Back-compat: (missing, pending)."""
    missing, unresolved, _ = review_findings(repo, baseline, log_path, cutoff)
    return missing, unresolved


def check_history(repo, baseline):
    if not git_ok(repo, "cat-file", "-e", baseline + "^{commit}") or not git_ok(
            repo, "merge-base", "--is-ancestor", baseline, "HEAD"):
        raise RuntimeError("baseline %s not in history" % baseline[:12])


def stop_hook():
    """Stop hook (decision "block" keeps Claude working): a direct or non-exec source commit, an executor
    merge with no review line, or an invalid record blocks the end of the turn. Unresolved does not."""
    try:
        v = violations()
        missing, _, invalid = review_findings()
    except Exception:  # never wedge a session on a broken check
        return 0
    if v or missing or invalid:
        reason = []
        if v:
            reason.append("module source committed outside an executor merge: " + "; ".join(v))
        if missing:
            reason.append("executor merges with no review record in state/reviews.log (run the reviewer, "
                          "then add PASS, or WAIVED with a reason): " + "; ".join(missing))
        if invalid:
            reason.append("invalid review records: " + "; ".join(invalid))
        import json
        print(json.dumps({"decision": "block", "reason": " | ".join(reason)}, ensure_ascii=False))
    return 0


def audit(repo=REPO, baseline=BASELINE, log_path=REVIEW_LOG, cutoff=REVIEW_CUTOFF, upstreams=None,
          ci=False, out=print):
    if ci:
        try:
            check_history(repo, baseline)
            v = violations(repo, baseline, upstreams)
            missing, unresolved, invalid = review_findings(repo, baseline, log_path, cutoff)
        except Exception as e:
            out("delegation_check --ci: history incomplete: fetch-depth 0 needed (%s)" % e)
            out("GATE delegation FAIL")
            out("GATE review-records FAIL")
            return 1
    else:
        v = violations(repo, baseline, upstreams)
        missing, unresolved, invalid = review_findings(repo, baseline, log_path, cutoff)
    for line in v:
        out("VIOLATION " + line if ci else line)
    if v:
        out("%d commit(s) wrote module source on the working branch outside an executor merge" % len(v))
    for line in missing:
        out("NO REVIEW " + line)
    for line in invalid:
        out(line)
    for line in unresolved:
        out("UNRESOLVED " + line)
    if missing:
        out("%d executor merge(s) without a review record (state/reviews.log)" % len(missing))
    records_fail = bool(missing or invalid or (ci and unresolved))
    if ci:
        out("GATE delegation %s" % ("FAIL" if v else "PASS"))
        out("GATE review-records %s" % ("FAIL" if records_fail else "PASS"))
    elif not v and not records_fail:
        out("delegation_check: no direct module-source commits since baseline; every executor merge has "
            "a review record" + (" (%d unresolved)" % len(unresolved) if unresolved else ""))
    return 1 if (v or records_fail) else 0


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
        g("merge", "-q", "--no-ff", "-m", "Merge exec/t: work", "exec/t")
        ok = violations(tmp, base, []) == []
        commit("safety/src/test/B.kt", "planner wrote source")
        v = violations(tmp, base, [])
        ok = ok and len(v) == 1 and v[0].endswith("planner wrote source")
        g("checkout", "-q", "-b", "foo", base)
        commit("app/src/main/D.kt", "bypass work")
        g("checkout", "-q", "main")
        g("merge", "-q", "--no-ff", "-m", "Merge branch 'foo'", "foo")
        v = violations(tmp, base, [])
        ok = ok and len(v) == 2 and any("Merge branch 'foo'" in x for x in v)
        ok = ok and len(violations(tmp, base, ["foo"])) == 1
        log = os.path.join(tmp, "reviews.log")
        open(log, "w").write("# header\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and len(missing) == 1 and invalid == []
        tip = g("rev-parse", "exec/t")
        open(log, "a").write(tip[:12] + " PENDING 2026-10-16 x\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and missing == [] and len(unresolved) == 1
        open(log, "a").write(tip[:12] + " PASSED 2026-10-16 x\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and len(invalid) == 1 and len(unresolved) == 1
        open(log, "w").write(tip[:12] + " PASS 2026-10-16 reviewer\n")
        ok = ok and review_findings(tmp, base, log, base) == ([], [], [])
        open(log, "w").write(tip[:12] + " WAIVED 2026-10-16 four words of reason\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and len(invalid) == 1
        print("delegation_check selftest: %s" % ("ok" if ok else "FAIL"))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if "--selftest" in argv:
        return selftest()
    if "--stop-hook" in argv:
        return stop_hook()
    upstreams = [argv[i + 1] for i, a in enumerate(argv) if a == "--upstream" and i + 1 < len(argv)]
    repo = REPO
    if upstreams:
        upstreams = existing_refs(repo, upstreams)
    else:
        upstreams = None
    return audit(repo, BASELINE, REVIEW_LOG, REVIEW_CUTOFF, upstreams, ci="--ci" in argv)


if __name__ == "__main__":
    sys.exit(main())
