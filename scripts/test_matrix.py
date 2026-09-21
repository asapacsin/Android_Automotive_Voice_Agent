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
    python scripts/test_matrix.py --verdict ID verdict the evidence for one entry actually earns
    python scripts/test_matrix.py --coverage   per-requirement scope coverage
    python scripts/test_matrix.py --selftest   the rules above, exercised
"""
import argparse
import datetime
import io
import json
import os
import re
import sys

import yaml

import acceptance

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MATRIX = os.path.join(REPO, "TEST_MATRIX.yaml")
CAPABILITIES = os.path.join(REPO, "config", "capabilities.yaml")
STATUS_DOC = os.path.join(REPO, "TEST_STATUS.md")
PACKET_DOC = os.path.join(REPO, "HUMAN_VALIDATION.md")
LOCAL_DEVICE_DOC = os.path.join(REPO, "LOCAL_DEVICE_REQUIRED.md")
PASSES = os.path.join(REPO, "state", "DISCOVERY_PASSES.json")

OWNERS = (
    "AUTONOMOUS", "HUMAN_PHYSICAL", "HUMAN_ACCOUNT", "HUMAN_CREDENTIAL",
    "HUMAN_DECISION", "EXTERNAL_RESOURCE",
)
STATUSES = (
    "NOT_RUN", "RUNNING", "PASS", "FAIL", "BLOCKED_AUTONOMOUS", "HUMAN_REQUIRED",
    "BLOCKED_EXTERNAL", "NOT_APPLICABLE", "HUMAN_PASS", "HUMAN_FAIL",
    "INCOMPLETE", "PARTIAL_PASS",
)
HUMAN_OWNERS = tuple(o for o in OWNERS if o.startswith("HUMAN_") or o == "EXTERNAL_RESOURCE")

# Statuses an autonomous test may hold when the human gate opens. Anything else is work.
# INCOMPLETE is deliberately absent: a run that never reached a verdict is unfinished work,
# never a pass. PARTIAL_PASS is settled — a finished verdict at an explicitly declared scope,
# with the remainder tracked by its parent entry.
AUTONOMOUS_SETTLED = ("PASS", "NOT_APPLICABLE", "BLOCKED_EXTERNAL", "PARTIAL_PASS")
# Statuses that mean a human still owes us a result.
AWAITING_HUMAN = ("HUMAN_REQUIRED", "HUMAN_FAIL")

REQUIRED = ("id", "capability", "name", "purpose", "type", "owner", "status",
            "scope", "release_blocking", "prerequisites", "procedure", "pass_criteria")

# Any stored verdict — autonomous or human. Only these are checked against evidence.
VERDICT_STATUSES = ("PASS", "FAIL", "PARTIAL_PASS", "INCOMPLETE",
                    "HUMAN_PASS", "HUMAN_FAIL")


def load(path=MATRIX):
    with io.open(path, encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def tests(doc=None):
    return (doc or load())["tests"]


def capability_requirements(path=CAPABILITIES):
    """{dotted.key: required_scope} from the capability registry.

    The registry owns the bar: a test author can scope their own test, but cannot lower
    what a requirement demands. Absent `required_scope` means COMPONENT — a single
    behaviour with no flow bar.
    """
    with io.open(path, encoding="utf-8") as handle:
        doc = yaml.safe_load(handle)
    out = {}
    for group, entries in doc.items():
        if not isinstance(entries, dict):
            continue
        for name, cap in entries.items():
            if not isinstance(cap, dict) or "verified" not in cap:
                continue
            out["%s.%s" % (group, name)] = cap.get("required_scope", "COMPONENT")
    return out


# ---- validation ------------------------------------------------------------------------------


def validate(doc=None, requirements=None):
    """Everything that would make the registry lie. Returns a list of problems."""
    doc = doc or load()
    found = []
    seen = set()
    ids = {entry.get("id") for entry in doc["tests"]}
    if requirements is None:
        try:
            requirements = capability_requirements()
        except Exception as exc:
            return ["capabilities.yaml unreadable: %r" % (exc,)]
    for key, need in sorted(requirements.items()):
        if need not in acceptance.SCOPES:
            found.append("capabilities.yaml: %s has required_scope %r" % (key, need))
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
        found.extend(verdict_problems(entry, ids, requirements))
    return found


def verdict_problems(entry, known_ids=(), requirements=None):
    """A stored verdict may not claim more than its scope and evidence earn.

    This is the 0.6.4 rule: intermediate health (launch, route, guidance, a clean manual
    stop) must never produce an END_TO_END PASS. Unit/sim verdicts keep the plain evidence
    rule — each assertion is already a checked criterion — while device flows and E2E
    verdicts must carry an acceptance matrix the evidence is checked against, with every
    observed row citing an artifact, not bare prose.
    """
    tid = entry.get("id", "<no id>")
    problems = []
    scope = entry.get("scope")
    status = entry.get("status")
    etype = entry.get("type")
    if scope not in acceptance.SCOPES:
        problems.append("%s: scope %r must be one of %s"
                        % (tid, scope, ", ".join(acceptance.SCOPES)))
        return problems
    for key in entry.get("covers") or []:
        if requirements is None or key not in requirements:
            problems.append("%s: covers unknown capability %r" % (tid, key))
        elif (acceptance.SCOPE_RANK[scope]
                < acceptance.SCOPE_RANK[requirements[key]]):
            problems.append(
                "%s: scope laundering: covers %s (requires %s) at %s"
                % (tid, key, requirements[key], scope))
    rows = entry.get("acceptance") or []
    for row in rows:
        if not isinstance(row, dict) or "criterion" not in row or "required" not in row:
            problems.append("%s: acceptance rows need criterion/required/observed/evidence"
                            % tid)
            break
    device_flow_verdict = (status in VERDICT_STATUSES and scope != "COMPONENT"
                           and etype == "device")
    if device_flow_verdict and not rows:
        problems.append("%s: %s %s device verdict needs an acceptance matrix" % (tid, scope, status))
    if device_flow_verdict and rows:
        earned = acceptance.evaluate(
            scope, rows,
            terminal_success=entry.get("terminal_success") or (),
            terminal_observed=entry.get("terminal_observed") or (),
            failure_observed=entry.get("failure_observed") or (),
            ended_by=entry.get("ended_by"),
            regressions=entry.get("regressions") or [],
            require_provenance=True)["verdict"]
        if status in ("PASS", "HUMAN_PASS", "PARTIAL_PASS") and earned != "PASS":
            problems.append("%s: stored %s but the evidence earns %s" % (tid, status, earned))
        if status == "FAIL" and earned == "PASS":
            problems.append("%s: stored FAIL but no criterion was violated" % tid)
    if scope == "END_TO_END":
        if not entry.get("terminal_success"):
            problems.append("%s: END_TO_END must declare terminal_success" % tid)
    elif entry.get("terminal_success"):
        problems.append("%s: only END_TO_END may declare terminal_success" % tid)
    if device_flow_verdict and entry.get("ended_by") not in acceptance.ENDED_BY:
        problems.append("%s: %s device verdict needs ended_by natural/manual/timeout/crash"
                        % (tid, scope))
    if (status in VERDICT_STATUSES and scope == "END_TO_END" and rows
            and entry.get("capability") in acceptance.MIN_CRITERIA):
        want = set(acceptance.MIN_CRITERIA[entry["capability"]])
        have = {row.get("criterion") for row in rows if isinstance(row, dict)}
        if not want <= have:
            problems.append("%s: E2E %s verdict misses criteria: %s"
                            % (tid, entry["capability"], ", ".join(sorted(want - have))))
    if entry.get("regressions") and status in ("PASS", "PARTIAL_PASS", "HUMAN_PASS"):
        problems.append("%s: stored %s with unexplained regressions" % (tid, status))
    if status in ("FAIL", "HUMAN_FAIL", "INCOMPLETE") and not entry.get("evidence"):
        problems.append("%s: %s with no evidence" % (tid, status))
    if status == "PARTIAL_PASS":
        if not entry.get("parent"):
            problems.append("%s: PARTIAL_PASS needs a parent entry" % tid)
        elif known_ids and entry["parent"] not in known_ids:
            problems.append("%s: parent %r does not exist" % (tid, entry["parent"]))
    if scope != "END_TO_END":
        claimed = "%s %s" % (entry.get("id", ""), entry.get("name", ""))
        if re.search(r"e2e|end.to.end", claimed, re.I):
            problems.append("%s: a %s entry must not claim end-to-end in id/name"
                            % (tid, scope))
        if (re.search(r"baseline", claimed, re.I)
                and not re.search(r"mid.route|component|intermediate", claimed, re.I)):
            problems.append("%s: unscoped 'baseline' claim at %s - qualify it (e.g. mid-route)"
                            % (tid, scope))
    return problems


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


def gate(doc=None, passes=None, requirements=None):
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

    for key, cov in coverage(doc, requirements).items():
        if cov["verdict"] == "UNCOVERED":
            reasons.append("%s requires %s but has no passing cover (linked: %s)"
                           % (key, cov["required_scope"],
                              ", ".join(cov["linked"]) or "none"))

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


PASSING = ("PASS", "HUMAN_PASS", "PARTIAL_PASS")


def coverage(doc=None, requirements=None):
    """Per-requirement scope coverage: required_scope vs the best passing cover.

    A requirement counts as covered only by a passing verdict scoped at or above its bar —
    lower-scope passes never satisfy it, however green. QUEUED means a human validation is
    already linked; only UNCOVERED with no queued human validation holds the gate shut.
    """
    doc = doc or load()
    if requirements is None:
        requirements = capability_requirements()
    entries = doc["tests"]
    out = {}
    for key, need in sorted(requirements.items()):
        if need == "COMPONENT" or need not in acceptance.SCOPES:
            continue
        linked = [e for e in entries if key in (e.get("covers") or [])]
        passing = sorted(e["id"] for e in linked
                         if e.get("status") in PASSING
                         and acceptance.SCOPE_RANK.get(e.get("scope"), -1)
                         >= acceptance.SCOPE_RANK[need])
        queued = sorted(e["id"] for e in linked
                        if e.get("owner") in HUMAN_OWNERS
                        and e.get("status") in AWAITING_HUMAN)
        verdict = "COVERED" if passing else ("QUEUED" if queued else "UNCOVERED")
        out[key] = {"required_scope": need, "passing": passing,
                    "queued_human": queued,
                    "linked": sorted(e["id"] for e in linked), "verdict": verdict}
    return out


# ---- verdict self-check --------------------------------------------------------------------------


def verdict_report(entry):
    """What verdict the evidence for one entry actually earns (§8 self-check).

    Run this before reporting PASS/STABLE on anything the matrix tracks. A stored verdict
    above the earned one is a harness violation, not a result.
    """
    rows = entry.get("acceptance") or []
    provenance = entry.get("scope") != "COMPONENT" and entry.get("type") == "device"
    result = acceptance.evaluate(
        entry.get("scope"), rows,
        terminal_success=entry.get("terminal_success") or (),
        terminal_observed=entry.get("terminal_observed") or (),
        failure_observed=entry.get("failure_observed") or (),
        ended_by=entry.get("ended_by"),
        regressions=entry.get("regressions") or [],
        require_provenance=provenance)
    lines = ["%s — %s" % (entry.get("id"), entry.get("name")),
             "stored: %s at %s (ended_by=%s)" % (entry.get("status"), entry.get("scope"),
                                                entry.get("ended_by")),
             "earned: %s" % result["verdict"]]
    for reason in result["reasons"]:
        lines.append("  - %s" % reason)
    if rows:
        lines.append("")
        lines.append(result["table"])
    return "\n".join(lines)


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
    lines.append("| incomplete | %d |" % tally.get("INCOMPLETE", 0))
    lines.append("| partial pass | %d |" % tally.get("PARTIAL_PASS", 0))
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
        ("A. Physical tests (LOCAL_DEVICE_REQUIRED)", "HUMAN_PHYSICAL"),
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
    lines.append("Physical-device cases are also collected in "
                 "[LOCAL_DEVICE_REQUIRED.md](LOCAL_DEVICE_REQUIRED.md).")
    lines.append("")

    for title, owner in groups:
        group = [e for e in entries if e["owner"] == owner]
        if not group:
            continue
        lines.append("## %s" % title)
        lines.append("")
        for entry in group:
            lines.extend(_entry_section(entry, local_device=(owner == "HUMAN_PHYSICAL")))
    lines.append("---")
    lines.append("")
    lines.append("When you have results for any of these, give me all of them at once. They will be "
                 "applied together, every resulting failure triaged together, and every fix that "
                 "follows made in one autonomous cycle before anything is asked of you again "
                 "([harness/PHASES.md](harness/PHASES.md)).")
    lines.append("")
    return "\n".join(lines)


def _entry_section(entry, local_device=False):
    lines = []
    label = "LOCAL_DEVICE_REQUIRED — " if local_device else ""
    lines.append("### %s%s — %s" % (label, entry["id"], entry["name"]))
    lines.append("")
    if local_device:
        lines.append("**Tag:** `LOCAL_DEVICE_REQUIRED`")
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
    return lines


def local_device_doc(doc=None):
    """One consolidated batch of every physical-device case for local execution."""
    doc = doc or load()
    entries = [e for e in doc["tests"]
               if e["status"] in AWAITING_HUMAN and e["owner"] == "HUMAN_PHYSICAL"]
    g = gate(doc)
    lines = []
    lines.append("# LOCAL_DEVICE_REQUIRED — consolidated device-test batch")
    lines.append("")
    lines.append("Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by "
                 "`python scripts/test_matrix.py --local-device`. **Do not edit by hand.**")
    lines.append("")
    lines.append("Every case below **requires a physical Android device** (and usually a real "
                 "cabin / GPS / human voice). Cloud agents must not block on these: record them "
                 "here and continue autonomous work.")
    lines.append("")
    if g["HUMAN_VALIDATION_READY"] == "TRUE":
        lines.append("Autonomous cloud work for the current frontier is settled. Run this batch "
                     "locally in one sitting.")
    else:
        lines.append("> Autonomous work may still be open. Prefer finishing cloud-verifiable work "
                     "first; this file is still the device queue.")
        lines.append(">")
        for reason in g["reasons"][:8]:
            lines.append("> - %s" % reason)
    lines.append("")
    lines.append("**%d LOCAL_DEVICE_REQUIRED item(s).**" % len(entries))
    lines.append("")
    lines.append("Install tip (from a cloud-built APK, when one exists):")
    lines.append("")
    lines.append("```bash")
    lines.append("adb install -r \"$NOVA_BUILD_DIR/app/outputs/apk/debug/app-debug.apk\"")
    lines.append("adb logcat -s NovaVoice:D")
    lines.append("```")
    lines.append("")
    for entry in entries:
        lines.extend(_entry_section(entry, local_device=True))
    lines.append("---")
    lines.append("")
    lines.append("Return every result together. Non-device human items remain in "
                 "[HUMAN_VALIDATION.md](HUMAN_VALIDATION.md).")
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
        "owner": "AUTONOMOUS", "status": "PASS", "scope": "COMPONENT",
        "release_blocking": False,
        "prerequisites": [], "procedure": ["do it"], "pass_criteria": ["it works"],
        "evidence": ["seen"],
    }
    base.update(over)
    return base


def _rows(*names, **kw):
    observed = kw.get("observed", True)
    return [{"criterion": name, "required": True, "observed": observed,
             "evidence": kw.get("evidence", "log:NovaVoice/%s" % name)}
            for name in names]


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
    # requirements={} isolates the batching mechanics; coverage has its own checks below.
    check("the gate opens with human items queued",
          gate(doc, passes=2, requirements={})["HUMAN_VALIDATION_READY"] == "TRUE")

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

    # The 0.6.4 rule: intermediate health must never produce an END_TO_END PASS.
    e2e_overclaim = _entry(
        id="V-1", capability="navigation", type="device", scope="END_TO_END",
        terminal_success=["arrival_callback"],
        acceptance=_rows("navigation_start", "route_resolved", "guidance_progress"),
        ended_by="manual")
    check("an E2E PASS without the terminal state is rejected",
          any("earns INCOMPLETE" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [e2e_overclaim]})))
    mid_ok = _entry(
        id="V-2", type="device", scope="INTERMEDIATE_FLOW",
        acceptance=_rows("navigation_start", "route_resolved", "guidance_progress"),
        ended_by="manual")
    check("the same evidence as an intermediate PASS is accepted",
          validate({"meta": {"updated": "x"}, "tests": [mid_ok]}) == [])
    check("INCOMPLETE is unsettled work",
          len(autonomous_work({"meta": {"updated": "x"},
                               "tests": [_entry(id="V-3", status="INCOMPLETE")]})) == 1)
    check("INCOMPLETE with no evidence is rejected",
          any("INCOMPLETE with no evidence" in p
              for p in validate({"meta": {"updated": "x"},
                                 "tests": [_entry(id="V-4", status="INCOMPLETE",
                                                  evidence=[])]})))
    check("PARTIAL_PASS without a parent is rejected",
          any("needs a parent" in p
              for p in validate({"meta": {"updated": "x"},
                                 "tests": [_entry(id="V-5", status="PARTIAL_PASS")]})))
    check("PARTIAL_PASS with a parent is settled",
          autonomous_work({"meta": {"updated": "x"}, "tests": [
              _entry(id="V-6a", status="INCOMPLETE", evidence=["partial run"]),
              _entry(id="V-6b", status="PARTIAL_PASS", parent="V-6a"),
          ]}) == [_entry(id="V-6a", status="INCOMPLETE", evidence=["partial run"])])
    check("an unscoped baseline name below E2E is rejected",
          any("unscoped 'baseline'" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-7", name="Stable navigation baseline")]})))
    check("E2E without declared terminal states is rejected",
          any("must declare terminal_success" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-8", type="device", scope="END_TO_END",
                         status="NOT_RUN", evidence=[])]})))
    check("PASS with unexplained regressions is rejected",
          any("unexplained regressions" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-9", regressions=["15 m -> 3.7 km"])]})))
    check("FAIL with no evidence is rejected",
          any("FAIL with no evidence" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-10", status="FAIL", evidence=[])]})))
    check("a device flow verdict without ended_by is rejected",
          any("needs ended_by" in p
              for p in validate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="V-11", type="device", scope="INTERMEDIATE_FLOW",
                         acceptance=_rows("navigation_start"))]})))

    # Scope laundering: the registry owns required_scope; a test below the bar cannot
    # cover the requirement, however it is labelled.
    reqs = {"drive.task": "END_TO_END", "guide.live": "INTERMEDIATE_FLOW"}
    launder = {"meta": {"updated": "x"}, "tests": [
        _entry(id="L-1", type="device", scope="INTERMEDIATE_FLOW",
               covers=["drive.task"],
               acceptance=_rows("navigation_start"), ended_by="manual")]}
    check("covering an E2E requirement at intermediate scope is rejected",
          any("scope laundering" in p for p in validate(launder, reqs)))
    check("covering an unknown capability is rejected",
          any("unknown capability" in p for p in validate(
              {"meta": {"updated": "x"}, "tests": [
                  _entry(id="L-2", covers=["no.such.thing"])]}, reqs)))
    honest = {"meta": {"updated": "x"}, "tests": [
        _entry(id="L-3", type="device", scope="END_TO_END", covers=["drive.task"],
               terminal_success=["done"], terminal_observed=["done"],
               acceptance=_rows("done"), ended_by="natural")]}
    check("covering at sufficient scope is accepted",
          validate(honest, reqs) == [])
    check("an E2E requirement with only lower-scope passes is UNCOVERED",
          coverage({"meta": {"updated": "x"}, "tests": [
              _entry(id="L-4", type="device", scope="INTERMEDIATE_FLOW")]},
              reqs)["drive.task"]["verdict"] == "UNCOVERED")
    check("an E2E requirement with a passing E2E cover is COVERED",
          coverage(honest, reqs)["drive.task"]["verdict"] == "COVERED")
    human_queued = {"meta": {"updated": "x"}, "tests": [
        _entry(id="L-5", type="device", scope="END_TO_END", owner="HUMAN_PHYSICAL",
               status="HUMAN_REQUIRED", evidence=None, covers=["drive.task"],
               terminal_success=["done"], human_reason="drive",
               returns=["done?"], autonomous_evidence=["sim"], remaining_uncertainty=["road"])]}
    check("a human-queued requirement is QUEUED, not a gate reason",
          coverage(human_queued, reqs)["drive.task"]["verdict"] == "QUEUED"
          and not any("drive.task" in r
                      for r in gate(human_queued, passes=2, requirements=reqs)["reasons"]))
    check("an uncovered requirement holds the gate with a reason",
          any("drive.task" in r and "no passing cover" in r
              for r in gate({"meta": {"updated": "x"}, "tests": [
                  _entry(id="L-6", status="NOT_RUN", evidence=[])]},
                  passes=2, requirements=reqs)["reasons"]))

    # Evidence provenance: prose about evidence is not evidence.
    prose = {"meta": {"updated": "x"}, "tests": [
        _entry(id="P-1", type="device", scope="END_TO_END",
               terminal_success=["done"], terminal_observed=["done"],
               acceptance=_rows("done", evidence="done was observed"),
               ended_by="natural")]}
    check("prose-only device E2E evidence cannot earn PASS",
          any("earns INCOMPLETE" in p for p in validate(prose, reqs)))
    backed = {"meta": {"updated": "x"}, "tests": [
        _entry(id="P-2", type="device", scope="END_TO_END",
               terminal_success=["done"], terminal_observed=["done"],
               acceptance=_rows("done", evidence="log:NovaVoice/done@12:00"),
               ended_by="natural")]}
    check("artifact-backed device E2E evidence can earn PASS",
          validate(backed, reqs) == [])

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
    ap.add_argument("--local-device", action="store_true",
                    help="regenerate LOCAL_DEVICE_REQUIRED.md (physical-device batch)")
    ap.add_argument("--work", action="store_true", help="autonomous tests that are not settled")
    ap.add_argument("--verdict", metavar="ID",
                    help="verdict the evidence for one entry actually earns")
    ap.add_argument("--coverage",
                    help="per-requirement scope coverage", action="store_true")
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

    if args.verdict:
        matches = [e for e in doc["tests"] if e["id"] == args.verdict]
        if not matches:
            print("no such test: %s" % args.verdict)
            return 1
        print(verdict_report(matches[0]))
        return 0

    if args.coverage:
        cov = coverage(doc)
        if not cov:
            print("no requirement declares above-COMPONENT required_scope")
            return 0
        print("Requirement | Required | Passing cover | Queued human | Verdict")
        print("--- | --- | --- | --- | ---")
        for key, row in cov.items():
            print("%s | %s | %s | %s | %s" % (
                key, row["required_scope"], ", ".join(row["passing"]) or "—",
                ", ".join(row["queued_human"]) or "—", row["verdict"]))
        return 0

    if args.status:
        print("wrote %s" % write(STATUS_DOC, status_doc(doc)))
        return 0

    if args.packet:
        print("wrote %s" % write(PACKET_DOC, packet_doc(doc)))
        print("wrote %s" % write(LOCAL_DEVICE_DOC, local_device_doc(doc)))
        return 0

    if args.local_device:
        print("wrote %s" % write(LOCAL_DEVICE_DOC, local_device_doc(doc)))
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
