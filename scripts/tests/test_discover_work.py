"""discover_work.unfinished_executor_work: finished executor branches stay on the frontier until merged."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import discover_work  # noqa: E402


def sh(repo, *args):
    return subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True,
                          text=True).stdout.strip()


class UnfinishedExecutorWork(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        sh(self.repo, "init", "-q", "-b", "main")
        sh(self.repo, "config", "user.email", "t@example.invalid")
        sh(self.repo, "config", "user.name", "t")
        self.commit("base")
        self.base = sh(self.repo, "rev-parse", "HEAD")
        os.makedirs(os.path.join(self.repo, "state", "contracts"))
        os.makedirs(os.path.join(self.repo, "state", "verification"))
        open(os.path.join(self.repo, "state", "reviews.log"), "w").close()

    def tearDown(self):
        self.tmp.cleanup()

    def commit(self, msg):
        sh(self.repo, "commit", "-q", "--allow-empty", "-m", msg)

    def contract(self, tid, branch, base=None):
        with open(os.path.join(self.repo, "state", "contracts", tid + ".yaml"), "w") as f:
            f.write("schema: nova-task-contract/1\ntask_id: %s\nbranch: %s\nbase_commit: %s\n"
                    % (tid, branch, base or self.base))

    def branch_with_work(self, branch):
        sh(self.repo, "checkout", "-q", "-b", branch)
        self.commit("work")
        tip = sh(self.repo, "rev-parse", "HEAD")
        sh(self.repo, "checkout", "-q", "main")
        return tip

    def verify(self, tid, tip):
        with open(os.path.join(self.repo, "state", "verification", tid + ".json"), "w") as f:
            json.dump({"task_id": tid, "tip": tip}, f)

    def review(self, tip):
        with open(os.path.join(self.repo, "state", "reviews.log"), "a") as f:
            f.write("%s PASS 2026-10-20 reviewed\n" % tip[:7])

    def found(self):
        return discover_work.unfinished_executor_work(repo=self.repo)

    def test_unverified_branch_is_verify_stage(self):
        self.contract("T-A", "exec/a")
        self.branch_with_work("exec/a")
        items = self.found()
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["priority"], discover_work.P_UNFINISHED)
        self.assertEqual(items[0]["item"], "T-A")
        self.assertIn("verify", items[0]["summary"])

    def test_stale_verification_is_verify_stage(self):
        self.contract("T-A", "exec/a")
        self.branch_with_work("exec/a")
        self.verify("T-A", self.base)
        self.assertIn("verify", self.found()[0]["summary"])

    def test_verified_unreviewed_is_review_stage(self):
        self.contract("T-A", "exec/a")
        tip = self.branch_with_work("exec/a")
        self.verify("T-A", tip)
        self.assertIn("review", self.found()[0]["summary"])

    def test_verified_and_reviewed_is_merge_stage(self):
        self.contract("T-A", "exec/a")
        tip = self.branch_with_work("exec/a")
        self.verify("T-A", tip)
        self.review(tip)
        self.assertIn("merge", self.found()[0]["summary"])

    def test_merged_branch_produces_nothing(self):
        self.contract("T-A", "exec/a")
        self.branch_with_work("exec/a")
        sh(self.repo, "merge", "-q", "--no-ff", "-m", "Merge exec/a", "exec/a")
        self.assertEqual(self.found(), [])

    def test_branch_at_base_produces_nothing(self):
        self.contract("T-A", "exec/a")
        sh(self.repo, "branch", "exec/a")
        self.assertEqual(self.found(), [])

    def test_missing_branch_and_bad_contract_are_skipped(self):
        self.contract("T-A", "exec/missing")
        with open(os.path.join(self.repo, "state", "contracts", "BAD.yaml"), "w") as f:
            f.write(": : not yaml [\n")
        self.assertEqual(self.found(), [])

    def test_reader_is_in_sources(self):
        self.assertIn(discover_work.unfinished_executor_work, discover_work.SOURCES)


if __name__ == "__main__":
    unittest.main()
