"""What is still worth doing here, ranked, and whether an agent is allowed to stop.

    python scripts/discover_work.py              # the ranked frontier and the stop state
    python scripts/discover_work.py --json       # the same, machine-readable
    python scripts/discover_work.py --selftest   # the scenarios the ranking must get right

Exists because agents kept stopping after one task while the repository still had obvious work in
it, and because "is there anything left?" was answered from memory rather than from evidence. The
answer is derived here from the same documents a person would read, so it can be checked.

A candidate is only listed when something in the repository authorises it: a capability registry
entry, recorded debt, an open problem, a milestone row, a backlog row, or failing evidence. Ideas
are not candidates. See harness/CONSTITUTION.md rule 12 and skills/continue.md.

Blockers are declared, not guessed. A canonical document marks an item with a line

    BLOCKED_BY: <the concrete thing that is unavailable>

and that item leaves the autonomous frontier while the line stands. "Needs review" is not a
blocker; the line must name what is missing.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUILD_DIR = r"C:\Users\Administrator\tools\nova-drive-build"

BLOCKED_BY = re.compile(r"^\s*BLOCKED_BY:\s*(.+?)\s*$", re.M)

# The priority ladder. Lower runs first. Correctness outranks tidiness, and nothing cosmetic can
# outrank something that is broken.
P_BUILD = 1              # the build or a required artifact is broken
P_TESTS = 2              # canonical regression tests fail
P_INVARIANT = 3          # a safety or correctness invariant is violated
P_SPEC = 4               # an acceptance criterion of an active SPEC is unmet
P_WIRING = 5             # implemented but not reachable from the production path
P_UNGUARDED = 6          # behaviour with no regression protection
P_MILESTONE = 7          # a row of the active milestone
P_DEBT = 8               # recorded debt with an implementation path
P_UNFINISHED = 9         # an open problem, or work started and not closed
P_HARNESS = 10           # stale canonical state, or a harness defect
P_BACKLOG = 11           # the next authorised backlog item

LADDER = {
    P_BUILD: "broken build or missing artifact",
    P_TESTS: "failing regression tests",
    P_INVARIANT: "invariant violation",
    P_SPEC: "unmet SPEC acceptance criterion",
    P_WIRING: "implemented but not wired into production",
    P_UNGUARDED: "behaviour without regression protection",
    P_MILESTONE: "active milestone row",
    P_DEBT: "recorded technical debt",
    P_UNFINISHED: "unfinished or open problem",
    P_HARNESS: "stale state or harness defect",
    P_BACKLOG: "next authorised backlog item",
}


def read(path):
    full = os.path.join(REPO, path)
    return open(full, encoding="utf-8").read() if os.path.isfile(full) else ""


def git(*args):
    try:
        return subprocess.run(
            ["git", *args], cwd=REPO, capture_output=True, text=True, check=True
        ).stdout.strip()
    except Exception:
        return None


def candidate(priority, source, item, summary, blocked_by=None):
    return {
        "priority": priority,
        "class": LADDER[priority],
        "source": source,
        "item": item,
        "summary": summary.strip()[:200],
        "blocked_by": blocked_by,
    }


# An item is off the frontier when its own status says so, in one of these words. The list is
# deliberately short: an entry whose status does not begin with one of them is treated as live, so
# a vague status produces noise rather than silence. Noise gets the document fixed; silence hides
# work. Documents are expected to use this vocabulary - see skills/continue.md.
TERMINAL = ("RESOLVED", "FIXED", "DONE", "DROPPED", "CLOSED", "SUPERSEDED", "NOT A DEFECT")


def is_terminal(status):
    # Strip markdown and any leading ornament - a status beginning "✅ RESOLVED" is resolved, and
    # matching on the bare word would have re-opened four closed problems.
    flat = re.sub(r"[*_`]", "", status or "").strip().upper()
    flat = re.sub(r"^[^A-Z]+", "", flat)
    return any(flat.startswith(word) for word in TERMINAL)


def section_blocker(text, start, end):
    """The BLOCKED_BY line governing a document section, or None."""
    match = BLOCKED_BY.search(text[start:end])
    return match.group(1) if match else None


# ---- sources -------------------------------------------------------------------------------


def failing_tests():
    """Test failures from the machine-readable reports, not from a summary line."""
    out = []
    pattern = os.path.join(BUILD_DIR, "**", "test-results", "**", "TEST-*.xml")
    for path in glob.glob(pattern, recursive=True):
        if "testReleaseUnitTest" in path.replace("/", "\\"):
            continue
        try:
            attrs = ET.parse(path).getroot().attrib
        except Exception:
            continue
        bad = int(attrs.get("failures", 0)) + int(attrs.get("errors", 0))
        if bad:
            name = attrs.get("name", os.path.basename(path))
            # An architecture or contract test failing is an invariant violation, not a plain test.
            structural = any(k in name for k in ("Architecture", "DependencyBoundary", "SecretScan",
                                                 "CapabilityContract", "FeaturePresence"))
            out.append(candidate(
                P_INVARIANT if structural else P_TESTS,
                "test report", name, "%d failing in %s" % (bad, name),
            ))
    return out


def missing_artifact():
    apk = os.path.join(BUILD_DIR, "app", "outputs", "apk", "debug", "app-debug.apk")
    if os.path.isfile(apk):
        return []
    return [candidate(P_BUILD, "build output", "app-debug.apk",
                      "no debug APK has been built from this tree")]


def unmet_spec_criteria():
    """SPEC rows that say, in the SPEC's own words, that something is not built."""
    out = []
    for path in sorted(glob.glob(os.path.join(REPO, "SPECS", "SPEC-*.md"))):
        name = os.path.basename(path)
        if name == "SPEC-TEMPLATE.md":
            continue  # the shape of a SPEC, not a SPEC
        text = open(path, encoding="utf-8").read()
        for match in re.finditer(r"^\|.*?\b(not built|NOT BUILT|not earned|not implemented)\b.*$",
                                 text, re.M | re.I):
            row = match.group(0)
            if "deliberately" in row.lower():
                continue  # a recorded decision, not an omission
            if "<" in row and ">" in row:
                continue  # an unfilled placeholder row, not a criterion
            blocker = section_blocker(text, max(0, match.start() - 600), match.end() + 600)
            out.append(candidate(P_SPEC, name, row.split("|")[1].strip()[:60], row, blocker))
    return out


