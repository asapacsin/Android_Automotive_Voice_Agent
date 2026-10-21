"""Claude Code Stop hook: block while the gate requires work; bounded; fail-safe (never silent PASS).

Bounds: (1) per change fingerprint at most gate.max_blocks blocks; (2) per session, at most
gate.max_blocks consecutive blocks while stop_hook_active is true (reset when it is false), so a
fingerprint that changes on every stop still cannot loop. After a bound the stop is allowed as UNVERIFIED.
"""
import json
import sys

from . import config as C
from .gate import Gate

CLI = 'python3 "$CLAUDE_PROJECT_DIR/tools/agent-routing/agent_routing_cli.py"'


def _emit(out, obj):
    out.write(json.dumps(obj) + "\n")


def _unverified(gate, out, why, d, extra=None):
    gate.audit(dict({"event": "stop_hook", "status": "UNVERIFIED", "why": why, "fingerprint": d.get("fingerprint"),
                     "reasons": d.get("reasons")}, **(extra or {})))
    _emit(out, {"systemMessage": "agent-routing: UNVERIFIED - %s; gate still requires: %s"
                                 % (why, "; ".join(d.get("reasons") or []) or "n/a")})
    return 0


def run(stdin=None, stdout=None, project=None):
    stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
    try:
        raw = stdin.read()
        data = json.loads(raw) if raw.strip() else {}
        root = C.find_project_root(project) if project else C.find_hook_root(data.get("cwd"))
        if not (root / C.PROJECT_DIR / "project.json").exists():
            return 0
        cfg = C.load_config(root)
        gate = Gate(cfg)
        sid = str(data.get("session_id") or "unknown")
        active = bool(data.get("stop_hook_active"))
        max_blocks = int(cfg["gate"]["max_blocks"])
        sessions = gate._load("hook_sessions.json", {})
        if not active:
            sessions[sid] = 0
            gate._save("hook_sessions.json", sessions)
        d = gate.evaluate()
        if d["status"] in ("PASS", "CLEAN"):
            sessions[sid] = 0
            gate._save("hook_sessions.json", sessions)
            return 0
        if d.get("unverified"):
            return _unverified(gate, stdout, "gate cleared by force (%s)" % d["unverified"], d)
        if (d.get("tests") or {}).get("timed_out"):
            return _unverified(gate, stdout, "test command timed out (limits.test_timeout_s)", d)
        fp = d["fingerprint"]
        blocks = gate._load("blocks.json", {})
        count, consec = int(blocks.get(fp, 0)), int(sessions.get(sid, 0))
        if count >= max_blocks:
            return _unverified(gate, stdout, "stop allowed after %d blocks for this change" % count, d)
        if active and consec >= max_blocks:
            return _unverified(gate, stdout, "stop allowed after %d consecutive blocks in this session" % consec, d)
        blocks[fp] = count + 1
        sessions[sid] = consec + 1
        gate._save("blocks.json", blocks)
        gate._save("hook_sessions.json", sessions)
        gate.audit({"event": "stop_hook", "status": "BLOCK", "fingerprint": fp, "blocks": count + 1,
                    "consecutive": consec + 1, "stop_hook_active": active})
        reason = ("agent-routing gate (risk %s, block %d/%d): %s. Next: fix failing tests, then run `%s review "
                  "--project .`; address actionable findings; for HIGH risk record evidence with `%s gate resolve "
                  "--id <finding> --evidence <text>`." % (d["risk"], max(count, consec) + 1, max_blocks,
                                                          "; ".join(d["reasons"]), CLI, CLI))
        _emit(stdout, {"decision": "block", "reason": reason})
        return 0
    except Exception as e:  # fail safe: allow the stop, never report PASS
        _emit(stdout, {"systemMessage": "agent-routing: UNVERIFIED - stop hook internal error (%s); gate not "
                                        "evaluated" % type(e).__name__})
        return 0
