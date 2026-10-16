"""Tier B emulator workflows: a verdict step that can fail the job, and no suppressed runners."""
import json
import os
import re
import subprocess
import unittest

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORKFLOWS = {
    "voice-checks.yml": "voice",
    "device-checks.yml": "device",
    "nav-demo.yml": "navpiece",
}
SCRIPTS = [
    "scripts/github_voice_checks.sh",
    "scripts/github_device_checks.sh",
    "scripts/github_nav_demo.sh",
    "scripts/github_test_worker.sh",
]


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
        return fh.read()


class WorkflowVerdictTest(unittest.TestCase):
    def test_verdict_step_runs_always_before_evidence_push(self):
        for name, suite in WORKFLOWS.items():
            with self.subTest(workflow=name):
                doc = yaml.safe_load(read(f".github/workflows/{name}"))
                steps = next(iter(doc["jobs"].values()))["steps"]
                names = [s.get("name", "") for s in steps]
                self.assertIn("Verdict", names)
                vi = names.index("Verdict")
                step = steps[vi]
                self.assertEqual(str(step.get("if")).strip(), "always()")
                self.assertNotIn("continue-on-error", step)
                self.assertIn(f"--suite {suite}", step["run"])
                self.assertIn("--results evidence/results.jsonl", step["run"])
                push = [i for i, n in enumerate(names) if n.startswith("Push the evidence")]
                self.assertTrue(push, "no evidence push step")
                self.assertLess(vi, push[0])
                self.assertEqual(str(steps[push[0]].get("if")).strip(), "always()")

    def test_device_checks_runs_on_pull_requests(self):
        doc = yaml.safe_load(read(".github/workflows/device-checks.yml"))
        on = doc.get("on", doc.get(True))
        self.assertIn("pull_request", on)
        self.assertIn("app/src/main/**", on["pull_request"]["paths"])
        self.assertIn("workflow_dispatch", on)
        self.assertTrue(doc["concurrency"]["cancel-in-progress"])
        job_if = next(iter(doc["jobs"].values()))["if"]
        self.assertIn("github.event_name != 'push'", job_if)

    def test_voice_checks_uses_shared_build(self):
        self.assertIn("bash scripts/github_build_apk.sh", read(".github/workflows/voice-checks.yml"))


class ScriptTest(unittest.TestCase):
    def test_no_suppressed_runner(self):
        pat = re.compile(r"(run_scenarios|host_audio_bridge|github_nav_demo)[^#\n]*\|\|\s*true")
        for rel in SCRIPTS:
            with self.subTest(script=rel):
                text = read(rel).replace("\\\n", " ")  # join continuation lines
                self.assertIsNone(pat.search(text))

    def test_bash_syntax(self):
        for rel in SCRIPTS:
            with self.subTest(script=rel):
                proc = subprocess.run(["bash", "-n", os.path.join(ROOT, rel)],
                                      capture_output=True, text=True)
                self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_device_result_ids_are_in_the_manifest(self):
        suite = json.loads(read("config/ci_checks.json"))["suites"]["device"]
        listed = set(suite["required"]) | set(suite["optional"])
        ids = set(re.findall(r"\b(?:res|pf) \"?(device:[A-Za-z0-9_.\-]+)",
                             read("scripts/github_device_checks.sh"), re.M))
        self.assertTrue(ids)
        self.assertEqual(sorted(ids - listed), [])
        self.assertEqual(sorted(listed - ids), [])

    def test_voice_script_uses_devrows_prefix(self):
        text = read("scripts/github_voice_checks.sh")
        self.assertIn("suite device devrows:", text)
        voice = json.loads(read("config/ci_checks.json"))["suites"]["voice"]
        self.assertFalse([p for p in voice["required"] + voice["optional"] if p.startswith("device:")])


if __name__ == "__main__":
    unittest.main()
