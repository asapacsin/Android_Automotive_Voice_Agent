"""Executor output handling: truncation, oversize rewrites, raw-reply capture."""
import json

from agent_routing import config as C
from agent_routing.executor import execute

from .support import Base, gemini_reply


def proposal(**kw):
    e = {"path": "src/app.py", "search": "x = 1", "replace": "x = 2"}
    e.update(kw)
    return json.dumps({"summary": "bump x", "edits": [e]})


class TestExecutorLimits(Base):
    def test_truncated_reply_is_named_and_retried_with_guidance(self):
        root, cfg = self.repo()
        cap = int(cfg["limits"]["max_output_tokens"])
        self.mock.push(200, gemini_reply('{"summary": "cut', ct=cap))
        self.mock.push(200, gemini_reply(proposal()))
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "APPLIED", st)
        self.assertEqual(st["rounds"], 2)
        self.assertIn("TRUNCATED", self.mock.requests[1]["body"]["contents"][0]["parts"][0]["text"])

    def test_truncated_twice_is_rejected_not_applied(self):
        root, cfg = self.repo()
        cap = int(cfg["limits"]["max_output_tokens"])
        for _ in range(2):
            self.mock.push(200, gemini_reply('{"summary": "cut', ct=cap))
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "REJECTED")
        self.assertTrue(st["errors"][0].startswith("TRUNCATED"))
        self.assertEqual((root / "src/app.py").read_text(), "x = 1\n")

    def test_full_rewrite_of_large_existing_file_is_refused_then_retried(self):
        root, cfg = self.repo()
        big = "\n".join("line %d" % i for i in range(100)) + "\n"
        (root / "src/app.py").write_text(big)
        rewrite = json.dumps({"summary": "s", "edits": [{"path": "src/app.py", "content": "x = 1\n"}]})
        self.mock.push(200, gemini_reply(rewrite))
        self.mock.push(200, gemini_reply(proposal(search="line 3\nline 4", replace="line three\nline 4")))
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "APPLIED", st)
        self.assertIn("OVERSIZE_REWRITE", self.mock.requests[1]["body"]["contents"][0]["parts"][0]["text"])
        self.assertIn("line three", (root / "src/app.py").read_text())
        self.assertEqual((root / "src/app.py").read_text().count("\n"), 100)

    def test_full_content_for_new_or_tiny_file_is_still_allowed(self):
        root, cfg = self.repo()
        new = json.dumps({"summary": "s", "edits": [{"path": "src/new.py", "content": "y = 1\n"}]})
        self.mock.push(200, gemini_reply(new))
        st = execute(root, cfg, "t", ["src/new.py"])
        self.assertEqual(st["status"], "APPLIED", st)

    def test_rejected_reply_is_saved_redacted_for_diagnosis(self):
        root, cfg = self.repo()
        self.mock.push(200, gemini_reply("not json at all"))
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "REJECTED")
        saved = (C.state_dir(cfg) / "last_reply.txt").read_text()
        self.assertIn("not json at all", saved)