def unwired_or_unverified_capabilities():
    """`verified: implemented` means code exists and nothing has proven it runs."""
    out = []
    text = read("config/capabilities.yaml")
    body = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("#"))
    for match in re.finditer(r"^  (\w[\w.]*):\n(?:.*\n)*?\s+verified: (\w+)", body, re.M):
        name, level = match.group(1), match.group(2)
        if level == "implemented":
            out.append(candidate(P_WIRING, "capabilities.yaml", name,
                                 "%s is only `implemented`: nothing proves it runs" % name))
    return out


def milestone_rows():
    out = []
    text = read("CURRENT_MILESTONE.md")
    # Only the active milestone: everything after a closed-milestone heading is history.
    active = text.split("\n# ")[0]
    for match in re.finditer(r"^\|.*?\*\*(not built|not earned)\*\*.*$", active, re.M | re.I):
        row = match.group(0)
        blocker = section_blocker(active, match.end(), match.end() + 1500)
        cells = [c.strip() for c in row.split("|") if c.strip()]
        out.append(candidate(P_MILESTONE, "CURRENT_MILESTONE.md",
                             cells[1][:60] if len(cells) > 1 else row[:60], row, blocker))
    return out


def open_debt():
    out = []
    text = read("docs/TECH_DEBT.md")
    headings = list(re.finditer(r"^## (D-\d+) — (.+?) — \*\*(\w+)", text, re.M))
    for i, match in enumerate(headings):
        item, title, priority = match.group(1), match.group(2), match.group(3)
        if priority == "RESOLVED":
            continue
        end = headings[i + 1].start() if i + 1 < len(headings) else len(text)
        out.append(candidate(P_DEBT, "docs/TECH_DEBT.md", item,
                             "%s %s — %s" % (item, priority, title),
                             section_blocker(text, match.start(), end)))
    return out


