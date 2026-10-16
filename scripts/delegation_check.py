#!/usr/bin/env python3
"""Audit: module source must arrive through reviewed executor merges (AGENTS.md hard rule, owner 2026-10-16).

Checks HEAD's first-parent chain since the baseline. "Module source" is any path `<top-dir>/src/**`
(case-insensitive). Paths are compared with renames off, so a move counts as delete + add.
- a non-merge commit touching module source is a VIOLATION (direct commit, fast-forward, cherry-pick);
- an octopus merge (more than 2 parents) touching module source is a VIOLATION, whatever its subject;
- module source that differs between a two-parent merge commit and git's own automatic re-merge of its
  parents (`git merge-tree --write-tree`: evil merge, conflict resolution, `--no-commit` extras, amended
  merges) is a VIOLATION "change made in the merge commit", for every merge, exec and upstream sync alike.
  Clean automatic merges of the same file edited on both sides pass. Conflicts are resolved on the
  executor branch (merge the base into it before review), never in the merge. An upstream sync whose
  source files conflict cannot be resolved in the sync merge either: the supported route is an executor
  branch that merges the upstream, resolves the conflict, is reviewed, and lands as `Merge exec/<sync>`.
  If merge-tree is unavailable or fails, the merge is reported as unverifiable (a VIOLATION: --ci fails);
- a merge touching module source whose subject does not start with "Merge exec/" is a VIOLATION, unless
  its second parent is reachable from an upstream ref u AND the merge itself is not reachable from u
  (a sync from the base branch; default upstream: origin/main; CI passes the PR base with --upstream);
- every "Merge exec/" merge needs an accepted record in state/reviews.log (grammar: parse_line).
  WAIVED is accepted only when every module-source path it touches is in a non-production source set
  (src/debug, src/test, src/testDebug, src/testRelease, src/androidTest); merges in the history of
  REVIEW_CUTOFF are grandfathered for that rule only. REVISED needs a fix that descends from the revised
  branch tip, has its own PASS and is merged into HEAD. A record key must resolve unambiguously to the
  commit it is matched against.

Modes: default audit (exit 1 on violation/missing/invalid; unresolved only reported), --ci (exit 1 on
anything, never fails open, refuses shallow history, ignores the env overrides unless NOVA_GATE_TEST=1),
--stop-hook (fails open, never blocks on unresolved), --selftest. Prints `sha subject` only.

A VIOLATION in first-parent history cannot be waived: the only way out is a reviewed change of BASELINE
in this file, which is visible in the diff. Record dates may be at most 1 day after today's UTC date.

Accepted limits (not closed here):
- state/reviews.log is self-attested: the gate checks records, not that a reviewer really ran;
- a PR can edit this checker or the log; that needs branch protection / CODEOWNERS;
- a build script can redirect source sets outside `<dir>/src/`;
- the PreToolUse hook is advisory; this --ci job is the enforcement.
"""
import datetime
import os
import re
import shutil
import subprocess
import sys
import tempfile

BASELINE_DEFAULT = "860371129f130852c918d99d49fb79bcb4e7b4bf"
# WAIVED on production module source is grandfathered only for merges in this commit's history.
REVIEW_CUTOFF_DEFAULT = "1fb1ed2185777865b4f6d2fa21321efaab01d552"
DEFAULT_UPSTREAMS = ("origin/main",)
NONPROD_SETS = ("debug", "test", "testdebug", "testrelease", "androidtest")
REPO_DEFAULT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

STATUSES = ("PASS", "WAIVED", "PENDING", "REVISE", "REVISED")
_SHA = re.compile(r"[0-9a-f]{7,40}", re.ASCII)
_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", re.ASCII)


def config(ci=False):
    """(repo, baseline, cutoff). Env overrides apply outside --ci, or in --ci only with NOVA_GATE_TEST=1."""
    env = os.environ
    test = env.get("NOVA_GATE_TEST") == "1"
    use_env = (not ci) or test
    repo = env.get("NOVA_GATE_REPO") if test else None
    baseline = (use_env and env.get("NOVA_DELEGATION_BASELINE")) or BASELINE_DEFAULT
    cutoff = (use_env and env.get("NOVA_REVIEW_CUTOFF")) or REVIEW_CUTOFF_DEFAULT
    return repo or REPO_DEFAULT, baseline, cutoff


