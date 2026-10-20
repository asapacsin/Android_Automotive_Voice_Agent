"""Executor adapter: task + explicit files -> JSON change proposal -> strict validation -> apply -> tests."""
import json
import os
import re
import subprocess
from pathlib import Path

from . import config as C
from .budget import Ledger
from .providers import ProviderError, make_provider
from .redact import redact
from .util import SECRET_GLOBS, head, inside, is_binary, is_ignored, match_any, normalize_rel

SYSTEM = (
    "You are a code-change executor. Reply with ONLY one JSON object, no prose, no markdown fences:\n"
    '{"summary": "<one paragraph>", "edits": [ {"path": "<listed file>", "search": "<exact text that occurs '
    'exactly once>", "replace": "<new text>"} OR {"path": "<listed file>", "content": "<full new file>"} ]}\n'
    "Edit only the files listed as EDITABLE. Never touch other paths.")


def refusal(root, cfg, path):
    """Return None if `path` may be sent/edited, else a reason string."""
    try:
        rel = normalize_rel(path)
    except ValueError as e:
        return "%s: %s" % (path, e)
    if not inside(root, rel):
        return "%s: resolves outside the project" % rel
    if rel.startswith(C.PROJECT_DIR + "/state/"):
        return "%s: agent-routing state" % rel
    if match_any(rel, SECRET_GLOBS):
        return "%s: secret-looking file" % rel
    if match_any(rel, cfg.get("never_send", [])):
        return "%s: project never_send" % rel
    f = Path(root) / rel
    if f.exists() and (f.is_dir() or is_binary(f)):
        return "%s: binary/archive or directory" % rel
    if f.suffix.lower() in (".jar", ".aar", ".zip", ".so", ".apk"):
        return "%s: binary/archive" % rel
    if is_ignored(root, rel):
        return "%s: gitignored" % rel
    return None


def parse_json_reply(text):
    t = (text or "").strip()
    m = re.match(r"^```(?:json)?\s*\n(.*)\n```\s*$", t, re.S)
    if m:
        t = m.group(1)
    return json.loads(t)


def validate_proposal(obj, allowed):
    errs = []
    if not isinstance(obj, dict) or set(obj) != {"summary", "edits"}:
        return ["proposal must be an object with exactly summary and edits"]
    if not isinstance(obj["summary"], str) or not obj["summary"].strip():
        errs.append("summary must be a non-empty string")
    if not isinstance(obj["edits"], list) or not obj["edits"]:
        return errs + ["edits must be a non-empty list"]
    for i, e in enumerate(obj["edits"]):
        if not isinstance(e, dict) or not isinstance(e.get("path"), str):
            errs.append("edit %d: needs path" % i)
            continue
        keys = set(e)
        if keys not in ({"path", "search", "replace"}, {"path", "content"}):
            errs.append("edit %d: must be {path, search, replace} or {path, content}" % i)
        elif not all(isinstance(e[k], str) for k in keys):
            errs.append("edit %d: fields must be strings" % i)
        elif "search" in e and not e["search"]:
            errs.append("edit %d: empty search" % i)
        try:
            rel = normalize_rel(e["path"])
        except ValueError as ex:
            errs.append("edit %d: %s" % (i, ex))
            continue
        if rel not in allowed:
            errs.append("edit %d: %s is not in the allowed file list" % (i, rel))
    return errs


def plan_edits(root, edits):
    """Compute new contents in memory; raise ValueError on any mismatch (nothing written)."""
    new = {}
    for i, e in enumerate(edits):
        rel = normalize_rel(e["path"])
        f = Path(root) / rel
        cur = new.get(rel)
        if cur is None:
            cur = f.read_text(encoding="utf-8") if f.exists() else None
        if "content" in e:
            new[rel] = e["content"]
            continue
        if cur is None:
            raise ValueError("edit %d: %s does not exist (use content)" % (i, rel))
        n = cur.count(e["search"])
        if n != 1:
            raise ValueError("edit %d: search text occurs %d times in %s (need exactly 1)" % (i, n, rel))
        new[rel] = cur.replace(e["search"], e["replace"], 1)
    return new


def run_tests(root, cmd, timeout, key_names=()):
    if not cmd:
        return {"command": None, "passed": False, "returncode": None, "output_tail": "no test command configured"}
    try:
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        r = subprocess.run(list(cmd), cwd=str(root), capture_output=True, text=True, timeout=timeout, env=env)
        out = (r.stdout or "") + (r.stderr or "")
        return {"command": list(cmd), "passed": r.returncode == 0, "returncode": r.returncode,
                "output_tail": redact(out[-4000:], key_names)}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"command": list(cmd), "passed": False, "returncode": None,
                "timed_out": isinstance(e, subprocess.TimeoutExpired),
                "output_tail": "could not run tests: %s" % type(e).__name__}


