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

    def test_bad_args_and_missing_secret(self):
        self.assertEqual(subprocess.run(["bash", SCRIPT, "nope"], capture_output=True).returncode, 2)
        env = {k: v for k, v in os.environ.items() if k not in ("GEMINI_API_KEY", "AMAP_WEB_KEY")}
        env["LIVE_JVM_SKIP_PUSH"] = "1"
        with tempfile.TemporaryDirectory() as d:
            env["LIVE_JVM_OUT"] = d
            p = subprocess.run(["bash", SCRIPT, "text-live"], capture_output=True, text=True, env=env)
            self.assertEqual(p.returncode, 3)
            self.assertIn("BLOCKED", open(os.path.join(d, "text-live", "summary.txt")).read())

    def test_workflow_shape(self):
        wf = yaml.safe_load(open(os.path.join(REPO, ".github/workflows/live-jvm.yml")))
        self.assertEqual(wf["name"], "Live JVM checks")
        self.assertEqual(wf["jobs"]["live"]["strategy"]["matrix"]["piece"], ["text-live", "amap-live"])


if __name__ == "__main__":
    unittest.main()
