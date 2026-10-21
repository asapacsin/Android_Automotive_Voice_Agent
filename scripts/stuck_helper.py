#!/usr/bin/env python3
"""Ask the reviewer model for a plan when a run is stuck.

Call it when you cannot work out what is going on, or are about to give up or terminate:

    python3 scripts/stuck_helper.py --goal "..." --tried "..." --observed "..." [--log path] [--max-log-bytes N]

The reviewer (role "reviewer" in .agent-routing/project.json) gets the goal, what was tried and the
observed evidence, and replies with likely causes, the next concrete steps and a stop condition.
The script prints that plan; the caller then continues by it.

It never blocks the run: if there is no reviewer, no key, a network error or an unusable reply, it
prints "PASS: no plan" and exits 0, and the caller carries on as before. Exit code is 0 either way.
Secrets named in the routing config are redacted before anything is sent. One call per invocation.
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools" / "agent-routing"))

SYSTEM = (
    "You are a senior engineer unblocking a stuck automation run. You get the goal, what was already "
    "tried and the evidence observed. Reply in plain text, at most 25 lines, with exactly these "
    "sections: CAUSES (ranked, each with the evidence that supports it), NEXT STEPS (numbered, each "
    "one a single concrete command or edit that tells causes apart), STOP IF (when to stop and ask "
    "the owner). Do not invent facts that are not in the evidence; say what is unknown.")


def tail_bytes(path, limit):
    data = Path(path).read_bytes()
    return data[-limit:].decode("utf-8", "replace")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--goal", required=True)
    ap.add_argument("--tried", default="")
    ap.add_argument("--observed", default="")
    ap.add_argument("--log", action="append", default=[], help="log file whose tail is attached (repeatable)")
    ap.add_argument("--max-log-bytes", type=int, default=6000)
    a = ap.parse_args(argv)

    try:
        from agent_routing import config as C
        from agent_routing.budget import Ledger
        from agent_routing.gate import Gate
        from agent_routing.providers import ProviderError, make_provider
        from agent_routing.redact import redact
        cfg = C.load_config(ROOT)
        g = Gate(cfg)
        pcfg = C.resolve(cfg, "reviewer", log=g.audit)
        prov = make_provider(pcfg, cfg["http"], Ledger(g.dir / "usage.jsonl", cfg["budgets"].get("daily")),
                             cfg["key_names"])
        logs = "".join("\n--- %s (tail) ---\n%s" % (p, tail_bytes(p, a.max_log_bytes))
                       for p in a.log if Path(p).is_file())
        user = "GOAL:\n%s\n\nTRIED:\n%s\n\nOBSERVED:\n%s\n%s" % (a.goal, a.tried or "(nothing recorded)",
                                                              a.observed or "(nothing recorded)", logs)
        res = prov.chat(SYSTEM, redact(user, cfg["key_names"]), max_tokens=2048)
        text = (res.text or "").strip()
    except Exception as e:  # any failure means "pass": never block the run
        print("PASS: no plan (%s: %s)" % (type(e).__name__, str(e)[:200]))
        return 0
    if not text:
        print("PASS: no plan (empty reply)")
        return 0
    print("PLAN from reviewer %s/%s:\n%s" % (res.provider, res.model, text))
    return 0


if __name__ == "__main__":
    sys.exit(main())
