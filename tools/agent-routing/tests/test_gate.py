"""E-J: risk classification, review gate lifecycle, reviewer failure modes, Stop hook bounds."""
import io
import json
import os
import sys
import unittest
from unittest import mock

from agent_routing import stop_hook
from agent_routing.config import ConfigError, load_config, load_defaults
from agent_routing.providers import make_provider
from agent_routing.gate import Gate
from agent_routing.redact import redact
from agent_routing.review import validate_review
from agent_routing.risk import classify

from .support import Base, git, finding, free_port, gemini_reply, make_repo, openai_reply, project_json, review_json

def _rules():
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
        # indentation is significant in Python: an indentation-only change is not formatting-only
        self.assertEqual(classify(["src/app.py"], "+    return x\n-return x\n", r)[0], "MEDIUM")
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
        self.assertNotIn("Zq8xk2Lm", redact("token=Zq8xk2Lm"))  # short (8-char) values after a key name


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
        outs = []
        for i in range(6):  # the fingerprint changes on every stop
            self.edit(root, text="x = %d\n" % (10 + i))
            outs.append(self.hook(root, i > 0))
        self.assertEqual([o.get("decision") for o in outs[:3]], ["block"] * 3)
        for o in outs[3:]:
            self.assertNotIn("decision", o)
            self.assertIn("UNVERIFIED", o["systemMessage"])
        self.edit(root, text="x = 99\n")
        self.assertEqual(self.hook(root, False)["decision"], "block")  # new user turn resets the session count

    def test_subdirectory_cwd_resolves_git_toplevel(self):
        root, _ = self.repo()
        self.edit(root)
        o = self.hook(root / "src", False)
        self.assertEqual(o["decision"], "block")
        self.assertTrue((root / ".agent-routing/state/blocks.json").exists())

    def test_claude_project_dir_wins(self):
        root, _ = self.repo()
        self.edit(root)
        os.environ["CLAUDE_PROJECT_DIR"] = str(root)
        out = io.StringIO()
        stop_hook.run(io.StringIO(json.dumps({"cwd": str(self.tmp), "stop_hook_active": False})), out)
        self.assertEqual(json.loads(out.getvalue())["decision"], "block")

    def test_test_timeout_is_unverified_not_killed(self):
        root, _ = self.repo(tests={"command": [sys.executable, "-c", "import time; time.sleep(5)"],
                                   "from_session": True}, limits={"test_timeout_s": 1})
        self.edit(root)
        o = self.hook(root, False)
        self.assertIn("UNVERIFIED", o["systemMessage"])
        self.assertIn("timed out", o["systemMessage"])

    def test_hook_timeout_config(self):
        from agent_routing.cli import HOOK_SNIPPET
        h = HOOK_SNIPPET["hooks"]["Stop"][0]["hooks"][0]
        self.assertIn('"$CLAUDE_PROJECT_DIR/tools/agent-routing/agent_routing_cli.py" stop-hook', h["command"])
        self.assertLess(load_defaults()["limits"]["test_timeout_s"], h["timeout"])
        bad = make_repo(self.tmp / "bad", project_json(limits={"test_timeout_s": 900}))
        with self.assertRaises(ConfigError):
            load_config(bad)

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


