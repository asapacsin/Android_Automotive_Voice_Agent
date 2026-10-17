#!/usr/bin/env python3
"""The deterministic half of reviewing an executor branch before `git merge exec/*`.

    python scripts/review_packet.py packet --branch exec/p67-claim-group [--base SHA]
    python scripts/review_packet.py record --sha <tip> --status PASS --note "reviewer PASS ..."
    python scripts/review_packet.py --selftest

`packet` prints what the reviewer packet needs and what the planner must decide: the commit range,
the changed files, whether production source changed (so review is required, not WAIVED), the
SPEC lines that name the changed symbols (candidates; the planner picks), and JUnit counts per
module and per variant, flagged STALE when older than the branch tip. `record` appends one
validated line to state/reviews.log. Run it as its own command, before the merge: the merge hook
checks the whole command before any part runs.

The judgement (which rows, whether review is warranted, the verdict) stays with the planner:
skills/review-packet.md. Why this exists: the planner rebuilt this packet by hand for every
executor merge (create-skill eval 3, 2026-10-17; 38 records in state/reviews.log).

Reuses delegation_check (record grammar, production-source rule) so each rule keeps one owner.
JUnit is counted per variant on purpose: summing testDebugUnitTest and testReleaseUnitTest
doubled the reported counts on 2026-10-17 (commit 412fd74).
"""
import argparse
import datetime
import glob
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import delegation_check  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REVIEW_LOG = os.path.join(REPO, "state", "reviews.log")


def git(*args, repo=REPO):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True,
                          check=True).stdout.strip()


def build_root(override=None):
    return override or os.environ.get("NOVA_BUILD_DIR") or os.path.join(os.path.expanduser("~"), "nova-drive-build")


def junit_by_variant(root, newer_than=None):
    """{(module, variant): (tests, failures+errors, skipped, stale)} from <root>/<module>/test-results/<variant>."""
    out = {}
    for vdir in sorted(glob.glob(os.path.join(root, "*", "test-results", "*"))):
        if not os.path.isdir(vdir):
            continue
        module = os.path.basename(os.path.dirname(os.path.dirname(vdir)))
        variant = os.path.basename(vdir)
        tests = bad = skipped = 0
        oldest = None
        for path in glob.glob(os.path.join(vdir, "**", "*.xml"), recursive=True):
            try:
                r = ET.parse(path).getroot()
            except (ET.ParseError, OSError):
                bad += 1
                continue
            for s in r.iter("testsuite"):
                if s.find("testsuite") is not None:
                    continue
                tests += int(s.get("tests", 0))
                bad += int(s.get("failures", 0)) + int(s.get("errors", 0))
                skipped += int(s.get("skipped", 0))
            m = os.path.getmtime(path)
            oldest = m if oldest is None else min(oldest, m)
        stale = newer_than is not None and oldest is not None and oldest < newer_than
        out[(module, variant)] = (tests, bad, skipped, stale)
    return out


def spec_candidates(paths, limit=12):
    """SPEC lines naming a changed Kotlin symbol (file stem). Candidates only."""
    stems = sorted({os.path.splitext(os.path.basename(p))[0] for p in paths
                    if p.endswith((".kt", ".kts", ".java")) and "/src/" in p})
    hits = []
    for spec in sorted(glob.glob(os.path.join(REPO, "SPECS", "SPEC-*.md"))):
        for n, line in enumerate(open(spec, encoding="utf-8"), 1):
            if any(re.search(r"\b%s\b" % re.escape(s), line) for s in stems):
                hits.append("%s:%d  %s" % (os.path.relpath(spec, REPO), n, line.strip()[:110]))
    return stems, hits[:limit]