def open_problems():
    out = []
    text = read("OPEN_PROBLEMS.md")
    headings = list(re.finditer(r"^## (P\d+) — (.+)$", text, re.M))
    for i, match in enumerate(headings):
        item, title = match.group(1), match.group(2)
        end = headings[i + 1].start() if i + 1 < len(headings) else len(text)
        body = text[match.start():end]
        status = re.search(r"\*\*Status:\*\*\s*(.+)", body)
        if is_terminal(status.group(1) if status else ""):
            continue
        out.append(candidate(P_UNFINISHED, "OPEN_PROBLEMS.md", item, "%s — %s" % (item, title),
                             section_blocker(text, match.start(), end)))
    return out


def open_backlog():
    out = []
    text = read("BACKLOG.md")
    for match in re.finditer(r"^\| (B-\d+) \| (.+?) \| ([\d-]+) \| (.+?) \|", text, re.M):
        item, title, _, status = match.groups()
        if is_terminal(status):
            continue
        out.append(candidate(P_BACKLOG, "BACKLOG.md", item, "%s — %s" % (item, title[:80]),
                             section_blocker(text, match.start(), match.end() + 800)))
    return out


def stale_state():
    stored_path = os.path.join(REPO, "state", "PROJECT_STATE.json")
    if not os.path.isfile(stored_path):
        return [candidate(P_HARNESS, "state", "PROJECT_STATE.json", "generated state is missing")]
    try:
        stored = json.load(open(stored_path, encoding="utf-8"))
    except Exception as exc:
        return [candidate(P_HARNESS, "state", "PROJECT_STATE.json", "unreadable: %s" % exc)]
    head = git("rev-parse", "--short", "HEAD")
    recorded = stored.get("git", {}).get("commit")
    if recorded == head:
        return []
    parent = git("rev-parse", "--short", "HEAD~1")
    if recorded == parent:
        changed = (git("diff", "--name-only", "HEAD~1", "HEAD") or "").split()
        if "state/PROJECT_STATE.json" in changed:
            return []
    return [candidate(P_HARNESS, "state", "PROJECT_STATE.json",
                      "records %s, repository is at %s" % (recorded, head))]


SOURCES = (
    missing_artifact,
    failing_tests,
    unmet_spec_criteria,
    unwired_or_unverified_capabilities,
    milestone_rows,
    open_debt,
    open_problems,
    stale_state,
    open_backlog,
)


def discover():
    found = []
    for source in SOURCES:
        try:
            found.extend(source())
        except Exception as exc:  # a broken reader must not hide the rest of the frontier
            found.append(candidate(P_HARNESS, "discover_work.py", source.__name__,
                                   "this reader raised %s: %r" % (type(exc).__name__, exc)))
    found.sort(key=lambda c: (c["priority"], c["item"]))
    return found


# ---- the stop decision ---------------------------------------------------------------------


