"""Test helpers: a local mock LLM server (http.server) and throwaway git repos. No network."""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from agent_routing import config as C

FAKE_KEY_ENVS = ("GEMINI_API_KEY", "OPENROUTER_API_KEY", "NVIDIA_API_KEY")


class MockLLM:
    """Replies from a queue of (status, body, headers); body dict -> JSON; 'DROP' closes the socket."""

    def __init__(self):
        self.queue, self.requests = [], []
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n).decode("utf-8")
                outer.requests.append({"path": self.path, "headers": dict(self.headers), "body": json.loads(body)})
                status, payload, hdrs = outer.queue.pop(0) if outer.queue else (500, {"error": "empty queue"}, {})
                if payload == "DROP":
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                data = payload.encode() if isinstance(payload, str) else json.dumps(payload).encode()
                self.send_response(status)
                for k, v in (hdrs or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def push(self, status, payload, headers=None):
        self.queue.append((status, payload, headers or {}))

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def openai_reply(content, pt=11, ct=7):
    return {"choices": [{"message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": pt, "completion_tokens": ct}}


def gemini_reply(content, pt=13, ct=5):
    return {"candidates": [{"content": {"parts": [{"text": content}]}}],
            "usageMetadata": {"promptTokenCount": pt, "candidatesTokenCount": ct}}


def review_json(verdict="PASS", findings=None, **kw):
    obj = {"verdict": verdict, "risk": "MEDIUM", "findings": findings or [], "confidence": 0.9,
           "model": "nvidia/nemotron-3-ultra-550b-a55b", "provider": "nvidia"}
    obj.update(kw)
    return json.dumps(obj)


def finding(fid="F1", severity="major", actionable=True, file="src/app.py"):
    return {"id": fid, "severity": severity, "file": file, "line": 1, "evidence": "x is wrong",
            "recommendation": "fix x", "actionable": actionable}


def git(root, *args):
    return subprocess.run(["git", "-C", str(root)] + list(args), capture_output=True, text=True, check=True)


def project_json(base_url=None, executor="gemini", **over):
    p = {"executor": executor, "reviewer": "nvidia", "fallback": {"executor": None, "reviewer": None},
         "providers": {}, "never_send": ["vendor/**"], "risk_rules": [], "risk_override": None,
         "tests": {"command": [sys.executable, "-c", "import sys; sys.exit(0)"], "from_session": True},
         "gate": {"scope": "all", "max_blocks": 3}, "budgets": {"daily": {}},
         "http": {"timeout_s": 5, "max_retries": 2, "backoff_base_s": 0, "backoff_max_s": 0}}
    if base_url:
        p["providers"] = {pid: {"base_url": base_url, "auth": "proxy-injected"} for pid in ("gemini", "poolside", "nvidia")}
    p.update(over)
    return p


def make_repo(root, pj, files=None):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    git(root, "init", "-q")
    git(root, "config", "user.email", "t@example.invalid")
    git(root, "config", "user.name", "t")
    git(root, "config", "commit.gpgsign", "false")
    (root / ".agent-routing").mkdir(exist_ok=True)
    (root / ".agent-routing" / "project.json").write_text(json.dumps(pj, indent=1))
    (root / ".agent-routing" / ".gitignore").write_text("state/\n")
    (root / ".gitignore").write_text("ignored.txt\n")
    for rel, content in (files or {"src/app.py": "x = 1\n", "README.md": "hello\n"}).items():
        f = root / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(content)
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "init")
    return root


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="agrt-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.mock = MockLLM()
        self.addCleanup(self.mock.close)
        self._env = {k: os.environ.pop(k) for k in FAKE_KEY_ENVS if k in os.environ}
        self.addCleanup(self._restore_env)

    def _restore_env(self):
        for k in FAKE_KEY_ENVS:
            os.environ.pop(k, None)
        os.environ.update(self._env)

    def repo(self, name="p", **over):
        root = make_repo(self.tmp / name, project_json(self.mock.url, **over))
        return root, C.load_config(root)
