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
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def resolve_build_dir():
    env = os.environ.get("NOVA_BUILD_DIR")
    if env:
        return env
    win = r"C:\Users\Administrator\tools\nova-drive-build"
    if os.name == "nt":
        return win
    return os.path.join(os.path.expanduser("~"), "nova-drive-build")


BUILD_DIR = resolve_build_dir()

BLOCKED_BY = re.compile(r"^\s*BLOCKED_BY:\s*(.+?)\s*$", re.M)
DEPENDS_ON = re.compile(r"^\s*DEPENDS_ON:\s*(.+?)\s*$", re.M)
UNBLOCK_WHEN = re.compile(r"^\s*UNBLOCK_WHEN:\s*(.+?)\s*$", re.M)

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


def candidate(priority, source, item, summary, blocked_by=None,
              depends_on=(), unblock_when=None, needs_compilation=False):
    return {
        "priority": priority,
        "class": LADDER[priority],
        "source": source,
        "item": item,
        "summary": summary.strip()[:200],
        "blocked_by": blocked_by,
        # Items this one cannot proceed without. A blocked dependency blocks this transitively;
        # everything else stays eligible, which is the whole point of a frontier.
        "depends_on": list(depends_on),
        # A machine-checkable condition that lifts the block without anyone editing a document.
        "unblock_when": unblock_when,
        # A demand nobody has turned into scenarios and acceptance criteria yet. Eligible work,
        # but the work is compiling it - not implementing whatever it seems to ask for.
        "needs_compilation": needs_compilation,
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


def section_depends_on(text, start, end):
    """`DEPENDS_ON: B-003, D-2` - ids this item cannot proceed without."""
    match = DEPENDS_ON.search(text[start:end])
    if not match:
        return []
    return [part.strip() for part in re.split(r"[,\s]+", match.group(1)) if part.strip()]


def section_unblock_when(text, start, end):
    """`UNBLOCK_WHEN: file_differs <path> sha256:<digest>` - checked on every refresh."""
    match = UNBLOCK_WHEN.search(text[start:end])
    return match.group(1).strip() if match else None


def unblock_condition_met(condition):
    """Has the world changed in the way the document said would lift this block?

    Only one form is supported, deliberately: a file whose content differs from the digest
    recorded when the block was raised. That covers "the resource we were given is the wrong
    one; the block lifts when it is replaced" without anyone remembering to edit a document,
    and without the repository ever holding the file itself.
    """
    if not condition:
        return False
    parts = condition.split()
    if len(parts) != 3 or parts[0] != "file_differs":
        return False
    path, expected = parts[1], parts[2]
    full = os.path.join(REPO, path)
    if not os.path.isfile(full):
        return False
    digest = "sha256:" + hashlib.sha256(open(full, "rb").read()).hexdigest()
    return digest != expected


def propagate_blocks(candidates):
    """A blocked item blocks what transitively depends on it, and nothing else.

    This is the rule that keeps one external dependency from stopping the project: it narrows
    the frontier to everything that does not need the missing thing.
    """
    by_item = {c["item"]: c for c in candidates}
    changed = True
    while changed:
        changed = False
        for c in candidates:
            if c["blocked_by"]:
                continue
            for needed in c["depends_on"]:
                upstream = by_item.get(needed)
                if upstream and upstream["blocked_by"]:
                    c["blocked_by"] = "depends on %s, which is blocked by: %s" % (
                        needed, upstream["blocked_by"],
                    )
                    changed = True
                    break
    return candidates


def lift_met_blocks(candidates):
    """Blocks whose recorded condition has come true are lifted before anything is ranked."""
    for c in candidates:
        if c["blocked_by"] and unblock_condition_met(c["unblock_when"]):
            c["unblocked_by_condition"] = c["blocked_by"]
            c["blocked_by"] = None
    return candidates


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
                             section_blocker(text, match.start(), end),
                             depends_on=section_depends_on(text, match.start(), end),
                             unblock_when=section_unblock_when(text, match.start(), end)))
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
                             section_blocker(text, match.start(), end),
                             depends_on=section_depends_on(text, match.start(), end),
                             unblock_when=section_unblock_when(text, match.start(), end)))
    return out


