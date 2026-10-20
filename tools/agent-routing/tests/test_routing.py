"""A-D: routing, model assignments, provider adapters, project independence."""
import datetime
import json
import os
import sys

from agent_routing import config as C
from agent_routing.budget import Ledger
from agent_routing.executor import execute
from agent_routing.providers import ProviderError, make_provider

from .support import Base, gemini_reply, openai_reply, project_json, make_repo

FAKE = "fake-test-key-0123456789abcdef"


def proposal(path="src/app.py", search="x = 1", replace="x = 2"):
    return json.dumps({"summary": "bump x", "edits": [{"path": path, "search": search, "replace": replace}]})


class TestA_GeminiRoute(Base):
    def test_routes_to_gemini_flash(self):
        _, cfg = self.repo()
        p = C.resolve(cfg, "executor")
        self.assertEqual((p["id"], p["model"]), ("gemini", "gemini-3.6-flash"))

    def test_execute_via_gemini_applies_and_tests(self):
        root, cfg = self.repo()
        self.mock.push(200, gemini_reply(proposal()))
        st = execute(root, cfg, "bump x", ["src/app.py"])
        self.assertEqual(st["status"], "APPLIED", st)
        self.assertEqual((root / "src/app.py").read_text(), "x = 2\n")
        req = self.mock.requests[0]
        self.assertEqual(req["path"], "/models/gemini-3.6-flash:generateContent")
        self.assertEqual((st["provider"], st["model"]), ("gemini", "gemini-3.6-flash"))
        self.assertEqual(st["tokens"], {"input": 13, "output": 5})
        self.assertTrue(st["tests"]["passed"])

    def test_key_header_sent_but_never_logged(self):
        root, cfg = self.repo()
        os.environ["GEMINI_API_KEY"] = FAKE
        self.mock.push(200, gemini_reply(proposal()))
        execute(root, cfg, "bump x", ["src/app.py"])
        hdrs = {k.lower(): v for k, v in self.mock.requests[0]["headers"].items()}
        self.assertEqual(hdrs.get("x-goog-api-key"), FAKE)
        for f in (root / ".agent-routing" / "state").iterdir():
            self.assertNotIn(FAKE, f.read_text())

    def test_unconfigured_without_proxy_injection(self):
        root, cfg = self.repo()
        cfg["providers"]["gemini"]["auth"] = "env"
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "FAILED")
        self.assertIn("PROVIDER_UNCONFIGURED", st["errors"][0])
        self.assertEqual(self.mock.requests, [])

    def test_proxy_injected_sends_no_key_header(self):
        root, cfg = self.repo()
        self.mock.push(200, gemini_reply(proposal()))
        execute(root, cfg, "t", ["src/app.py"])
        self.assertNotIn("x-goog-api-key", {k.lower() for k in self.mock.requests[0]["headers"]})

    def test_tests_failing_is_not_success(self):
        root, cfg = self.repo(tests={"command": [sys.executable, "-c", "import sys; sys.exit(3)"], "from_session": True})
        cfg["executor_rounds"] = 2
        self.mock.push(200, gemini_reply(proposal()))
        self.mock.push(200, gemini_reply(proposal(search="x = 2", replace="x = 3")))
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "FAILED")
        self.assertEqual(st["rounds"], 2)
        self.assertIn("tests failed", self.mock.requests[1]["body"]["contents"][0]["parts"][0]["text"])

    def test_refuses_unsafe_paths(self):
        root, cfg = self.repo()
        (root / "ignored.txt").write_text("i")
        (root / "vendor").mkdir()
        (root / "vendor/lib.py").write_text("v")
        for bad in ("../x.py", "/etc/passwd", "C:\\x.py", ".env", "key.pem", "ignored.txt", "vendor/lib.py",
                    "libs/a.jar", ".agent-routing/state/x.json"):
            st = execute(root, cfg, "t", [bad])
            self.assertEqual(st["status"], "REJECTED", bad)
        st = execute(root, cfg, "t", ["src/app.py"], context=[".env"])
        self.assertEqual(st["status"], "REJECTED")
        self.assertEqual(self.mock.requests, [])

    def test_rejects_proposal_outside_list_and_writes_nothing(self):
        root, cfg = self.repo()
        bad = json.dumps({"summary": "s", "edits": [{"path": "src/app.py", "search": "x = 1", "replace": "x = 9"},
                                                     {"path": "README.md", "content": "pwned"}]})
        self.mock.push(200, gemini_reply(bad))
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertEqual(st["status"], "REJECTED")
        self.assertEqual((root / "src/app.py").read_text(), "x = 1\n")
        for reply in ("not json", json.dumps({"summary": "s"}), json.dumps({"summary": "s", "edits": [
                {"path": "src/app.py", "search": "nope", "replace": "y"}]}),
                json.dumps({"summary": "s", "edits": [{"path": "src/app.py", "content": "a", "extra": 1}]})):
            self.mock.push(200, gemini_reply(reply))
            self.assertEqual(execute(root, cfg, "t", ["src/app.py"])["status"], "REJECTED", reply)
        self.assertEqual((root / "src/app.py").read_text(), "x = 1\n")


