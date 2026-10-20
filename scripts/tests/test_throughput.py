"""scripts/throughput.py measures exec merges per hour from first-parent history."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "throughput.py")


class Throughput(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "t")
        self.git("commit", "-q", "--allow-empty", "-m", "base", when="2026-10-20T00:00:00Z")
        # merges at 01:00, 01:30, 02:00 (before split) and 03:00, 03:10, 03:20, 03:30 (after)
        for i, t in enumerate(["01:00", "01:30", "02:00", "03:00", "03:10", "03:20", "03:30"]):
            b = "exec/b%d" % i
            self.git("checkout", "-q", "-b", b)
            self.git("commit", "-q", "--allow-empty", "-m", "w", when="2026-10-20T%s:00Z" % t)
            self.git("checkout", "-q", "main")
            self.git("merge", "-q", "--no-ff", "-m", "Merge %s: thing" % b, b,
                     when="2026-10-20T%s:00Z" % t)
        self.git("commit", "-q", "--allow-empty", "-m", "Merge branch 'other'",
                 when="2026-10-20T03:40:00Z")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args, when=None):
        env = dict(os.environ)
        if when:
            env["GIT_AUTHOR_DATE"] = env["GIT_COMMITTER_DATE"] = when
        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True, env=env)

    def run_json(self, *extra):
        p = subprocess.run([sys.executable, SCRIPT, "--json", *extra], cwd=self.repo,
                           capture_output=True, text=True, check=True)
        return json.loads(p.stdout)

    def test_all(self):
        d = self.run_json()
        self.assertEqual(d["all"]["merges"], 7)
        self.assertAlmostEqual(d["all"]["median_gap_minutes"], 20.0)
        self.assertAlmostEqual(d["all"]["merges_per_hour"], 7 / 2.5, places=2)
        self.assertTrue(d["merges"][0]["subject"].startswith("Merge exec/"))

    def test_split(self):
        d = self.run_json("--split", "2026-10-20T02:30:00Z")
        self.assertEqual(d["before"]["merges"], 3)
        self.assertEqual(d["after"]["merges"], 4)
        self.assertAlmostEqual(d["before"]["median_gap_minutes"], 30.0)
        self.assertAlmostEqual(d["after"]["median_gap_minutes"], 10.0)
        self.assertAlmostEqual(d["after"]["merges_per_hour"], 4 / 0.5, places=2)

    def test_text_output(self):
        p = subprocess.run([sys.executable, SCRIPT], cwd=self.repo, capture_output=True,
                           text=True, check=True)
        self.assertIn("merges per hour", p.stdout)


if __name__ == "__main__":
    unittest.main()
