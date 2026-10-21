"""Stop hook .claude/hooks/keep-going.py: block while autonomous work remains, with a loop guard."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HOOK = os.path.join(ROOT, ".claude", "hooks", "keep-going.py")


def frontier(available):
    return {"candidates": [], "stop": {"AUTONOMOUS_ACTION_AVAILABLE": "YES" if available else "NO",
                                       "NEXT_ACTION": "DEMO-TRYOUT-001: FAIL" if available else "NONE",
                                       "actionable_count": 87 if available else 0}}


class KeepGoingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.discover = os.path.join(self.tmp, "dw.json")

    def run_hook(self, event, available=True, env_extra=None):
        with open(self.discover, "w") as f:
            json.dump(frontier(available), f)
        env = dict(os.environ, NOVA_KEEP_GOING_DISCOVER=self.discover, NOVA_KEEP_GOING_STATE=self.tmp)
        env.update(env_extra or {})
        out = subprocess.run([sys.executable, HOOK], input=json.dumps(event), capture_output=True,
                             text=True, env=env, timeout=30)
        self.assertEqual(out.returncode, 0, out.stderr)
        return json.loads(out.stdout) if out.stdout.strip() else None

    def test_blocks_while_autonomous_work_remains(self):
        r = self.run_hook({"session_id": "s1", "stop_hook_active": False, "last_assistant_message": "Done."})
        self.assertEqual(r["decision"], "block")
        self.assertIn("DEMO-TRYOUT-001", r["reason"])
        self.assertNotIn("Do not ask", r["reason"])

    def test_allows_stop_when_frontier_has_no_autonomous_action(self):
        self.assertIsNone(self.run_hook({"session_id": "s2", "stop_hook_active": False}, available=False))

    def test_permission_question_gets_do_not_ask_reason(self):
        r = self.run_hook({"session_id": "s3", "stop_hook_active": False,
                           "last_assistant_message": "Items 1-3 remain. Want me to keep going?"})
        self.assertIn("Do not ask", r["reason"])

    def test_question_read_from_transcript_when_message_field_absent(self):
        path = os.path.join(self.tmp, "t.jsonl")
        with open(path, "w") as f:
            f.write(json.dumps({"message": {"role": "assistant",
                                            "content": [{"type": "text", "text": "Should I start item 3?"}]}}) + "\n")
        r = self.run_hook({"session_id": "s4", "stop_hook_active": False, "transcript_path": path})
        self.assertIn("Do not ask", r["reason"])

    def test_loop_guard_allows_stop_after_max_chain(self):
        ev = {"session_id": "s5", "stop_hook_active": False}
        self.assertIsNotNone(self.run_hook(ev))
        ev["stop_hook_active"] = True
        blocks = 1
        while self.run_hook(ev) is not None:
            blocks += 1
            self.assertLess(blocks, 100)
        self.assertEqual(blocks, 20)
        # a fresh stop (after the owner writes) starts a new chain
        self.assertIsNotNone(self.run_hook({"session_id": "s5", "stop_hook_active": False}))

    def test_disabled_and_fail_open(self):
        self.assertIsNone(self.run_hook({"session_id": "s6"}, env_extra={"NOVA_KEEP_GOING": "0"}))
        self.assertIsNone(self.run_hook({"session_id": "s7"},
                                        env_extra={"NOVA_KEEP_GOING_DISCOVER": "/nonexistent.json"}))


if __name__ == "__main__":
    unittest.main()