class TestB_PoolsideRoute(Base):
    def test_routes_to_laguna_free(self):
        _, cfg = self.repo(executor="poolside")
        p = C.resolve(cfg, "executor", today=datetime.date(2026, 10, 31))
        self.assertEqual((p["id"], p["model"]), ("poolside", "poolside/laguna-s-2.1:free"))

    def test_openrouter_request_uses_exact_model_and_bearer(self):
        root, cfg = self.repo(executor="poolside")
        os.environ["OPENROUTER_API_KEY"] = FAKE
        self.mock.push(200, openai_reply(proposal()))
        st = execute(root, cfg, "t", ["src/app.py"], today=datetime.date(2026, 10, 20))
        self.assertEqual(st["status"], "APPLIED", st)
        req = self.mock.requests[0]
        self.assertEqual(req["path"], "/chat/completions")
        self.assertEqual(req["body"]["model"], "poolside/laguna-s-2.1:free")
        self.assertEqual(req["headers"].get("Authorization"), "Bearer " + FAKE)

    def test_expired_free_endpoint_never_switches(self):
        root, cfg = self.repo(executor="poolside")
        with self.assertRaises(C.RoutingError) as cm:
            C.resolve(cfg, "executor", today=datetime.date(2026, 11, 1))
        self.assertEqual(cm.exception.code, "PROVIDER_EXPIRED")
        st = execute(root, cfg, "t", ["src/app.py"], today=datetime.date(2026, 11, 1))
        self.assertEqual(st["status"], "FAILED")
        self.assertIn("PROVIDER_EXPIRED", st["errors"][0])
        self.assertEqual(self.mock.requests, [])

    def test_model_override_refused(self):
        root = make_repo(self.tmp / "m", project_json(providers={"poolside": {"model": "poolside/laguna-s-2.1"}}))
        with self.assertRaises(C.ConfigError):
            C.load_config(root)


class TestC_Reviewer(Base):
    def test_reviewer_is_nemotron(self):
        _, cfg = self.repo()
        p = C.resolve(cfg, "reviewer")
        self.assertEqual((p["id"], p["model"]), ("nvidia", "nvidia/nemotron-3-ultra-550b-a55b"))
        self.assertEqual(p["base_url"], self.mock.url)
        self.assertEqual(C.load_defaults()["providers"]["nvidia"]["base_url"], "https://integrate.api.nvidia.com/v1")

    def test_reviewer_role_is_fixed(self):
        root = make_repo(self.tmp / "r", project_json(reviewer="gemini"))
        with self.assertRaises(C.ConfigError):
            C.load_config(root)

    def test_fallback_is_off_by_default_and_explicit(self):
        _, cfg = self.repo()
        self.assertEqual(cfg["fallback"], {"executor": None, "reviewer": None})
        with self.assertRaises(C.RoutingError):
            C.resolve(cfg, "executor", use_fallback=True)
        cfg["fallback"]["executor"] = "poolside"
        logged = []
        p = C.resolve(cfg, "executor", use_fallback=True, log=logged.append, today=datetime.date(2026, 10, 1))
        self.assertEqual(p["id"], "poolside")
        self.assertEqual(logged[0]["event"], "fallback_used")


class TestD_ProjectsIndependent(Base):
    def test_two_projects(self):
        r1, c1 = self.repo("one")
        r2, c2 = self.repo("two", executor="poolside")
        self.assertEqual(C.resolve(c1, "executor")["model"], "gemini-3.6-flash")
        self.assertEqual(C.resolve(c2, "executor", today=datetime.date(2026, 10, 1))["model"], "poolside/laguna-s-2.1:free")
        before = (r2 / ".agent-routing/project.json").read_bytes()
        self.mock.push(200, gemini_reply(proposal()))
        execute(r1, c1, "t", ["src/app.py"])
        self.assertEqual((r2 / ".agent-routing/project.json").read_bytes(), before)
        self.assertEqual((r2 / "src/app.py").read_text(), "x = 1\n")
        self.assertFalse((r2 / ".agent-routing/state/usage.jsonl").exists())
        self.assertTrue((r1 / ".agent-routing/state/usage.jsonl").exists())


class TestX_BudgetAndPrice(Base):
    def test_budget_exceeded_refuses_call(self):
        root, cfg = self.repo(budgets={"daily": {"gemini": {"tokens": 10, "usd": None}}})
        st = execute(root, cfg, "t", ["src/app.py"])
        self.assertIn("BUDGET_EXCEEDED", st["errors"][0])
        self.assertEqual(self.mock.requests, [])

    def test_cost_from_price_table(self):
        _, cfg = self.repo()
        p = dict(cfg["providers"]["nvidia"], price_per_mtok={"input": 1.0, "output": 2.0})
        led = Ledger(self.tmp / "u.jsonl", {})
        self.mock.push(200, openai_reply("ok", pt=1000, ct=500))
        r = make_provider(p, cfg["http"], led).chat("", "hi")
        self.assertAlmostEqual(r.cost_usd, 0.002)
        self.assertEqual(led.totals("nvidia"), (1500, 0.002))

    def test_retry_after_honoured(self):
        _, cfg = self.repo()
        slept = []
        http = dict(cfg["http"], backoff_max_s=60)
        self.mock.push(429, {"e": 1}, {"Retry-After": "7"})
        self.mock.push(200, openai_reply("ok"))
        r = make_provider(cfg["providers"]["nvidia"], http, sleep=slept.append).chat("", "hi")
        self.assertEqual((r.attempts, slept), (2, [7.0]))
        self.mock.push(400, {"e": 1})
        with self.assertRaises(ProviderError) as cm:
            make_provider(cfg["providers"]["nvidia"], http, sleep=slept.append).chat("", "hi")
        self.assertEqual(cm.exception.code, "HTTP_ERROR")
