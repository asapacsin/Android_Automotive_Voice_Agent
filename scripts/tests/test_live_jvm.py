"""scripts/github_live_jvm.sh: argument handling and a text-free summary."""
import os
import subprocess
import tempfile
import unittest

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(REPO, "scripts", "github_live_jvm.sh")
XML = '<testsuite name="A" tests="{n}" failures="{f}" errors="0" skipped="0"><testcase classname="c" name="t"/></testsuite>'


def results(fz_miss=(), music_ctl=0, music_miss=0):
    rows = []
    for i in range(1, 20):
        ok = f"FZ-{i:02d}" not in fz_miss
        rows.append(f"FUZZY | FZ-{i:02d} | expected PHRASE_SECRET | got x | {'PASS' if ok else 'MISS'}")
    for i in range(1, 11):
        if i <= music_ctl:
            rows.append(f"MUSIC | M-{i:02d} | expected y | got control_music{{\"action\":\"play\"}} | MISS")
        elif i <= music_ctl + music_miss:
            rows.append(f"MUSIC | M-{i:02d} | expected y | got no call | MISS")
        else:
            rows.append(f"MUSIC | M-{i:02d} | expected y | got play_music{{\"title\":\"SECRET\"}} | PASS")
    rows.append("MUSIC_CONTROL | M-CTL | expected z | got no call | MISS")
    return "model=m\n" + "\n".join(rows) + "\n"


def tryout_output(fail=None):
    rows = ["== /tmp/run"]
    for i in range(19):
        key = f"u_k{i:02d}"
        verdict = "FAIL" if key == fail else "PASS"
        note = "\u8bf4\u4e86\u300c\u4f60\u597d\u5bfc\u822a SECRET\u300d" if i % 2 else ""
        tool = "control_window\u2713"
        rows.append(f"  {key:10s} {verdict:4s} {2.1:4.1f}s  {tool:40s} {note}")
    rows.append("RESULT " + ("PASS" if not fail else "FAIL " + fail))
    rows.append("WARN -")
    return "\n".join(rows) + "\n"


_STYLE = {}


def style_output(passing=True):
    """Real style_check.py output: its own selftest (pass case, and the 'no tool at s_sweet' case)."""
    if not _STYLE:
        p = subprocess.run(["python3", os.path.join(REPO, "tools/demo/live/style_check.py"), "--selftest"],
                           capture_output=True, text=True, cwd=REPO)
        blocks = ["== " + b for b in p.stdout.split("== ")[1:]]
        _STYLE["pass"], _STYLE["fail"] = blocks[0].split("selftest ")[0], blocks[1].split("selftest ")[0]
    # A note with reply-like Chinese text and a CJK marker column, as a real take can carry.
    return _STYLE["pass" if passing else "fail"].replace("sounds sweet (info)", "\u8bf4\u4e86\u300c\u55f2\u4e00\u70b9 SECRET\u300d")


