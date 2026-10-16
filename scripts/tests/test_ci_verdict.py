"""Tests for scripts/ci_verdict.py, run through the CLI for exit codes."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOL = os.path.join(REPO, "scripts", "ci_verdict.py")
SHIPPED = os.path.join(REPO, "config", "ci_checks.json")
TODAY = "2026-10-16"


def run(*args, clock=True):
    env = dict(os.environ)
    env.pop("NOVA_CI_TEST_CLOCK", None)
    if clock:
        env["NOVA_CI_TEST_CLOCK"] = "1"
    p = subprocess.run([sys.executable, TOOL, *args], capture_output=True, text=True,
                       encoding="utf-8", env=env)
    return p.returncode, p.stdout + p.stderr


class Base(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def path(self, name):
        return os.path.join(self.dir, name)

    def manifest(self, required=(), optional=(), quarantine=(), extra=None):
        data = {"_doc": "test", "suites": {"s": {"description": "t", "required": list(required),
                                                  "optional": list(optional)}},
                "quarantine": list(quarantine)}
        if extra:
            data.update(extra)
        p = self.path("m.json")
        with open(p, "w") as fh:
            json.dump(data, fh)
        return p

    def results(self, records, name="r.jsonl"):
        p = self.path(name)
        with open(p, "w") as fh:
            for r in records:
                fh.write((r if isinstance(r, str) else json.dumps(r)) + "\n")
        return p

    def verdict(self, manifest, *results, suite="s"):
        args = ["verdict", "--manifest", manifest, "--suite", suite, "--today", TODAY]
        for r in results:
            args += ["--results", r]
        return run(*args)


def rec(i, s, d=""):
    return {"id": i, "status": s, "detail": d}


def quar(i, expires="2026-10-30", **over):
    q = {"id": i, "suite": "s", "reason": "known", "owner": "planner", "tracking": "T-1",
         "remove_when": "passes", "expires": expires}
    q.update(over)
    return q


class VerdictTest(Base):
    def test_all_required_pass(self):
        rc, out = self.verdict(self.manifest(["a", "b"]), self.results([rec("a", "PASS"), rec("b", "PASS")]))
        self.assertEqual(rc, 0, out)
        self.assertIn("VERDICT s PASS", out)

    def test_required_fail(self):
        rc, out = self.verdict(self.manifest(["a", "b"]), self.results([rec("a", "PASS"), rec("b", "FAIL")]))
        self.assertEqual(rc, 1, out)
        self.assertIn("VERDICT s FAIL", out)

    def test_required_flaky(self):
        rc, _ = self.verdict(self.manifest(["a"]), self.results([rec("a", "FLAKY")]))
        self.assertEqual(rc, 1)

    def test_required_skipped(self):
        rc, _ = self.verdict(self.manifest(["a"]), self.results([rec("a", "SKIPPED")]))
        self.assertEqual(rc, 1)

    def test_required_missing(self):
        rc, out = self.verdict(self.manifest(["a", "b"]), self.results([rec("a", "PASS")]))
        self.assertEqual(rc, 1)
        self.assertIn("MISSING b", out)

    def test_wildcard_zero_matches(self):
        rc, out = self.verdict(self.manifest(["a", "v:S*"]), self.results([rec("a", "PASS")]))
        self.assertEqual(rc, 1)
        self.assertIn("MISSING v:S*", out)

    def test_wildcard_matches_all(self):
        m = self.manifest(["v:S*"])
        self.assertEqual(self.verdict(m, self.results([rec("v:S1", "PASS"), rec("v:S2", "PASS")]))[0], 0)
        self.assertEqual(self.verdict(m, self.results([rec("v:S1", "PASS"), rec("v:S2", "FAIL")]))[0], 1)

    def test_empty_results_file(self):
        p = self.path("e.jsonl")
        open(p, "w").close()
        rc, _ = self.verdict(self.manifest([]), p)
        self.assertEqual(rc, 1)
        rc, _ = self.verdict(self.manifest([]), self.results(["", "  "], "blank.jsonl"))
        self.assertEqual(rc, 1)

    def test_results_file_absent(self):
        rc, out = self.verdict(self.manifest(["a"]), self.path("nope.jsonl"))
        self.assertEqual(rc, 1)
        self.assertIn("missing", out)

    def test_malformed_json_line(self):
        rc, out = self.verdict(self.manifest(["a"]), self.results([rec("a", "PASS"), "{not json"]))
        self.assertEqual(rc, 1)
        self.assertIn(":2", out)

    def test_unknown_status(self):
        rc, _ = self.verdict(self.manifest(["a"]), self.results([rec("a", "PASS"), rec("b", "GREEN")]))
        self.assertEqual(rc, 1)

    def test_missing_id_or_bad_detail(self):
        m = self.manifest(["a"])
        self.assertEqual(self.verdict(m, self.results([rec("a", "PASS"), rec("", "PASS")]))[0], 1)
        self.assertEqual(self.verdict(m, self.results([rec("a", "PASS"), {"id": "x", "status": "PASS", "detail": 3}]))[0], 1)

    def test_duplicate_id(self):
        rc, _ = self.verdict(self.manifest(["a"]), self.results([rec("a", "PASS"), rec("a", "PASS")]))
        self.assertEqual(rc, 1)

    def test_duplicate_id_across_files(self):
        m = self.manifest(["a"])
        rc, _ = self.verdict(m, self.results([rec("a", "PASS")], "1.jsonl"), self.results([rec("a", "PASS")], "2.jsonl"))
        self.assertEqual(rc, 1)

    def test_required_blocked_only(self):
        rc, out = self.verdict(self.manifest(["a", "b"]), self.results([rec("a", "PASS"), rec("b", "BLOCKED")]))
        self.assertEqual(rc, 3, out)
        self.assertIn("VERDICT s BLOCKED", out)

    def test_blocked_plus_fail(self):
        rc, _ = self.verdict(self.manifest(["a", "b"]), self.results([rec("a", "FAIL"), rec("b", "BLOCKED")]))
        self.assertEqual(rc, 1)

    def test_optional_absent(self):
        rc, out = self.verdict(self.manifest(["a"], ["o"]), self.results([rec("a", "PASS")]))
        self.assertEqual(rc, 0)
        self.assertIn("SKIPPED o not executed", out)

    def test_optional_fail(self):
        rc, out = self.verdict(self.manifest(["a"], ["o"]), self.results([rec("a", "PASS"), rec("o", "FAIL")]))
        self.assertEqual(rc, 0)
        self.assertIn("::warning", out)

    def test_quarantine_on_fail(self):
        js = self.path("out.json")
        rc, out = run("verdict", "--manifest", self.manifest(["a"], quarantine=[quar("a")]), "--suite", "s",
                      "--today", TODAY, "--results", self.results([rec("a", "FAIL")]), "--json-out", js)
        self.assertEqual(rc, 0, out)
        self.assertIn("QUARANTINED a", out)
        self.assertIn("::warning", out)
        with open(js) as fh:
            data = json.load(fh)
        self.assertEqual(data["checks"][0]["status"], "QUARANTINED")
        self.assertIn("quarantine", data["checks"][0])

    def test_quarantine_on_pass_warns(self):
        rc, out = self.verdict(self.manifest(["a"], quarantine=[quar("a")]), self.results([rec("a", "PASS")]))
        self.assertEqual(rc, 0)
        self.assertIn("quarantine can be lifted", out)

    def test_quarantine_on_missing(self):
        rc, _ = self.verdict(self.manifest(["a", "b"], quarantine=[quar("b")]), self.results([rec("a", "PASS")]))
        self.assertEqual(rc, 1)

    def test_quarantine_on_blocked(self):
        rc, _ = self.verdict(self.manifest(["a"], quarantine=[quar("a")]), self.results([rec("a", "BLOCKED")]))
        self.assertEqual(rc, 3)

    def test_expired_quarantine(self):
        rc, out = self.verdict(self.manifest(["a"], quarantine=[quar("a", expires="2026-10-01")]),
                               self.results([rec("a", "FAIL")]))
        self.assertEqual(rc, 2)
        self.assertIn("VERDICT s FAIL", out)

    def test_quarantine_too_far(self):
        rc, _ = self.verdict(self.manifest(["a"], quarantine=[quar("a", expires="2026-12-31")]),
                             self.results([rec("a", "FAIL")]))
        self.assertEqual(rc, 2)

    def test_quarantine_missing_field(self):
        q = quar("a")
        del q["owner"]
        rc, _ = self.verdict(self.manifest(["a"], quarantine=[q]), self.results([rec("a", "FAIL")]))
        self.assertEqual(rc, 2)
        rc, _ = self.verdict(self.manifest(["a"], quarantine=[quar("a", reason=" ")]), self.results([rec("a", "FAIL")]))
        self.assertEqual(rc, 2)

    def test_manifest_invalid(self):
        self.assertEqual(self.verdict(self.manifest(["a"], extra={"bogus": 1}), self.results([rec("a", "PASS")]))[0], 2)
        self.assertEqual(self.verdict(self.manifest(["a"], ["a"]), self.results([rec("a", "PASS")]))[0], 2)

    def test_unlisted(self):
        rc, out = self.verdict(self.manifest(["a"]), self.results([rec("a", "PASS"), rec("z", "PASS")]))
        self.assertEqual(rc, 0)
        self.assertIn("unlisted", out)
        self.assertIn("::warning", out)

    def test_summary_written(self):
        sm = self.path("sum.md")
        rc, _ = run("verdict", "--manifest", self.manifest(["a"]), "--suite", "s", "--today", TODAY,
                    "--results", self.results([rec("a", "PASS")]), "--summary", sm)
        self.assertEqual(rc, 0)
        with open(sm) as fh:
            self.assertIn("| PASS | `a` |", fh.read())

    def test_shipped_manifest_validates(self):
        r = self.results([rec("junit:app", "PASS")])
        rc, out = run("verdict", "--manifest", SHIPPED, "--suite", "tier-a-build", "--today", TODAY, "--results", r)
        self.assertEqual(rc, 1, out)  # missing required checks, but the manifest itself is valid
        self.assertNotIn("manifest invalid", out)
        for suite in ("voice", "device", "navpiece"):
            rc, out = run("verdict", "--manifest", SHIPPED, "--suite", suite, "--today", TODAY, "--results", r)
            self.assertNotEqual(rc, 2, out)

    def test_absent_results_tier_a(self):
        rc, _ = run("verdict", "--manifest", SHIPPED, "--suite", "tier-a-build", "--results",
                    self.path("does-not-exist.jsonl"), "--today", TODAY)
        self.assertEqual(rc, 1)


class JunitTest(Base):
    XML_OK = '<testsuite name="A" tests="3" failures="0" errors="0" skipped="1"><testcase classname="c" name="t"/></testsuite>'

    def write_xml(self, module, content, name="TEST-a.xml", task="testDebugUnitTest"):
        d = os.path.join(self.dir, "root", module, "test-results", task)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, name), "w") as fh:
            fh.write(content)

    def junit(self, *modules):
        out = self.path("j.jsonl")
        args = ["junit", "--root", os.path.join(self.dir, "root"), "--out", out]
        for m in modules:
            args += ["--module", m]
        rc, text = run(*args)
        self.assertEqual(rc, 0, text)
        with open(out) as fh:
            return [json.loads(l) for l in fh if l.strip()]

    def test_pass(self):
        self.write_xml("safety", self.XML_OK)
        r = self.junit("safety")[0]
        self.assertEqual((r["id"], r["status"]), ("junit:safety", "PASS"))
        self.assertEqual(r["detail"], "tests=3 failures=0 errors=0 skipped=1")

    def test_failures(self):
        self.write_xml("safety", '<testsuite tests="2" failures="1" errors="0"><testcase classname="pkg.C" name="t1"><failure/></testcase><testcase classname="pkg.C" name="t2"/></testsuite>')
        r = self.junit("safety")[0]
        self.assertEqual(r["status"], "FAIL")
        self.assertIn("pkg.C.t1", r["detail"])

    def test_zero_tests(self):
        self.write_xml("safety", '<testsuite tests="0" failures="0" errors="0"/>')
        self.assertEqual(self.junit("safety")[0]["status"], "FAIL")

    def test_no_xml(self):
        os.makedirs(os.path.join(self.dir, "root", "safety"), exist_ok=True)
        r = self.junit("safety")[0]
        self.assertEqual(r["status"], "FAIL")
        self.assertEqual(r["detail"], "no test report")

    def test_malformed(self):
        self.write_xml("safety", self.XML_OK)
        self.write_xml("safety", '<testsuite tests="3"><testcase', name="TEST-b.xml")
        r = self.junit("safety")[0]
        self.assertEqual(r["status"], "FAIL")
        self.assertIn("malformed report TEST-b.xml", r["detail"])

    def test_testsuites_root(self):
        self.write_xml("app", '<testsuites><testsuite tests="2" failures="0" errors="0" skipped="0"/>'
                              '<testsuite tests="4" failures="0" errors="0" skipped="2"/></testsuites>')
        r = self.junit("app")[0]
        self.assertEqual(r["status"], "PASS")
        self.assertEqual(r["detail"], "tests=6 failures=0 errors=0 skipped=2")

    def test_multiple_modules(self):
        self.write_xml("a", self.XML_OK)
        rs = self.junit("a", "b")
        self.assertEqual([(r["id"], r["status"]) for r in rs], [("junit:a", "PASS"), ("junit:b", "FAIL")])


class ScenariosTest(Base):
    def scen(self, raw_text, prefix="x:", rc=None):
        raw = self.path("raw.txt")
        if raw_text is not None:
            with open(raw, "w", encoding="utf-8") as fh:
                fh.write(raw_text)
        out = self.path("s.jsonl")
        extra = [] if rc is None else ["--runner-rc", str(rc)]
        code, text = run("scenarios", "--raw", raw, "--out", out, "--prefix", prefix, *extra)
        self.assertEqual(code, 0, text)
        with open(out, encoding="ascii") as fh:
            return out, [json.loads(l) for l in fh if l.strip()]

    def test_sample_lines_and_false_success_regression(self):
        out, rs = self.scen("  FAIL YUE-HOME 0/1\n  FLAKY LI-PLACE 6/10\n")
        by = {r["id"]: r for r in rs}
        self.assertEqual(by["x:YUE-HOME"]["status"], "FAIL")
        self.assertEqual((by["x:LI-PLACE"]["status"], by["x:LI-PLACE"]["passes"], by["x:LI-PLACE"]["runs"]), ("FLAKY", 6, 10))
        self.assertEqual(by["x:runner"]["status"], "FAIL")  # no "=== summary ===" header (R3)
        rc, text = self.verdict(self.manifest(["x:runner", "x:YUE-HOME", "x:LI-PLACE"]), out)
        self.assertEqual(rc, 1, text)

    def test_real_runner_format(self):
        raw = ("\n=== pass 1/1 ===\n  PASS  S3    带我回家\n  FAIL  S12b 你好\n"
               "          expected /x/\n\n=== summary ===\n  ok  S3    1/1  带我\n"
               "  FAIL  S12b  0/1  你好\n\n=== last failing log per scenario ===\n"
               "   FAIL S99 noise\n")
        out, rs = self.scen(raw, prefix="voice:")
        by = {r["id"]: r for r in rs}
        self.assertEqual(set(by), {"voice:S3", "voice:S12b", "voice:runner"})
        self.assertEqual(by["voice:S3"]["status"], "PASS")
        self.assertEqual(by["voice:S12b"]["status"], "FAIL")
        with open(out, "rb") as fh:
            fh.read().decode("ascii")

    def test_single_lines_only(self):
        _, rs = self.scen("  PASS S3\n  FAIL S12b\n")
        self.assertEqual({r["id"]: r["status"] for r in rs},
                         {"x:S3": "PASS", "x:S12b": "FAIL", "x:runner": "FAIL"})

    def test_no_scenario_lines(self):
        _, rs = self.scen("adb: device offline\n")
        self.assertEqual(rs, [{"id": "x:runner", "status": "FAIL", "detail": "runner produced no results"}])

    def test_raw_missing(self):
        _, rs = self.scen(None)
        self.assertEqual(rs, [{"id": "x:runner", "status": "BLOCKED", "detail": "raw output missing"}])


class RecordTest(Base):
    def test_escapes_and_strips(self):
        out = os.path.join(self.dir, "sub", "dir", "r.jsonl")
        rc, _ = run("record", "--out", out, "--id", "a", "--status", "FAIL",
                    "--detail", 'say "hi"\nnext 带我回家 end')
        self.assertEqual(rc, 0)
        rc, _ = run("record", "--out", out, "--id", "b", "--status", "FLAKY", "--passes", "6", "--runs", "10",
                    "--detail", "x" * 500)
        self.assertEqual(rc, 0)
        with open(out, "rb") as fh:
            lines = fh.read().decode("ascii").splitlines()
        self.assertEqual(len(lines), 2)
        a, b = json.loads(lines[0]), json.loads(lines[1])
        self.assertEqual(a["detail"], 'say "hi" next  end')
        self.assertEqual((b["passes"], b["runs"], len(b["detail"])), (6, 10, 300))

    def test_invalid_status(self):
        out = self.path("r.jsonl")
        rc, _ = run("record", "--out", out, "--id", "a", "--status", "GREEN")
        self.assertEqual(rc, 2)
        self.assertFalse(os.path.exists(out))


SUMMARY = "\n=== summary ===\n"


class ReviewR1JunitTest(JunitTest):
    def status(self, xml):
        self.write_xml("m", xml)
        return self.junit("m")[0]

    def test_child_only_failure(self):
        r = self.status('<testsuite tests="1" failures="0" errors="0"><testcase classname="c" name="t"><failure/></testcase></testsuite>')
        self.assertEqual(r["status"], "FAIL")

    def test_nested_failing_inner_suite(self):
        r = self.status('<testsuites><testsuite tests="3" failures="0" errors="0"><testsuite tests="2" failures="0" errors="0"/>'
                        '<testsuite tests="1" failures="1" errors="0"><testcase classname="c" name="x"><error/></testcase></testsuite></testsuite></testsuites>')
        self.assertEqual(r["status"], "FAIL")

    def test_negative_counts(self):
        r = self.status('<testsuite tests="2" failures="-1" errors="1"/>')
        self.assertEqual(r["status"], "FAIL")
        self.assertIn("malformed report", r["detail"])

    def test_all_skipped(self):
        r = self.status('<testsuite tests="1" failures="0" errors="0" skipped="1"/>')
        self.assertEqual(r["status"], "FAIL")
        self.assertIn("no test executed", r["detail"])


class ReviewScenarioTest(ScenariosTest):
    def verdict_of(self, raw, required=("x:runner", "x:*")):
        out, rs = self.scen(raw)
        return self.verdict(self.manifest(list(required)), out), rs

    def test_ok_zero_runs(self):
        (rc, text), _ = self.verdict_of("  PASS S1\n" + SUMMARY + "  ok  S1 0/0\n")
        self.assertNotEqual(rc, 0, text)

    def test_ok_flag_with_partial_counts(self):
        (rc, text), rs = self.verdict_of("  PASS S1\n" + SUMMARY + "  ok  S1 6/10\n")
        self.assertNotEqual(rc, 0, text)
        self.assertIn("summary flag contradicts counts", json.dumps(rs))

    def test_run_failed_summary_ok(self):
        (rc, text), _ = self.verdict_of("  FAIL S1\n" + SUMMARY + "  ok  S1 1/1\n")
        self.assertNotEqual(rc, 0, text)

    def test_complete_run_passes(self):
        (rc, text), _ = self.verdict_of("  PASS S1\n" + SUMMARY + "  ok  S1 1/1\n")
        self.assertEqual(rc, 0, text)

    def test_traceback_without_summary(self):
        (rc, text), rs = self.verdict_of("  PASS S1\n  PASS S2\nTraceback (most recent call last):\n  File x\n")
        self.assertEqual({r["id"]: r for r in rs}["x:runner"]["detail"], "run incomplete: no summary")
        self.assertEqual(rc, 1, text)

    def test_summary_missing_an_id(self):
        _, rs = self.scen("  PASS S1\n  PASS S2\n" + SUMMARY + "  ok  S1 1/1\n")
        self.assertEqual({r["id"]: r["status"] for r in rs}["x:runner"], "FAIL")

    def test_runner_rc(self):
        raw = "  PASS S1\n" + SUMMARY + "  ok  S1 1/1\n"
        _, rs = self.scen(raw, rc=2)
        self.assertEqual(rs[-1], {"id": "x:runner", "status": "FAIL", "detail": "runner exit 2"})
        _, rs = self.scen(raw, rc=3)
        self.assertEqual(rs[-1], {"id": "x:runner", "status": "BLOCKED", "detail": "quota guard"})
        _, rs = self.scen(raw, rc=0)
        self.assertEqual(rs[-1]["status"], "PASS")


class RunnerRcOneTest(ScenariosTest):
    def test_rc1_complete_summary(self):
        for sid, want in (("S1", 1), ("YUE-HOME", 0)):
            raw = f"  PASS  S9\n  FAIL  {sid}\n" + SUMMARY + f"  ok  S9 1/1\n  FAIL  {sid} 0/1\n"
            out, rs = self.scen(raw, prefix="cantonese:", rc=1)
            by = {r["id"]: r for r in rs}
            self.assertEqual(by["cantonese:runner"]["status"], "PASS")
            self.assertEqual(by["cantonese:runner"]["detail"], "runner exit 1: scenario failures judged per scenario")
            self.assertEqual(by[f"cantonese:{sid}"]["status"], "FAIL")
            other = self.results([rec("voice:runner", "PASS"), rec("voice:S1", "PASS"), rec("device:runner", "PASS"),
                                  rec("device:D1", "PASS"), rec("liveinfo:runner", "PASS"), rec("liveinfo:L1", "PASS"),
                                  rec("voice:quota-guard", "PASS")], "other.jsonl")
            rc, text = run("verdict", "--manifest", SHIPPED, "--suite", "voice", "--today", TODAY,
                           "--results", out, "--results", other)
            self.assertEqual(rc != 0, bool(want), text)

    def test_rc1_traceback(self):
        _, rs = self.scen("  PASS S1\nTraceback (most recent call last):\n", rc=1)
        self.assertEqual(rs[-1]["status"], "FAIL")

    def test_rc2_complete_summary(self):
        _, rs = self.scen("  PASS S1\n" + SUMMARY + "  ok  S1 1/1\n", rc=2)
        self.assertEqual(rs[-1], {"id": "x:runner", "status": "FAIL", "detail": "runner exit 2"})


class R3Test(ScenariosTest):
    def test_forward_today_refused_without_test_clock(self):
        import datetime
        real = datetime.datetime.now(datetime.timezone.utc).date()
        fwd = (real + datetime.timedelta(days=60)).isoformat()
        q = quar("a", expires=(real + datetime.timedelta(days=75)).isoformat())
        rc, out = run("verdict", "--manifest", self.manifest(["a"], quarantine=[q]), "--suite", "s",
                      "--today", fwd, "--results", self.results([rec("a", "FAIL")]), clock=False)
        self.assertEqual(rc, 2, out)

    def test_duplicate_summary(self):
        _, rs = self.scen("  PASS S1\n" + SUMMARY + "  ok  S1 1/1\n  ok  S1 1/1\n")
        by = {r["id"]: r for r in rs}
        self.assertEqual((by["x:S1"]["status"], by["x:S1"]["detail"]), ("FAIL", "duplicate summary"))

    def test_safety_and_global_checks_not_quarantinable(self):
        for rid in ("voice:quota-guard", "navpiece:quota-guard", "junit:app", "build:assembleDebug",
                    "gate:delegation", "ci:script-tests", "matrix:validate", "gradle:test"):
            with self.subTest(rid=rid):
                rc, out = self.verdict(self.manifest([rid], quarantine=[quar(rid)]), self.results([rec(rid, "FAIL")]))
                self.assertEqual(rc, 2, out)
                self.assertIn("cannot be quarantined", out)


class ReviewVerdictTest(Base):
    def test_pass_record_with_partial_counts_malformed(self):
        rc, _ = self.verdict(self.manifest(["a"]), self.results([{"id": "a", "status": "PASS", "detail": "", "passes": 6, "runs": 10}]))
        self.assertEqual(rc, 1)

    def test_inconsistent_counts_malformed(self):
        m = self.manifest(["a"])
        for p_, n_ in ((7, 5), (-1, 5), (0, 0)):
            rc, _ = self.verdict(m, self.results([{"id": "a", "status": "FAIL", "detail": "", "passes": p_, "runs": n_}]))
            self.assertEqual(rc, 1, (p_, n_))

    def test_empty_required_suite_blocked(self):
        r = self.results([rec("anything", "PASS")])
        rc, out = run("verdict", "--manifest", SHIPPED, "--suite", "device", "--today", TODAY, "--results", r)
        self.assertNotEqual(rc, 0, out)
        rc, out = self.verdict(self.manifest([], ["o"]), self.results([rec("o", "PASS")]))
        self.assertEqual(rc, 3, out)
        self.assertIn("lists no required checks", out)

    def incident(self, li_status, li_passes):
        recs = [rec("voice:runner", "PASS"), rec("voice:S1", "PASS"), rec("device:runner", "PASS"),
                rec("device:D1", "PASS"), rec("liveinfo:runner", "PASS"), rec("cantonese:runner", "PASS"),
                rec("voice:quota-guard", "PASS"),
                {"id": "cantonese:YUE-HOME", "status": "FAIL", "detail": "", "passes": 0, "runs": 1},
                {"id": "liveinfo:LI-PLACE", "status": li_status, "detail": "", "passes": li_passes, "runs": 10}]
        return run("verdict", "--manifest", SHIPPED, "--suite", "voice", "--today", TODAY,
                   "--results", self.results(recs))

    def test_incident_evidence_against_shipped_manifest(self):
        rc, out = self.incident("FLAKY", 6)
        self.assertEqual(rc, 1, out)
        self.assertIn("QUARANTINED cantonese:YUE-HOME", out)
        rc, out = self.incident("PASS", 10)
        self.assertEqual(rc, 0, out)
        self.assertIn("QUARANTINED cantonese:YUE-HOME", out)
        self.assertEqual([l for l in out.splitlines() if l.startswith("VERDICT")], ["VERDICT voice PASS (1 quarantined)"])

    def test_quarantine_wildcard_id(self):
        self.assertEqual(self.verdict(self.manifest(["a:*"], quarantine=[quar("a:*")]), self.results([rec("a:x", "FAIL")]))[0], 2)

    def test_quarantine_runner_id(self):
        self.assertEqual(self.verdict(self.manifest(["a:*"], quarantine=[quar("a:runner")]), self.results([rec("a:runner", "FAIL")]))[0], 2)

    def test_quarantine_unmatched_id(self):
        self.assertEqual(self.verdict(self.manifest(["a"], ["b"], quarantine=[quar("b")]), self.results([rec("a", "PASS")]))[0], 2)

    def test_backdated_today_refused_without_test_clock(self):
        rc, out = run("verdict", "--manifest", self.manifest(["a"]), "--suite", "s", "--today", "2020-01-01",
                      "--results", self.results([rec("a", "PASS")]), clock=False)
        self.assertEqual(rc, 2, out)

    def bad_id(self, rid):
        line = json.dumps({"id": rid, "status": "PASS", "detail": ""})
        rc, out = self.verdict(self.manifest(["a"]), self.results([rec("a", "PASS"), line]))
        self.assertEqual(rc, 1, out)
        self.assertEqual([l for l in out.splitlines() if l.startswith("VERDICT")], ["VERDICT s FAIL"])
        self.assertNotIn("::notice", out)

    def test_whitespace_id(self):
        self.bad_id("   ")

    def test_newline_id(self):
        self.bad_id("x\nVERDICT s PASS\n::notice::hi")
        self.bad_id("x\n")

    def test_wildcard_order_independent(self):
        for req in (["v:*", "v:a"], ["v:a", "v:*"]):
            rc, out = self.verdict(self.manifest(req), self.results([rec("v:a", "PASS")]))
            self.assertEqual(rc, 1, out)  # v:a is exact; the wildcard has no own result
            self.assertIn("MISSING v:*", out)

    def test_record_rejects_bad_id(self):
        rc, _ = run("record", "--out", self.path("r.jsonl"), "--id", "a b", "--status", "PASS")
        self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