def cmd_packet(args):
    tip = git("rev-parse", args.branch)
    base = args.base or git("merge-base", "HEAD", args.branch)
    commits = git("log", "--oneline", "%s..%s" % (base, tip)).splitlines()
    paths = [p for p in git("diff", "--name-only", base, tip).splitlines() if p]
    prod = [p for p in paths if delegation_check.is_prod_src(p)]
    tests = [p for p in paths if delegation_check.is_src(p) and p not in prod]
    tip_time = int(git("log", "-1", "--format=%ct", tip))
    if not commits:
        print("BRANCH      %s: no commits beyond %s - nothing to review or merge" % (args.branch, base[:9]))
        return 1
    print("BRANCH      %s" % args.branch)
    print("RANGE       %s..%s  (%d commits)" % (base[:9], tip[:9], len(commits)))
    for c in commits[:15]:
        print("            %s" % c)
    print("CHANGED     %d files: %d production source, %d test source, %d other"
          % (len(paths), len(prod), len(tests), len(paths) - len(prod) - len(tests)))
    for p in paths[:40]:
        print("            %s%s" % ("[prod] " if p in prod else "", p))
    print("REVIEW      %s" % ("required: production source changed (reviewer PASS before merge)" if prod
                              else "may be WAIVED with a reason of 4+ words (no production source)"))
    stems, hits = spec_candidates(paths)
    print("SPEC LINES  naming %s (candidates; pick the rows this branch earns):" % (", ".join(stems) or "-"))
    for h in hits or ["(none)"]:
        print("            %s" % h)
    print("JUNIT       %s  (per variant; never sum variants; STALE = older than the tip commit)"
          % build_root(args.build_dir))
    for (module, variant), (t, bad, skipped, stale) in junit_by_variant(build_root(args.build_dir), tip_time).items():
        print("            %-14s %-22s tests=%d failed=%d skipped=%d%s"
              % (module, variant, t, bad, skipped, "  STALE" if stale else ""))
    print("NEXT        rerun the packet's ACCEPTANCE_COMMANDS yourself (--rerun-tasks), send `reviewer`")
    print("            the packet + range + this output (not the executor's narrative), then:")
    print("            python scripts/review_packet.py record --sha %s --status PASS --note \"...\"" % tip[:7])
    print("            and only then, in a separate command: git merge --no-ff %s" % args.branch)
    return 0


def cmd_record(args):
    line = "%s %s %s %s" % (args.sha, args.status, datetime.date.today().isoformat(), args.note.strip())
    delegation_check.parse_line(line)  # raises ValueError with the grammar's own message
    with open(args.log, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(line + "\n")
    print("recorded: %s" % line)
    return 0


def selftest():
    bad = 0
    with tempfile.TemporaryDirectory() as tmp:
        log = os.path.join(tmp, "reviews.log")
        ns = argparse.Namespace(sha="abc1234", status="PASS", note="reviewer PASS on the guard", log=log)
        cmd_record(ns)
        if "abc1234 PASS" not in open(log).read():
            bad += 1
            print("FAIL record did not append")
        for status, note in (("WAIVED", "docs only"), ("BOGUS", "x y z w")):
            try:
                cmd_record(argparse.Namespace(sha="abc1234", status=status, note=note, log=log))
                bad += 1
                print("FAIL accepted %s %r" % (status, note))
            except ValueError:
                pass
        for variant, tests in (("testDebugUnitTest", 5), ("testReleaseUnitTest", 5)):
            d = os.path.join(tmp, "build", "app", "test-results", variant)
            os.makedirs(d)
            open(os.path.join(d, "TEST-x.xml"), "w").write(
                '<testsuite tests="%d" failures="0" errors="0" skipped="1"/>' % tests)
        counts = junit_by_variant(os.path.join(tmp, "build"), newer_than=0)
        if counts.get(("app", "testDebugUnitTest"), (0,))[0] != 5 or len(counts) != 2:
            bad += 1
            print("FAIL per-variant counts %r" % counts)
        stale = junit_by_variant(os.path.join(tmp, "build"), newer_than=4102444800)  # year 2100
        if not all(v[3] for v in stale.values()):
            bad += 1
            print("FAIL stale not flagged")
    if not delegation_check.is_prod_src("app/src/main/kotlin/A.kt") or \
            delegation_check.is_prod_src("app/src/test/kotlin/A.kt"):
        bad += 1
        print("FAIL production-source rule")
    print("review_packet selftest: %d problem(s)" % bad)
    return 1 if bad else 0


def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]
    if "--selftest" in argv:
        return selftest()
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("packet")
    a.add_argument("--branch", required=True)
    a.add_argument("--base")
    a.add_argument("--build-dir", help="the executor's NOVA_BUILD_DIR (default: env or ~/nova-drive-build)")
    r = sub.add_parser("record")
    r.add_argument("--sha", required=True)
    r.add_argument("--status", required=True, choices=delegation_check.STATUSES)
    r.add_argument("--note", required=True)
    r.add_argument("--log", default=REVIEW_LOG)
    args = p.parse_args(argv)
    try:
        return cmd_packet(args) if args.cmd == "packet" else cmd_record(args)
    except ValueError as exc:
        print("refused: %s" % exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
