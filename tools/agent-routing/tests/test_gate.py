"""E-J: risk classification, review gate lifecycle, reviewer failure modes, Stop hook bounds."""
import io
import json
import sys
import unittest
from unittest import mock

from agent_routing import stop_hook
from agent_routing.gate import Gate
from agent_routing.redact import redact
from agent_routing.review import validate_review
from agent_routing.risk import classify

from .support import Base, finding, free_port, openai_reply, review_json

RULES = None


def _rules():
    from agent_routing.config import load_defaults
    return load_defaults()["risk_rules"]


class GateBase(Base):
    def edit(self, root, rel="src/app.py", text="x = 2\n"):
        f = root / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text)


class TestE_RiskTriggersReview(GateBase):
    def test_classification(self):
        r = _rules()
        self.assertEqual(classify(["README.md"], "+hi\n", r)[0], "LOW")
        self.assertEqual(classify(["src/app.py"], "+x = 2\n-x = 1\n", r)[0], "MEDIUM")
        self.assertEqual(classify(["src/app.py"], "+x  =  1\n-x = 1\n", r), ("LOW", ["formatting-only"]))
        lvl, fired = classify([".claude/settings.json"], "+{}\n", r)
        self.assertEqual((lvl, "hooks-orchestration" in fired), ("HIGH", True))
        self.assertEqual(classify(["src/app.py"], "+lock = threading.Lock()\n", r)[0], "HIGH")
        self.assertEqual(classify(["src/net/client.kt"], "+a\n", r)[0], "HIGH")
        proj = [{"id": "p", "level": "HIGH", "paths": ["src/core/**"]}] + r
        self.assertEqual(classify(["src/core/a.py"], "+a\n", proj)[1][0], "p")
        self.assertEqual(classify(["src/app.py"], "+a\n", r, {"level": "LOW", "reason": "generated"})[0], "LOW")

    def test_low_change_needs_no_review(self):
        root, cfg = self.repo()
        self.edit(root, "README.md", "changed\n")
        d = Gate(cfg).evaluate()
        self.assertEqual((d["risk"], d["status"]), ("LOW", "PASS"))
        self.assertEqual(self.mock.requests, [])

    def test_medium_and_high_require_review(self):
        root, cfg = self.repo()
        self.edit(root)
        d = Gate(cfg).evaluate()
        self.assertEqual((d["risk"], d["status"]), ("MEDIUM", "BLOCK"))
        self.assertIn("review missing", d["reasons"][0])
        self.edit(root, "hooks/pre.py", "a = 1\n")
        d = Gate(cfg).evaluate()
        self.assertEqual((d["risk"], d["status"]), ("HIGH", "BLOCK"))

    def test_clean_tree(self):
        _, cfg = self.repo()
        self.assertEqual(Gate(cfg).evaluate()["status"], "CLEAN")

    def test_actual_diff_is_sent(self):
        root, cfg = self.repo()
        self.edit(root)
        self.edit(root, "src/new.py", "y = 5\n")
        self.mock.push(200, openai_reply(review_json()))
        Gate(cfg).run_review("bump")
        sent = self.mock.requests[0]["body"]["messages"][1]["content"]
        self.assertIn("-x = 1", sent)
        self.assertIn("+x = 2", sent)
        self.assertIn("+y = 5", sent)
        self.assertEqual(self.mock.requests[0]["body"]["model"], "nvidia/nemotron-3-ultra-550b-a55b")


class TestF_FailBlocks(GateBase):
    def test_fail_verdict_blocks(self):
        root, cfg = self.repo()
        self.edit(root)
        self.mock.push(200, openai_reply(review_json("FAIL", [finding()])))
        g = Gate(cfg)
        self.assertEqual(g.run_review("t")["verdict"], "FAIL")
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertIn("review FAIL", " ".join(d["reasons"]))

    def test_pass_review_never_overrides_failing_tests(self):
        root, cfg = self.repo(tests={"command": [sys.executable, "-c", "raise SystemExit(1)"], "from_session": True})
        self.edit(root)
        self.mock.push(200, openai_reply(review_json()))
        g = Gate(cfg)
        self.assertEqual(g.run_review("t")["verdict"], "PASS")
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertIn("tests failing", d["reasons"][0])


