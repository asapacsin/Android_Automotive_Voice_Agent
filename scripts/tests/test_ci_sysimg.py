import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ["device-checks", "voice-checks", "nav-demo", "emulator"]


class SysImgTest(unittest.TestCase):
    def test_script_syntax(self):
        subprocess.run(["bash", "-n", str(ROOT / "scripts/ci_sysimg.sh")], check=True)

    def test_cache_and_preinstall_before_emulator(self):
        for w in WORKFLOWS:
            t = (ROOT / ".github/workflows" / f"{w}.yml").read_text()
            c = t.index("actions/cache@v4")
            i = t.index("bash scripts/ci_sysimg.sh")
            e = t.index("reactivecircus/android-emulator-runner")
            self.assertTrue(c < i < e, w)
            self.assertIn("system-images/android-34/google_apis/x86_64", t, w)


if __name__ == "__main__":
    unittest.main()