REPO, BASELINE, REVIEW_CUTOFF = config()
REVIEW_LOG = os.path.join(REPO, "state", "reviews.log")


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
    if not _SHA.fullmatch(sha):
        raise ValueError("bad sha %r" % sha)
    if status not in STATUSES:
        raise ValueError("unknown status %r" % status)
    if not _DATE.fullmatch(date):
        raise ValueError("bad date %r" % date)
    try:
        datetime.date.fromisoformat(date)
    except ValueError:
        raise ValueError("bad date %r" % date)
    if datetime.date.fromisoformat(date) > datetime.datetime.now(datetime.timezone.utc).date() + \
            datetime.timedelta(days=1):
        raise ValueError("date %r is in the future" % date)
    if status == "WAIVED" and len(note) < 4:
        raise ValueError("WAIVED needs a reason of at least 4 words")
    if status == "REVISED" and not _SHA.fullmatch(note[0]):
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


def resolve(repo, ref):
    """Full sha of ref as a commit, or None (unknown or ambiguous)."""
    r = subprocess.run(["git", "-C", repo, "rev-parse", "--verify", "--quiet", ref + "^{commit}"],
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None


def lookup(records, candidates, repo=None):
    """Last valid record whose sha prefixes a candidate full sha. With repo, a key that is ambiguous or
    resolves to another commit is refused and (record or None, refused keys) is returned."""
    found, refused = None, []
    for r in records:
        hit = next((c for c in candidates if c.startswith(r[0])), None)
        if hit is None:
            continue
        if repo is not None and resolve(repo, r[0]) != hit:
            refused.append(r[0])
            continue
        found = r
    return (found, refused) if repo is not None else found


def changed(repo, parent, sha):
    return git(repo, "diff", "--no-renames", "--name-only", parent, sha).split()


def is_src(path):
    parts = path.split("/")
    return len(parts) >= 3 and parts[1].lower() == "src"


def is_prod_src(path):
    parts = path.split("/")
    return is_src(path) and (len(parts) < 4 or parts[2].lower() not in NONPROD_SETS)


def touches(paths, sub=""):
    """Any module-source path; sub="main/" means production source (anything outside NONPROD_SETS)."""
    return any((is_prod_src(p) if sub else is_src(p)) for p in paths)


def existing_refs(repo, refs):
    return [r for r in refs if git_ok(repo, "rev-parse", "--verify", "--quiet", r + "^{commit}")]


def first_parent_merges(repo, baseline):
    out = git(repo, "log", "--first-parent", "--merges", "--format=%H %P|%s", "%s..HEAD" % baseline)
    for line in out.splitlines():
        shas, _, subject = line.partition("|")
        sha, *parents = shas.split()
        yield sha, parents, subject


def merge_commit_change(repo, sha, parents):
    """None, or why the merge commit itself changes module source compared with git's own automatic
    re-merge of its two parents (`git merge-tree --write-tree`, git >= 2.38; exit 1 = conflicts, and the
    re-merged tree then holds conflict markers, so any resolution of a source conflict is flagged)."""
    r = subprocess.run(["git", "-C", repo, "merge-tree", "--write-tree", "--no-messages", parents[0],
                        parents[1]], capture_output=True, text=True)
    tree = r.stdout.split("\n", 1)[0].strip() if r.stdout else ""
    if r.returncode not in (0, 1) or not _SHA.fullmatch(tree):
        return "cannot verify: git merge-tree --write-tree unavailable or failed (git >= 2.38 needed)"
    if touches(changed(repo, tree, sha)):
        return "change made in the merge commit"
    return None


def violations(repo=REPO, baseline=BASELINE, upstreams=None):
    if upstreams is None:
        upstreams = existing_refs(repo, DEFAULT_UPSTREAMS)
    result = []
    out = git(repo, "log", "--first-parent", "--no-merges", "--format=%H", "%s..HEAD" % baseline)
    for sha in out.split():
        paths = git(repo, "diff-tree", "--no-commit-id", "--no-renames", "--name-only", "-r", "--root",
                    sha).split()
        if touches(paths):
            subject = git(repo, "log", "-1", "--format=%s", sha).strip()
            result.append("%s %s" % (sha[:12], subject))
    for sha, parents, subject in first_parent_merges(repo, baseline):
        label = "%s %s" % (sha[:12], subject)
        if len(parents) > 2:
            if touches(changed(repo, parents[0], sha)):
                result.append("%s (octopus merge of module source)" % label)
            continue
        problem = merge_commit_change(repo, sha, parents)
        if problem:
            result.append("%s (%s)" % (label, problem))
            continue
        if not touches(changed(repo, parents[0], sha)):
            continue
        if subject.startswith("Merge exec/"):
            continue
        if any(git_ok(repo, "merge-base", "--is-ancestor", p, u)
               and not git_ok(repo, "merge-base", "--is-ancestor", sha, u)
               for p in parents[1:] for u in upstreams):
            continue
        result.append("%s (non-exec merge of module source)" % label)
    return result


def review_findings(repo=REPO, baseline=BASELINE, log_path=REVIEW_LOG, cutoff=REVIEW_CUTOFF):
    """(missing, unresolved, invalid) for executor merges since the baseline."""
    records, invalid = parse_log(log_path)
    invalid = ["INVALID RECORD " + i for i in invalid]
    missing, unresolved = [], []
    for sha, parents, subject in first_parent_merges(repo, baseline):
        if not subject.startswith("Merge exec/"):
            continue
        label = "%s %s" % (sha[:12], subject)
        rec, refused = lookup(records, [sha] + parents[1:], repo)
        for k in refused:
            invalid.append("INVALID RECORD key %s is ambiguous or names another commit: %s" % (k, label))
        if rec is None:
            if not refused:
                missing.append(label)
            continue
        status = rec[1]
        if status == "PASS":
            continue
        if status == "WAIVED":
            if touches(changed(repo, parents[0], sha), "main/") and not git_ok(
                    repo, "merge-base", "--is-ancestor", sha, cutoff):
                invalid.append("WAIVED on production module source after the cutoff: " + label)
            continue
        if status == "REVISED":
            fix = resolve(repo, rec[2][0])
            tip = parents[1] if len(parents) > 1 else None
            ok = False
            if fix and tip and fix != tip and git_ok(repo, "merge-base", "--is-ancestor", tip, fix) \
                    and git_ok(repo, "merge-base", "--is-ancestor", fix, "HEAD"):
                fix_rec, _ = lookup(records, [fix], repo)
                ok = bool(fix_rec and fix_rec[1] == "PASS")
            if not ok:
                unresolved.append("%s (REVISED fix %s: no PASS, not merged, or not a fix of this work)"
                                  % (label, rec[2][0]))
            continue
        unresolved.append("%s (%s)" % (label, status))
    return missing, unresolved, invalid


def review_status(repo=REPO, baseline=BASELINE, log_path=REVIEW_LOG, cutoff=REVIEW_CUTOFF):
    """Back-compat: (missing, pending)."""
    missing, unresolved, _ = review_findings(repo, baseline, log_path, cutoff)
    return missing, unresolved


def check_history(repo, baseline):
    if git(repo, "rev-parse", "--is-shallow-repository").strip() == "true":
        raise RuntimeError("shallow repository")
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
        ok = ok and len(violations(tmp, base, ["HEAD"])) == 2  # circular upstream does not exempt
        log = os.path.join(tmp, "reviews.log")
        with open(log, "w") as f:
            f.write("# header\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and len(missing) == 1 and invalid == []
        tip = g("rev-parse", "exec/t")
        with open(log, "a") as f:
            f.write(tip[:12] + " PENDING 2026-10-16 x\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and missing == [] and len(unresolved) == 1
        with open(log, "a") as f:
            f.write(tip[:12] + " PASSED 2026-10-16 x\n")
        missing, unresolved, invalid = review_findings(tmp, base, log, base)
        ok = ok and len(invalid) == 1 and len(unresolved) == 1
        with open(log, "w") as f:
            f.write(tip[:12] + " PASS 2026-10-16 reviewer\n")
        ok = ok and review_findings(tmp, base, log, base) == ([], [], [])
        with open(log, "w") as f:
            f.write(tip[:12] + " WAIVED 2026-10-16 four words of reason\n")
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
    ci = "--ci" in argv
    repo, baseline, cutoff = config(ci)
    upstreams = [argv[i + 1] for i, a in enumerate(argv) if a == "--upstream" and i + 1 < len(argv)]
    upstreams = existing_refs(repo, upstreams) if upstreams else None
    return audit(repo, baseline, os.path.join(repo, "state", "reviews.log"), cutoff, upstreams, ci=ci)


if __name__ == "__main__":
    sys.exit(main())
