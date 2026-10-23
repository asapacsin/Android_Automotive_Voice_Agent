#!/usr/bin/env python3
"""Is the iOS transfer spec (docs/ios-port) finished? Deterministic; exit 0 = done.

    python3 scripts/ios_spec_check.py

Owner 2026-10-23: the objective is "finish the iOS transfer spec only" (state/objective.json). Done means:
every open question has a status other than OPEN, every ANSWERED/PARTIAL answer cites a source (a URL or
a code path), no claim is left UNKNOWN, and every relative link resolves. DEVICE_SPIKE and OWNER rows are
allowed: they name what only an iPhone run or the owner can settle.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = os.path.join(ROOT, "docs", "ios-port")
STATUSES = ("ANSWERED", "PARTIAL", "DEVICE_SPIKE", "OWNER")


def problems(spec=SPEC):
    out = []
    oq = open(os.path.join(spec, "open-questions.md"), encoding="utf-8").read()
    rows = [l for l in oq.splitlines() if re.match(r"\|\s*OQ-[A-Z]+-\d+\s*\|", l)]
    if not rows:
        out.append("open-questions.md has no OQ rows")
    for row in rows:
        cells = [c.strip() for c in row.strip().strip("|").split("|")]
        qid = cells[0]
        status = next((c for c in cells if any(c.startswith(s) for s in STATUSES) or c.startswith("OPEN")), "")
        if not status or status.startswith("OPEN"):
            out.append("%s has no status (ANSWERED/PARTIAL/DEVICE_SPIKE/OWNER)" % qid)
            continue
        answer = cells[-1]
        if status.startswith(("ANSWERED", "PARTIAL")) and not re.search(r"https?://|`[A-Z]/|\.kt|\.py|\.yaml", answer):
            out.append("%s is %s but cites no source" % (qid, status.split()[0]))
    for dirpath, _, files in os.walk(spec):
        for name in files:
            if not name.endswith(".md"):
                continue
            path = os.path.join(dirpath, name)
            text = open(path, encoding="utf-8").read()
            rel = os.path.relpath(path, spec)
            if re.search(r"\bUNKNOWN\b", text):
                out.append("%s still has an UNKNOWN claim" % rel)
            for link in re.findall(r"\]\(([^)#\s]+)", text):
                if not link.startswith("http") and not os.path.exists(os.path.join(dirpath, link)):
                    out.append("%s: broken link %s" % (rel, link))
    return out


def main():
    found = problems()
    for p in found:
        print("NOT DONE: " + p)
    print("ios spec: %s" % ("DONE" if not found else "%d problem(s)" % len(found)))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
