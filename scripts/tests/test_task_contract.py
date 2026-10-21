"""scripts/task_contract.py: scope, contract integrity and merge eligibility are decided from git and
records, never from an executor's self-report. Each test builds a throwaway repo with an executor worktree.
"""
import contextlib
import datetime
import glob
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, os.path.join(REPO, path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


tc = _load("task_contract", "scripts/task_contract.py")
merge_hook = _load("merge_hook", ".claude/hooks/require-review-before-merge.py")
guard = _load("guard_contract_writes", ".claude/hooks/guard-contract-writes.py")
TODAY = datetime.date.today().isoformat()


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.main = os.path.join(self.tmp, "main")
        self.wt = os.path.join(self.tmp, "wt")
        os.makedirs(os.path.join(self.main, "scripts"))
        self.write(self.main, "scripts/tool.py", "x = 1\n")
        self.write(self.main, "README.md", "readme\n")
        self.write(self.main, ".gitignore", "*.pyc\n")  # as in the real repo
        self.git(self.main, "init", "-q", "-b", "main")
        self.git(self.main, "add", "-A")
        self.git(self.main, "commit", "-qm", "base")
        self.base = self.git(self.main, "rev-parse", "HEAD")
        self.git(self.main, "worktree", "add", "-q", "-b", "exec/t-1", self.wt)
        self.contract = os.path.join(self.main, "state", "contracts", "T-1.yaml")
        self.write_contract()
        env = mock.patch.dict(os.environ, {"NOVA_BUILD_DIR": os.path.join(self.tmp, "build")})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("NOVA_AGENT_ROLE", None)

    # helpers
    def git(self, where, *args):
        return subprocess.run(["git", "-C", where, "-c", "user.name=t", "-c", "user.email=t@t"] + list(args),
                              capture_output=True, text=True, check=True).stdout.strip()

    def write(self, root, rel, text):
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(text)

    def write_contract(self, **override):
        c = {
            "schema": tc.SCHEMA_ID, "task_id": "T-1", "title": "fixture task", "branch": "exec/t-1",
            "base_commit": self.base, "approved_paths": ["scripts/tool.py", "scripts/tests/**"],
            "allowed_changes": ["change x"], "architecture_constraints": ["scripts only"],
            "forbidden_changes": ["no runtime code"], "forbidden_paths": ["app/**"],
            "required_verification": [
                {"id": "compile", "kind": "compile", "command": "%s -m py_compile scripts/tool.py" % sys.executable},
                {"id": "unit", "kind": "regression", "command": "%s -c pass" % sys.executable}],
            "acceptance_criteria": ["x is 2"],
        }
        c.update(override)
        os.makedirs(os.path.dirname(self.contract), exist_ok=True)
        with open(self.contract, "w") as f:
            yaml.safe_dump(c, f, sort_keys=False)

    def run_cli(self, *args, role=None):
        env = {"NOVA_AGENT_ROLE": role} if role else {}
        out = io.StringIO()
        with mock.patch.dict(os.environ, env), contextlib.redirect_stdout(out):
            code = tc.main(list(args[:1]) + [self.contract] + list(args[1:]) + ["--repo", self.main])
        return code, out.getvalue()

    def executor_commits(self, rel="scripts/tool.py", text="x = 2\n"):
        self.write(self.wt, rel, text)
        self.git(self.wt, "add", "-A")
        self.git(self.wt, "commit", "-qm", "executor change")
        return self.git(self.wt, "rev-parse", "HEAD")

    def review(self, tip, status="PASS"):
        with open(os.path.join(self.main, "state", "reviews.log"), "a") as f:
            f.write("%s %s %s independent reviewer ran\n" % (tip[:12], status, TODAY))

    def approve(self):
        self.assertEqual(self.run_cli("approve", "--role", "architect", "--note", "fixture approved")[0], 0)

    def eligible(self):
        return self.run_cli("eligible", "--worktree", self.wt)

    # 1. approved in-scope edit passes scope validation
    def test_in_scope_edit_passes(self):
        self.executor_commits()
        self.write(self.wt, "scripts/tests/test_tool.py", "pass\n")  # untracked but approved
        code, out = self.run_cli("scope", "--worktree", self.wt)
        self.assertEqual(code, 0, out)
        self.assertIn("SCOPE T-1 PASS: 2 changed path(s)", out)

    # 2. out-of-scope edits fail: committed, staged, unstaged and untracked are all seen
    def test_out_of_scope_edit_fails(self):
        self.executor_commits()
        for kind in ("committed", "staged", "unstaged", "untracked", "forbidden"):
            with self.subTest(kind=kind):
                self.git(self.wt, "reset", "-q", "--hard")
                self.git(self.wt, "clean", "-qfd")
                if kind == "committed":
                    self.executor_commits("docs/x.md", "x\n")
                elif kind == "staged":
                    self.write(self.wt, "other.txt", "x\n")
                    self.git(self.wt, "add", "other.txt")
                elif kind == "unstaged":
                    self.write(self.wt, "README.md", "changed\n")
                elif kind == "untracked":
                    self.write(self.wt, "new_dir/new.py", "x\n")
                else:
                    self.write(self.wt, "app/src/main/A.kt", "x\n")
                code, out = self.run_cli("scope", "--worktree", self.wt)
                self.assertEqual(code, 2, out)
                self.assertIn("SCOPE T-1 FAIL", out)

    # 3. executor changes to the contract or acceptance criteria fail
    def test_executor_cannot_alter_contract(self):
        self.approve()
        self.executor_commits("state/contracts/T-1.yaml", "acceptance_criteria: [anything]\n")
        code, out = self.run_cli("scope", "--worktree", self.wt)
        self.assertEqual(code, 2, out)
        self.assertIn("state/contracts/T-1.yaml: forbidden", out)
        # editing the approved contract in the main checkout invalidates the approval
        self.write_contract(acceptance_criteria=["something weaker"])
        code, out = self.eligible()
        self.assertEqual(code, 2)
        self.assertIn("contract changed after approval", out)
        # the executor cannot re-approve, record verification, or have its hook let it edit
        self.assertEqual(self.run_cli("approve", "--role", "architect", "--note", "self approve", role="executor")[0], 2)
        self.assertEqual(self.run_cli("approve", "--role", "executor", "--note", "self approve")[0], 2)
        self.assertEqual(self.run_cli("verify", "--worktree", self.wt, role="executor")[0], 2)
        self.assertIsNotNone(guard.decide("Edit", {"file_path": self.contract}, True))
        self.assertIsNotNone(guard.decide("Bash", {"command": "python3 scripts/task_contract.py approve c"}, True))
        # a contract may not approve protected record paths
        self.write_contract(approved_paths=["state/**"])
        self.assertEqual(self.run_cli("validate")[0], 1)

    # 4. missing or failing required tests: not merge-eligible
    def test_missing_required_tests_not_eligible(self):
        self.approve()
        tip = self.executor_commits()
        self.review(tip)
        code, out = self.eligible()
        self.assertEqual(code, 2)
        self.assertIn("no verification record", out)
        self.assertEqual(self.run_cli("verify", "--worktree", self.wt)[0], 0)
        record_path = tc.verification_path(self.main, "T-1")
        rec = json.load(open(record_path))
        rec["results"] = [r for r in rec["results"] if r["id"] != "unit"]  # drop one required result
        json.dump(rec, open(record_path, "w"))
        code, out = self.eligible()
        self.assertEqual(code, 2)
        self.assertIn("required verification 'unit' missing", out)
        # a failing command is recorded as a failure, never a pass
        self.write_contract(required_verification=[
            {"id": "unit", "kind": "regression", "command": "%s -c 'raise SystemExit(3)'" % sys.executable}])
        self.approve()
        self.assertEqual(self.run_cli("verify", "--worktree", self.wt)[0], 2)
        code, out = self.eligible()
        self.assertEqual(code, 2)
        self.assertIn("'unit' did not pass", out)

    # 5. missing independent review: not merge-eligible, and the merge hook refuses
    def test_missing_review_not_eligible(self):
        self.approve()
        tip = self.executor_commits()
        self.assertEqual(self.run_cli("verify", "--worktree", self.wt)[0], 0)
        code, out = self.eligible()
        self.assertEqual(code, 2)
        self.assertIn("no independent reviewer PASS", out)
        self.review(tip, "PENDING")
        self.assertEqual(self.eligible()[0], 2)
        self.review(tip, "WAIVED")  # WAIVED never satisfies a contracted branch
        self.assertIsNotNone(merge_hook.decide("git merge --no-ff exec/t-1", self.main,
                                               os.path.join(self.main, "state", "reviews.log")))

    # 6. fully verified: ELIGIBLE, and nothing is merged automatically
    def test_fully_verified_is_eligible_not_merged(self):
        self.approve()
        tip = self.executor_commits()
        self.assertEqual(self.run_cli("verify", "--worktree", self.wt)[0], 0)
        self.review(tip)
        code, out = self.eligible()
        self.assertEqual(code, 0, out)
        self.assertIn("ELIGIBLE T-1", out)
        self.assertEqual(self.run_cli("eligible", "--ref", "exec/t-1")[0], 0)
        self.assertIsNone(merge_hook.decide("git merge --no-ff exec/t-1", self.main,
                                            os.path.join(self.main, "state", "reviews.log")))
        self.assertEqual(self.git(self.main, "rev-parse", "HEAD"), self.base)  # main untouched
        # a new executor commit after verification makes it ineligible again
        self.executor_commits("scripts/tool.py", "x = 3\n")
        code, out = self.eligible()
        self.assertEqual(code, 2)
        self.assertIn("not the current tip", out)
        # the handoff summary is written and compact
        code, out = self.run_cli("handoff", "--worktree", self.wt, "--next", "re-verify")
        self.assertEqual(code, 0)
        self.assertTrue(os.path.exists(os.path.join(self.main, "state", "handoff", "T-1.md")))
        self.assertLess(len(out.splitlines()), 15)


class Committed(unittest.TestCase):
    """CI: committed contracts are valid; an approved one must match its approval. The example validates."""

    def test_example_contract_validates(self):
        c, _ = tc.load(os.path.join(REPO, "docs", "examples", "task_contract.example.yaml"))
        self.assertEqual(tc.validate(c), [])

    def test_committed_contracts(self):
        for path in glob.glob(os.path.join(REPO, "state", "contracts", "*.yaml")):
            c, digest = tc.load(path)
            self.assertEqual(tc.validate(c), [], path)
            appr = tc.latest_approval(REPO, c["task_id"])
            if appr:
                self.assertEqual(appr[0], digest, "%s changed after approval" % path)

    def test_hook_selftests(self):
        self.assertEqual(subprocess.run([sys.executable, os.path.join(REPO, ".claude/hooks/guard-contract-writes.py"),
                                         "--selftest"], capture_output=True).returncode, 0)


if __name__ == "__main__":
    unittest.main()
