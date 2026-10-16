"""Tier B emulator workflows: a verdict step that can fail the job, and no suppressed runners."""
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

import yaml

from scripts.tests.test_ci_verdict import Base, rec, run

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


def read_abs(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


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
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", job_if)
        self.assertIn("github.event_name == 'pull_request' && github.ref || github.run_id",
                      doc["concurrency"]["group"])

    def test_device_checks_need_no_voice_model(self):
        self.assertNotIn("GEMINI", read(".github/workflows/device-checks.yml"))
        self.assertNotIn("GEMINI", read("scripts/github_device_checks.sh"))

    def test_missing_app_secrets_give_blocked(self):
        for name, suite in WORKFLOWS.items():
            with self.subTest(workflow=name):
                doc = yaml.safe_load(read(f".github/workflows/{name}"))
                steps = next(iter(doc["jobs"].values()))["steps"]
                step = next(s for s in steps if s.get("name") == "Require the app secrets")
                self.assertEqual(step.get("id"), "secrets")
                for later in steps[steps.index(step) + 1:]:
                    if later["name"] in ("Verdict",) or later["name"].startswith("Push the evidence"):
                        continue
                    self.assertEqual(later.get("if"), "steps.secrets.outputs.ok == 'true'", later["name"])
                with tempfile.TemporaryDirectory() as tmp:
                    os.makedirs(os.path.join(tmp, "scripts"))
                    os.makedirs(os.path.join(tmp, "config"))
                    shutil.copy(os.path.join(ROOT, "scripts/ci_verdict.py"), os.path.join(tmp, "scripts"))
                    shutil.copy(os.path.join(ROOT, "config/ci_checks.json"), os.path.join(tmp, "config"))
                    out = os.path.join(tmp, "gh_output")
                    env = {k: v for k, v in os.environ.items()
                           if k not in ("AMAP_API_KEY", "NOVA_VENDOR_ZIP_URL", "NOVA_DEBUG_KEYSTORE_B64")}
                    env["GITHUB_OUTPUT"] = out
                    proc = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=tmp, env=env,
                                          capture_output=True, text=True)
                    self.assertEqual(proc.returncode, 0, proc.stderr)
                    self.assertIn("ok=false", read_abs(out))
                    rc, text = run("verdict", "--manifest", os.path.join(tmp, "config/ci_checks.json"),
                                   "--suite", suite, "--results",
                                   os.path.join(tmp, "evidence/results.jsonl"), clock=False)
                    self.assertEqual(rc, 3, text)
                    self.assertIn("secret AMAP_API_KEY not set", read_abs(os.path.join(tmp, "evidence/results.jsonl")))

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


# Real debug_tool lines (formats of DebugToolReceiver.kt; values from the 2026-10-16 device run
# run_20261016_1134_2db208c), and refusals or empty output that must not pass.
GOOD = {
    "settings": 'debug_tool tool=open_app arg=settings result=Accepted',
    "maps": 'debug_tool tool=open_app arg=maps result=Accepted',
    "music": 'debug_tool tool=dispatch arg=open_app result={"ok":false,"error":"APP_NOT_ALLOWED"}',
    "overview": 'debug_tool tool=nav_overview arg=overview result=overview=true',
    "lock": 'debug_tool tool=nav_lock arg=lock result=lock=true',
    "p61": 'debug_tool tool=dispatch arg=navigate_to result={"ok":false,"error":"NEARBY_QUESTION_WHILE_NAVIGATING"}',
    "call": 'debug_tool tool=dispatch arg=place_call result={"ok":false,"error":"CONTACT_NOT_FOUND"}',
}


def device_script():
    return read("scripts/github_device_checks.sh")


def result_of(line):
    """Apply the script's own result_of extraction to one log line."""
    body = re.search(r"^result_of\(\) \{\n(.*?)\n\}", device_script(), re.M | re.S).group(1)
    pattern = re.search(r"grep -oE '([^']+)'", body).group(1)
    proc = subprocess.run(["grep", "-oE", pattern], input=line, capture_output=True, text=True)
    return " ".join(proc.stdout.split("\n")).strip() + (" " if proc.stdout else "")


def condition(check_id):
    m = re.search(r"pf " + re.escape(check_id) + r" \"[^\"]*\" '(.*)'(?:; fi)?$", device_script(), re.M)
    return m.group(1)


def judge(check_id, **variables):
    env = dict(os.environ)
    env.update({k: str(v) for k, v in variables.items()})
    proc = subprocess.run(["bash", "-c", 'if eval "$COND"; then echo PASS; else echo FAIL; fi'],
                          env={**env, "COND": condition(check_id)}, capture_output=True, text=True)
    return proc.stdout.strip()


