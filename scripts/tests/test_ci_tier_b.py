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
    def test_nav_demo_main_piece_push_marker_selects_demo(self):
        doc = yaml.safe_load(read(".github/workflows/nav-demo.yml"))
        steps = next(iter(doc["jobs"].values()))["steps"]
        recorder = next(s for s in steps if s.get("name") == "Boot the emulator and record the navigation demo")
        piece = recorder["env"]["NAV_DEMO_PIECE"]
        self.assertIn("contains(github.event.head_commit.message, '[navpiece:demo]') && 'demo'", piece)
        script = read("scripts/github_nav_demo.sh")
        self.assertIn("duration=360; DEMO_GAP=1; DEMO_LEAD=1", script)
        self.assertIn('if [ "${NAV_DEMO_PIECE:-}" = "demo" ]', script)

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
                if name == "voice-checks.yml":  # one verdict per shard: the shard's own suite
                    self.assertIn('--suite "${VOICE_SUITE:-voice}"', step["run"])
                elif name == "device-checks.yml":  # one verdict per shard (DEV-SPLIT)
                    self.assertIn('--suite "${DEVICE_SUITE:-device}"', step["run"])
                else:
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
                    # The APK build may also be skipped on an APK cache hit; it still needs the secrets.
                    want = "steps.secrets.outputs.ok == 'true'"
                    if later["name"] == "Build the debug APK":
                        self.assertIn(later.get("if"), (want, want + " && steps.apkcache.outputs.cache-hit != 'true'"), later["name"])
                        continue
                    self.assertEqual(later.get("if"), want, later["name"])
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
        # Recorded by `ci_verdict.py vendor`, not by res (StubVendorWorkflowTest checks the call).
        ids.add("build:vendor-real")
        self.assertEqual(sorted(ids - listed), [])
        self.assertEqual(sorted(listed - ids), [])

    def test_voice_script_uses_devrows_prefix(self):
        text = read("scripts/github_voice_checks.sh")
        self.assertIn("suite device devrows:", text)
        voice = json.loads(read("config/ci_checks.json"))["suites"]["voice"]
        self.assertFalse([p for p in voice["required"] + voice["optional"] if p.startswith("device:")])



SHARD_SUITES = {"voice-a": "voice-a", "voice-b": "voice-b", "voice-c": "voice-c",
                "device": "voice-device", "li-weather": "voice-li-weather",
                "li-place": "voice-li-place", "li-traffic": "voice-li-traffic",
                "li-along": "voice-li-along", "demo": "voice-demo"}