def decide(candidates):
    """The machine-checkable stop state. Pure, so the scenarios below can exercise it."""
    actionable = [c for c in candidates if not c["blocked_by"]]
    blocked = [c for c in candidates if c["blocked_by"]]

    if actionable:
        top = actionable[0]
        return {
            "AUTONOMOUS_ACTION_AVAILABLE": "YES",
            "HUMAN_ACTION_REQUIRED": "NO",
            "STOP_REASON": "NOT_STOPPING",
            "BLOCKING_DEPENDENCY": "none",
            "NEXT_ACTION": "%s: %s" % (top["item"], top["summary"]),
            "actionable_count": len(actionable),
            "blocked_count": len(blocked),
        }
    if blocked:
        first = blocked[0]
        return {
            "AUTONOMOUS_ACTION_AVAILABLE": "NO",
            "HUMAN_ACTION_REQUIRED": "YES",
            "STOP_REASON": "BLOCKED_ON_EXTERNAL_DEPENDENCY",
            "BLOCKING_DEPENDENCY": first["blocked_by"],
            "NEXT_ACTION": "NONE",
            "actionable_count": 0,
            "blocked_count": len(blocked),
        }
    return {
        "AUTONOMOUS_ACTION_AVAILABLE": "NO",
        "HUMAN_ACTION_REQUIRED": "NO",
        "STOP_REASON": "WORK_FRONTIER_EXHAUSTED",
        "BLOCKING_DEPENDENCY": "none",
        "NEXT_ACTION": "NONE",
        "actionable_count": 0,
        "blocked_count": 0,
    }


# Phrases that describe wanting a human rather than naming what is missing. A blocker has to say
# which credential, which device, which approval - something a person could go and supply.
VAGUE = (
    "none", "unknown", "unclear", "tbd", "n/a", "pending", "later", "see above",
    "needs review", "need review", "review needed", "human input", "ask the user",
    "product decision", "awaiting decision", "blocked", "external",
)


def is_vague(dependency):
    flat = dependency.strip().lower().rstrip(".")
    if not flat or len(flat) < 20:
        return True
    return any(flat == v or flat.startswith(v) for v in VAGUE)


def contradictions(stop):
    """Stop states that cannot be true at once. Used by scripts/harness_check.py."""
    problems = []
    if stop.get("AUTONOMOUS_ACTION_AVAILABLE") == "YES" and stop.get("NEXT_ACTION") in (None, "", "NONE"):
        problems.append("AUTONOMOUS_ACTION_AVAILABLE=YES but NEXT_ACTION is NONE")
    if stop.get("AUTONOMOUS_ACTION_AVAILABLE") == "NO" and stop.get("actionable_count", 0) > 0:
        problems.append("AUTONOMOUS_ACTION_AVAILABLE=NO while %d actionable items remain"
                        % stop["actionable_count"])
    if stop.get("HUMAN_ACTION_REQUIRED") == "YES":
        dependency = (stop.get("BLOCKING_DEPENDENCY") or "").strip()
        if is_vague(dependency):
            problems.append("HUMAN_ACTION_REQUIRED=YES without a concrete BLOCKING_DEPENDENCY "
                            "(got %r); name what is unavailable, not that a decision is wanted"
                            % dependency)
    if stop.get("HUMAN_ACTION_REQUIRED") == "YES" and stop.get("AUTONOMOUS_ACTION_AVAILABLE") == "YES":
        problems.append("HUMAN_ACTION_REQUIRED=YES while autonomous work is still available")
    return problems


# ---- scenarios -----------------------------------------------------------------------------


