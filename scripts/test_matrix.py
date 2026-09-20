"""The test registry: validate it, generate its views, and decide whether a human is needed yet.

The behaviour this exists to change: a test only a person can run used to end the run. The agent
hit it, stopped, asked, waited, resumed, hit the next one, stopped again. Every human-dependent
case was its own interruption.

Here a HUMAN_REQUIRED test is a **queued item with an owner**, not a wall. Autonomous execution
walks straight past it. The human is asked once, at a phase boundary, with everything collected
(harness/PHASES.md).

    python scripts/test_matrix.py --validate   schema and internal consistency
    python scripts/test_matrix.py --gate       is HUMAN_VALIDATION_READY legal yet, and if not why
    python scripts/test_matrix.py --status     regenerate TEST_STATUS.md
    python scripts/test_matrix.py --packet     regenerate HUMAN_VALIDATION.md
    python scripts/test_matrix.py --selftest   the rules above, exercised
"""
import argparse
import datetime
import io
import json
import os
import sys

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MATRIX = os.path.join(REPO, "TEST_MATRIX.yaml")
STATUS_DOC = os.path.join(REPO, "TEST_STATUS.md")
PACKET_DOC = os.path.join(REPO, "HUMAN_VALIDATION.md")
PASSES = os.path.join(REPO, "state", "DISCOVERY_PASSES.json")

OWNERS = (
    "AUTONOMOUS", "HUMAN_PHYSICAL", "HUMAN_ACCOUNT", "HUMAN_CREDENTIAL",
    "HUMAN_DECISION", "EXTERNAL_RESOURCE",
)
STATUSES = (
    "NOT_RUN", "RUNNING", "PASS", "FAIL", "BLOCKED_AUTONOMOUS", "HUMAN_REQUIRED",
    "BLOCKED_EXTERNAL", "NOT_APPLICABLE", "HUMAN_PASS", "HUMAN_FAIL",
)
HUMAN_OWNERS = tuple(o for o in OWNERS if o.startswith("HUMAN_") or o == "EXTERNAL_RESOURCE")

# Statuses an autonomous test may hold when the human gate opens. Anything else is work.
AUTONOMOUS_SETTLED = ("PASS", "NOT_APPLICABLE", "BLOCKED_EXTERNAL")
# Statuses that mean a human still owes us a result.
AWAITING_HUMAN = ("HUMAN_REQUIRED", "HUMAN_FAIL")

REQUIRED = ("id", "capability", "name", "purpose", "type", "owner", "status",
            "release_blocking", "prerequisites", "procedure", "pass_criteria")


def load(path=MATRIX):
    with io.open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def tests(doc=None):
    return (doc or load())["tests"]


# ---- validation ------------------------------------------------------------------------------


def validate(doc=None):
    """Everything that would make the registry lie. Returns a list of problems."""
    doc = doc or load()
    found = []
    seen = set()
    for entry in doc["tests"]:
        tid = entry.get("id", "<no id>")
        for field in REQUIRED:
            if field not in entry:
                found.append("%s: missing %s" % (tid, field))
        if tid in seen:
            found.append("%s: duplicate id" % tid)
        seen.add(tid)
        owner, status = entry.get("owner"), entry.get("status")
        if owner not in OWNERS:
            found.append("%s: owner %r is not one of %s" % (tid, owner, ", ".join(OWNERS)))
        if status not in STATUSES:
            found.append("%s: status %r is not one of %s" % (tid, status, ", ".join(STATUSES)))
        # A human owner with an autonomous status, or the reverse, means nobody knows who is
        # waiting for whom.
        if owner == "AUTONOMOUS" and status in ("HUMAN_REQUIRED", "HUMAN_PASS", "HUMAN_FAIL"):
            found.append("%s: AUTONOMOUS cannot be %s" % (tid, status))
        if owner in HUMAN_OWNERS and status in ("PASS", "FAIL"):
            found.append("%s: %s must use HUMAN_PASS/HUMAN_FAIL, not %s" % (tid, owner, status))
        if entry.get("status") == "PASS" and not entry.get("evidence"):
            found.append("%s: PASS with no evidence" % tid)
        if owner in HUMAN_OWNERS:
            found.extend(human_entry_problems(entry))
    return found