class VoiceShardTest(unittest.TestCase):
    """VC-SPLIT: voice checks run as shards of <=5 min of tests, one verdict per shard."""

    def manifest(self):
        return json.loads(read("config/ci_checks.json"))["suites"]

    def scenario_ids(self, name):
        return [s["id"] for s in json.loads(read("tools/speech-harness/" + name))["scenarios"]]

    def pick(self, shards):
        doc = yaml.safe_load(read(".github/workflows/voice-checks.yml"))
        step = next(s for s in doc["jobs"]["plan"]["steps"] if s.get("id") == "pick")
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "out")
            env = dict(os.environ, GITHUB_OUTPUT=out, SHARDS=shards)
            proc = subprocess.run(["bash", "-e", "-c", step["run"]], env=env, capture_output=True, text=True)
            if proc.returncode:
                return None
            line = read_abs(out).strip()
        self.assertTrue(line.startswith("matrix="))
        return json.loads(line[len("matrix="):])["include"]

    def test_plan_picks_all_or_named_shards(self):
        for raw in ("", "all"):
            self.assertEqual({e["shard"]: e["suite"] for e in self.pick(raw)}, SHARD_SUITES)
        self.assertEqual(self.pick("device, li-along,device"),
                         [{"shard": "device", "suite": "voice-device"},
                          {"shard": "li-along", "suite": "voice-li-along"}])
        self.assertIsNone(self.pick("voice-z"))

    def test_matrix_is_fail_fast_false_and_built_per_shard(self):
        doc = yaml.safe_load(read(".github/workflows/voice-checks.yml"))
        job = doc["jobs"]["drive"]
        self.assertIs(job["strategy"]["fail-fast"], False)
        self.assertEqual(job["needs"], "plan")
        runs = "\n".join(str(s.get("run", "")) + str(s.get("uses", "")) for s in job["steps"])
        self.assertIn("bash scripts/github_build_apk.sh", runs)
        self.assertNotIn("upload-artifact", runs)  # the debug APK is key-bearing
        on = doc.get("on", doc.get(True))
        self.assertEqual(on["workflow_dispatch"]["inputs"]["shards"]["default"], "all")
        self.assertIn("[voice]", doc["jobs"]["plan"]["if"])

    def test_evidence_push_is_per_shard_with_retry(self):
        doc = yaml.safe_load(read(".github/workflows/voice-checks.yml"))
        step = next(s for s in doc["jobs"]["drive"]["steps"] if s["name"].startswith("Push the evidence"))
        self.assertIn('runs/$dir/$VOICE_SHARD', step["run"])
        self.assertIn("git rebase", step["run"])
        self.assertIn("for attempt in", step["run"])

    def test_script_id_lists_match_the_shard_suites(self):
        text = read("scripts/github_voice_checks.sh")
        lists = dict(re.findall(r'^ids_([a-z_]+)="([^"]*)"', text, re.M))
        suites = self.manifest()
        for key, suite, prefix in (("voice_a", "voice-a", "voice:"), ("voice_b", "voice-b", "voice:"),
                                   ("voice_c", "voice-c", "voice:")):
            with self.subTest(suite=suite):
                listed = [r[len(prefix):] for r in suites[suite]["required"]
                          if r.startswith(prefix) and r not in (prefix + "runner", "voice:quota-guard")]
                self.assertEqual(lists[key].split(), listed)
        # LIVE-INFO-DEVICE-001: >=9/10 per kind, so each live-info shard runs one kind x10.
        self.assertNotRegex(text, r"--repeat [0-9]\b")
        for kind in ("WEATHER", "PLACE", "TRAFFIC", "ALONG"):
            self.assertIn(f"li-{kind.lower()}) suite liveinfo liveinfo: --file tools/speech-harness/"
                          f"scenarios-liveinfo.json --repeat 10 --only LI-{kind} ;;", text)
            self.assertEqual(suites[f"voice-li-{kind.lower()}"]["required"],
                             ["build:vendor-real", "liveinfo:runner", f"liveinfo:LI-{kind}", "voice:quota-guard"])

    def test_every_scenario_is_in_exactly_one_shard(self):
        suites = self.manifest()
        owners = {}
        for suite in SHARD_SUITES.values():
            for rid in suites[suite]["required"] + suites[suite]["optional"]:
                if rid in ("build:vendor-real", "voice:quota-guard") or rid.endswith(":runner"):
                    continue
                self.assertNotIn(rid, owners, f"{rid} in {owners.get(rid)} and {suite}")
                owners[rid] = suite
        want = (["voice:" + i for i in self.scenario_ids("scenarios.json")]
                + ["devrows:" + i for i in self.scenario_ids("scenarios-device.json")]
                + ["liveinfo:" + i for i in self.scenario_ids("scenarios-liveinfo.json")]
                + ["voice:demo-recording", "wake:WAKE-SYNTH-001"])
        self.assertEqual(sorted(owners), sorted(want))

    def test_shards_together_cover_the_voice_suite(self):
        suites = self.manifest()
        union_req = set()
        union_opt = set()
        for suite in SHARD_SUITES.values():
            union_req |= set(suites[suite]["required"])
            union_opt |= set(suites[suite]["optional"])
            # Shared gates: each shard builds its own APK and opens its own Gemini session.
            self.assertIn("build:vendor-real", suites[suite]["required"])
            self.assertIn("voice:quota-guard", suites[suite]["required"])
        for pattern in suites["voice"]["required"]:
            with self.subTest(pattern=pattern):
                hit = ([r for r in union_req if r.startswith(pattern[:-1])] if pattern.endswith("*")
                       else [r for r in union_req if r == pattern])
                self.assertTrue(hit)
        self.assertEqual(union_opt, set(suites["voice"]["optional"]))
        self.assertEqual(sorted(r for r in union_req if r.split(":")[0] not in ("voice", "devrows", "liveinfo", "build")), [])

    def test_shard_verdict_judges_only_its_own_checks(self):
        manifest = os.path.join(ROOT, "config/ci_checks.json")
        with tempfile.TemporaryDirectory() as tmp:
            res = os.path.join(tmp, "r.jsonl")
            base = [rec("build:vendor-real", "PASS"), rec("voice:quota-guard", "PASS"),
                    rec("liveinfo:runner", "PASS")]
            with open(res, "w") as fh:
                fh.write("\n".join(json.dumps(r) for r in base + [rec("liveinfo:LI-PLACE", "PASS"),
                                                                   rec("liveinfo:LI-WEATHER", "FAIL")]) + "\n")
            rc, text = run("verdict", "--manifest", manifest, "--suite", "voice-li-place",
                           "--results", res, clock=False)
            self.assertEqual(rc, 0, text)  # the other shards' checks are not judged here
            with open(res, "w") as fh:
                fh.write("\n".join(json.dumps(r) for r in base) + "\n")
            rc, text = run("verdict", "--manifest", manifest, "--suite", "voice-li-place",
                           "--results", res, clock=False)
            self.assertEqual(rc, 1, text)  # its own check with no result is MISSING, never passed
            self.assertIn("liveinfo:LI-PLACE has no result", text)


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


