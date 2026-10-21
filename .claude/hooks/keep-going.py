#!/usr/bin/env python3
"""Stop hook: the turn may not end while autonomous work remains (CONSTITUTION rule 12, owner 2026-10-20).

skills/continue.md already says "AUTONOMOUS_ACTION_AVAILABLE = YES means the run may not end", but prose
did not hold: the agent finished a unit, listed the next three and asked "want me to keep going?". This
hook enforces it the way the community does (a Stop hook that returns decision "block" with the next
action as the reason; Claude then continues instead of handing control back):

- runs scripts/discover_work.py --json and blocks while AUTONOMOUS_ACTION_AVAILABLE is YES, naming
  NEXT_ACTION;
- when the last assistant message asks permission for work the frontier already authorises ("want me
  to", "should I", "shall I", ...), the reason says so: take the documented default, record the
  assumption, and queue only genuinely owner-only items (skills/continue.md "When a human is genuinely
  required") as HUMAN_* rows instead of asking;
- lets the turn end when the frontier has no autonomous action (human-only items wait in the packet).

Loop guard: stop_hook_active is true while Claude is continuing because of an earlier block. After
MAX_CHAIN consecutive blocks the stop is allowed, so a frontier item the agent cannot move never pins a
session forever. NOVA_KEEP_GOING=0 turns the hook off. Fails open on anything unexpected (a broken
check must not wedge a session). Test seam: NOVA_KEEP_GOING_DISCOVER names a JSON file used instead of
running discover_work.py, NOVA_KEEP_GOING_STATE a directory for the chain counter.
"""
import json
import os
import re
import subprocess
import sys
import tempfile

MAX_CHAIN = 20
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASKING = re.compile(
    r"\b(want me to|would you like( me)? to|should i|shall i|do you want( me)? to|let me know if|"
    r"if you want(,| me to)|say the word|ready when you are|which (one|option) (do you|should))\b",
    re.IGNORECASE)


def discover():
    path = os.environ.get("NOVA_KEEP_GOING_DISCOVER")
    if path:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    out = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "discover_work.py"), "--json"],
                         capture_output=True, text=True, timeout=100, cwd=ROOT).stdout
    return json.loads(out)


def last_assistant_text(event):
    text = event.get("last_assistant_message")
    if isinstance(text, str):
        return text
    path = event.get("transcript_path")
    if not path or not os.path.isfile(path):
        return ""
    last = ""
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                rec = json.loads(line)
            except ValueError:
                continue
            msg = rec.get("message") if isinstance(rec, dict) else None
            if not isinstance(msg, dict) or msg.get("role") != "assistant":
                continue
            content = msg.get("content")
            if isinstance(content, str):
                parts = [content]
            else:
                parts = [c.get("text", "") for c in content or [] if isinstance(c, dict) and c.get("type") == "text"]
            joined = "\n".join(p for p in parts if p)
            if joined.strip():
                last = joined
    return last


def chain_file(event):
    base = os.environ.get("NOVA_KEEP_GOING_STATE") or tempfile.gettempdir()
    sid = re.sub(r"[^A-Za-z0-9_-]", "_", str(event.get("session_id") or "nosession"))[:80]
    return os.path.join(base, "nova-keep-going-%s" % sid)


def chain_count(event):
    """Consecutive blocks in this chain; a stop not caused by our block starts a new chain."""
    if not event.get("stop_hook_active"):
        return 0
    try:
        with open(chain_file(event), encoding="utf-8") as f:
            return int(f.read().strip() or 0)
    except (OSError, ValueError):
        return 0


def save_chain(event, n):
    try:
        with open(chain_file(event), "w", encoding="utf-8") as f:
            f.write(str(n))
    except OSError:
        pass


def decide(event, frontier):
    """(block, reason). Pure apart from the chain counter."""
    stop = (frontier or {}).get("stop") or {}
    if stop.get("AUTONOMOUS_ACTION_AVAILABLE") != "YES":
        return False, ""
    n = chain_count(event)
    if n >= MAX_CHAIN:
        return False, ""
    save_chain(event, n + 1)
    nxt = stop.get("NEXT_ACTION") or "the top candidate of scripts/discover_work.py"
    reason = ("Autonomous work remains (discover_work: AUTONOMOUS_ACTION_AVAILABLE=YES, %s actionable). "
              "Do not end the turn. NEXT_ACTION: %s. Follow skills/continue.md: implement via an executor "
              "packet, verify, hand off, then take the next candidate."
              % (stop.get("actionable_count", "?"), nxt))
    if ASKING.search(last_assistant_text(event)[-1500:]):
        reason += (" Your last message asks the owner to choose work the frontier already authorises. Do "
                   "not ask: pick the documented or recommended default, record the assumption in the "
                   "handoff, and continue. Only owner-only items (credential, physical action, product "
                   "choice the documents leave open, irreversible operation) are queued as HUMAN_* rows "
                   "in TEST_MATRIX.yaml for the one batched packet; independent work keeps running.")
    return True, reason


def main():
    if os.environ.get("NOVA_KEEP_GOING") == "0":
        return 0
    try:
        event = json.load(sys.stdin)
        block, reason = decide(event, discover())
    except Exception:  # never wedge a session on a broken check
        return 0
    if block:
        print(json.dumps({"decision": "block", "reason": reason}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