def human_entry_problems(entry):
    """What a queued human item must carry so the person never has to come back and ask."""
    tid = entry["id"]
    problems = []
    if not entry.get("human_reason"):
        problems.append("%s: no human_reason - say why an agent cannot do it" % tid)
    if not entry.get("procedure"):
        problems.append("%s: no procedure" % tid)
    if not entry.get("pass_criteria"):
        problems.append("%s: no pass_criteria" % tid)
    if entry["owner"] == "HUMAN_DECISION":
        if not entry.get("decision_options"):
            problems.append("%s: a decision with no options to choose between" % tid)
        if not entry.get("quantified"):
            problems.append("%s: a decision with nothing measured - reduce it to evidence first" % tid)
    else:
        if not entry.get("returns"):
            problems.append("%s: does not say what result to report back" % tid)
        if not entry.get("autonomous_evidence"):
            problems.append("%s: no record of what was already established without a human" % tid)
        if not entry.get("remaining_uncertainty"):
            problems.append("%s: does not say what is still unknown" % tid)
    return problems


# ---- the gate --------------------------------------------------------------------------------


def clean_passes():
    try:
        with io.open(PASSES, encoding="utf-8") as handle:
            return int(json.load(handle).get("consecutive_clean", 0))
    except Exception:
        return 0


def record_pass(clean):
    """A discovery/review pass that found no autonomous work increments; anything else resets."""
    count = clean_passes() + 1 if clean else 0
    os.makedirs(os.path.dirname(PASSES), exist_ok=True)
    with io.open(PASSES, "w", encoding="utf-8", newline="\n") as handle:
        json.dump({
            "consecutive_clean": count,
            "updated": datetime.date.today().isoformat(),
            "note": "Two consecutive clean passes are required before HUMAN_VALIDATION_READY. "
                    "Finding any autonomous work resets this to 0.",
        }, handle, indent=2)
        handle.write("\n")
    return count


def gate(doc=None, passes=None):
    """HUMAN_VALIDATION_READY, and every reason it is not.

    Deliberately not `AUTONOMOUS_ACTION_AVAILABLE = NO`, which was a judgement call about whether
    anything was left. This is a property of the registry: either every autonomous test has
    settled and every remaining case names a human, or it has not.
    """
    doc = doc or load()
    entries = doc["tests"]
    passes = clean_passes() if passes is None else passes
    reasons = []

    problems = validate(doc)
    if problems:
        reasons.append("the registry does not validate: %d problem(s)" % len(problems))

    unsettled = [e for e in entries
                 if e["owner"] == "AUTONOMOUS" and e["status"] not in AUTONOMOUS_SETTLED]
    for entry in unsettled:
        reasons.append("%s is AUTONOMOUS and %s - run it or fix it" % (entry["id"], entry["status"]))

    for entry in entries:
        if entry["status"] == "BLOCKED_EXTERNAL" and not entry.get("evidence"):
            reasons.append("%s is BLOCKED_EXTERNAL with no evidence for the block" % entry["id"])

    if passes < 2:
        reasons.append("only %d consecutive clean discovery/review pass(es); two are required" % passes)

    queued = [e for e in entries if e["status"] in AWAITING_HUMAN]
    return {
        "HUMAN_VALIDATION_READY": "FALSE" if reasons else "TRUE",
        "reasons": reasons,
        "queued_human_items": len(queued),
        "autonomous_unsettled": len(unsettled),
        "consecutive_clean_passes": passes,
    }


def autonomous_work(doc=None):
    """Autonomous tests that are not settled. This is what the frontier picks up as work.

    HUMAN_REQUIRED entries are deliberately absent: they are queued, and a queued item is not a
    reason to stop.
    """
    return [e for e in (doc or load())["tests"]
            if e["owner"] == "AUTONOMOUS" and e["status"] not in AUTONOMOUS_SETTLED]


# ---- generated views ---------------------------------------------------------------------


def counts(entries):
    out = {s: 0 for s in STATUSES}
    for entry in entries:
        out[entry["status"]] = out.get(entry["status"], 0) + 1
    return out


