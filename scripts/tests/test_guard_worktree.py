"""scripts/hooks/guard_worktree.py refuses hand-made worktrees and fails open otherwise."""
import json
import os
import subprocess
import sys
import unittest

HOOK = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hooks",
                    "guard_worktree.py")


def run(stdin, env_extra=None):
    env = dict(os.environ)
    env.pop("NOVA_GUARD_WORKTREE", None)
    env.update(env_extra or {})
    p = subprocess.run([sys.executable, HOOK], input=stdin, capture_output=True, text=True, env=env)
    return p.returncode, p.stdout


def payload(cmd):
    return json.dumps({"tool_name": "Bash", "tool_input": {"command": cmd}})


class GuardWorktree(unittest.TestCase):
    def assertDenied(self, cmd):
        code, out = run(payload(cmd))
        self.assertEqual(code, 0)
        out = json.loads(out)["hookSpecificOutput"]
        self.assertEqual(out["hookEventName"], "PreToolUse")
        self.assertEqual(out["permissionDecision"], "deny")
        self.assertIn("scripts/new_worktree.sh", out["permissionDecisionReason"])

    def assertAllowed(self, stdin, env=None):
        self.assertEqual(run(stdin, env), (0, ""))

    def test_denies_spellings(self):
        for cmd in ("git worktree add ../wt exec/x",
                    "git -C /home/user/repo worktree add -b exec/x /tmp/wt abc123",
                    "/usr/bin/git worktree add --detach /tmp/wt",
                    "cd /repo && git  worktree   add /tmp/wt",
                    "git -c core.x=y worktree add /tmp/wt",
                    "echo hi; git --git-dir=/r/.git worktree add /tmp/wt"):
            with self.subTest(cmd=cmd):
                self.assertDenied(cmd)

    def test_allows_others(self):
        for cmd in ("bash scripts/new_worktree.sh T exec/t abc123",
                    "git worktree list", "git worktree remove /tmp/wt", "ls -la",
                    "git status"):
            with self.subTest(cmd=cmd):
                self.assertAllowed(payload(cmd))

    def test_bad_json_fails_open(self):
        self.assertAllowed("not json{")
        self.assertAllowed("")
        self.assertAllowed(json.dumps([1, 2]))

    def test_disabled(self):
        self.assertAllowed(payload("git worktree add /tmp/wt"), {"NOVA_GUARD_WORKTREE": "0"})


if __name__ == "__main__":
    unittest.main()