class TestReviseGate(GateBase):
    """Reviewer REVISE findings 4-9 and build-artifact exclusion."""

    def test_clear_refused_unless_pass_then_forced_is_unverified(self):
        root, cfg = self.repo()
        self.edit(root)
        g = Gate(cfg)
        with self.assertRaises(ValueError):
            g.clear()
        with self.assertRaises(ValueError):
            g.clear(force=True, reason=" ")
        g.clear(force=True, reason="owner accepted risk")
        audit = (root / ".agent-routing/state/audit.jsonl").read_text()
        self.assertIn('"forced": true', audit)
        out = io.StringIO()
        stop_hook.run(io.StringIO(json.dumps({"cwd": str(root)})), out)
        self.assertIn("UNVERIFIED", json.loads(out.getvalue())["systemMessage"])
        self.assertIn("by force", out.getvalue())

    def test_clear_allowed_when_pass(self):
        root, cfg = self.repo()
        self.edit(root)
        g = Gate(cfg)
        self.mock.push(200, openai_reply(review_json()))
        g.run_review("t")
        self.assertEqual(g.clear()["status"], "PASS")

    def test_commit_of_unreviewed_change_does_not_clear(self):
        root, cfg = self.repo()
        self.edit(root)
        g = Gate(cfg)
        self.assertEqual(g.evaluate()["status"], "BLOCK")
        git(root, "commit", "-qam", "sneak")
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertEqual(d["paths"], ["src/app.py"])
        self.mock.push(200, openai_reply(review_json()))
        g.run_review("t")
        self.assertEqual(g.evaluate()["status"], "PASS")
        head_sha = git(root, "rev-parse", "HEAD").stdout.strip()
        self.assertEqual(json.loads((root / ".agent-routing/state/gate_base.json").read_text())["base"], head_sha)
        self.assertEqual(g.evaluate()["status"], "CLEAN")

    def test_edit_commit_after_clean_stop_still_blocks(self):
        root, cfg = self.repo()
        g = Gate(cfg)
        self.assertEqual(g.evaluate()["status"], "CLEAN")
        self.edit(root)
        git(root, "commit", "-qam", "unreviewed")
        out = io.StringIO()
        stop_hook.run(io.StringIO(json.dumps({"cwd": str(root)})), out)
        o = json.loads(out.getvalue())
        self.assertEqual(o["decision"], "block")
        self.assertIn("review missing", o["reason"])

    def test_unsendable_paths_fail_closed_and_hash_only(self):
        root, cfg = self.repo()
        self.edit(root, "config/db_secret.txt", "SUPERSECRETVALUE\n")
        self.edit(root, "vendor/lib.py", "VENDORCODE = 1\n")
        g = Gate(cfg)
        snap = g.snapshot()
        self.assertEqual(sorted(snap["refused"]), ["config/db_secret.txt", "vendor/lib.py"])
        self.assertNotIn("SUPERSECRETVALUE", snap["diff"])
        self.assertNotIn("VENDORCODE", snap["diff"])
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertTrue(any("cannot be sent" in r for r in d["reasons"]))
        self.assertEqual(g.run_review("t")["verdict"], "ERROR")
        self.assertEqual(self.mock.requests, [])
        self.edit(root, "vendor/lib.py", "VENDORCODE = 2\n")
        self.assertNotEqual(g.snapshot()["fingerprint"], snap["fingerprint"])

    def test_oversized_diff_is_error_not_pass(self):
        root, cfg = self.repo(limits={"max_diff_bytes": 50})
        self.edit(root, text="x = 2\n" * 40)
        self.mock.push(200, openai_reply(review_json()))
        g = Gate(cfg)
        res = g.run_review("t")
        self.assertEqual(res["verdict"], "ERROR")
        self.assertIn("split it", res["errors"][0])
        self.assertEqual(self.mock.requests, [])
        self.assertEqual(g.evaluate()["status"], "BLOCK")

    def test_build_artifacts_do_not_stale_review(self):
        root, cfg = self.repo(tests={"command": [sys.executable, "-c",
                                                 "import os,sys; sys.exit(1 if os.environ.get('PYTHONDONTWRITEBYTECODE') != '1' else 0)"],
                                     "from_session": True}, ignore=["out/**"])
        self.edit(root)
        g = Gate(cfg)
        self.mock.push(200, openai_reply(review_json()))
        g.run_review("t")
        self.edit(root, "src/__pycache__/app.cpython-313.pyc", "bytecode")
        self.edit(root, "tools/agent-routing/agent_routing/__pycache__/stop_hook.cpython-313.pyc", "b")
        self.edit(root, "out/gen.py", "generated = 1\n")
        d = g.evaluate()
        self.assertEqual((d["status"], d["risk"]), ("PASS", "MEDIUM"), d)

    def test_cache_requires_configured_reviewer_model(self):
        root, cfg = self.repo()
        self.edit(root)
        g = Gate(cfg)
        other = make_provider(cfg["providers"]["gemini"], cfg["http"])
        self.mock.push(200, gemini_reply(review_json()))
        self.assertEqual(g.run_review("t", provider=other)["verdict"], "PASS")
        self.assertEqual(g.evaluate()["status"], "BLOCK")

    def test_medium_without_test_command_blocks(self):
        root, cfg = self.repo(tests={"command": None, "from_session": True})
        self.edit(root)
        self.mock.push(200, openai_reply(review_json()))
        g = Gate(cfg)
        g.run_review("t")
        d = g.evaluate()
        self.assertEqual(d["status"], "BLOCK")
        self.assertIn("requires tests", d["reasons"][0])

    def test_high_globs_respect_name_boundaries(self):
        r = _rules()
        for p in ("src/ui/PreviewScreen.kt", "src/nav/NavigateUseCase.kt", "src/x/delegate.py",
                  "src/AuthorList.kt", "src/chem/Reagent.kt", "src/Internet.txt.kt", "src/Gateway.kt",
                  "src/Cryptic.kt", "src/Authoring.kt", "src/Socketeer.kt"):
            self.assertEqual(classify([p], "+a = 1\n", r)[0], "MEDIUM", p)
        for p in ("src/AuthManager.kt", "src/OAuthClient.kt", "src/review_gate.py", "src/HttpClient.kt",
                  "src/agent/Planner.kt", "src/stop_hook.py", "src/net/Api.kt", "src/NetworkModule.kt",
                  "src/CryptUtil.kt", "src/EncryptionHelper.kt", "src/decrypt_blob.py", "src/Authorizer.kt",
                  "src/authorize.py", "src/AuthorizationFilter.kt", "src/SocketPool.kt", "src/web_sockets.py"):
            self.assertEqual(classify([p], "+a = 1\n", r)[0], "HIGH", p)


if __name__ == "__main__":
    unittest.main()
