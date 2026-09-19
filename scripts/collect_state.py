"""Generate state/PROJECT_STATE.json from evidence. Nothing here is written by hand or by a model.

    python scripts/collect_state.py            # refresh state from the repo as it is now
    python scripts/collect_state.py --check    # non-zero exit if the state file is stale

Sources, in order of trust: git, the JUnit XML of the last real run, the capability registry, the
issue documents. Anything that cannot be derived is recorded as null rather than guessed — a made-up
"build_status": "PASS" is worse than no field at all.
"""

import argparse
import datetime
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STATE = os.path.join(REPO, "state", "PROJECT_STATE.json")
# Build output is redirected outside the repo (non-ASCII source path); see ACCEPTANCE_TESTS.md.
BUILD_DIR = r"C:\Users\Administrator\tools\nova-drive-build"


def git(*args):
    try:
        return subprocess.run(
            ["git", *args], cwd=REPO, capture_output=True, text=True, check=True
        ).stdout.strip()
    except Exception:
        return None


def test_summary():
    """Counts from the JUnit XML of the most recent run, debug variant only.

    testReleaseUnitTest re-runs the same app tests over a second variant; summing both overstates
    coverage, which ACCEPTANCE_TESTS.md records as a real past mistake.
    """
    pattern = os.path.join(BUILD_DIR, "**", "test-results", "**", "TEST-*.xml")
    files = glob.glob(pattern, recursive=True)
    if not files:
        return {"available": False, "reason": "no JUnit XML found; run gradlew test"}
    newest = max(os.path.getmtime(f) for f in files)
    modules, totals = {}, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for f in files:
        parts = f.replace("\\", "/").split("/")
        task = parts[parts.index("test-results") + 1]
        if task == "testReleaseUnitTest":
            continue
        # Deliberately no time window here. Gradle rewrites the reports of the tasks it runs and
        # leaves an up-to-date module's reports untouched, so an older file is still the current
        # result for that module. A one-hour window used to discard exactly that, and recorded
        # "56 tests" for a 631-test suite (measured 2026-09-19) - canonical state that was wrong in
        # the direction of looking worse, which is how it went unnoticed. Staleness is caught by
        # --check against the commit instead.
        a = ET.parse(f).getroot().attrib
        module = parts[parts.index("test-results") - 1]
        m = modules.setdefault(module, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
        for key in totals:
            value = int(a.get(key, 0))
            m[key] += value
            totals[key] += value
    return {
        "available": True,
        "collected_at": datetime.datetime.fromtimestamp(newest).isoformat(timespec="seconds"),
        "total": totals,
        "by_module": modules,
        "passing": totals["failures"] == 0 and totals["errors"] == 0,
    }


def apk_status():
    apk = os.path.join(BUILD_DIR, "app", "outputs", "apk", "debug", "app-debug.apk")
    if not os.path.isfile(apk):
        return {"built": False}
    return {
        "built": True,
        "bytes": os.path.getsize(apk),
        "built_at": datetime.datetime.fromtimestamp(os.path.getmtime(apk)).isoformat(timespec="seconds"),
    }


def capability_status():
    path = os.path.join(REPO, "config", "capabilities.yaml")
    if not os.path.isfile(path):
        return {"available": False}
    text = open(path, encoding="utf-8").read()
    body = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
    levels = re.findall(r"verified: ([a-z]+)", body)
    counts = {}
    for level in levels:
        counts[level] = counts.get(level, 0) + 1
    pending = re.findall(r"- capability: ([\w.]+)", body)
    return {"available": True, "by_level": counts, "human_verification_pending": pending}


def open_issues():
    """Problems recorded as still open, read from the defect log rather than remembered."""
    path = os.path.join(REPO, "OPEN_PROBLEMS.md")
    if not os.path.isfile(path):
        return []
    text = open(path, encoding="utf-8").read()
    issues = []
    for match in re.finditer(r"^## (P\d+) — (.+)$", text, re.M):
        section = text[match.end():]
        status_line = re.search(r"\*\*Status:\*\*\s*(.+)", section)
        status = status_line.group(1).strip() if status_line else "unknown"
        resolved = any(w in status.upper() for w in ("RESOLVED", "FIXED", "NOT A DEFECT", "IMPLEMENTED"))
        if not resolved:
            issues.append({"id": match.group(1), "title": match.group(2).strip(), "status": status[:120]})
    return issues


def tech_debt():
    path = os.path.join(REPO, "docs", "TECH_DEBT.md")
    if not os.path.isfile(path):
        return []
    text = open(path, encoding="utf-8").read()
    return [
        {"id": m.group(1), "title": m.group(2).strip(), "priority": m.group(3)}
        for m in re.finditer(r"^## (D-\d+) — (.+?) — \*\*(\w+)", text, re.M)
    ]


def work_frontier():
    """The ranked frontier and the stop state, from scripts/discover_work.py."""
    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import discover_work

        candidates = discover_work.discover()
        stop = discover_work.decide(candidates)
        stop["candidates"] = [
            {k: c[k] for k in ("priority", "class", "source", "item", "blocked_by")}
            for c in candidates
        ]
        return stop
    except Exception as exc:
        # Never guess this one. A fabricated "nothing left to do" is the worst field in the file.
        return {"available": False, "reason": "discover_work failed: %r" % exc}


def build_state():
    return {
        "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "generated_by": "scripts/collect_state.py",
        "git": {
            "commit": git("rev-parse", "--short", "HEAD"),
            "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
            "subject": git("log", "-1", "--pretty=%s"),
            "working_tree_clean": git("status", "--porcelain") == "",
            "uncommitted_files": len([l for l in (git("status", "--porcelain") or "").splitlines() if l]),
        },
        "tests": test_summary(),
        "apk": apk_status(),
        "capabilities": capability_status(),
        "open_issues": open_issues(),
        "tech_debt": tech_debt(),
        # Whether an agent is allowed to stop, derived from the same documents a person would read.
        # CONSTITUTION rule 12: AUTONOMOUS_ACTION_AVAILABLE=YES means the run may not end.
        "work_frontier": work_frontier(),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if the stored state is stale")
    args = parser.parse_args()
    state = build_state()
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    if args.check:
        if not os.path.isfile(STATE):
            print("PROJECT_STATE.json missing; run: python scripts/collect_state.py")
            return 1
        stored = json.load(open(STATE, encoding="utf-8"))
        if stored.get("git", {}).get("commit") != state["git"]["commit"]:
            print(
                "PROJECT_STATE.json is stale: recorded %s, repository is at %s"
                % (stored.get("git", {}).get("commit"), state["git"]["commit"])
            )
            return 1
        print("PROJECT_STATE.json is current (%s)" % state["git"]["commit"])
        return 0
    with open(STATE, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(state, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    tests = state["tests"]
    print("wrote %s" % os.path.relpath(STATE, REPO))
    print("  commit      %s (%s)" % (state["git"]["commit"], "clean" if state["git"]["working_tree_clean"] else "dirty"))
    if tests.get("available"):
        t = tests["total"]
        print("  tests       %d, %d failed, %d skipped" % (t["tests"], t["failures"], t["skipped"]))
    print("  open issues %d,  tech debt %d" % (len(state["open_issues"]), len(state["tech_debt"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
