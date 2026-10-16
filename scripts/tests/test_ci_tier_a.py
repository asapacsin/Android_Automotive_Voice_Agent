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
        write(os.path.join(r, "scripts/tests/test_trivial.py"),
              "import unittest\nclass T(unittest.TestCase):\n    def test_ok(self):\n        pass\n")
        write(os.path.join(r, ".claude/hooks/require-review-before-merge.py"),
              "print('require-review selftest: ok')\n")
        write(os.path.join(r, "scripts/test_matrix.py"), "print('valid')\n")
        self.set_delegation("GATE delegation PASS\nGATE review-records PASS\n", 0)
        self.build_dir = os.path.join(self.tmp, "build")
        self.results = os.path.join(self.tmp, "results")
        self.bin = os.path.join(self.tmp, "bin")
        self.set_build(0)
        self.set_gradle(0, MODULES)

    def set_delegation(self, out, rc):
        write(os.path.join(self.repo, "scripts/delegation_check.py"),
              "import sys\nassert '--ci' in sys.argv\nsys.stdout.write(%r)\nsys.exit(%d)\n" % (out, rc))

    def set_build(self, rc):
        write(os.path.join(self.tmp, "bin/build.sh"), "#!/bin/bash\nexit %d\n" % rc, 0o755)

    def set_gradle(self, rc, modules):
        lines = ["#!/bin/bash"]
        for m in modules:
            d = os.path.join(self.build_dir, m, "test-results", "test")
            lines.append("mkdir -p '%s' && echo '%s' > '%s/TEST-a.xml'" % (d, XML_OK, d))
        lines.append("exit %d" % rc)
        write(os.path.join(self.tmp, "bin/gradle"), "\n".join(lines) + "\n", 0o755)

    def run_phase(self, phase, secrets=True):
        env = {k: v for k, v in os.environ.items() if k not in SECRETS and k != "GITHUB_STEP_SUMMARY"}
        env.update(CI_RESULTS_DIR=self.results, CI_PYTHON=sys.executable, NOVA_BUILD_DIR=self.build_dir,
                   CI_GRADLE=os.path.join(self.bin, "gradle"), CI_BUILD_SCRIPT=os.path.join(self.bin, "build.sh"))
        env.pop("CI_UPSTREAM_REF", None)
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


if __name__ == "__main__":
    unittest.main()
