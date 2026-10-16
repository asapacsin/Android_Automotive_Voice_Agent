"""scripts/ci_tier_a.sh: the Tier A verdict comes from ci_verdict.py, never from finishing.

Each test runs the script in a temp copy of the repo layout: the real ci_verdict.py and manifest,
stubs for delegation_check, the review hook, test_matrix, the build script and Gradle.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODULES = ("app", "behavior-test", "contracts", "evaluation", "ingress", "safety", "simulator")
SECRETS = ("AMAP_API_KEY", "NOVA_VENDOR_ZIP_URL", "NOVA_DEBUG_KEYSTORE_B64")
XML_OK = '<testsuite name="A" tests="2" failures="0" errors="0" skipped="0"><testcase classname="c" name="t"/></testsuite>'


def write(path, text, mode=0o644):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        fh.write(text)
    os.chmod(path, mode)


class TierATest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ci_tier_a_")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        r = self.repo = os.path.join(self.tmp, "repo")
        for rel in ("scripts/ci_tier_a.sh", "scripts/ci_verdict.py", "config/ci_checks.json"):
            os.makedirs(os.path.dirname(os.path.join(r, rel)), exist_ok=True)
            shutil.copy(os.path.join(REPO, rel), os.path.join(r, rel))
        # A trivial test tree, so the selftest and script-tests do not recurse into this file.
        write(os.path.join(r, "scripts/tests/__init__.py"), "")
        # ci_verdict --selftest requires scripts.tests.test_ci_verdict to load.
        shutil.copy(os.path.join(REPO, "scripts/tests/test_ci_verdict.py"),
                    os.path.join(r, "scripts/tests/test_ci_verdict.py"))
        write(os.path.join(r, "scripts/tests/test_trivial.py"),
              "import unittest\nclass T(unittest.TestCase):\n    def test_ok(self):\n        pass\n")
        write(os.path.join(r, ".claude/hooks/require-review-before-merge.py"),
              "print('require-review selftest: ok')\n")
        self.set_matrix(0)
        self.set_delegation("GATE delegation PASS\nGATE review-records PASS\n", 0)
        self.build_dir = os.path.join(self.tmp, "build")
        self.results = os.path.join(self.tmp, "results")
        self.bin = os.path.join(self.tmp, "bin")
        self.set_build(0)
        self.set_gradle(0, MODULES)

    def set_matrix(self, stale):
        """Stub: --structural always valid; the full --validate reports `stale` STALE_BIND lines."""
        write(os.path.join(self.repo, "scripts/test_matrix.py"),
              "import sys\nif '--structural' in sys.argv:\n    print('0 problem(s)')\n    sys.exit(0)\n"
              "for i in range(%d):\n    print('  R%%d: STALE_BIND code_digest mismatch' %% i)\n"
              "sys.exit(1 if %d else 0)\n" % (stale, stale))

    def set_delegation(self, out, rc):
        write(os.path.join(self.repo, "scripts/delegation_check.py"),
              "import os, sys\nassert '--ci' in sys.argv\n"
              "open(os.path.join(os.path.dirname(__file__), 'argv.txt'), 'w').write(' '.join(sys.argv[1:]))\n"
              "sys.stdout.write(%r)\nsys.exit(%d)\n" % (out, rc))

    def set_build(self, rc):
        write(os.path.join(self.tmp, "bin/build.sh"), "#!/bin/bash\nexit %d\n" % rc, 0o755)

    def set_gradle(self, rc, modules):
        lines = ["#!/bin/bash"]
        for m in modules:
            d = os.path.join(self.build_dir, m, "test-results", "test")
            lines.append("mkdir -p '%s' && echo '%s' > '%s/TEST-a.xml'" % (d, XML_OK, d))
        lines.append("exit %d" % rc)
        write(os.path.join(self.tmp, "bin/gradle"), "\n".join(lines) + "\n", 0o755)

    def run_phase(self, phase, secrets=True, upstream=None):
        env = {k: v for k, v in os.environ.items() if k not in SECRETS and k != "GITHUB_STEP_SUMMARY"}
        env.update(CI_RESULTS_DIR=self.results, CI_PYTHON=sys.executable, NOVA_BUILD_DIR=self.build_dir,
                   CI_GRADLE=os.path.join(self.bin, "gradle"), CI_BUILD_SCRIPT=os.path.join(self.bin, "build.sh"))
        env.pop("CI_UPSTREAM_REF", None)
        env.pop("GRADLE_OPTS", None)
        if upstream:
            env["CI_UPSTREAM_REF"] = upstream
        if secrets:
            env.update({k: "x" for k in SECRETS})
        p = subprocess.run(["bash", os.path.join(self.repo, "scripts/ci_tier_a.sh"), phase], env=env,
                           cwd=self.tmp, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
        return p.returncode, p.stdout

    def records(self, phase):
        with open(os.path.join(self.results, "tier-a-%s.jsonl" % phase)) as fh:
            return {r["id"]: r for r in map(json.loads, fh)}

    def test_build_missing_secret_blocked(self):
        rc, out = self.run_phase("build-test", secrets=False)
        self.assertEqual(rc, 3, out)
        with open(os.path.join(self.results, "verdict-build-test.json")) as fh:
            self.assertIn("BLOCKED", json.dumps(json.load(fh)))
        recs = self.records("build-test")
        self.assertEqual(len(recs), 9)
        for r in recs.values():
            self.assertEqual(r["status"], "BLOCKED", r)
            self.assertIn("not set", r["detail"])
        self.assertNotIn("=x", out)

    def test_gates_review_records_fail(self):
        self.set_delegation("GATE delegation PASS\nGATE review-records FAIL\n", 1)
        rc, out = self.run_phase("gates")
        self.assertEqual(rc, 1, out)
        self.assertEqual(self.records("gates")["gate:review-records"]["status"], "FAIL")

    def test_gates_no_gate_line_fails(self):
        self.set_delegation("something else\n", 0)
        rc, out = self.run_phase("gates")
        self.assertEqual(rc, 1, out)
        recs = self.records("gates")
        for g in ("gate:delegation", "gate:review-records"):
            self.assertEqual(recs[g]["status"], "FAIL")
            self.assertIn("no GATE line", recs[g]["detail"])

    def test_gates_all_pass(self):
        rc, out = self.run_phase("gates")
        if "PyYAML missing" in json.dumps(self.records("gates")):
            self.assertEqual(rc, 3, out)
        else:
            self.assertEqual(rc, 0, out)

    def test_stale_binds_are_optional_warning(self):
        self.set_matrix(3)
        rc, out = self.run_phase("gates")
        recs = self.records("gates")
        if recs["matrix:validate"]["status"] == "BLOCKED":
            self.skipTest("PyYAML missing")
        self.assertEqual(rc, 0, out)
        self.assertEqual(recs["matrix:validate"]["status"], "PASS")
        self.assertEqual(recs["matrix:evidence-current"]["status"], "FAIL")
        self.assertIn("3 rows with stale binds", recs["matrix:evidence-current"]["detail"])

    def delegation_argv(self):
        with open(os.path.join(self.repo, "scripts/argv.txt")) as fh:
            return fh.read().split()

    def test_upstream_passed_only_when_set(self):
        self.run_phase("gates", upstream="origin/main")
        self.assertEqual(self.delegation_argv(), ["--ci", "--upstream", "origin/main"])
        self.run_phase("gates")
        self.assertEqual(self.delegation_argv(), ["--ci"])

    def test_gate_pass_lines_with_nonzero_exit_fail(self):
        self.set_delegation("GATE delegation PASS\nGATE review-records PASS\n", 1)
        rc, out = self.run_phase("gates")
        self.assertEqual(rc, 1, out)
        recs = self.records("gates")
        for g in ("gate:delegation", "gate:review-records"):
            self.assertEqual(recs[g]["status"], "FAIL")
            self.assertIn("exit 1", recs[g]["detail"])

    def test_gate_duplicate_lines_fail(self):
        self.set_delegation("GATE delegation FAIL\nGATE delegation PASS\nGATE review-records PASS\n", 0)
        rc, out = self.run_phase("gates")
        self.assertEqual(rc, 1, out)
        recs = self.records("gates")
        self.assertEqual(recs["gate:delegation"]["status"], "FAIL")
        self.assertIn("2 line(s)", recs["gate:delegation"]["detail"])
        self.assertEqual(recs["gate:review-records"]["status"], "PASS")

    def test_stale_xml_is_removed(self):
        for m in MODULES:
            d = os.path.join(self.build_dir, m, "test-results", "test")
            write(os.path.join(d, "TEST-old.xml"), XML_OK)
        self.set_gradle(0, ())
        rc, out = self.run_phase("build-test")
        self.assertEqual(rc, 1, out)
        recs = self.records("build-test")
        for m in MODULES:
            self.assertEqual(recs["junit:" + m]["status"], "FAIL", m)

    def test_gradle_gets_no_build_cache(self):
        write(os.path.join(self.bin, "gradle"),
              "#!/bin/bash\necho \"$* | $GRADLE_OPTS\" > '%s'\nexit 1\n" % os.path.join(self.tmp, "g.txt"), 0o755)
        self.run_phase("build-test")
        with open(os.path.join(self.tmp, "g.txt")) as fh:
            args = fh.read()
        self.assertIn("--no-build-cache", args)
        self.assertIn("-Dorg.gradle.caching=false", args)

    def test_build_all_pass(self):
        rc, out = self.run_phase("build-test")
        self.assertEqual(rc, 0, out)

    def test_gradle_fail_with_green_xml_fails(self):
        self.set_gradle(1, MODULES)
        rc, out = self.run_phase("build-test")
        self.assertEqual(rc, 1, out)
        recs = self.records("build-test")
        self.assertEqual(recs["gradle:test"]["status"], "FAIL")
        self.assertEqual(recs["junit:app"]["status"], "PASS")

    def test_missing_module_xml_fails(self):
        self.set_gradle(0, MODULES[:-1])
        rc, out = self.run_phase("build-test")
        self.assertEqual(rc, 1, out)
        self.assertEqual(self.records("build-test")["junit:simulator"]["status"], "FAIL")

    def test_build_fail_still_runs_tests(self):
        self.set_build(1)
        rc, out = self.run_phase("build-test")
        self.assertEqual(rc, 1, out)
        recs = self.records("build-test")
        self.assertEqual(recs["build:assembleDebug"]["status"], "FAIL")
        self.assertEqual(recs["gradle:test"]["status"], "PASS")


class WorkflowCacheTest(unittest.TestCase):
    """The Gradle build cache (secret-derived outputs) must never reach actions/cache."""

    def test_no_build_cache_in_cached_paths(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML missing")
        with open(os.path.join(REPO, ".github/workflows/unit-tests.yml")) as fh:
            wf = yaml.safe_load(fh)
        paths = []
        for job in wf["jobs"].values():
            for step in job.get("steps", []):
                if str(step.get("uses", "")).startswith("actions/cache"):
                    paths += [p.strip() for p in str(step["with"]["path"]).splitlines() if p.strip()]
        self.assertTrue(paths)
        for p in paths:
            norm = p.rstrip("/").replace("$HOME", "~")
            self.assertNotIn("build-cache", norm)
            self.assertNotIn("*", norm)
            self.assertNotIn(norm, ("~/.gradle", "~/.gradle/caches", "~"), p)

    def test_script_gradle_uses_no_build_cache(self):
        with open(os.path.join(REPO, "scripts/ci_tier_a.sh")) as fh:
            lines = [l for l in fh if "gradle_cmd[@]" in l and "run_check" in l]
        self.assertTrue(lines)
        for l in lines:
            self.assertIn("--no-build-cache", l)


if __name__ == "__main__":
    unittest.main()