def _record_session(root, cfg, files, tests_cmd):
    p = C.state_dir(cfg) / "sessions.json"
    s = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    s.setdefault("base", head(root))
    s["files"] = sorted(set(s.get("files", [])) | set(files))
    if tests_cmd:
        s["tests_command"] = list(tests_cmd)
    p.write_text(json.dumps(s, indent=2), encoding="utf-8")


def _build_prompt(root, cfg, task, files, context, feedback):
    lim = cfg["limits"]
    parts, total = ["TASK:\n" + task.strip() + "\n"], 0
    for label, group in (("EDITABLE", files), ("READ-ONLY CONTEXT", context)):
        for rel in group:
            f = Path(root) / rel
            body = f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""
            if len(body) > lim["max_file_bytes"]:
                body = body[: int(lim["max_file_bytes"])] + "\n[... truncated ...]"
            total += len(body)
            if total > lim["max_context_bytes"]:
                parts.append("%s %s: [omitted, context size cap reached]" % (label, rel))
                continue
            parts.append("%s FILE %s %s\n<<<\n%s\n>>>" % (label, rel, "(new file)" if not f.exists() else "", body))
    if feedback:
        parts.append("PREVIOUS ATTEMPT FEEDBACK (fix this):\n" + feedback)
    return redact("\n\n".join(parts), cfg["key_names"])


def execute(root, cfg, task, files, context=(), tests_cmd=None, findings=None, provider=None,
            use_fallback=False, today=None):
    root = Path(root)
    status = {"status": "REJECTED", "files": [], "tests": None, "provider": None, "model": None,
              "tokens": {"input": 0, "output": 0}, "cost_usd": 0.0, "rounds": 0, "errors": [], "summary": None}
    errs = [r for r in (refusal(root, cfg, p) for p in list(files) + list(context)) if r]
    if not files:
        errs.append("no files given")
    if errs:
        status["errors"] = errs
        return status
    files = [normalize_rel(p) for p in files]
    context = [normalize_rel(p) for p in context]
    tests_cmd = tests_cmd or cfg["tests"].get("command")
    if provider is None:
        try:
            pcfg = C.resolve(cfg, "executor", today=today, use_fallback=use_fallback)
        except C.RoutingError as e:
            status.update(status="FAILED", errors=[str(e)])
            return status
        ledger = Ledger(C.state_dir(cfg) / "usage.jsonl", cfg["budgets"].get("daily"))
        provider = make_provider(pcfg, cfg["http"], ledger, cfg["key_names"])
    status.update(provider=provider.id, model=provider.model)
    feedback = ""
    if findings:
        feedback = "Reviewer findings to address:\n" + redact(json.dumps(findings, indent=1), cfg["key_names"])
    rounds = max(1, int(cfg.get("executor_rounds", 2)))
    for rnd in range(1, rounds + 1):
        status["rounds"] = rnd
        try:
            res = provider.chat(SYSTEM, _build_prompt(root, cfg, task, files, context, feedback),
                                max_tokens=int(cfg["limits"]["max_output_tokens"]), json_mode=True)
        except ProviderError as e:
            status.update(status="FAILED", errors=[str(e)])
            return status
        status["tokens"]["input"] += res.input_tokens
        status["tokens"]["output"] += res.output_tokens
        if res.cost_usd is None:
            status["cost_usd"] = None
        elif status["cost_usd"] is not None:
            status["cost_usd"] = round(status["cost_usd"] + res.cost_usd, 6)
        try:
            obj = parse_json_reply(res.text)
        except (json.JSONDecodeError, ValueError):
            status.update(status="REJECTED", errors=["executor reply is not valid JSON"])
            return status
        verrs = validate_proposal(obj, set(files))
        if verrs:
            status.update(status="REJECTED", errors=verrs)
            return status
        try:
            new = plan_edits(root, obj["edits"])
        except (ValueError, OSError) as e:
            status.update(status="REJECTED", errors=[str(e)])
            return status
        for rel, content in new.items():
            f = root / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content, encoding="utf-8")
        _record_session(root, cfg, list(new), tests_cmd)
        status["files"] = sorted(set(status["files"]) | set(new))
        status["summary"] = obj["summary"]
        t = run_tests(root, tests_cmd, cfg["limits"]["test_timeout_s"], cfg["key_names"])
        status["tests"] = t
        if t["passed"]:
            status.update(status="APPLIED", errors=[])
            return status
        status.update(status="FAILED", errors=["tests did not pass" if t["command"] else "NO_TEST_COMMAND"])
        if not t["command"]:
            return status
        feedback = "The patch was applied but the tests failed:\n" + t["output_tail"][-3000:]
    return status