def status_doc(doc=None):
    doc = doc or load()
    entries = doc["tests"]
    tally = counts(entries)
    g = gate(doc)
    blocking_fail = [e for e in entries
                     if e.get("release_blocking") and e["status"] in ("FAIL", "HUMAN_FAIL")]

    lines = []
    lines.append("# Test status")
    lines.append("")
    lines.append("Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by "
                 "`python scripts/test_matrix.py --status`. **Do not edit by hand** — the registry "
                 "is the source of truth and this is a view of it.")
    lines.append("")
    lines.append("Updated %s · %d tests" % (doc["meta"]["updated"], len(entries)))
    lines.append("")
    lines.append("| | |")
    lines.append("| --- | --- |")
    lines.append("| autonomous PASS | %d |" % tally.get("PASS", 0))
    lines.append("| autonomous FAIL | %d |" % tally.get("FAIL", 0))
    lines.append("| not run | %d |" % tally.get("NOT_RUN", 0))
    lines.append("| human required | %d |" % tally.get("HUMAN_REQUIRED", 0))
    lines.append("| human pass | %d |" % tally.get("HUMAN_PASS", 0))
    lines.append("| human fail | %d |" % tally.get("HUMAN_FAIL", 0))
    lines.append("| blocked external | %d |" % tally.get("BLOCKED_EXTERNAL", 0))
    lines.append("| not applicable | %d |" % tally.get("NOT_APPLICABLE", 0))
    lines.append("| **release-blocking failures** | **%d** |" % len(blocking_fail))
    lines.append("")
    lines.append("**HUMAN_VALIDATION_READY = %s**" % g["HUMAN_VALIDATION_READY"])
    if g["reasons"]:
        lines.append("")
        for reason in g["reasons"]:
            lines.append("- %s" % reason)
    lines.append("")

    lines.append("## By capability")
    lines.append("")
    lines.append("| Capability | Tests | Passing | Awaiting a human | Not run |")
    lines.append("| --- | --- | --- | --- | --- |")
    caps = {}
    for entry in entries:
        caps.setdefault(entry["capability"], []).append(entry)
    for cap in sorted(caps):
        group = caps[cap]
        lines.append("| %s | %d | %d | %d | %d |" % (
            cap, len(group),
            sum(1 for e in group if e["status"] in ("PASS", "HUMAN_PASS")),
            sum(1 for e in group if e["status"] in AWAITING_HUMAN),
            sum(1 for e in group if e["status"] == "NOT_RUN"),
        ))
    lines.append("")

    lines.append("## Every test")
    lines.append("")
    lines.append("| ID | Capability | Test | Owner | Status | Release blocking | Evidence |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for entry in sorted(entries, key=lambda e: (e["capability"], e["id"])):
        evidence = entry.get("evidence") or entry.get("autonomous_evidence") or []
        first = evidence[0] if evidence else "—"
        if len(first) > 90:
            first = first[:87] + "…"
        lines.append("| %s | %s | %s | %s | %s | %s | %s |" % (
            entry["id"], entry["capability"], entry["name"], entry["owner"], entry["status"],
            "yes" if entry.get("release_blocking") else "no",
            first.replace("|", "\\|"),
        ))
    lines.append("")
    return "\n".join(lines)


def packet_doc(doc=None):
    doc = doc or load()
    entries = [e for e in doc["tests"] if e["status"] in AWAITING_HUMAN]
    g = gate(doc)

    groups = [
        ("A. Physical tests", "HUMAN_PHYSICAL"),
        ("B. Account and real-service tests", "HUMAN_ACCOUNT"),
        ("C. Required credentials", "HUMAN_CREDENTIAL"),
        ("D. Product decisions", "HUMAN_DECISION"),
        ("E. External blockers", "EXTERNAL_RESOURCE"),
    ]

    lines = []
    lines.append("# Human validation packet")
    lines.append("")
    lines.append("Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by "
                 "`python scripts/test_matrix.py --packet`. **Do not edit by hand.**")
    lines.append("")
    if g["HUMAN_VALIDATION_READY"] == "TRUE":
        lines.append("Everything an agent could do has been done. What follows is the whole of what "
                     "needs a person — **in one batch, to be handled in one sitting**, rather than "
                     "one interruption per test.")
    else:
        lines.append("> **Not ready yet.** This is a preview of the queue; autonomous work remains:")
        lines.append(">")
        for reason in g["reasons"][:8]:
            lines.append("> - %s" % reason)
    lines.append("")
    lines.append("%d item(s) queued." % len(entries))
    lines.append("")

    for title, owner in groups:
        group = [e for e in entries if e["owner"] == owner]
        if not group:
            continue
        lines.append("## %s" % title)
        lines.append("")
        for entry in group:
            lines.append("### %s — %s" % (entry["id"], entry["name"]))
            lines.append("")
            lines.append("**Why this needs you.** %s" % entry.get("human_reason", ""))
            lines.append("")
            if entry.get("autonomous_evidence"):
                lines.append("**Already established without you:**")
                lines.append("")
                for item in entry["autonomous_evidence"]:
                    lines.append("- %s" % item)
                lines.append("")
            if entry.get("quantified"):
                lines.append("**Measured, so this is a choice and not a question:**")
                lines.append("")
                for item in entry["quantified"]:
                    lines.append("- %s" % item)
                lines.append("")
            if entry.get("decision_options"):
                lines.append("**Options:**")
                lines.append("")
                for item in entry["decision_options"]:
                    lines.append("- %s" % item)
                lines.append("")
            if entry.get("prerequisites"):
                lines.append("**You will need:** %s" % "; ".join(entry["prerequisites"]))
                lines.append("")
            lines.append("**What to do:**")
            lines.append("")
            for i, step in enumerate(entry["procedure"], 1):
                lines.append("%d. %s" % (i, step))
            lines.append("")
            lines.append("**It passes if:**")
            lines.append("")
            for item in entry["pass_criteria"]:
                lines.append("- %s" % item)
            lines.append("")
            if entry.get("returns"):
                lines.append("**Tell me back:** %s" % "; ".join(entry["returns"]))
                lines.append("")
            if entry.get("remaining_uncertainty"):
                lines.append("**Still unknown until you do:** %s"
                             % "; ".join(entry["remaining_uncertainty"]))
                lines.append("")
            if entry.get("release_blocking"):
                lines.append("*Release-blocking.*")
                lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("When you have results for any of these, give me all of them at once. They will be "
                 "applied together, every resulting failure triaged together, and every fix that "
                 "follows made in one autonomous cycle before anything is asked of you again "
                 "([harness/PHASES.md](harness/PHASES.md)).")
    lines.append("")
    return "\n".join(lines)


def write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return path


# ---- selftest --------------------------------------------------------------------------------


def _entry(**over):
    base = {
        "id": "X-001", "capability": "c", "name": "n", "purpose": "p", "type": "unit",
        "owner": "AUTONOMOUS", "status": "PASS", "release_blocking": False,
        "prerequisites": [], "procedure": ["do it"], "pass_criteria": ["it works"],
        "evidence": ["seen"],
    }
    base.update(over)
    return base


def selftest():
    results = []

    def check(name, ok):
        results.append((name, ok))

    # The rule this harness exists for.
    doc = {"meta": {"updated": "x"}, "tests": [
        _entry(id="A-1"),
        _entry(id="H-1", owner="HUMAN_PHYSICAL", status="HUMAN_REQUIRED", evidence=None,
               human_reason="a person must drive", returns=["what happened"],
               autonomous_evidence=["everything short of driving"],
               remaining_uncertainty=["the road"]),
    ]}
    check("a queued human item is not autonomous work", autonomous_work(doc) == [])
    check("the gate opens with human items queued",
          gate(doc, passes=2)["HUMAN_VALIDATION_READY"] == "TRUE")

    # An autonomous test that has not run is work, and holds the gate shut.
    doc2 = {"meta": {"updated": "x"}, "tests": [_entry(id="A-2", status="NOT_RUN", evidence=[])]}
    check("a NOT_RUN autonomous test is work", len(autonomous_work(doc2)) == 1)
    check("and holds the gate shut", gate(doc2, passes=2)["HUMAN_VALIDATION_READY"] == "FALSE")

    # An autonomous failure is work, not a reason to call a human.
    doc3 = {"meta": {"updated": "x"}, "tests": [_entry(id="A-3", status="FAIL", evidence=["red"])]}
    check("a FAIL autonomous test is work", len(autonomous_work(doc3)) == 1)
    check("and holds the gate shut", gate(doc3, passes=2)["HUMAN_VALIDATION_READY"] == "FALSE")

    # One clean pass is not two.
    check("one clean pass is not enough", gate(doc, passes=1)["HUMAN_VALIDATION_READY"] == "FALSE")
    check("zero clean passes is not enough", gate(doc, passes=0)["HUMAN_VALIDATION_READY"] == "FALSE")

    # A human item that would send the person away uninformed.
    thin = _entry(id="H-2", owner="HUMAN_ACCOUNT", status="HUMAN_REQUIRED", evidence=None)
    check("a human item with no reason or return is rejected",
          len(human_entry_problems(thin)) >= 3)

    # A decision with no numbers is not ready to be asked.
    undecided = _entry(id="D-1", owner="HUMAN_DECISION", status="HUMAN_REQUIRED", evidence=None,
                       human_reason="policy", decision_options=["A", "B"])
    check("a decision with nothing measured is rejected",
          any("reduce it to evidence" in p for p in human_entry_problems(undecided)))

    # Owner and status must agree about who is waiting.
    mixed = {"meta": {"updated": "x"}, "tests": [
        _entry(id="M-1", owner="AUTONOMOUS", status="HUMAN_REQUIRED"),
        _entry(id="M-2", owner="HUMAN_PHYSICAL", status="PASS", human_reason="r",
               returns=["r"], autonomous_evidence=["e"], remaining_uncertainty=["u"]),
    ]}
    problems = validate(mixed)
    check("AUTONOMOUS cannot be HUMAN_REQUIRED",
          any("cannot be HUMAN_REQUIRED" in p for p in problems))
    check("a human owner cannot report a bare PASS",
          any("HUMAN_PASS/HUMAN_FAIL" in p for p in problems))

    # PASS must carry evidence.
    check("PASS with no evidence is rejected",
          any("PASS with no evidence" in p
              for p in validate({"meta": {"updated": "x"},
                                 "tests": [_entry(id="E-1", evidence=[])]})))

    # The real registry.
    real = load()
    check("the repository's own registry validates", validate(real) == [])

    width = max(len(n) for n, _ in results)
    for name, ok in results:
        print("  %-4s %s" % ("ok" if ok else "FAIL", name.ljust(width)))
    failed = [n for n, ok in results if not ok]
    print("\n%s (%d checks)" % ("SELFTEST PASSED" if not failed else "SELFTEST FAILED", len(results)))
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--validate", action="store_true")
    ap.add_argument("--gate", action="store_true")
    ap.add_argument("--status", action="store_true")
    ap.add_argument("--packet", action="store_true")
    ap.add_argument("--work", action="store_true", help="autonomous tests that are not settled")
    ap.add_argument("--record-pass", choices=["clean", "dirty"],
                    help="record a discovery/review pass result")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.record_pass:
        count = record_pass(args.record_pass == "clean")
        print("consecutive clean passes: %d" % count)
        return 0

    doc = load()

    if args.validate:
        problems = validate(doc)
        for problem in problems:
            print("  %s" % problem)
        print("%d test(s), %d problem(s)" % (len(doc["tests"]), len(problems)))
        return 1 if problems else 0

    if args.work:
        for entry in autonomous_work(doc):
            print("  %-18s %-14s %s" % (entry["id"], entry["status"], entry["name"]))
        return 0

    if args.status:
        print("wrote %s" % write(STATUS_DOC, status_doc(doc)))
        return 0

    if args.packet:
        print("wrote %s" % write(PACKET_DOC, packet_doc(doc)))
        return 0

    g = gate(doc)
    print("HUMAN_VALIDATION_READY = %s" % g["HUMAN_VALIDATION_READY"])
    print("  queued for a human      %d" % g["queued_human_items"])
    print("  autonomous unsettled    %d" % g["autonomous_unsettled"])
    print("  consecutive clean passes %d" % g["consecutive_clean_passes"])
    for reason in g["reasons"]:
        print("  - %s" % reason)
    return 0


if __name__ == "__main__":
    sys.exit(main())
