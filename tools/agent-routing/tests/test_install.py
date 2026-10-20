"""K-L: install leaves unrelated configs untouched; a fresh repo adopts the tool and its gate works."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

from agent_routing.config import TOOL_ROOT

from .support import Base, git, openai_reply, review_json

CLI = [sys.executable, str(TOOL_ROOT / "agent_routing_cli.py")]


def sums(root, rels):
    return {r: hashlib.sha256((root / r).read_bytes()).hexdigest() for r in rels}


def run(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


class TestK_InstallLeavesUnrelatedConfigs(Base):
    def test_checksums_unchanged(self):
        t = self.tmp / "other"
        (t / ".claude").mkdir(parents=True)
        files = {".claude/settings.json": '{"hooks": {}}', "package.json": '{"name": "x"}', ".gitignore": "node_modules/\n",
                 "pyproject.toml": "[project]\nname='x'\n", "AGENTS.md": "rules\n"}
        for r, c in files.items():
            (t / r).write_text(c)
        before = sums(t, files)
        p = run(CLI + ["install", "--target", str(t)])
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(sums(t, files), before)
        self.assertTrue((t / "tools/agent-routing/agent_routing/gate.py").exists())
        self.assertFalse(list((t / "tools/agent-routing").rglob("__pycache__")))
        pj = t / ".agent-routing/project.json"
        pj.write_text(json.dumps(dict(json.loads(pj.read_text()), executor="poolside")))
        keep = sums(t, [".agent-routing/project.json"])
        self.assertNotEqual(run(CLI + ["install", "--target", str(t)]).returncode, 0)
        self.assertEqual(run(CLI + ["install", "--target", str(t), "--force"]).returncode, 0)
        self.assertEqual(sums(t, [".agent-routing/project.json"]), keep)
        self.assertEqual(sums(t, files), before)


class TestL_FreshRepoAdopts(Base):
    def test_install_steps_and_gate(self):
        t = self.tmp / "fresh"
        t.mkdir()
        git(t, "init", "-q")
        git(t, "config", "user.email", "t@example.invalid")
        git(t, "config", "user.name", "t")
        git(t, "config", "commit.gpgsign", "false")
        (t / "app.py").write_text("x = 1\n")
        self.assertEqual(run(CLI + ["install", "--target", str(t)]).returncode, 0)
        local = [sys.executable, str(t / "tools/agent-routing/agent_routing_cli.py")]
        pj = t / ".agent-routing/project.json"
        cfg = json.loads(pj.read_text())
        cfg["providers"] = {"nvidia": {"base_url": self.mock.url, "auth": "proxy-injected"}}
        cfg["tests"]["command"] = [sys.executable, "-c", "import sys; sys.exit(0)"]
        cfg["http"] = {"max_retries": 0, "backoff_base_s": 0, "backoff_max_s": 0, "timeout_s": 5}
        pj.write_text(json.dumps(cfg, indent=2))
        doc = run(local + ["doctor", "--project", str(t)])
        self.assertEqual(doc.returncode, 0, doc.stdout + doc.stderr)
        self.assertIn("SKIPPED", doc.stdout)
        git(t, "add", "-A")
        git(t, "commit", "-q", "-m", "adopt agent-routing")
        self.assertFalse(run(["git", "-C", str(t), "status", "--porcelain"]).stdout.strip())
        self.assertEqual(run(local + ["gate", "check", "--project", str(t)]).returncode, 0)
        (t / "app.py").write_text("x = 2\n")
        g = run(local + ["gate", "check", "--project", str(t)])
        self.assertEqual(g.returncode, 1, g.stdout)
        self.assertEqual(json.loads(g.stdout)["status"], "BLOCK")
        hook = run(local + ["stop-hook"], input=json.dumps({"cwd": str(t), "stop_hook_active": False}), cwd=str(t))
        self.assertEqual(json.loads(hook.stdout)["decision"], "block")
        self.mock.push(200, openai_reply(review_json()))
        r = run(local + ["review", "--project", str(t), "--summary", "bump x"])
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(run(local + ["gate", "check", "--project", str(t)]).returncode, 0)
        hook = run(local + ["stop-hook"], input=json.dumps({"cwd": str(t), "stop_hook_active": False}), cwd=str(t))
        self.assertEqual(hook.stdout.strip(), "")
        self.assertTrue((t / ".agent-routing/state/audit.jsonl").exists())
        self.assertEqual(Path(t / ".agent-routing/.gitignore").read_text(), "state/\n")