def item_section_bounds(text, item):
    """Where `## <item> - ...` starts and ends, so every marker is read from one place."""
    start = re.search(r"^## %s(?![\w-])" % re.escape(item), text, re.M)
    if not start:
        return None
    following = re.search(r"^## ", text[start.end():], re.M)
    end = start.end() + (following.start() if following else len(text))
    return start.start(), end


def item_section_blocker(text, item):
    """The BLOCKED_BY line inside `## <item> — ...`, wherever that section sits in the file."""
    start = re.search(r"^## %s(?![\w-])" % re.escape(item), text, re.M)
    if not start:
        return None
    following = re.search(r"^## ", text[start.end():], re.M)
    end = start.end() + (following.start() if following else len(text))
    return section_blocker(text, start.start(), end)


def open_backlog():
    out = []
    text = read("BACKLOG.md")
    for match in re.finditer(r"^\| (B-\d+) \| (.+?) \| ([\d-]+) \| (.+?) \|", text, re.M):
        item, title, _, status = match.groups()
        if is_terminal(status):
            continue
        # Markers live in the item's own section further down, not beside the table row.
        bounds = item_section_bounds(text, item)
        blocker = section_blocker(text, match.start(), match.end() + 800)
        depends, unblock = [], None
        if bounds:
            start, end = bounds
            blocker = blocker or section_blocker(text, start, end)
            depends = section_depends_on(text, start, end)
            unblock = section_unblock_when(text, start, end)
        # A demand with no SPEC has not been turned into scenarios and acceptance criteria. It is
        # eligible work, but the work is compiling it - not guessing what it asks for and editing
        # code (skills/continue.md, "Compile the demand first").
        section = text[bounds[0]:bounds[1]] if bounds else ""
        compiled = "SPECS/SPEC-" in section or "SPECS/SPEC-" in match.group(0)
        summary = "%s — %s" % (item, title[:80])
        if not compiled:
            summary = "%s — no SPEC yet: compile this demand into scenarios and acceptance criteria" % item
        out.append(candidate(P_BACKLOG, "BACKLOG.md", item, summary, blocker,
                             depends_on=depends, unblock_when=unblock,
                             needs_compilation=not compiled))
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


def design_basis_gaps():
    """Gated design-basis entries that are not implementation-cleared."""
    try:
        import design_basis
    except Exception as exc:
        return [candidate(P_HARNESS, "design_basis.yaml", "design_basis.py",
                          "the design-basis checker could not be loaded: %r" % (exc,))]
    out = []
    for gap in design_basis.discover_gaps():
        out.append(candidate(
            P_SPEC,
            "design_basis.yaml",
            gap["id"],
            gap["summary"],
            needs_compilation=True,
        ))
    return out


def unsettled_matrix_tests():
    """Autonomous tests in TEST_MATRIX.yaml that have not settled.

    A registry entry is work when an agent owns it and it is NOT_RUN, FAIL, RUNNING or
    BLOCKED_AUTONOMOUS. Entries a *person* owns are deliberately absent from this reader: a
    HUMAN_REQUIRED test is queued for a batch, and a queued item is not a reason to stop. That is
    the whole behavioural change (harness/PHASES.md, CONSTITUTION rule 17).
    """
    try:
        import test_matrix
    except Exception as exc:
        return [candidate(P_HARNESS, "TEST_MATRIX.yaml", "test_matrix.py",
                          "the registry could not be loaded: %r" % (exc,))]
    out = []
    seen = set()
    for entry in test_matrix.autonomous_work():
        priority = P_TESTS if entry["status"] == "FAIL" else P_UNGUARDED
        seen.add(entry["id"])
        out.append(candidate(priority, "TEST_MATRIX.yaml", entry["id"],
                             "%s: %s" % (entry["status"], entry["name"])))
    for entry in test_matrix.tests():
        if entry.get("owner") != "AUTONOMOUS" or entry["id"] in seen:
            continue
        if test_matrix.bind_problems(entry):
            out.append(candidate(P_TESTS, "TEST_MATRIX.yaml", entry["id"],
                                 "STALE_BIND: %s" % entry["name"]))
    return out


