#!/usr/bin/env python3
"""AI_NATIVE_REVIEWER_CMD for this repository: one read-only review by the routed reviewer model.

ai_native.py sets AI_NATIVE_REVIEW_PACKET and AI_NATIVE_REVIEW_RESULT. This bridge sends the packet's
goal, acceptance, boundaries and gate results plus the actual git diff to the provider configured as
role "reviewer" in .agent-routing/project.json (tools/agent-routing), and writes the strict result
JSON ai_native.py validates. Secrets named in the routing config are redacted before sending.

The diff is the dirty tree against HEAD; with a clean tree it is AI_NATIVE_REVIEW_BASE..HEAD
(default HEAD~1). It never edits the repository. Any failure exits non-zero, which ai_native.py
records as NOT_VERIFIED: a missing reviewer is never a PASS.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(os.environ.get("CLAUDE_PROJECT_DIR") or Path.cwd()).resolve()
sys.path.insert(0, str(REPO / "tools" / "agent-routing"))

MAX_DIFF_BYTES = 60_000
SYSTEM = (
    "You are an independent, read-only code reviewer. Judge ONLY the diff and evidence given against the "
    "goal, acceptance and boundaries. Reply with ONLY one JSON object, no prose: "
    '{"verdict": "PASS"|"REWORK"|"BLOCKED"|"NOT_VERIFIED", "findings": [string], "next_action": string}. '
    "PASS only if every required gate passed and the diff meets the acceptance with genuine evidence; "
    "REWORK names concrete defects; BLOCKED means an external dependency or human is needed; "
    "NOT_VERIFIED means the evidence given cannot prove the acceptance (for example device proof is required "
    "but only unit tests ran).")


def git(*args):
    return subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True, check=True).stdout


def main():
    packet = json.loads(Path(os.environ["AI_NATIVE_REVIEW_PACKET"]).read_text(encoding="utf-8"))
    out = Path(os.environ["AI_NATIVE_REVIEW_RESULT"])
    if packet["snapshot"].get("dirty"):
        diff = git("diff", "HEAD")
        # New files are part of the work too; git diff HEAD leaves them out.
        for rel in packet["snapshot"].get("untracked_hashes", {}):
            diff += subprocess.run(["git", "diff", "--no-index", "/dev/null", rel], cwd=REPO,
                                   capture_output=True, text=True).stdout
    else:
        diff = git("diff", os.environ.get("AI_NATIVE_REVIEW_BASE", "HEAD~1"), "HEAD")
    if not diff.strip():
        print("routing_reviewer: empty diff, nothing to review", file=sys.stderr)
        return 3
    if len(diff.encode()) > MAX_DIFF_BYTES:
        print("routing_reviewer: diff too large (%d bytes), split the task" % len(diff.encode()), file=sys.stderr)
        return 3

    from agent_routing import config as C
    from agent_routing.budget import Ledger
    from agent_routing.executor import parse_json_reply
    from agent_routing.gate import Gate
    from agent_routing.providers import make_provider
    from agent_routing.redact import redact
    cfg = C.load_config(REPO)
    g = Gate(cfg)
    prov = make_provider(C.resolve(cfg, "reviewer", log=g.audit), cfg["http"],
                         Ledger(g.dir / "usage.jsonl", cfg["budgets"].get("daily")), cfg["key_names"])
    gates = [{k: x.get(k) for k in ("id", "level", "required", "status")} for x in packet.get("gates", [])]
    user = ("GOAL:\n%s\n\nACCEPTANCE:\n%s\n\nBOUNDARIES:\n%s\n\nGATES:\n%s\n\nDIFF:\n%s" % (
        packet["goal"], packet["acceptance"], "\n".join(packet.get("boundaries", [])),
        json.dumps(gates, ensure_ascii=False), diff))
    res = prov.chat(SYSTEM, redact(user, cfg["key_names"]), max_tokens=int(cfg["limits"]["max_output_tokens"]),
                    json_mode=True)
    reply = parse_json_reply(res.text)
    if not isinstance(reply, dict) or reply.get("verdict") not in ("PASS", "REWORK", "BLOCKED", "NOT_VERIFIED"):
        print("routing_reviewer: unusable reviewer reply", file=sys.stderr)
        return 4
    findings = reply.get("findings") if isinstance(reply.get("findings"), list) else []
    out.write_text(json.dumps({
        "verdict": reply["verdict"],
        # Bound to exactly what was reviewed; ai_native.py rejects a mismatch.
        "reviewed_fingerprint": packet["snapshot"]["fingerprint"],
        "reviewed_contract_hash": packet["contract_hash"],
        "findings": [str(f) for f in findings],
        "next_action": str(reply.get("next_action", "")),
        "reviewer": "%s/%s" % (res.provider, res.model),
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print("routing_reviewer: %s by %s/%s" % (reply["verdict"], res.provider, res.model))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # reviewer unavailable: ai_native.py records NOT_VERIFIED
        print("routing_reviewer: %s: %s" % (type(e).__name__, str(e)[:300]), file=sys.stderr)
        sys.exit(5)
