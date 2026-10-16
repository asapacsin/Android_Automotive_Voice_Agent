"""Review-gate tests: scripts/delegation_check.py and .claude/hooks/require-review-before-merge.py."""
import importlib.util
import io
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _load(name, rel):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, rel))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


dc = _load("delegation_check", "scripts/delegation_check.py")
hook = _load("require_review", ".claude/hooks/require-review-before-merge.py")
D = "2026-10-16"


class Repo(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.tmp = os.path.join(self.root, "r")
        os.makedirs(self.tmp)
        self.log = os.path.join(self.root, "reviews.log")
        with open(self.log, "w") as f:
            f.write("# header\n")
        self.g("init", "-q", "-b", "main")
        self.commit("README.md", "base")
        self.base = self.g("rev-parse", "HEAD")
        self.cutoff = self.base  # nothing after base is grandfathered

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def g(self, *a, cwd=None):
        return subprocess.run(["git", "-C", cwd or self.tmp, "-c", "user.name=t", "-c", "user.email=t@t"]
                              + list(a), capture_output=True, text=True, check=True).stdout.strip()

    def commit(self, path, msg, cwd=None):
        full = os.path.join(cwd or self.tmp, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "a") as f:
            f.write(msg + "\n")
        self.g("add", "-A", cwd=cwd)
        self.g("commit", "-q", "-m", msg, cwd=cwd)
        return self.g("rev-parse", "HEAD", cwd=cwd)

    def exec_merge(self, name, path="app/src/main/A.kt"):
        self.g("checkout", "-q", "-b", "exec/" + name)
        tip = self.commit(path, "work " + name)
        self.g("checkout", "-q", "main")
        self.g("merge", "-q", "--no-ff", "-m", "Merge exec/%s: thing" % name, "exec/" + name)
        return tip

    def record(self, *lines):
        with open(self.log, "a") as f:
            for line in lines:
                f.write(line + "\n")

    def run_audit(self, ci, upstreams=()):
        out = []
        rc = dc.audit(self.tmp, self.base, self.log, self.cutoff, list(upstreams), ci=ci, out=out.append)
        return rc, "\n".join(out)

    def assertAccepted(self):
        rc, out = self.run_audit(True)
        self.assertEqual(rc, 0, out)
        self.assertIn("GATE review-records PASS", out)

    def assertCiFails(self):
        rc, out = self.run_audit(True)
        self.assertEqual(rc, 1, out)
        return out


class RecordTests(Repo):
    def test_pass_accepted(self):
        tip = self.exec_merge("a")
        self.record("%s PASS %s reviewer ok" % (tip[:7], D))
        self.assertAccepted()
        self.assertEqual(self.run_audit(False)[0], 0)

    def test_missing_fails(self):
        self.exec_merge("a")
        self.assertIn("NO REVIEW", self.assertCiFails())
        self.assertEqual(self.run_audit(False)[0], 1)

    def test_pending_ci_fails_audit_reports(self):
        tip = self.exec_merge("a")
        self.record("%s PENDING %s review running" % (tip[:12], D))
        self.assertIn("UNRESOLVED", self.assertCiFails())
        rc, out = self.run_audit(False)
        self.assertEqual(rc, 0, out)
        self.assertIn("UNRESOLVED", out)

    def test_revise_unresolved(self):
        tip = self.exec_merge("a")
        self.record("%s REVISE %s reviewer asked changes" % (tip[:12], D))
        self.assertIn("UNRESOLVED", self.assertCiFails())

    def test_revised_with_passed_merged_fix_accepted(self):
        tip = self.exec_merge("a")
        fix = self.exec_merge("a-fix", "app/src/main/B.kt")
        self.record("%s REVISED %s %s fix reviewed" % (tip[:12], D, fix[:12]),
                    "%s PASS %s reviewer" % (fix[:12], D))
        self.assertAccepted()

    def test_revised_fix_without_pass_fails(self):
        tip = self.exec_merge("a")
        fix = self.exec_merge("a-fix", "app/src/main/B.kt")
        self.record("%s REVISED %s %s fix" % (tip[:12], D, fix[:12]),
                    "%s PENDING %s reviewing" % (fix[:12], D))
        self.assertCiFails()

    def test_revised_without_sha_invalid(self):
        tip = self.exec_merge("a")
        self.record("%s REVISED %s fixed somewhere" % (tip[:12], D))
        self.assertIn("INVALID", self.assertCiFails())

    def test_typo_status_invalid(self):
        tip = self.exec_merge("a")
        self.record("%s PASS %s reviewer" % (tip[:12], D), "%s PASSED %s reviewer" % (tip[:12], D))
        self.assertIn("INVALID", self.assertCiFails())
        self.assertEqual(self.run_audit(False)[0], 1)

    def test_bad_date_and_missing_note_invalid(self):
        self.assertRaises(ValueError, dc.parse_line, "abcdef0 PASS 16-10-2026 x")
        self.assertRaises(ValueError, dc.parse_line, "abcdef0 PASS 2026-10-16")
        self.assertRaises(ValueError, dc.parse_line, "xyz1234 PASS 2026-10-16 x")
        self.assertIsNone(dc.parse_line("# c"))
        self.assertIsNone(dc.parse_line("   "))

    def test_waived_short_reason_invalid(self):
        tip = self.exec_merge("a", "app/src/debug/A.kt")
        self.record("%s WAIVED %s debug only" % (tip[:12], D))
        self.assertIn("INVALID", self.assertCiFails())

    def test_waived_src_main_after_cutoff_fails(self):
        tip = self.exec_merge("a")
        self.record("%s WAIVED %s small change trust me" % (tip[:12], D))
        self.assertIn("WAIVED on <module>/src/main", self.assertCiFails())

    def test_waived_src_main_grandfathered_before_cutoff(self):
        tip = self.exec_merge("a")
        self.cutoff = self.g("rev-parse", "HEAD")
        self.record("%s WAIVED %s grandfathered before the cutoff" % (tip[:12], D))
        self.assertAccepted()

    def test_waived_debug_only_accepted(self):
        tip = self.exec_merge("a", "app/src/debug/A.kt")
        self.record("%s WAIVED %s debug only tool no product path" % (tip[:12], D))
        self.assertAccepted()

    def test_last_line_wins(self):
        tip = self.exec_merge("a")
        self.record("%s PASS %s reviewer" % (tip[:12], D), "%s REVISE %s reviewer again" % (tip[:12], D))
        self.assertIn("UNRESOLVED", self.assertCiFails())


class BypassTests(Repo):
    def test_non_exec_merge_from_other_worktree(self):
        wt = os.path.join(self.root, "wt")
        self.g("worktree", "add", "-q", "-b", "foo", wt, self.base)
        self.commit("app/src/main/X.kt", "sneaky", cwd=wt)
        self.g("merge", "-q", "--no-ff", "foo", "-m", "Merge branch 'foo'")
        merge = self.g("rev-parse", "HEAD")
        out = self.assertCiFails()
        self.assertIn(merge[:12], out)
        self.assertIn("GATE delegation FAIL", out)

    def test_fast_forward(self):
        wt = os.path.join(self.root, "wt")
        self.g("worktree", "add", "-q", "-b", "foo", wt, self.base)
        sha = self.commit("app/src/main/X.kt", "sneaky ff", cwd=wt)
        self.g("merge", "-q", "--ff-only", "foo")
        self.assertIn(sha[:12], self.assertCiFails())

    def test_upstream_sync_allowed(self):
        self.g("checkout", "-q", "-b", "upstream", self.base)
        self.commit("app/src/main/U.kt", "upstream work")
        self.g("checkout", "-q", "main")
        self.commit("docs/a.md", "feature docs")
        self.g("merge", "-q", "--no-ff", "upstream", "-m", "Merge branch 'upstream'")
        rc, out = self.run_audit(True, ["upstream"])
        self.assertEqual(rc, 0, out)
        rc, out = self.run_audit(True, [])
        self.assertEqual(rc, 1, out)

    def test_missing_baseline_ci_fails_closed(self):
        self.base = "0" * 40
        rc, out = self.run_audit(True)
        self.assertEqual(rc, 1)
        self.assertIn("history incomplete: fetch-depth 0 needed", out)

    def test_shallow_clone_ci_fails_closed(self):
        self.commit("docs/a.md", "one")
        self.commit("docs/b.md", "two")
        clone = os.path.join(self.root, "shallow")
        subprocess.run(["git", "clone", "-q", "--depth", "1", "file://" + self.tmp, clone], check=True)
        out = []
        rc = dc.audit(clone, self.base, self.log, self.cutoff, [], ci=True, out=out.append)
        self.assertEqual(rc, 1)
        self.assertIn("history incomplete", "\n".join(out))


class HookTests(Repo):
    def setUp(self):
        super().setUp()
        self.g("checkout", "-q", "-b", "exec/x")
        self.tip = self.commit("app/src/main/A.kt", "work")
        self.g("checkout", "-q", "main")

    def decide(self, cmd="git merge --no-ff exec/x"):
        return hook.decide(cmd, self.tmp, self.log)

    def test_pass_allowed(self):
        self.record("%s PASS %s reviewer" % (self.tip[:12], D))
        self.assertIsNone(self.decide())

    def test_pending_refused(self):
        self.record("%s PENDING %s running" % (self.tip[:12], D))
        self.assertIsNotNone(self.decide())

    def test_revise_after_pass_refused(self):
        self.record("%s PASS %s reviewer" % (self.tip[:12], D), "%s REVISE %s again" % (self.tip[:12], D))
        self.assertIsNotNone(self.decide())

    def test_invalid_line_refused(self):
        self.record("%s PASSED %s reviewer" % (self.tip[:12], D))
        self.assertIsNotNone(self.decide())

    def test_waived_src_main_refused(self):
        self.record("%s WAIVED %s small change trust me" % (self.tip[:12], D))
        self.assertIsNotNone(self.decide())

    def test_pull_without_record_refused(self):
        self.assertIsNotNone(self.decide("git pull . exec/x"))


if __name__ == "__main__":
    unittest.main()