class DeviceConditionTest(unittest.TestCase):
    def test_result_of_on_real_lines(self):
        self.assertEqual(result_of(GOOD["settings"]).strip(), "result=Accepted")
        self.assertEqual(result_of(GOOD["overview"]).strip(), "result=overview=true")
        self.assertIn('"error":"NEARBY_QUESTION_WHILE_NAVIGATING"', result_of(GOOD["p61"]))

    def test_apps(self):
        # The script derives apps_ok and music_refused with these greps; replay them.
        def apps(settings, maps, music):
            ok = sum(1 for line in (settings, maps) if "result=Accepted" in result_of(line))
            refused = re.search(r'"ok":false|"error":"[A-Z_]+"|Rejected\(', result_of(music)) is not None
            return judge("device:APPS-001", apps_ok=ok, music_refused="yes" if refused else "no")
        self.assertEqual(apps(GOOD["settings"], GOOD["maps"], GOOD["music"]), "PASS")
        self.assertEqual(apps("debug_tool tool=open_app arg=settings result=Rejected(NOT_ALLOWED)",
                              GOOD["maps"], GOOD["music"]), "FAIL")
        self.assertEqual(apps(GOOD["settings"], GOOD["maps"],
                              'debug_tool tool=dispatch arg=open_app result={"ok":true}'), "FAIL")
        self.assertEqual(apps("", "", ""), "FAIL")
        self.assertIn("result=Accepted", device_script())

    def test_nav_ui_007(self):
        self.assertEqual(judge("device:NAV-UI-007", ov_res=result_of(GOOD["overview"]),
                               lock_res=result_of(GOOD["lock"])), "PASS")
        self.assertEqual(judge("device:NAV-UI-007", ov_res=result_of(GOOD["overview"]),
                               lock_res=result_of("debug_tool tool=nav_lock arg=lock result=lock=false")), "FAIL")
        self.assertEqual(judge("device:NAV-UI-007", ov_res="", lock_res=""), "FAIL")

    def test_p61(self):
        self.assertEqual(judge("device:PIECE-P61", replaced=0, p61_res=result_of(GOOD["p61"])), "PASS")
        self.assertEqual(judge("device:PIECE-P61", replaced=1, p61_res=result_of(GOOD["p61"])), "FAIL")
        self.assertEqual(judge("device:PIECE-P61", replaced=0, p61_res=""), "FAIL")
        self.assertEqual(judge("device:PIECE-P61", replaced=0,
                               p61_res=result_of('debug_tool tool=dispatch arg=x result={"ok":true}')), "FAIL")
        self.assertIn('res device:PIECE-P61 FAIL "not navigating"', device_script())

    def test_p63(self):
        self.assertEqual(judge("device:PIECE-P63", p63_amap=1,
                               p63_drop="nav_alert_dropped reason=not_navigating"), "PASS")
        self.assertEqual(judge("device:PIECE-P63", p63_amap=1, p63_drop="nav_alert_dropped reason=busy"), "FAIL")
        self.assertEqual(judge("device:PIECE-P63", p63_amap=0,
                               p63_drop="nav_alert_dropped reason=not_navigating"), "FAIL")
        self.assertEqual(judge("device:PIECE-P63", p63_amap=1, p63_drop=""), "FAIL")

    def test_arrival(self):
        self.assertEqual(judge("device:NAV-E2E-ARRIVAL-001", prox=1,
                               stopped="nav_stopped reached=true reason=emulator_end"), "PASS")
        self.assertEqual(judge("device:NAV-E2E-ARRIVAL-001", prox=0,
                               stopped="nav_stopped reached=true reason=user"), "FAIL")
        self.assertEqual(judge("device:NAV-E2E-ARRIVAL-001", prox=1,
                               stopped="nav_stopped reached=false reason=emulator_end"), "FAIL")
        self.assertEqual(judge("device:NAV-E2E-ARRIVAL-001", prox=0, stopped=""), "FAIL")

    def test_call_nosim_graceful(self):
        self.assertEqual(judge("device:CALL-NOSIM-001", call_res=result_of(GOOD["call"]), call_fatal=0), "PASS")
        self.assertEqual(judge("device:CALL-NOSIM-001", call_res=result_of(GOOD["call"]), call_fatal=1), "FAIL")
        self.assertEqual(judge("device:CALL-NOSIM-001", call_res="", call_fatal=0), "FAIL")

    def test_place_save_and_crash(self):
        self.assertEqual(judge("device:PLACE-SAVE-001", saved=1), "PASS")
        self.assertEqual(judge("device:PLACE-SAVE-001", saved=0), "FAIL")
        self.assertEqual(judge("device:NAV-CRASH-001", fatal=0, anr=0), "PASS")
        self.assertEqual(judge("device:NAV-CRASH-001", fatal=0, anr=1), "FAIL")
        self.assertIn("Process: com\\.novadrive", device_script())

    def test_procdeath_marks_before_relaunch(self):
        text = device_script()
        kill = text.index("am kill com.novadrive.app")
        self.assertLess(text.index("\nmark", kill), text.index("if app_up; then pd_up=yes", kill))