class TestG_FixAndPassPermits(GateBase):
    def test_fail_then_fix_then_pass(self):
        root, cfg = self.repo()
        self.edit(root, text="x = None\n")
        g = Gate(cfg)
        self.mock.push(200, openai_reply(review_json("FAIL", [finding()])))
        g.run_review("t")
        self.assertEqual(g.evaluate()["status"], "BLOCK")
        self.edit(root, text="x = 2\n")
        self.mock.push(200, openai_reply(review_json()))
        self.assertEqual(g.run_review("t")["verdict"], "PASS")
        self.assertEqual(g.evaluate()["status"], "PASS")

    def test_high_requires_resolution_evidence(self):
        root, cfg = self.repo()
        self.edit(root, "hooks/pre.py", "a = None\n")
        g = Gate(cfg)
        self.mock.push(200, openai_reply(review_json("FAIL", [finding("H1", file="hooks/pre.py")], risk="HIGH")))
        g.run_review("t")
        self.edit(root, "hooks/pre.py", "a = 1\n")
        self.mock.push(200, openai_reply(review_json(risk="HIGH")))
        g.run_review("t")
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertIn("H1", d["reasons"][0])
        with self.assertRaises(ValueError):
            g.resolve_finding("H1", " ")
        g.resolve_finding("H1", "a is now initialised; regression test test_a added")
        self.assertEqual(g.evaluate()["status"], "PASS")


class TestH_ChangeInvalidatesPass(GateBase):
    def test_new_edit_after_pass(self):
        root, cfg = self.repo()
        self.edit(root)
        g = Gate(cfg)
        self.mock.push(200, openai_reply(review_json()))
        g.run_review("t")
        self.assertEqual(g.evaluate()["status"], "PASS")
        self.edit(root, text="x = 3\n")
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertIn("stale", d["reasons"][0])
        self.edit(root, "src/other.py", "z = 1\n")
        self.edit(root, text="x = 2\n")
        self.assertEqual(g.evaluate()["status"], "BLOCK")

    def test_cache_key_includes_policy_and_model(self):
        _, cfg = self.repo()
        g = Gate(cfg)
        k = g.cache_key("fp")
        cfg["policy_version"] = "2"
        self.assertNotEqual(k, g.cache_key("fp"))