class StubVendorWorkflowTest(unittest.TestCase):
    """Only the Tier B emulator workflows may build on the stand-in, and only by opting in."""

    def test_tier_b_workflows_opt_in(self):
        for wf in ("device-checks.yml", "voice-checks.yml", "nav-demo.yml", "test-worker.yml"):
            with self.subTest(wf=wf):
                self.assertIn('NOVA_VENDOR_STUB_OK: "yes"', read(".github/workflows/" + wf))

    def test_tier_a_and_drive_video_never_opt_in(self):
        for wf in ("unit-tests.yml", "emulator.yml"):
            with self.subTest(wf=wf):
                self.assertNotIn("NOVA_VENDOR_STUB_OK", read(".github/workflows/" + wf))
        self.assertNotIn("NOVA_VENDOR_STUB_OK", read("scripts/ci_tier_a.sh"))

    def test_cloud_setup_needs_the_opt_in_on_actions(self):
        body = read("scripts/cloud_setup.sh")
        self.assertIn('[ "${NOVA_VENDOR_STUB_OK:-}" = yes ] || return', body)

    def test_each_suite_script_records_the_vendor_check(self):
        for rel in ("scripts/github_device_checks.sh", "scripts/github_voice_checks.sh",
                    "scripts/github_nav_demo.sh"):
            with self.subTest(script=rel):
                self.assertIn('ci_verdict.py vendor --apk "$apk" --out "$R"', read(rel))

    def test_wake_row_is_blocked_on_a_stub_build(self):
        self.assertIn('res wake:WAKE-SYNTH-001 BLOCKED "STUB_VENDOR', read("scripts/github_voice_checks.sh"))


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


def stub_tree(tmp):
    """A copy of the nav demo with stub adb/sudo/sleep/ffmpeg and a stub bridge; returns PATH."""
    for rel in ("scripts/github_nav_demo.sh", "scripts/ci_verdict.py", "scripts/emulator.ps1",
                "config/ci_checks.json"):
        os.makedirs(os.path.join(tmp, os.path.dirname(rel)), exist_ok=True)
        shutil.copy(os.path.join(ROOT, rel), os.path.join(tmp, rel))
    # The demo's apk argument: packaged like a real-vendor build, so build:vendor-real passes here.
    from scripts.tests.test_ci_verdict import REAL_ENTRIES, make_apk
    make_apk(os.path.join(tmp, "app.apk"), REAL_ENTRIES)
    stub = os.path.join(tmp, "stub")
    os.makedirs(stub)
    for name, body in (("python3", STUB_PY), ("adb", STUB_ADB), ("sudo", "#!/bin/sh\nexit 0\n"),
                       ("sleep", "#!/bin/sh\nexit 0\n"), ("ffmpeg", "#!/bin/sh\nexit 1\n")):
        path = os.path.join(stub, name)
        with open(path, "w") as fh:
            fh.write(body)
        os.chmod(path, 0o755)
    return stub + os.pathsep + os.environ["PATH"]


