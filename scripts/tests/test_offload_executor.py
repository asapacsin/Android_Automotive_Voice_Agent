"""Tests for scripts/offload_executor.py with a fake aider in a temporary git repo (no network)."""
import contextlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
import offload_executor as oe  # noqa: E402

import yaml  # noqa: E402

FAKE_AIDER = textwrap.dedent("""\
    #!/usr/bin/env python3
    import os, sys
    args = sys.argv[1:]
    msg = args[args.index("--message") + 1]
    assert "--no-detect-urls" in args, "missing --no-detect-urls"
    with open(os.environ["FAKE_LOG"] + ".ignore", "w") as f:
        f.write(open(args[args.index("--aiderignore") + 1]).read())
    print("key=" + os.environ.get("OPENROUTER_API_KEY", ""))
    log = os.environ["FAKE_LOG"]
    n = sum(1 for _ in open(log)) if os.path.exists(log) else 0
    with open(log, "a") as f:
        f.write(msg.replace("\\n", "\\\\n") + "\\n")
    mode = os.environ.get("FAKE_MODE", "fix")
    good = mode == "fix" or (mode == "fix2" and n >= 1)
    with open("lib/code.py", "w") as f:
        f.write("VALUE = %d\\n" % (42 if good else 0))
    if os.environ.get("FAKE_STRAY"):
        with open(".gitignore", "a") as f:
            f.write("junk\\n")
        open("stray_new.txt", "w").write("x")
    if os.environ.get("FAKE_GITADD"):
        open("added.txt", "w").write("x")
        import subprocess
        subprocess.run(["git", "add", "added.txt"], check=True)
    print("Tokens: 1k sent. Cost: $0.01 message, $%s session." % os.environ.get("FAKE_COST", "0.01"))
""")

CHECK = "import sys; sys.path.insert(0, 'lib'); import code as c; print('VALUE is', c.VALUE); sys.exit(c.VALUE != 42)"


def read(path):
    with open(path) as f:
        return f.read()


def git(repo, *a):
    return subprocess.run(["git", "-C", repo] + list(a), capture_output=True, text=True, check=True).stdout


class OffloadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="offload-test-")
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "lib"))
        os.makedirs(os.path.join(self.repo, "app", "src"))
        for p, s in {"lib/code.py": "VALUE = 0\n", "lib/other.py": "x = 1\n", "app/src/a.py": "",
                     "check.py": CHECK + "\n", ".gitignore": "secret.txt\n__pycache__/\n"}.items():
            with open(os.path.join(self.repo, p), "w") as f:
                f.write(s)
        git(self.repo, "init", "-q", "-b", "exec/t")
        git(self.repo, "-c", "user.email=t@t", "-c", "user.name=t", "add", "-A")
        git(self.repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "base")
        self.base = git(self.repo, "rev-parse", "HEAD").strip()
        self.aider = os.path.join(self.tmp, "fake_aider")
        with open(self.aider, "w") as f:
            f.write(FAKE_AIDER)
        os.chmod(self.aider, 0o755)
        self.log = os.path.join(self.tmp, "aider.log")
        self.env = mock.patch.dict(os.environ, {"NOVA_AIDER_BIN": self.aider, "FAKE_LOG": self.log,
                                                "OPENROUTER_API_KEY": ""})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def contract(self, approved=("lib/code.py",), **kw):
        c = {"schema": "nova-task-contract/1", "task_id": "T-1", "title": "Set VALUE", "branch": "exec/t", "base_commit": self.base,
             "approved_paths": list(approved), "allowed_changes": ["set VALUE to 42"],
             "architecture_constraints": ["stdlib"], "forbidden_changes": ["nothing else"],
             "forbidden_paths": ["*/src/**"], "acceptance_criteria": ["VALUE is 42"],
             "required_verification": [{"id": "value", "kind": "regression",
                                        "command": "%s check.py" % sys.executable}]}
        c.update(kw)
        path = os.path.join(self.tmp, "c.yaml")
        with open(path, "w") as f:
            yaml.safe_dump(c, f)
        return path

    def run_main(self, *args, **env):
        out = io.StringIO()
        with mock.patch.dict(os.environ, env), contextlib.redirect_stdout(out):
            code = oe.main([args[0], "--worktree", self.repo] + list(args[1:]))
        return code, out.getvalue()

    def calls(self):
        return read(self.log).splitlines() if os.path.exists(self.log) else []

    def test_file_list_selection_and_forbidden_drop(self):
        c = yaml.safe_load(read(self.contract(approved=("lib/*.py", "app/**", "lib/new.py"))))
        self.assertEqual(oe.file_list(c, self.repo), ["lib/code.py", "lib/new.py", "lib/other.py"])

    def test_refuse_gitignored(self):
        code, out = self.run_main(self.contract(approved=("secret.txt",)))
        self.assertEqual(code, 2)
        self.assertIn("REFUSED", out)
        self.assertIn("gitignored", out)
        self.assertEqual(self.calls(), [])

    def test_refuse_jar_and_app_libs(self):
        for p in ("lib/x.jar", "app/libs/y.txt", "vendor/z.txt", "local.properties"):
            code, out = self.run_main(self.contract(approved=(p,)))
            self.assertEqual(code, 2, p)
            self.assertIn("OFFLOAD T-1 REFUSED", out)
        self.assertEqual(self.calls(), [])

    def test_wrong_branch_refused(self):
        code, out = self.run_main(self.contract(branch="exec/other"))
        self.assertEqual(code, 2)
        self.assertIn("REFUSED", out)
        self.assertEqual(self.calls(), [])

    def test_dry_run(self):
        code, out = self.run_main(self.contract(), "--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("lib/code.py", out)
        self.assertIn("set VALUE to 42", out)
        self.assertIn("Edit only the files", out)
        self.assertEqual(self.calls(), [])

    def test_done_first_round(self):
        code, out = self.run_main(self.contract(), FAKE_MODE="fix")
        self.assertEqual(code, 0, out)
        self.assertIn("OFFLOAD T-1 DONE rounds=1", out)
        self.assertIn("checks=scope:PASS,value:PASS", out)
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(git(self.repo, "rev-parse", "HEAD").strip(), self.base)  # never commits

    def test_retry_with_failure_output(self):
        code, out = self.run_main(self.contract(), FAKE_MODE="fix2")
        self.assertEqual(code, 0, out)
        self.assertIn("DONE rounds=2", out)
        calls = self.calls()
        self.assertEqual(len(calls), 2)
        self.assertIn("check.py", calls[1])
        self.assertIn("VALUE is 0", calls[1])

    def test_max_rounds_failed(self):
        code, out = self.run_main(self.contract(), "--max-rounds", "2", FAKE_MODE="never")
        self.assertEqual(code, 1)
        self.assertIn("FAILED rounds=2", out)
        self.assertIn("value:FAIL", out)

    def test_cost_cap(self):
        code, out = self.run_main(self.contract(), "--max-cost", "0.20", FAKE_MODE="never", FAKE_COST="0.25")
        self.assertEqual(code, 1)
        self.assertIn("FAILED rounds=1 cost_usd=0.2500", out)
        self.assertEqual(len(self.calls()), 1)

    def test_strays_restored(self):
        code, out = self.run_main(self.contract(), FAKE_MODE="fix", FAKE_STRAY="1")
        self.assertEqual(code, 0, out)
        self.assertIn("stray=2", out)
        self.assertEqual(read(os.path.join(self.repo, ".gitignore")), "secret.txt\n__pycache__/\n")
        self.assertFalse(os.path.exists(os.path.join(self.repo, "stray_new.txt")))

    def test_key_not_printed(self):
        keycheck = os.path.join(self.repo, "keycheck.py")
        with open(keycheck, "w") as f:
            f.write("import os, sys; sys.exit('OPENROUTER_API_KEY' in os.environ)\n")
        git(self.repo, "add", "keycheck.py")
        git(self.repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "kc")
        c = self.contract(base_commit=git(self.repo, "rev-parse", "HEAD").strip(), required_verification=[
            {"id": "value", "kind": "regression", "command": "%s check.py" % sys.executable},
            {"id": "nokey", "kind": "regression", "command": "%s keycheck.py" % sys.executable}])
        code, out = self.run_main(c, FAKE_MODE="fix", OPENROUTER_API_KEY="sk-SECRET-123")
        self.assertEqual(code, 0, out)
        self.assertIn("nokey:PASS", out)
        self.assertNotIn("sk-SECRET-123", out)

    def test_aider_flags_and_aiderignore(self):
        code, out = self.run_main(self.contract(approved=("lib/code.py", "lib/new.py")), FAKE_MODE="fix")
        self.assertEqual(code, 0, out)
        self.assertEqual(read(self.log + ".ignore").splitlines(),
                         ["*", "**", "!/lib/code.py", "!/lib/new.py"])
        self.assertFalse(any(l.endswith("/") for l in read(self.log + ".ignore").splitlines()))

    def test_check_ignore_error_refused(self):
        os.symlink("lib", os.path.join(self.repo, "link"))
        with open(os.path.join(self.repo, "secret.txt"), "w") as f:
            f.write("s")
        git(self.repo, "add", "-f", "secret.txt")
        code, out = self.run_main(self.contract(approved=("link/new.py", "secret.txt")))
        self.assertEqual(code, 2, out)
        self.assertIn("REFUSED", out)
        self.assertIn("cannot check ignore status", out)
        self.assertEqual(self.calls(), [])

    def test_non_relative_paths_refused(self):
        for p in ("../x.py", "/tmp/x.py", "./app/libs/x.txt"):
            code, out = self.run_main(self.contract(approved=(p,)))
            self.assertEqual(code, 2, p)
            self.assertIn("OFFLOAD T-1 REFUSED", out)
        self.assertEqual(self.calls(), [])

    def test_git_added_stray_removed(self):
        code, out = self.run_main(self.contract(), FAKE_MODE="fix", FAKE_GITADD="1")
        self.assertEqual(code, 0, out)
        self.assertIn("stray=1", out)
        self.assertIn("scope:PASS", out)
        self.assertFalse(os.path.exists(os.path.join(self.repo, "added.txt")))
        self.assertEqual(git(self.repo, "ls-files", "added.txt"), "")


if __name__ == "__main__":
    unittest.main()