def selftest():
    """The scenarios the ranking and the stop decision must get right (skills/continue.md)."""
    failures = []

    def check(label, condition, detail=""):
        print("  %-4s %s" % ("ok" if condition else "FAIL", label))
        if not condition:
            failures.append("%s %s" % (label, detail))

    spec_done = candidate(P_BACKLOG, "BACKLOG.md", "B-009", "a remaining backlog item")
    wiring = candidate(P_WIRING, "capabilities.yaml", "thing", "implemented, nothing proves it runs")
    unguarded = candidate(P_UNGUARDED, "tests", "thing", "no regression protection")
    stale = candidate(P_HARNESS, "state", "PROJECT_STATE.json", "records an older commit")
    broken = candidate(P_BUILD, "build output", "app-debug.apk", "no APK")
    device = candidate(P_MILESTONE, "CURRENT_MILESTONE.md", "row 6", "live model unproven",
                       blocked_by="the test phone has no network route to the provider")

    print("A  SPEC finished, backlog remains")
    a = decide([spec_done])
    check("continues", a["AUTONOMOUS_ACTION_AVAILABLE"] == "YES", a["STOP_REASON"])

    print("B  implementation exists, production wiring missing")
    b = decide([wiring])
    check("continues", b["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")
    check("names the wiring gap", "thing" in b["NEXT_ACTION"])

    print("C  behaviour tested, wiring unguarded")
    c = decide([unguarded])
    check("continues", c["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")

    print("D  tests green, canonical state stale")
    d = decide([stale])
    check("continues", d["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")
    check("no human needed", d["HUMAN_ACTION_REQUIRED"] == "NO")

    print("E  milestone complete, next authorised item exists")
    e = decide([spec_done])
    check("continues", e["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")

    print("F  physical device genuinely required")
    f = decide([device])
    check("stops", f["AUTONOMOUS_ACTION_AVAILABLE"] == "NO")
    check("human required", f["HUMAN_ACTION_REQUIRED"] == "YES")
    check("blocker is concrete", len(f["BLOCKING_DEPENDENCY"]) > 12, f["BLOCKING_DEPENDENCY"])
    check("no contradictions", contradictions(f) == [], str(contradictions(f)))

    print("G  nothing left at all")
    g = decide([])
    check("stops", g["AUTONOMOUS_ACTION_AVAILABLE"] == "NO")
    check("no human needed", g["HUMAN_ACTION_REQUIRED"] == "NO")
    check("exhaustion reason", g["STOP_REASON"] == "WORK_FRONTIER_EXHAUSTED")

    print("H  a blocked item must not hide an actionable one")
    h = decide([device, spec_done])
    check("continues", h["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")
    check("picks the actionable one", "B-009" in h["NEXT_ACTION"], h["NEXT_ACTION"])

    print("   ranking")
    ranked = sorted([spec_done, broken, wiring], key=lambda c: c["priority"])
    check("broken build outranks everything", ranked[0]["item"] == "app-debug.apk")
    check("backlog ranks last", ranked[-1]["item"] == "B-009")

    print("   contradictions are caught")
    check("YES with no next action", contradictions(
        {"AUTONOMOUS_ACTION_AVAILABLE": "YES", "NEXT_ACTION": "NONE"}) != [])
    check("NO while work remains", contradictions(
        {"AUTONOMOUS_ACTION_AVAILABLE": "NO", "actionable_count": 3}) != [])
    check("vague blocker rejected", contradictions(
        {"HUMAN_ACTION_REQUIRED": "YES", "BLOCKING_DEPENDENCY": "needs review"}) != [])

    print()
    if failures:
        print("SELFTEST FAILED (%d)" % len(failures))
        for f_ in failures:
            print("  - %s" % f_)
        return 1
    print("SELFTEST PASSED")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()

    if args.selftest:
        print("discover_work scenarios")
        return selftest()

    found = discover()
    stop = decide(found)
    if args.json:
        print(json.dumps({"candidates": found, "stop": stop}, indent=2, ensure_ascii=False))
        return 0

    print("work frontier — %d actionable, %d blocked" % (stop["actionable_count"], stop["blocked_count"]))
    for c in found:
        mark = "BLOCKED" if c["blocked_by"] else "%d" % c["priority"]
        print("  [%-7s] %-28s %s" % (mark, c["item"], c["summary"]))
        if c["blocked_by"]:
            print("            blocked by: %s" % c["blocked_by"])
    print()
    for key in ("AUTONOMOUS_ACTION_AVAILABLE", "HUMAN_ACTION_REQUIRED", "STOP_REASON",
                "BLOCKING_DEPENDENCY", "NEXT_ACTION"):
        print("%s = %s" % (key, stop[key]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