SOURCES = (
    missing_artifact,
    failing_tests,
    design_basis_gaps,
    unsettled_matrix_tests,
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
    # Order matters: a block whose condition has come true is lifted before dependants are
    # computed, so the whole chain reopens in one refresh rather than one refresh per link.
    found = lift_met_blocks(found)
    found = propagate_blocks(found)
    found.sort(key=lambda c: (c["priority"], c["item"]))
    return found


# ---- the stop decision ---------------------------------------------------------------------


def human_gate():
    """HUMAN_VALIDATION_READY from TEST_MATRIX.yaml, or None when the registry is unreadable.

    This, not AUTONOMOUS_ACTION_AVAILABLE, is the canonical transition into the human phase.
    The old field was a judgement about whether anything was left; this is a property of the
    registry (CONSTITUTION rule 18).
    """
    try:
        import test_matrix
        return test_matrix.gate()
    except Exception:
        return None


def decide(candidates, gate=None):
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


def with_gate(stop, gate):
    """Adds the registry-based phase decision to a stop state."""
    if gate is None:
        stop["HUMAN_VALIDATION_READY"] = "UNKNOWN"
        stop["PHASE"] = "AUTONOMOUS_TEST"
        return stop
    ready = gate["HUMAN_VALIDATION_READY"] == "TRUE"
    stop["HUMAN_VALIDATION_READY"] = gate["HUMAN_VALIDATION_READY"]
    stop["QUEUED_FOR_HUMAN"] = gate["queued_human_items"]
    stop["PHASE"] = "HUMAN_VALIDATION_READY" if ready else (
        "AUTONOMOUS_TEST" if stop["AUTONOMOUS_ACTION_AVAILABLE"] == "YES" else "TEST_REVIEW"
    )
    return stop


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


# ---- completion lifecycle ------------------------------------------------------------------

# CONSTITUTION rule 13. Ordered: each stage is only reachable once the previous one holds.
STAGES = (
    ("IMPLEMENTED", "implemented"),
    ("PRODUCTION_WIRED", "wired"),
    ("BEHAVIOR_VERIFIED", "verified"),
    ("REGRESSION_PROTECTED", "protected"),
    ("ARTIFACT_VERIFIED", "packaged"),
    ("CANONICAL_STATE_RECONCILED", "reconciled"),
)


def completion_stage(evidence):
    """How far a work item has actually got, from the evidence that exists for it.

    `evidence` is a dict of the flags in [STAGES]. The point is that COMPLETE is not reachable by
    writing a test, or by compiling, or by a document saying so: every earlier stage has to hold
    first, and the stage names say which evidence is missing.
    """
    reached = "NOT_STARTED"
    for name, key in STAGES:
        if not evidence.get(key):
            return reached
        reached = name
    return "COMPLETE"


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

    print("A  vague intent does not become an implementation task")
    vague = candidate(P_BACKLOG, "BACKLOG.md", "B-099",
                      "B-099 — no SPEC yet: compile this demand into scenarios and acceptance criteria",
                      needs_compilation=True)
    a1 = decide([vague])
    check("A1 the demand is eligible work", a1["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")
    check("A1 but the work is compiling it, not implementing it",
          "compile this demand" in a1["NEXT_ACTION"], a1["NEXT_ACTION"])
    check("A2 a compiled demand names the implementation instead",
          "compile this demand" not in decide([
              candidate(P_BACKLOG, "BACKLOG.md", "B-098", "B-098 — specced work")])["NEXT_ACTION"])
    check("A3 a test existing is not completion",
          completion_stage({"implemented": True, "protected": True}) == "IMPLEMENTED",
          completion_stage({"implemented": True, "protected": True}))
    check("A4 implementation plus a green build is not completion",
          completion_stage({"implemented": True, "wired": True, "packaged": True})
          == "PRODUCTION_WIRED")
    check("A4 verification is what unlocks the next stage",
          completion_stage({"implemented": True, "wired": True, "verified": True})
          == "BEHAVIOR_VERIFIED")
    check("A5 only the full chain is COMPLETE",
          completion_stage(dict.fromkeys(
              [k for _, k in STAGES], True)) == "COMPLETE")

    print("B  a blocked task narrows the frontier, it does not end the run")
    blocked = candidate(P_MILESTONE, "CURRENT_MILESTONE.md", "A-1", "needs a file only a person has",
                        blocked_by="an APPID-matched resource that is not on this machine")
    independent = candidate(P_DEBT, "docs/TECH_DEBT.md", "B-1", "unrelated authorised work")
    dependent = candidate(P_DEBT, "docs/TECH_DEBT.md", "C-1", "needs A-1 first", depends_on=["A-1"])

    b1 = decide(propagate_blocks([dict(blocked), dict(independent)]))
    check("B1 independent work stays eligible", b1["AUTONOMOUS_ACTION_AVAILABLE"] == "YES")
    check("B1 and it is what gets picked", "B-1" in b1["NEXT_ACTION"], b1["NEXT_ACTION"])

    chain = propagate_blocks([dict(blocked), dict(dependent)])
    b2 = decide(chain)
    check("B2 a dependant of a blocked task is not executable",
          b2["AUTONOMOUS_ACTION_AVAILABLE"] == "NO", b2["NEXT_ACTION"])
    check("B2 and it says which dependency blocked it",
          any("depends on A-1" in (c["blocked_by"] or "") for c in chain))

    both = propagate_blocks([dict(blocked), dict(dependent), dict(independent)])
    b3 = decide(both)
    check("B3 one blocked chain does not disable unrelated work",
          b3["AUTONOMOUS_ACTION_AVAILABLE"] == "YES" and "B-1" in b3["NEXT_ACTION"])
    check("B3 a blocked task is never the next action",
          "A-1" not in b3["NEXT_ACTION"] and "C-1" not in b3["NEXT_ACTION"])

    b5 = decide(propagate_blocks([dict(blocked), dict(dependent)]))
    check("B5 everything blocked is a legitimate global stop",
          b5["STOP_REASON"] == "BLOCKED_ON_EXTERNAL_DEPENDENCY")
    check("B5 and the report names a concrete missing input",
          not is_vague(b5["BLOCKING_DEPENDENCY"]), b5["BLOCKING_DEPENDENCY"])

    print("B4 external input arriving reopens the chain")
    import tempfile
    handle, path = tempfile.mkstemp(dir=REPO, suffix=".selftest")
    try:
        os.write(handle, b"the resource as first delivered")
        os.close(handle)
        rel = os.path.relpath(path, REPO).replace("\\", "/")
        stale = "sha256:" + hashlib.sha256(b"the resource as first delivered").hexdigest()
        waiting = candidate(P_MILESTONE, "m", "A-2", "waiting on a replacement file",
                            blocked_by="the delivered resource is the wrong one",
                            unblock_when="file_differs %s %s" % (rel, stale))
        still = lift_met_blocks([dict(waiting)])
        check("B4 unchanged file keeps the block", still[0]["blocked_by"] is not None)
        open(path, "wb").write(b"the replacement the owner supplied")
        lifted = lift_met_blocks([dict(waiting)])
        check("B4 replacing the file lifts it on the next refresh", lifted[0]["blocked_by"] is None)
        chained = propagate_blocks(lift_met_blocks([
            dict(waiting), candidate(P_DEBT, "d", "C-2", "needs A-2", depends_on=["A-2"])]))
        check("B4 and its dependants reopen in the same refresh",
              all(c["blocked_by"] is None for c in chained))
    finally:
        if os.path.exists(path):
            os.remove(path)

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
    stop = with_gate(decide(found), human_gate())
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
    for key in ("PHASE", "HUMAN_VALIDATION_READY", "AUTONOMOUS_ACTION_AVAILABLE",
                "HUMAN_ACTION_REQUIRED", "STOP_REASON", "BLOCKING_DEPENDENCY", "NEXT_ACTION"):
        if key in stop:
            print("%s = %s" % (key, stop[key]))
    # discover_work never authorizes ending a run. AUTONOMOUS_ACTION_AVAILABLE=NO is a
    # frontier observation only — termination requires MAX_GROK via model_route.py.
    print("TERMINATION_AUTHORIZED = NO")
    print("TERMINATION_GATE = python scripts/model_route.py --action terminate-request")
    print("  (DEFAULT may not end the run; MAX_GROK terminate-review then terminate-consume)")
    if stop.get("QUEUED_FOR_HUMAN"):
        # Said out loud so it is never mistaken for a stop condition: these are waiting for a
        # batch, not holding anything up.
        print("QUEUED_FOR_HUMAN = %d (queued for the next batch, not blocking)"
              % stop["QUEUED_FOR_HUMAN"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