class VerdictGapTest(Base):
    def test_navpiece_only_bridge_is_not_pass(self):
        rc, text = run("verdict", "--manifest", os.path.join(ROOT, "config/ci_checks.json"),
                       "--suite", "navpiece", "--results",
                       self.results([rec("navpiece:bridge", "PASS")]), clock=False)
        self.assertNotEqual(rc, 0, text)
        self.assertIn("navpiece:complete", text)

    def test_scenarios_no_summary_rc3_blocked(self):
        raw = self.path("raw.txt")
        with open(raw, "w") as fh:
            fh.write("Traceback (most recent call last):\n  QUOTA GUARD\n")
        out = self.path("out.jsonl")
        rc, text = run("scenarios", "--raw", raw, "--out", out, "--prefix", "voice:", "--runner-rc", "3")
        self.assertEqual(rc, 0, text)
        recs = [json.loads(line) for line in read_abs(out).splitlines()]
        self.assertIn({"id": "voice:runner", "status": "BLOCKED"},
                      [{"id": r["id"], "status": r["status"]} for r in recs])

    def test_wildcard_blocked_by_blocked_runner(self):
        m = self.manifest(required=["x:runner", "x:*"])
        rc, text = self.verdict(m, self.results([rec("x:runner", "BLOCKED", "secret not set")]))
        self.assertEqual(rc, 3, text)

    def test_wildcard_missing_when_runner_fails_or_absent(self):
        m = self.manifest(required=["x:runner", "x:*"])
        rc, text = self.verdict(m, self.results([rec("x:runner", "FAIL")]))
        self.assertEqual(rc, 1, text)
        m = self.manifest(required=["x:*", "y:ok"])
        rc, text = self.verdict(m, self.results([rec("y:ok", "PASS")]))
        self.assertEqual(rc, 1, text)
        self.assertIn("required x:* has no result", text)


class WorkerTest(unittest.TestCase):
    def test_nonzero_piece_never_publishes_pass(self):
        text = read("scripts/github_test_worker.sh")
        snippet = re.search(r"^  if \[ \"\$piece_rc\" != 0 \].*$", text, re.M).group(0)
        for rc, verdict, want in ((124, "PASS", "FAIL (piece exit 124)"), (0, "PASS", "PASS"),
                                  (1, "FAIL", "FAIL"), (3, "BLOCKED", "BLOCKED")):
            proc = subprocess.run(["bash", "-c", f'piece_rc={rc}; verdict={verdict}\n{snippet}\necho "$verdict"'],
                                  capture_output=True, text=True)
            self.assertEqual(proc.stdout.strip(), want)


STUB_PY = """#!/bin/sh
case "$1" in
  *host_audio_bridge.py)
    echo "bridge transcript stand-in"
    echo "bridge err stand-in" >&2
    : >"$NOVA_TEST_MARK"
    exec "$NOVA_REAL_PY" -c "import time; time.sleep(60)" ;;
esac
exec "$NOVA_REAL_PY" "$@"
"""
STUB_ADB = """#!/bin/sh
case "$*" in
  *"logcat -d"*) echo "map_loaded session_provider choice=gemini_live" ;;
esac
exit 0
"""


class AbortCleanupTest(unittest.TestCase):
    def test_kill_mid_bridge_leaves_no_bridge_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            for rel in ("scripts/github_nav_demo.sh", "scripts/ci_verdict.py", "scripts/emulator.ps1",
                        "config/ci_checks.json"):
                os.makedirs(os.path.join(tmp, os.path.dirname(rel)), exist_ok=True)
                shutil.copy(os.path.join(ROOT, rel), os.path.join(tmp, rel))
            stub = os.path.join(tmp, "stub")
            os.makedirs(stub)
            for name, body in (("python3", STUB_PY), ("adb", STUB_ADB), ("sudo", "#!/bin/sh\nexit 0\n"),
                               ("sleep", "#!/bin/sh\nexit 0\n"), ("ffmpeg", "#!/bin/sh\nexit 1\n")):
                path = os.path.join(stub, name)
                with open(path, "w") as fh:
                    fh.write(body)
                os.chmod(path, 0o755)
            mark = os.path.join(tmp, "bridge_running")
            env = dict(os.environ, PATH=stub + os.pathsep + os.environ["PATH"], NOVA_REAL_PY=sys.executable,
                       NOVA_TEST_MARK=mark, GEMINI_API_KEY="x", AMAP_WEB_KEY="x", NAV_DEMO_PIECE="")
            proc = subprocess.Popen(["bash", os.path.join(tmp, "scripts/github_nav_demo.sh"), "app.apk"],
                                    env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                    start_new_session=True)
            try:
                deadline = time.time() + 60
                while not os.path.exists(mark) and time.time() < deadline and proc.poll() is None:
                    time.sleep(0.1)
                self.assertTrue(os.path.exists(mark), "bridge stub never started")
                ev = os.path.join(tmp, "evidence")
                self.assertTrue([f for f in os.listdir(ev) if f.startswith("bridge_")])
                os.killpg(proc.pid, signal.SIGTERM)
                proc.wait(timeout=30)
            finally:
                if proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGKILL)
            self.assertEqual([f for f in os.listdir(ev) if f.startswith("bridge_")], [])
            self.assertNotIn("navpiece:complete", read_abs(os.path.join(ev, "results.jsonl")))


if __name__ == "__main__":
    unittest.main()
