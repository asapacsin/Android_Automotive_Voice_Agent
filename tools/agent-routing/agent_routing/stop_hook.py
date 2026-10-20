"""Claude Code Stop hook: block while the gate requires work; bounded; fail-safe (never silent PASS)."""
import json
import sys

from . import config as C
from .gate import Gate


def _emit(out, obj):
    out.write(json.dumps(obj) + "\n")


def run(stdin=None, stdout=None, project=None):
    stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
    try:
        raw = stdin.read()
        data = json.loads(raw) if raw.strip() else {}
        root = C.find_project_root(project or data.get("cwd"))
        if not (root / C.PROJECT_DIR / "project.json").exists():
            return 0
        cfg = C.load_config(root)
        gate = Gate(cfg)
        d = gate.evaluate()
        if d["status"] in ("PASS", "CLEAN"):
            return 0
        fp = d["fingerprint"]
        blocks = gate._load("blocks.json", {})
        count = int(blocks.get(fp, 0))
        max_blocks = int(cfg["gate"]["max_blocks"])
        if count >= max_blocks:
            gate.audit({"event": "stop_hook", "status": "UNVERIFIED", "fingerprint": fp, "blocks": count,
                        "stop_hook_active": bool(data.get("stop_hook_active")), "reasons": d["reasons"]})
            _emit(stdout, {"systemMessage": "agent-routing: UNVERIFIED - stop allowed after %d blocks; gate still "
                                            "requires: %s" % (count, "; ".join(d["reasons"]))})
            return 0
        blocks[fp] = count + 1
        gate._save("blocks.json", blocks)
        gate.audit({"event": "stop_hook", "status": "BLOCK", "fingerprint": fp, "blocks": count + 1,
                    "stop_hook_active": bool(data.get("stop_hook_active"))})
        cli = "python3 tools/agent-routing/agent_routing_cli.py"
        reason = ("agent-routing gate (risk %s, block %d/%d): %s. Next: fix failing tests, then run `%s review "
                  "--project .`; address actionable findings; for HIGH risk record evidence with `%s gate resolve "
                  "--id <finding> --evidence <text>`." % (d["risk"], count + 1, max_blocks, "; ".join(d["reasons"]),
                                                          cli, cli))
        _emit(stdout, {"decision": "block", "reason": reason})
        return 0
    except Exception as e:  # fail safe: allow the stop, never report PASS
        _emit(stdout, {"systemMessage": "agent-routing: UNVERIFIED - stop hook internal error (%s); gate not "
                                        "evaluated" % type(e).__name__})
        return 0