class TestI_UnavailableReviewerNeverPasses(GateBase):
    def _assert_error(self, root, cfg):
        g = Gate(cfg)
        res = g.run_review("t")
        self.assertEqual(res["verdict"], "ERROR", res)
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        return res

    def test_connection_refused(self):
        root, cfg = self.repo()
        self.edit(root)
        cfg["providers"]["nvidia"]["base_url"] = "http://127.0.0.1:%d" % free_port()
        self.assertIn("CONNECTION_ERROR", self._assert_error(root, cfg)["errors"][0])

    def test_401(self):
        root, cfg = self.repo()
        self.edit(root)
        self.mock.push(401, {"error": "unauthorized"})
        res = self._assert_error(root, cfg)
        self.assertEqual(res["error_code"], "AUTH_FAILED")
        self.assertEqual(len(self.mock.requests), 1)

    def test_5xx_after_retries(self):
        root, cfg = self.repo()
        self.edit(root)
        for _ in range(3):
            self.mock.push(503, {"error": "busy"})
        res = self._assert_error(root, cfg)
        self.assertEqual(res["error_code"], "SERVER_ERROR")
        self.assertEqual(len(self.mock.requests), 3)

    def test_dropped_connection(self):
        root, cfg = self.repo()
        self.edit(root)
        for _ in range(3):
            self.mock.push(200, "DROP")
        self._assert_error(root, cfg)

    def test_malformed_replies(self):
        root, cfg = self.repo()
        self.edit(root)
        bad = [
            openai_reply("Looks good to me!"),
            openai_reply(review_json(confidence=2)),
            openai_reply(json.dumps({"verdict": "PASS"})),
            openai_reply(review_json("FAIL", [])),
            openai_reply(review_json("PASS", [finding(severity="critical")])),
            openai_reply(review_json("PASS", [dict(finding(), line="3")])),
            openai_reply(review_json("APPROVED")),
            {"no": "choices"},
            "<html>not json</html>",
        ]
        for b in bad:
            self.mock.push(200, b)
            self._assert_error(root, cfg)

    def test_contract_validation_examples(self):
        ok = json.loads(review_json("PASS", [finding(severity="minor")]))
        self.assertEqual(validate_review(ok), [])
        self.assertEqual(validate_review(json.loads(review_json("PASS", [finding(actionable=False)]))), [])
        self.assertTrue(validate_review(dict(ok, confidence=True)))

    def test_model_identity_overwritten(self):
        root, cfg = self.repo()
        self.edit(root)
        self.mock.push(200, openai_reply(review_json(model="gpt-x", provider="other")))
        res = Gate(cfg).run_review("t")
        self.assertEqual((res["verdict"], res["model"], res["provider"]),
                         ("PASS", "nvidia/nemotron-3-ultra-550b-a55b", "nvidia"))

    def test_redaction(self):
        s = redact("k=AIza" + "A" * 35 + " Authorization: Bearer abcdefghijklmnop nvapi-" + "b" * 30)
        self.assertNotIn("AAAA", s)
        self.assertNotIn("abcdefghijklmnop", s)
        self.assertNotIn("bbbb", s)


class TestJ_StopHookBounded(GateBase):
    def hook(self, root, active):
        out = io.StringIO()
        rc = stop_hook.run(io.StringIO(json.dumps({"session_id": "s", "cwd": str(root), "hook_event_name": "Stop",
                                                   "stop_hook_active": active})), out)
        self.assertEqual(rc, 0)
        return json.loads(out.getvalue()) if out.getvalue().strip() else None

    def test_blocks_then_unverified_after_max(self):
        root, _ = self.repo()
        self.edit(root)
        outs = [self.hook(root, i > 0) for i in range(5)]
        self.assertEqual([o.get("decision") for o in outs[:3]], ["block"] * 3)
        for o in outs[3:]:
            self.assertNotIn("decision", o)
            self.assertIn("UNVERIFIED", o["systemMessage"])
            self.assertNotIn("PASS", o["systemMessage"])

    def test_new_fingerprint_gets_new_budget_but_is_bounded(self):
        root, _ = self.repo()
        self.edit(root)
        for _ in range(4):
            self.hook(root, True)
        self.edit(root, text="x = 7\n")
        self.assertEqual(self.hook(root, True)["decision"], "block")

    def test_clean_allows_silently(self):
        root, _ = self.repo()
        self.assertIsNone(self.hook(root, False))

    def test_internal_error_is_unverified_not_pass(self):
        root, _ = self.repo()
        self.edit(root)
        with mock.patch("agent_routing.stop_hook.Gate.evaluate", side_effect=RuntimeError("boom")):
            o = self.hook(root, False)
        self.assertIn("UNVERIFIED", o["systemMessage"])
        self.assertNotIn("decision", o)
        out = io.StringIO()
        stop_hook.run(io.StringIO("{not json"), out)
        self.assertIn("UNVERIFIED", out.getvalue())

    def test_sessions_scope_ignores_unrelated_changes(self):
        root, cfg = self.repo(gate={"scope": "sessions", "max_blocks": 3})
        self.edit(root)
        self.assertIsNone(self.hook(root, False))
        self.assertEqual(Gate(cfg).evaluate()["status"], "CLEAN")


if __name__ == "__main__":
    unittest.main()