class BlockedRunTest(Base):
    def run_demo(self, tmp, piece=None, **keys):
        path = stub_tree(tmp)
        env = {k: v for k, v in os.environ.items() if k not in ("GEMINI_API_KEY", "AMAP_WEB_KEY")}
        env.update(PATH=path, NOVA_REAL_PY=sys.executable, NOVA_TEST_MARK=os.path.join(tmp, "m"),
                   NAV_DEMO_PIECE="1" if piece else "", **keys)
        if piece:
            with open(os.path.join(tmp, "scripts/nav_piece.txt"), "w") as fh:
                fh.write(piece + "\n")
        proc = subprocess.run(["bash", os.path.join(tmp, "scripts/github_nav_demo.sh"), "app.apk"],
                              env=env, capture_output=True, text=True, timeout=120)
        verdict = json.loads(read_abs(os.path.join(tmp, "evidence/verdict.json")))["verdict"]
        return proc.returncode, verdict

    def test_no_amap_web_key_is_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            rc, verdict = self.run_demo(tmp, GEMINI_API_KEY="x")
            self.assertEqual((rc, verdict), (3, "BLOCKED"))

    def test_no_gemini_key_is_blocked_for_a_voice_piece(self):
        with tempfile.TemporaryDirectory() as tmp:
            rc, verdict = self.run_demo(tmp, AMAP_WEB_KEY="x")
            self.assertEqual((rc, verdict), (3, "BLOCKED"))

    def test_routes_without_gemini_key_is_not_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            rc, verdict = self.run_demo(tmp, piece="routes", AMAP_WEB_KEY="x")
            # The stub logs no route, so it fails - but it ran: not BLOCKED.
            self.assertEqual((rc, verdict), (1, "FAIL"))

    def test_session_and_complete_blocked_verdict(self):
        rc, text = run("verdict", "--manifest", os.path.join(ROOT, "config/ci_checks.json"),
                       "--suite", "navpiece", "--results",
                       self.results([rec("build:vendor-real", "PASS"),
                                     rec("navpiece:session", "BLOCKED", "secret AMAP_WEB_KEY not set"),
                                     rec("navpiece:complete", "BLOCKED", "secret AMAP_WEB_KEY not set")]),
                       clock=False)
        self.assertEqual(rc, 3, text)
        self.assertIn("VERDICT navpiece BLOCKED", text)


class AbortCleanupTest(unittest.TestCase):
    def test_kill_mid_bridge_leaves_no_bridge_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = stub_tree(tmp)
            mark = os.path.join(tmp, "bridge_running")
            env = dict(os.environ, PATH=path, NOVA_REAL_PY=sys.executable,
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


class VoiceShardBlockedTest(Base):
    """No GEMINI_API_KEY: every shard's own verdict is BLOCKED (never FAIL on MISSING ids)."""

    def test_no_gemini_key_is_blocked_for_each_shard(self):
        for shard, suite in list(SHARD_SUITES.items()) + [("all", "voice")]:
            with self.subTest(shard=shard), tempfile.TemporaryDirectory() as tmp:
                path = stub_tree(tmp)
                shutil.copy(os.path.join(ROOT, "scripts/github_voice_checks.sh"),
                            os.path.join(tmp, "scripts/github_voice_checks.sh"))
                env = {k: v for k, v in os.environ.items() if k not in ("GEMINI_API_KEY", "AMAP_WEB_KEY")}
                env.update(PATH=path, NOVA_REAL_PY=sys.executable, NOVA_TEST_MARK=os.path.join(tmp, "m"))
                proc = subprocess.run(["bash", os.path.join(tmp, "scripts/github_voice_checks.sh"),
                                       "app.apk", shard], env=env, capture_output=True, text=True,
                                      timeout=180)
                self.assertEqual(proc.returncode, 0, proc.stdout[-2000:] + proc.stderr[-2000:])
                rc, text = run("verdict", "--manifest", os.path.join(tmp, "config/ci_checks.json"),
                               "--suite", suite, "--results", os.path.join(tmp, "evidence/results.jsonl"),
                               clock=False)
                self.assertEqual(rc, 3, text)
                self.assertIn(f"VERDICT {suite} BLOCKED", text)