class LiveJvmTest(unittest.TestCase):
    def run_summary(self, piece, res, xml):
        with tempfile.TemporaryDirectory() as d:
            rp, xp = os.path.join(d, "results.txt"), os.path.join(d, "x.xml")
            open(rp, "w").write(res)
            open(xp, "w").write(xml)
            p = subprocess.run(["bash", SCRIPT, "summarize", piece, rp, xp, os.path.join(d, "out")],
                               capture_output=True, text=True)
            self.assertEqual(p.returncode, 0, p.stderr)
            self.assertEqual(p.stdout, open(os.path.join(d, "out", "summary.txt")).read())
            return p.stdout

    def test_text_live_met_and_text_free(self):
        out = self.run_summary("text-live", results(), XML.format(n=1, f=0))
        self.assertIn("FUZZY correct=19/19 fz15_19_all_correct=yes", out)
        self.assertIn("MUSIC play_music_correct=10/10 control_music_play=0/10", out)
        self.assertIn("FUZZY-TEXT-LIVE-001 rule=MET", out)
        self.assertIn("MUSIC-TEXT-LIVE-001 rule=MET", out)
        self.assertNotIn("SECRET", out)
        self.assertNotIn("play_music{", out)

    def test_text_live_not_met(self):
        out = self.run_summary("text-live", results(fz_miss=("FZ-17",), music_ctl=1), XML.format(n=1, f=0))
        self.assertIn("fz15_19_all_correct=no", out)
        self.assertIn("FUZZY-TEXT-LIVE-001 rule=NOT_MET", out)
        self.assertIn("control_music_play=1/10", out)
        self.assertIn("MUSIC-TEXT-LIVE-001 rule=NOT_MET", out)

    def test_amap_rule(self):
        self.assertIn("rule=MET", self.run_summary("amap-live", "", XML.format(n=2, f=0)))
        self.assertIn("rule=NOT_MET", self.run_summary("amap-live", "", XML.format(n=2, f=1)))

    def evidence_dir(self, run_id):
        env = {k: v for k, v in os.environ.items() if k != "GITHUB_RUN_ID"}
        if run_id is not None:
            env["GITHUB_RUN_ID"] = run_id
        p = subprocess.run(["bash", SCRIPT, "evidence-dir", "abcdef0123456789", "20261020_1200", "style"],
                           capture_output=True, text=True, env=env)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout.strip()

    def test_evidence_dir_carries_run_id(self):
        self.assertEqual(self.evidence_dir(None), "runs/run_20261020_1200_abcdef0/style")
        a, b = self.evidence_dir("111"), self.evidence_dir("222")
        self.assertEqual(a, "runs/run_20261020_1200_abcdef0_r111/style")
        self.assertNotEqual(a, b)

    def test_bad_args_and_missing_secret(self):
        self.assertEqual(subprocess.run(["bash", SCRIPT, "nope"], capture_output=True).returncode, 2)
        env = {k: v for k, v in os.environ.items() if k not in ("GEMINI_API_KEY", "AMAP_WEB_KEY")}
        env["LIVE_JVM_SKIP_PUSH"] = "1"
        with tempfile.TemporaryDirectory() as d:
            env["LIVE_JVM_OUT"] = d
            p = subprocess.run(["bash", SCRIPT, "text-live"], capture_output=True, text=True, env=env)
            self.assertEqual(p.returncode, 3)
            self.assertIn("BLOCKED", open(os.path.join(d, "text-live", "summary.txt")).read())

    def test_blank_model_not_passed_and_model_in_summary(self):
        with tempfile.TemporaryDirectory() as d:
            stub = os.path.join(d, "g.sh")
            open(stub, "w").write('#!/bin/bash\nif [ "${TEXT_LIVE_MODEL+set}" = set ]; then echo set; else echo unset; fi >"$LIVE_JVM_OUT/model_seen"\nexit 124\n')
            os.chmod(stub, 0o755)
            env = dict(os.environ, GEMINI_API_KEY="k", TEXT_LIVE_MODEL="", LIVE_JVM_SKIP_SETUP="1",
                       LIVE_JVM_SKIP_PUSH="1", LIVE_JVM_GRADLE=stub, LIVE_JVM_OUT=d)
            p = subprocess.run(["bash", SCRIPT, "text-live"], capture_output=True, text=True, env=env)
            self.assertEqual(p.returncode, 124)
            self.assertEqual(open(os.path.join(d, "model_seen")).read().strip(), "unset")
            self.assertIn("timeout=yes", open(os.path.join(d, "text-live", "summary.txt")).read())

    def test_tryout_met_and_text_free(self):
        out = self.run_summary("tryout", tryout_output(), XML.format(n=1, f=0))
        self.assertIn("DEMO-TRYOUT-001 rule=MET", out)
        self.assertIn("graded=19 pass=19", out)
        self.assertIn("u_k00 PASS 2.1s control_window", out)
        self.assertIn("WARN -", out)
        for bad in ("\u4f60", "\u5bfc\u822a", "SECRET", "\u2713"):
            self.assertNotIn(bad, out)
        self.assertTrue(out.isascii(), out)

    def test_tryout_not_met(self):
        out = self.run_summary("tryout", tryout_output(fail="u_k03"), XML.format(n=1, f=0))
        self.assertIn("RESULT FAIL u_k03", out)
        self.assertIn("u_k03 FAIL", out)
        self.assertIn("DEMO-TRYOUT-001 rule=NOT_MET", out)
        short = "\n".join(tryout_output().splitlines()[:5]) + "\nRESULT PASS\nWARN -\n"
        self.assertIn("rule=NOT_MET", self.run_summary("tryout", short, XML.format(n=1, f=0)))

    def test_tryout_missing_secret_blocked(self):
        env = {k: v for k, v in os.environ.items() if k != "GEMINI_API_KEY"}
        env["LIVE_JVM_SKIP_PUSH"] = "1"
        with tempfile.TemporaryDirectory() as d:
            env["LIVE_JVM_OUT"] = d
            p = subprocess.run(["bash", SCRIPT, "tryout"], capture_output=True, text=True, env=env)
            self.assertEqual(p.returncode, 3)
            self.assertIn("BLOCKED secret GEMINI_API_KEY", open(os.path.join(d, "tryout", "summary.txt")).read())

    def test_style_met_and_text_free(self):
        res = style_output()
        self.assertIn("RESULT PASS", res)
        out = self.run_summary("style", res, XML.format(n=1, f=0))
        self.assertIn("DEMO-STYLE-001 rule=MET", out)
        self.assertIn("graded=7 pass=7", out)
        self.assertRegex(out, r"s_sweet PASS 1\.5s tools=set_speaking_style style=sweet f0=246Hz\+20% ".replace("%", ""))
        self.assertIn("junit tests=1 failures=0", out)
        for bad in ("\u55f2", "SECRET", "\u2713", "sounds", "info"):
            self.assertNotIn(bad, out)
        self.assertTrue(out.isascii(), out)
        self.assertRegex(out, r"^[A-Za-z0-9_.,=+ \n-]*$")

    def test_style_not_met(self):
        out = self.run_summary("style", style_output(passing=False), XML.format(n=1, f=1))
        self.assertIn("RESULT FAIL s_sweet", out)
        self.assertIn("s_sweet FAIL", out)
        self.assertIn("DEMO-STYLE-001 rule=NOT_MET", out)
        self.assertIn("rule=NOT_MET", self.run_summary("style", "", XML.format(n=1, f=0)))

    def test_style_missing_secret_blocked(self):
        env = {k: v for k, v in os.environ.items() if k != "GEMINI_API_KEY"}
        env["LIVE_JVM_SKIP_PUSH"] = "1"
        with tempfile.TemporaryDirectory() as d:
            env["LIVE_JVM_OUT"] = d
            p = subprocess.run(["bash", SCRIPT, "style"], capture_output=True, text=True, env=env)
            self.assertEqual(p.returncode, 3)
            self.assertIn("BLOCKED secret GEMINI_API_KEY", open(os.path.join(d, "style", "summary.txt")).read())

    def test_model_line(self):
        out = self.run_summary("text-live", results().replace("model=m", "model=gem-1.5"), XML.format(n=1, f=0))
        self.assertIn("model=gem-1.5", out)

    def run_style_piece(self, model):
        with tempfile.TemporaryDirectory() as d:
            stub = os.path.join(d, "g.sh")
            open(stub, "w").write('#!/bin/bash\necho "${NOVA_GEMINI_MODEL-<unset>}" >"$LIVE_JVM_OUT/model_seen"\nexit 0\n')
            pystub = os.path.join(d, "py.sh")
            open(pystub, "w").write("#!/bin/bash\nexit 0\n")
            os.chmod(stub, 0o755); os.chmod(pystub, 0o755)
            env = dict(os.environ, GEMINI_API_KEY="k", NOVA_GEMINI_MODEL=model, LIVE_JVM_SKIP_SETUP="1",
                       LIVE_JVM_SKIP_PUSH="1", LIVE_JVM_GRADLE=stub, LIVE_JVM_OUT=d, LIVE_JVM_PYTHON=pystub,
                       LIVE_JVM_TRYOUT_WORK=os.path.join(d, "work"))
            subprocess.run(["bash", SCRIPT, "style"], capture_output=True, text=True, env=env)
            return (open(os.path.join(d, "model_seen")).read().strip(),
                    open(os.path.join(d, "style", "summary.txt")).read())

    def test_style_blank_model_is_38(self):
        seen, summary = self.run_style_piece("")
        self.assertEqual(seen, "gemini-3.8-live")
        self.assertIn("model=gemini-3.8-live\n", summary)

    def test_style_explicit_model_passes_through(self):
        seen, summary = self.run_style_piece("gemini-x")
        self.assertEqual(seen, "gemini-x")
        self.assertIn("model=gemini-x\n", summary)

    def test_non_style_blank_model_line_harness_default(self):
        with tempfile.TemporaryDirectory() as d:
            stub = os.path.join(d, "g.sh")
            open(stub, "w").write("#!/bin/bash\nexit 1\n")
            os.chmod(stub, 0o755)
            env = dict(os.environ, AMAP_WEB_KEY="k", NOVA_GEMINI_MODEL="", TEXT_LIVE_MODEL="",
                       LIVE_JVM_SKIP_SETUP="1", LIVE_JVM_SKIP_PUSH="1", LIVE_JVM_GRADLE=stub, LIVE_JVM_OUT=d)
            subprocess.run(["bash", SCRIPT, "amap-live"], capture_output=True, text=True, env=env)
            self.assertIn("model=harness-default\n", open(os.path.join(d, "amap-live", "summary.txt")).read())

    def test_style_pass_with_fewer_than_7_graded_not_met(self):
        res = style_output()
        rows = res.splitlines()
        drop = next(i for i, r in enumerate(rows) if r.lstrip().startswith("s_"))
        out = self.run_summary("style", "\n".join(rows[:drop] + rows[drop + 1:]) + "\n", XML.format(n=1, f=0))
        self.assertIn("RESULT PASS", out)
        self.assertIn("graded=6", out)
        self.assertIn("DEMO-STYLE-001 rule=NOT_MET", out)

    def test_style_negative_zero_time_row_parsed(self):
        res = style_output().replace(" 1.5s ", " -0.0s ", 1)
        out = self.run_summary("style", res, XML.format(n=1, f=0))
        self.assertIn("s_mood1 PASS -0.0s", out)
        self.assertIn("graded=7 pass=7", out)

    def test_workflow_shape(self):
        wf = yaml.safe_load(open(os.path.join(REPO, ".github/workflows/live-jvm.yml")))
        self.assertEqual(wf["name"], "Live JVM checks")
        self.assertEqual(wf["jobs"]["live"]["strategy"]["matrix"]["piece"], ["text-live", "amap-live", "tryout", "style"])
        self.assertEqual(wf["jobs"]["live"]["timeout-minutes"], 40)
        self.assertIn("3.8", wf[True]["workflow_dispatch"]["inputs"]["gemini_model"]["description"])


if __name__ == "__main__":
    unittest.main()
