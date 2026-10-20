#!/usr/bin/env python3
"""Throughput of the executor pipeline: "Merge exec/..." commits on the first-parent history.

  python3 scripts/throughput.py [--ref HEAD] [--split 2026-10-20T05:00:00Z] [--json]

Merges per hour is merges / hours between the first and last merge in the window; the median gap
is between consecutive merges. Read-only.
"""
import argparse
import datetime
import json
import statistics
import subprocess
import sys


def merges(ref):
    out = subprocess.run(["git", "log", "--first-parent", "--merges", "--format=%H%x09%cI%x09%s",
                          ref], capture_output=True, text=True, check=True).stdout
    found = []
    for line in out.splitlines():
        sha, when, subject = line.split("\t", 2)
        if subject.startswith("Merge exec/"):
            found.append({"sha": sha, "time": when, "subject": subject,
                          "_t": datetime.datetime.fromisoformat(when)})
    found.sort(key=lambda m: m["_t"])
    return found


def stats(items):
    times = [m["_t"] for m in items]
    gaps = [(b - a).total_seconds() / 60 for a, b in zip(times, times[1:])]
    hours = (times[-1] - times[0]).total_seconds() / 3600 if len(times) > 1 else 0
    return {
        "merges": len(times),
        "first": times[0].isoformat() if times else None,
        "last": times[-1].isoformat() if times else None,
        "merges_per_hour": round(len(times) / hours, 3) if hours > 0 else None,
        "median_gap_minutes": round(statistics.median(gaps), 2) if gaps else None,
    }


def parse_time(text):
    t = datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
    return t if t.tzinfo else t.replace(tzinfo=datetime.timezone.utc)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--ref", default="HEAD")
    ap.add_argument("--split", help="ISO time; report before/after separately")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    items = merges(a.ref)
    report = {"ref": a.ref, "all": stats(items)}
    if a.split:
        cut = parse_time(a.split)
        report["split"] = cut.isoformat()
        report["before"] = stats([m for m in items if m["_t"] < cut])
        report["after"] = stats([m for m in items if m["_t"] >= cut])
    report["merges"] = [{k: v for k, v in m.items() if k != "_t"} for m in items]
    if a.json:
        print(json.dumps(report, indent=1))
        return 0
    for m in report["merges"]:
        print("%s  %s  %s" % (m["time"], m["sha"][:10], m["subject"][:90]))
    for key in ("all", "before", "after"):
        if key in report:
            s = report[key]
            print("%-6s %3d merges, %s merges per hour, median gap %s min (%s .. %s)"
                  % (key, s["merges"], s["merges_per_hour"], s["median_gap_minutes"],
                     s["first"], s["last"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
