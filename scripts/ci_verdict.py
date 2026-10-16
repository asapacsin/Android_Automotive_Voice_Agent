#!/usr/bin/env python3
"""Deterministic CI verdict: test results in, one PASS/FAIL/BLOCKED job verdict out.

A required failure, a missing result, an empty or malformed results file can never come out
green. Python 3 stdlib only, so it runs on any runner.

Subcommands: record, scenarios, junit, verdict; and --selftest.
Exit codes of `verdict`: 0 PASS, 1 FAIL, 3 BLOCKED, 2 usage/manifest error.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

STATUSES = ("PASS", "FAIL", "SKIPPED", "BLOCKED", "FLAKY")
DETAIL_MAX = 300
QUARANTINE_FIELDS = ("id", "suite", "reason", "owner", "tracking", "remove_when", "expires")
QUARANTINE_MAX_DAYS = 30
ID_RE = re.compile(r"^[A-Za-z0-9_.:\-]+$")
PATTERN_RE = re.compile(r"^[A-Za-z0-9_.:\-]+\*?$")
TEST_CLOCK_ENV = "NOVA_CI_TEST_CLOCK"
UNQUARANTINABLE = ("voice:quota-guard", "navpiece:quota-guard")
UNQUARANTINABLE_PREFIXES = ("junit:", "build:", "gate:", "ci:", "matrix:", "gradle:")


class ManifestError(Exception):
    pass


def clean_detail(text) -> str:
    """ASCII only (no transcripts or place names in CI evidence), one line, capped."""
    text = "" if text is None else str(text)
    text = text.encode("ascii", "ignore").decode("ascii")
    text = re.sub(r"[\x00-\x1f\x7f]+", " ", text)
    return text[:DETAIL_MAX]


def make_record(rid, status, detail="", passes=None, runs=None) -> dict:
    rec = {"id": rid, "status": status, "detail": clean_detail(detail)}
    if passes is not None:
        rec["passes"] = int(passes)
    if runs is not None:
        rec["runs"] = int(runs)
    return rec


def write_records(path, records, append=False) -> None:
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, exist_ok=True)
    with open(path, "a" if append else "w", encoding="ascii", newline="\n") as fh:
        for rec in records:
            fh.write(json.dumps(rec, ensure_ascii=True) + "\n")


# ---------------------------------------------------------------- results files

def parse_results_file(path):
    """Return (records, problems). Problems are blocking strings."""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except (OSError, UnicodeDecodeError):
        return [], [f"results file missing or unreadable: {path}"]
    records, problems = [], []
    seen = set()
    for no, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        where = f"{path}:{no}"
        try:
            obj = json.loads(line)
        except ValueError:
            problems.append(f"malformed line {where}: invalid JSON")
            continue
        if not isinstance(obj, dict):
            problems.append(f"malformed line {where}: not an object")
            continue
        rid = obj.get("id")
        if not isinstance(rid, str) or not ID_RE.fullmatch(rid):
            problems.append(f"malformed line {where}: missing or invalid id")
            continue
        status = obj.get("status")
        if status not in STATUSES:
            problems.append(f"malformed line {where}: missing or unknown status")
            continue
        detail = obj.get("detail", "")
        if not isinstance(detail, str):
            problems.append(f"malformed line {where}: detail is not a string")
            continue
        bad_int = False
        for key in ("passes", "runs"):
            if key in obj and (not isinstance(obj[key], int) or isinstance(obj[key], bool)):
                bad_int = True
        if bad_int:
            problems.append(f"malformed line {where}: passes/runs not an integer")
            continue
        if ("passes" in obj) != ("runs" in obj):
            problems.append(f"malformed line {where}: passes and runs go together")
            continue
        if "runs" in obj:
            p_, n_ = obj["passes"], obj["runs"]
            if p_ < 0 or n_ <= 0 or p_ > n_ or (status == "PASS" and p_ != n_):
                problems.append(f"malformed line {where}: inconsistent passes/runs")
                continue
        if rid in seen:
            problems.append(f"malformed line {where}: duplicate id")
            continue
        seen.add(rid)
        records.append(obj)
    if not records and not problems:
        problems.append(f"results file empty: {path}")
    return records, problems


# ---------------------------------------------------------------- manifest

def _parse_date(value):
    try:
        return _dt.date.fromisoformat(value) if isinstance(value, str) and len(value) == 10 else None
    except ValueError:
        return None


def load_manifest(path, today):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        raise ManifestError(f"manifest unreadable: {exc}")
    if not isinstance(data, dict):
        raise ManifestError("manifest is not an object")
    unknown = set(data) - {"_doc", "suites", "quarantine"}
    if unknown:
        raise ManifestError(f"unknown manifest keys: {sorted(unknown)}")
    suites = data.get("suites")
    if not isinstance(suites, dict) or not suites:
        raise ManifestError("manifest needs a non-empty 'suites' object")
    for name, suite in suites.items():
        if not isinstance(suite, dict):
            raise ManifestError(f"suite {name} is not an object")
        unk = set(suite) - {"description", "required", "optional"}
        if unk:
            raise ManifestError(f"suite {name}: unknown keys {sorted(unk)}")
        if not isinstance(suite.get("description"), str):
            raise ManifestError(f"suite {name}: description must be a string")
        for key in ("required", "optional"):
            ids = suite.get(key)
            if not isinstance(ids, list) or not all(
                    isinstance(i, str) and PATTERN_RE.fullmatch(i) for i in ids):
                raise ManifestError(f"suite {name}: {key} must be a list of non-empty strings")
            if len(set(ids)) != len(ids):
                raise ManifestError(f"suite {name}: duplicate id in {key}")
        both = set(suite["required"]) & set(suite["optional"])
        if both:
            raise ManifestError(f"suite {name}: ids in both required and optional: {sorted(both)}")
    quarantine = data.get("quarantine", [])
    if not isinstance(quarantine, list):
        raise ManifestError("quarantine must be a list")
    seen = set()
    for n, q in enumerate(quarantine):
        if not isinstance(q, dict):
            raise ManifestError(f"quarantine[{n}] is not an object")
        unk = set(q) - set(QUARANTINE_FIELDS)
        if unk:
            raise ManifestError(f"quarantine[{n}]: unknown keys {sorted(unk)}")
        for f in QUARANTINE_FIELDS:
            if not isinstance(q.get(f), str) or not q[f].strip():
                raise ManifestError(f"quarantine[{n}]: field '{f}' missing or empty")
        if q["suite"] not in suites:
            raise ManifestError(f"quarantine[{n}]: unknown suite {q['suite']}")
        if not ID_RE.fullmatch(q["id"]):
            raise ManifestError(f"quarantine[{n}]: id must be one concrete check id, no wildcard")
        if q["id"] in UNQUARANTINABLE or q["id"].startswith(UNQUARANTINABLE_PREFIXES):
            raise ManifestError(
                f"quarantine[{n}] {q['id']}: safety and global checks cannot be quarantined")
        if q["id"].endswith(":runner"):
            raise ManifestError(f"quarantine[{n}] {q['id']}: a runner check cannot be quarantined")
        if not any(_matches(pt, q["id"]) for pt in suites[q["suite"]]["required"]):
            raise ManifestError(
                f"quarantine[{n}] {q['id']}: no required check of suite {q['suite']} matches it")
        exp = _parse_date(q["expires"])
        if exp is None:
            raise ManifestError(f"quarantine[{n}] {q['id']}: expires is not a YYYY-MM-DD date")
        if exp < today:
            raise ManifestError(f"quarantine[{n}] {q['id']}: expired on {q['expires']}")
        if (exp - today).days > QUARANTINE_MAX_DAYS:
            raise ManifestError(
                f"quarantine[{n}] {q['id']}: expires more than {QUARANTINE_MAX_DAYS} days ahead")
        key = (q["suite"], q["id"])
        if key in seen:
            raise ManifestError(f"quarantine[{n}]: duplicate entry for {q['id']}")
        seen.add(key)
    return data


# ---------------------------------------------------------------- verdict

def _matches(pattern, rid):
    return rid.startswith(pattern[:-1]) if pattern.endswith("*") else rid == pattern


def evaluate(manifest, suite_name, records, file_problems):
    suite = manifest["suites"][suite_name]
    quarantine = {q["id"]: q for q in manifest.get("quarantine", []) if q["suite"] == suite_name}
    by_id = {r["id"]: r for r in records}
    exact = set(p for p in suite["required"] + suite["optional"] if not p.endswith("*"))

    def owner_of(rid):
        if rid in exact:
            return "required" if rid in suite["required"] else "optional"
        for p in suite["required"]:
            if p.endswith("*") and _matches(p, rid):
                return "required"
        for p in suite["optional"]:
            if p.endswith("*") and _matches(p, rid):
                return "optional"
        return None

    checks = []
    problems = []  # dicts: level (error|warning), kind (FAIL|BLOCKED|None), text
    for msg in file_problems:
        problems.append({"level": "error", "kind": "FAIL", "text": msg})

    def add_result(rid, required, rec):
        status = rec["status"]
        detail = clean_detail(rec.get("detail", ""))
        if "passes" in rec and "runs" in rec and not detail:
            detail = (f"{rec['passes']}/{rec['runs']} " + detail).strip()
        entry = {"id": rid, "required": required, "status": status, "detail": detail}
        if required:
            q = quarantine.get(rid)
            if status == "PASS":
                if q:
                    entry["quarantine"] = q
                    problems.append({"level": "warning", "kind": None,
                                     "text": f"{rid} passed: quarantine can be lifted"})
            elif status in ("FAIL", "FLAKY") and q:
                entry["status"] = "QUARANTINED"
                entry["quarantine"] = q
                problems.append({"level": "warning", "kind": None, "text":
                                 f"{rid} {status} quarantined: {q['reason']} (owner {q['owner']}, "
                                 f"expires {q['expires']})"})
            elif status in ("FAIL", "FLAKY", "SKIPPED"):
                problems.append({"level": "error", "kind": "FAIL",
                                 "text": f"required {rid} is {status}"})
            elif status == "BLOCKED":
                problems.append({"level": "error", "kind": "BLOCKED",
                                 "text": f"required {rid} is BLOCKED"})
        elif status in ("FAIL", "FLAKY", "BLOCKED"):
            problems.append({"level": "warning", "kind": None,
                             "text": f"optional {rid} is {status}"})
        checks.append(entry)

    def add_missing(pid, required):
        if required:
            checks.append({"id": pid, "required": True, "status": "MISSING",
                           "detail": "no result"})
            problems.append({"level": "error", "kind": "FAIL",
                             "text": f"required {pid} has no result"})
        else:
            checks.append({"id": pid, "required": False, "status": "SKIPPED",
                           "detail": "not executed"})

    done = set()
    for kind, required in (("required", True), ("optional", False)):
        for pattern in suite[kind]:
            if pattern.endswith("*"):
                hits = [r["id"] for r in records
                        if _matches(pattern, r["id"]) and r["id"] not in done
                        and r["id"] not in exact
                        and owner_of(r["id"]) == kind]
                if not hits:
                    # A required wildcard with no results is BLOCKED (not missing) when its
                    # runner (<prefix up to the last ':'>runner) itself reported BLOCKED.
                    runner_id = pattern[:pattern.rfind(":") + 1] + "runner" if ":" in pattern else None
                    runner = by_id.get(runner_id) if runner_id else None
                    if required and runner is not None and runner["status"] == "BLOCKED":
                        checks.append({"id": pattern, "required": True, "status": "BLOCKED",
                                       "detail": f"runner {runner_id} BLOCKED"})
                        problems.append({"level": "error", "kind": "BLOCKED",
                                         "text": f"required {pattern} is BLOCKED: {runner_id} BLOCKED"})
                    else:
                        add_missing(pattern, required)
                for rid in hits:
                    done.add(rid)
                    add_result(rid, required, by_id[rid])
            elif pattern in by_id:
                done.add(pattern)
                add_result(pattern, required, by_id[pattern])
            else:
                add_missing(pattern, required)
    for r in records:
        if r["id"] not in done:
            checks.append({"id": r["id"], "required": False, "status": r["status"],
                           "detail": ("unlisted " + clean_detail(r.get("detail", ""))).strip()})
            problems.append({"level": "warning", "kind": None,
                             "text": f"unlisted result {r['id']} ({r['status']}) is not part of suite {suite_name}"})

    if not suite["required"]:
        problems.append({"level": "error", "kind": "BLOCKED",
                         "text": f"suite {suite_name} lists no required checks"})
    if any(p["kind"] == "FAIL" for p in problems):
        verdict = "FAIL"
    elif any(p["kind"] == "BLOCKED" for p in problems):
        verdict = "BLOCKED"
    else:
        verdict = "PASS"
    return verdict, checks, problems


def _gh_escape(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def cmd_verdict(args):
    real_today = _dt.datetime.now(_dt.timezone.utc).date()
    if args.today:
        today = _parse_date(args.today)
        if today is None:
            print("bad --today: expected YYYY-MM-DD", file=sys.stderr)
            print(f"VERDICT {args.suite} FAIL")
            return 2
        if os.environ.get(TEST_CLOCK_ENV) != "1":
            print(f"--today is only allowed with {TEST_CLOCK_ENV}=1 (tests); production uses "
                  "the real UTC date", file=sys.stderr)
            print(f"VERDICT {args.suite} FAIL")
            return 2
    else:
        today = real_today
    try:
        manifest = load_manifest(args.manifest, today)
        if args.suite not in manifest["suites"]:
            raise ManifestError(f"unknown suite {args.suite}")
    except ManifestError as exc:
        print(f"::error title=ci_verdict manifest invalid::{_gh_escape(str(exc))}")
        print(f"VERDICT {args.suite} FAIL")
        return 2

    records, file_problems = [], []
    seen = {}
    for path in args.results:
        recs, probs = parse_results_file(path)
        file_problems.extend(probs)
        for r in recs:
            if r["id"] in seen:
                file_problems.append(f"malformed: duplicate id {r['id']} in {path} and {seen[r['id']]}")
                continue
            seen[r["id"]] = path
            records.append(r)

    verdict, checks, problems = evaluate(manifest, args.suite, records, file_problems)
    for c in checks:
        print(f"{c['status']} {c['id']} {c['detail']}".rstrip())
    for p in problems:
        if p["level"] == "error":
            print(f"::error title=ci_verdict {args.suite}::{_gh_escape(p['text'])}")
        else:
            print(f"::warning title=ci_verdict {args.suite}::{_gh_escape(p['text'])}")
    n_quar = sum(1 for c in checks if c["status"] == "QUARANTINED")
    suffix = f" ({n_quar} quarantined)" if n_quar else ""
    print(f"VERDICT {args.suite} {verdict}{suffix}")

    if args.json_out:
        out = {"suite": args.suite, "verdict": verdict, "checks": [], "problems": problems}
        for c in checks:
            item = {"id": c["id"], "required": c["required"], "status": c["status"],
                    "detail": c["detail"]}
            if "quarantine" in c:
                item["quarantine"] = c["quarantine"]
            out["checks"].append(item)
        os.makedirs(os.path.dirname(os.path.abspath(args.json_out)), exist_ok=True)
        with open(args.json_out, "w", encoding="utf-8") as fh:
            json.dump(out, fh, indent=2, ensure_ascii=True)
    if args.summary:
        lines = [f"\n### CI verdict `{args.suite}`: **{verdict}**\n", "",
                 "| Status | Check | Required | Detail |", "| --- | --- | --- | --- |"]
        for c in checks:
            d = c["detail"].replace("|", "\\|")
            lines.append(f"| {c['status']} | `{c['id']}` | {'yes' if c['required'] else 'no'} | {d} |")
        for p in problems:
            lines.append(f"\n- {p['level']}: {p['text'].replace('|', '/')}")
        with open(args.summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    return {"PASS": 0, "FAIL": 1, "BLOCKED": 3}[verdict]


# ---------------------------------------------------------------- record

def cmd_record(args):
    if args.status not in STATUSES:
        print(f"invalid status {args.status!r}; expected one of {', '.join(STATUSES)}",
              file=sys.stderr)
        return 2
    if not ID_RE.fullmatch(args.id or ""):
        print("invalid --id: expected [A-Za-z0-9_.:-]+", file=sys.stderr)
        return 2
    if (args.passes is None) != (args.runs is None):
        print("--passes and --runs go together", file=sys.stderr)
        return 2
    write_records(args.out, [make_record(args.id, args.status, args.detail or "",
                                         args.passes, args.runs)], append=True)
    return 0


# ---------------------------------------------------------------- scenarios

_SINGLE = re.compile(r"^\s+(PASS|FAIL)\s+([A-Za-z0-9_.\-]+)(?:\s|$)")
_REPEAT = re.compile(r"^\s+(ok|FLAKY|FAIL)\s+([A-Za-z0-9_.\-]+)\s+(\d+)/(\d+)(?:\s|$)")
_SUMMARY_HEADER = "=== summary ==="
_LOG_HEADER = "=== last failing log"
QUOTA_GUARD_RC = 3  # run_scenarios.py exits 3 when the quota guard stops a non-Gemini session


def _status_from_counts(flag, passes, runs, single_failed):
    if runs <= 0 or passes > runs:
        return "FAIL", "summary counts invalid"
    derived = "PASS" if passes == runs else ("FLAKY" if passes > 0 else "FAIL")
    if {"ok": "PASS", "FLAKY": "FLAKY", "FAIL": "FAIL"}[flag] != derived:
        return "FAIL", "summary flag contradicts counts"
    if derived == "PASS" and single_failed:
        return "FAIL", "a run failed but the summary says all passed"
    return derived, f"{passes}/{runs} runs passed"


def parse_scenarios(text):
    """Parse run_scenarios.py output.

    Returns (results, summary_seen, per_run_ids, summary_ids):
    results is an ordered {id: (status, passes, runs, detail)} where passes/runs are None for
    single-run results or invalid counts; summary_seen tells whether the "=== summary ===" header
    appeared; per_run_ids and summary_ids are the sets of ids on per-run and summary lines.
    """
    single, repeat, order, dup = {}, {}, [], set()
    summary_seen = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith(_LOG_HEADER):
            break  # log tails follow: never parse them as results
        if stripped == _SUMMARY_HEADER:
            summary_seen = True
            continue
        m = _REPEAT.match(line)
        if m:
            sid = m.group(2)
            if sid in repeat:
                dup.add(sid)
            repeat[sid] = (m.group(1), int(m.group(3)), int(m.group(4)))
            if sid not in order:
                order.append(sid)
            continue
        m = _SINGLE.match(line)
        if m:
            sid = m.group(2)
            single[sid] = "FAIL" if (m.group(1) == "FAIL" or single.get(sid) == "FAIL") else "PASS"
            if sid not in order:
                order.append(sid)
    out = {}
    for sid in order:
        if sid in dup:
            out[sid] = ("FAIL", None, None, "duplicate summary")
        elif sid in repeat:
            flag, p, n = repeat[sid]
            status, detail = _status_from_counts(flag, p, n, single.get(sid) == "FAIL")
            ok_counts = n > 0 and 0 <= p <= n and not (status == "PASS" and p != n)
            out[sid] = (status, p if ok_counts else None, n if ok_counts else None, detail)
        else:
            out[sid] = (single[sid], None, None, "")
    return out, summary_seen, set(single), set(repeat)


def cmd_scenarios(args):
    prefix = args.prefix or ""
    try:
        with open(args.raw, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        write_records(args.out, [make_record(f"{prefix}runner", "BLOCKED", "raw output missing")])
        return 0
    parsed, summary_seen, run_ids, summary_ids = parse_scenarios(text)
    records = []
    for sid, (status, p, n, detail) in parsed.items():
        records.append(make_record(f"{prefix}{sid}", status, detail, p, n))
    rc = args.runner_rc
    if rc is not None and rc == QUOTA_GUARD_RC:
        runner = make_record(f"{prefix}runner", "BLOCKED", "quota guard")
    elif rc is not None and rc not in (0, 1):
        runner = make_record(f"{prefix}runner", "FAIL", f"runner exit {rc}")
    elif not parsed:
        runner = make_record(f"{prefix}runner", "FAIL", "runner produced no results")
    elif not summary_seen or not run_ids <= summary_ids:
        runner = make_record(f"{prefix}runner", "FAIL", "run incomplete: no summary")
    elif rc == 1:
        # run_scenarios.py exits 1 when any scenario is under 100%; with a complete summary the
        # per-scenario records carry that and the verdict judges them (planner, CI-1R2).
        runner = make_record(f"{prefix}runner", "PASS",
                             "runner exit 1: scenario failures judged per scenario")
    else:
        runner = make_record(f"{prefix}runner", "PASS", f"{len(parsed)} scenario results")
    records.append(runner)
    write_records(args.out, records)
    return 0


# ---------------------------------------------------------------- junit

def junit_record(root, module):
    base = os.path.join(root, module)
    files = []
    for dirpath, _dirs, names in os.walk(base):
        if "test-results" not in os.path.relpath(dirpath, base).split(os.sep):
            continue
        files.extend(os.path.join(dirpath, n) for n in names if n.endswith(".xml"))
    files.sort()
    rid = f"junit:{module}"
    if not files:
        return make_record(rid, "FAIL", "no test report")
    tests = failures = errors = skipped = 0
    failing = []
    for path in files:
        try:
            tree_root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            return make_record(rid, "FAIL", f"malformed report {os.path.basename(path)}")
        if tree_root.tag not in ("testsuite", "testsuites"):
            return make_record(rid, "FAIL", f"malformed report {os.path.basename(path)}")
        leaves = [s for s in tree_root.iter("testsuite") if s.find("testsuite") is None]
        for s in leaves:
            try:
                counts = [int(s.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
            except ValueError:
                return make_record(rid, "FAIL", f"malformed report {os.path.basename(path)}")
            if any(c < 0 for c in counts):
                return make_record(rid, "FAIL", f"malformed report {os.path.basename(path)}")
            tests += counts[0]
            failures += counts[1]
            errors += counts[2]
            skipped += counts[3]
        for case in tree_root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failing.append(f"{case.get('classname', '')}.{case.get('name', '')}")
    if failures + errors > 0 or failing:
        names = ", ".join(failing[:5])
        return make_record(rid, "FAIL",
                           f"tests={tests} failures={failures} errors={errors}: {names}")
    if tests - skipped <= 0:
        return make_record(rid, "FAIL", f"no test executed (tests={tests} skipped={skipped})")
    return make_record(rid, "PASS",
                       f"tests={tests} failures=0 errors=0 skipped={skipped}")


def cmd_junit(args):
    write_records(args.out, [junit_record(args.root, m) for m in args.module])
    return 0


# ---------------------------------------------------------------- main

def selftest():
    import unittest
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    if repo not in sys.path:
        sys.path.insert(0, repo)
    suite = unittest.defaultTestLoader.discover(os.path.join(here, "tests"), top_level_dir=repo)
    if "scripts.tests.test_ci_verdict" not in sys.modules:
        print("FAILED: scripts.tests.test_ci_verdict did not load")
        return 1
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if result.wasSuccessful() and result.testsRun > 0:
        print("ok")
        return 0
    print("FAILED")
    return 1


def build_parser():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--selftest", action="store_true")
    sub = p.add_subparsers(dest="cmd")
    r = sub.add_parser("record")
    r.add_argument("--out", required=True)
    r.add_argument("--id", required=True)
    r.add_argument("--status", required=True)
    r.add_argument("--detail", default="")
    r.add_argument("--passes", type=int)
    r.add_argument("--runs", type=int)
    s = sub.add_parser("scenarios")
    s.add_argument("--raw", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--prefix", default="")
    s.add_argument("--runner-rc", type=int)
    j = sub.add_parser("junit")
    j.add_argument("--root", required=True)
    j.add_argument("--module", action="append", required=True)
    j.add_argument("--out", required=True)
    v = sub.add_parser("verdict")
    v.add_argument("--manifest", required=True)
    v.add_argument("--suite", required=True)
    v.add_argument("--results", action="append", required=True)
    v.add_argument("--today")
    v.add_argument("--json-out")
    v.add_argument("--summary")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.selftest:
        return selftest()
    handler = {"record": cmd_record, "scenarios": cmd_scenarios, "junit": cmd_junit,
               "verdict": cmd_verdict}.get(args.cmd)
    if handler is None:
        build_parser().print_usage(sys.stderr)
        return 2
    return handler(args)


if __name__ == "__main__":
    sys.exit(main())
