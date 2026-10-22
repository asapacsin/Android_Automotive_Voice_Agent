#!/usr/bin/env python3
"""Claude Code Stop hook: run deterministic verification and external review once.

Reads JSON on stdin per Claude Stop hook. Does NOT automatically spawn Claude
inside its own stop hook. Can block a stop after REWORK for bounded continuation.
"""
import json
import os
import subprocess
import sys
from pathlib import Path


def main():
    try:
        event = json.load(sys.stdin)
        # A blocked stop hook recurs; don't create infinite re-review loops.
        if event.get("stop_hook_active") or os.environ.get("AI_NATIVE_STOP_ENABLED") != "1":
            return 0
        repo = Path(os.environ.get("CLAUDE_PROJECT_DIR", ".")).resolve()
        contract = os.environ.get("AI_NATIVE_CONTRACT_PATH")
        if not contract:
            return 0
        script = Path(__file__).resolve().parents[1] / "scripts" / "ai_native.py"
        process = subprocess.run([sys.executable, str(script), "cycle", "--repo", str(repo),
                                  "--contract", contract], capture_output=True, text=True, timeout=360)
        if process.returncode == 2:
            print(json.dumps({"decision": "block", "reason": "AI-native review: REWORK. Read .ai-native/runs/*/latest.json, fix targeted issues, rerun tests. Bounded Stop hook: only one automatic block per stop chain."}))
        elif process.returncode in (3, 4, 5):
            # No repeated spins on BLOCKED/NOT_VERIFIED. Fail visibly, not a fake PASS.
            print(process.stdout[-1000:] + "\nAI-native verdict not PASS; review evidence and dependencies.", file=sys.stderr)
    except Exception as exc:
        print("AI-native Stop hook could not verify: " + str(exc), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
