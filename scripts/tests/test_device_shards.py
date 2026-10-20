"""DEV-SPLIT: device checks run as shards, one emulator and one verdict per shard."""
import json
import os
import re
import subprocess
import tempfile
import unittest

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SHARDS = ["dispatch", "drive", "lifecycle", "navpiece"]
# Recorded by the shared setup (ci_verdict.py vendor) in every shard, so required in every shard.
SHARED = {"build:vendor-real"}


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
        return fh.read()


def suites():
    return json.loads(read("config/ci_checks.json"))["suites"]


def sections():
    """[(header, shard)] for every `# ---- ` section of the device script."""
    lines = read("scripts/github_device_checks.sh").splitlines()
    out = []
    for i, line in enumerate(lines):
        if line.startswith("# ---- "):
            gate = next(l for l in lines[i + 1:] if not l.startswith("#"))
            m = re.fullmatch(r"if in_shard ([a-z]+); then", gate)
            out.append((line, m.group(1) if m else None))
    return out


class DeviceShardTest(unittest.TestCase):
    def test_shard_suites_partition_the_device_suite(self):
        s = suites()
        for kind in ("required", "optional"):
            with self.subTest(kind=kind):
                seen = []
                for shard in SHARDS:
                    seen += [i for i in s["device-" + shard][kind] if i not in SHARED]
                self.assertEqual(len(seen), len(set(seen)), "an id is in two shards")
                self.assertEqual(set(seen) | (SHARED & set(s["device"][kind])), set(s["device"][kind]))
        for shard in SHARDS:
            self.assertTrue(SHARED <= set(s["device-" + shard]["required"]))

    def test_every_section_is_in_exactly_one_known_shard(self):
        found = sections()
        self.assertGreaterEqual(len(found), 8)
        for header, shard in found:
            self.assertIn(shard, SHARDS, header)
        self.assertEqual(sorted({sh for _, sh in found}), sorted(SHARDS))

    def test_check_ids_are_recorded_in_their_shard_section(self):
        text = read("scripts/github_device_checks.sh")
        parts = re.split(r"^(?=# ---- )", text, flags=re.M)[1:]
        by_shard = {sh: set() for sh in SHARDS}
        for part in parts:
            shard = re.search(r"^if in_shard ([a-z]+); then", part, re.M).group(1)
            by_shard[shard] |= set(re.findall(r"\b(?:res|pf) \"?(device:[A-Za-z0-9_.\-]+)", part))
        s = suites()
        for shard in SHARDS:
            listed = set(s["device-" + shard]["required"] + s["device-" + shard]["optional"]) - SHARED
            self.assertEqual(by_shard[shard], listed, shard)

    def test_unknown_shard_fails_and_unset_runs_everything(self):
        text = read("scripts/github_device_checks.sh")
        self.assertIn('shard="${DEVICE_SHARD:-}"', text)
        body = re.search(r'^in_shard\(\) \{ (.*) \}$', text, re.M).group(1)
        for env, shard, want in (("", "drive", 0), ("all", "navpiece", 0), ("drive", "drive", 0),
                                 ("drive", "dispatch", 1)):
            proc = subprocess.run(["bash", "-c", f'shard="{env}"; {body}', "x", shard])
            self.assertEqual(proc.returncode, want, (env, shard))

    def pick(self, shards):
        doc = yaml.safe_load(read(".github/workflows/device-checks.yml"))
        step = next(s for s in doc["jobs"]["plan"]["steps"] if s.get("id") == "pick")
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "out")
            env = dict(os.environ, GITHUB_OUTPUT=out, SHARDS=shards)
            proc = subprocess.run(["bash", "-e", "-c", step["run"]], env=env, capture_output=True, text=True)
            if proc.returncode:
                return None
            with open(out, encoding="utf-8") as fh:
                line = fh.read().strip()
        return json.loads(line[len("matrix="):])["include"]

    def test_plan_picks_all_or_named_shards(self):
        for raw in ("", "all"):
            self.assertEqual([e["shard"] for e in self.pick(raw)], SHARDS)
        self.assertEqual(self.pick("dispatch"), [{"shard": "dispatch", "suite": "device-dispatch"}])
        self.assertIsNone(self.pick("voice-a"))
        for e in self.pick("all"):
            self.assertIn(e["suite"], suites())

    def test_matrix_job_shape(self):
        doc = yaml.safe_load(read(".github/workflows/device-checks.yml"))
        job = doc["jobs"]["drive"]
        self.assertIs(job["strategy"]["fail-fast"], False)
        self.assertEqual(job["needs"], "plan")
        self.assertEqual(job["env"]["DEVICE_SHARD"], "${{ matrix.shard }}")
        self.assertEqual(job["if"], doc["jobs"]["plan"]["if"])
        self.assertIn("[checks]", doc["jobs"]["plan"]["if"])
        runs = "\n".join(str(s.get("run", "")) + str(s.get("uses", "")) for s in job["steps"])
        self.assertIn("bash scripts/github_build_apk.sh", runs)
        self.assertIn("actions/cache", runs)
        self.assertNotIn("upload-artifact", runs)
        push = next(s for s in job["steps"] if s["name"].startswith("Push the evidence"))
        self.assertIn("runs/$dir/$DEVICE_SHARD", push["run"])
        self.assertIn("git rebase", push["run"])
        self.assertIn("_r${GITHUB_RUN_ID}", push["run"])
        self.assertIn("_stubvendor", push["run"])


if __name__ == "__main__":
    unittest.main()
