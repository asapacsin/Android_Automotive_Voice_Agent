"""Acceptance scopes and verdict validation — one framework for every flow.

The failure this exists to prevent: the 0.6.4 navigation run showed healthy launch, route
resolution, guidance, HUD, traffic-light icons and a clean manual stop, and was reported as a
stable navigation baseline — while the recording never reached the destination (remaining
distance jumped from ~15 m back to ~3.7 km near the end). Intermediate health was allowed to
stand in for end-to-end success.

Rules, all enforced by :func:`evaluate`:

* Every scenario declares a scope: COMPONENT, INTERMEDIATE_FLOW or END_TO_END. A result may
  only claim success at its declared scope — never above it.
* END_TO_END requires observed terminal success: every ``terminal_success`` state seen, the
  run ended naturally (``ended_by == "natural"``), and no unexplained regressions.
* MANUAL_STOP_CANNOT_SATISFY_A_NATURAL_TERMINATION_REQUIREMENT: arrival, playback
  completion, call completion, task completion, automatic timeout/recovery — if the scenario
  is about a natural terminal state, a manual stop proves the terminal was NOT demonstrated.
* Evidence completeness: every required acceptance criterion needs direct observed evidence.
  No inference from partial health ("guidance worked, therefore navigation works").
* Evidence provenance: for device flows and E2E verdicts, "observed" must cite an actual
  artifact — `log:<tag>/<pattern>[@range]`, `video:<file>[@range]`, `xml:<file>#<test>`
  or `artifact:<name>` — so a CLAIM ABOUT EVIDENCE ("arrival callback observed") can never
  pass as a REFERENCE TO ACTUAL EVIDENCE. Absence claims (observed=false) may use prose:
  there is no line to cite for something that never happened.
* Verdict vocabulary: PASS (every required criterion satisfied), FAIL (an explicit
  criterion violated), INCOMPLETE (ended before success/failure could be proven — never a
  PASS), BLOCKED (external dependency), PARTIAL_PASS (explicitly scoped subtests only).

This module is deliberately product-neutral: no navigation imports. Navigation specifics
(minimum E2E criteria) live in :data:`MIN_CRITERIA`, which other capabilities extend the
same way. Worked examples: voice command (heard -> tool executed -> observable result),
phone call (request -> dialling -> connected/failed -> final state), music (request ->
playback starts -> expected state), AC (request -> command -> state reflects value).

    python scripts/acceptance.py --selftest
"""

import argparse
import re

SCOPES = ("COMPONENT", "INTERMEDIATE_FLOW", "END_TO_END")
SCOPE_RANK = {name: rank for rank, name in enumerate(SCOPES)}

VERDICTS = ("PASS", "FAIL", "INCOMPLETE", "BLOCKED", "PARTIAL_PASS")

ENDED_BY = ("natural", "manual", "timeout", "crash")

# An evidence string carries provenance when it names the artifact it comes from: a device
# log tag plus grep-able pattern, a video file, a structured result plus assertion id, or a
# named runtime artifact. Ranges (@12:28-12:34, @~end) are encouraged but optional — the
# tag/file/id is what makes the reference checkable. Deliberately light: the goal is to
# separate references from claims, not to cryptographically verify them.
PROVENANCE_RE = re.compile(
    r"\b(?:log:[^\s|/]+/[^\s|]+|video:[^\s|]+|xml:[^\s|]+#[^\s|]+|artifact:[^\s|]+)")


def has_provenance(evidence):
    """True when the string references an actual evidence artifact, not just prose."""
    return bool(evidence and PROVENANCE_RE.search(str(evidence)))

# Minimum acceptance criteria for END_TO_END device verdicts, per capability. Generic
# mechanism; the navigation row comes from the 0.6.4 post-mortem. Other capabilities add
# their own rows instead of inventing a second framework.
MIN_CRITERIA = {
    "navigation": (
        "navigation_start",
        "route_resolved",
        "guidance_progress",
        "near_destination",
        "arrival_callback",
        "arrival_handling",
        "automatic_session_end",
    ),
}


def claim_ok(evidence_scope, claimed_scope):
    """A result may claim its own scope or anything narrower — never wider."""
    return SCOPE_RANK[evidence_scope] >= SCOPE_RANK[claimed_scope]


def _row_result(row, require_provenance=False):
    """One acceptance-matrix row verdict: "pass", "fail", "gap" or "info"."""
    required = bool(row.get("required"))
    observed = row.get("observed")  # True seen / False violated / None never measured
    evidence = row.get("evidence") or ""
    if not required:
        return "info"
    if observed is False:
        return "fail"
    if observed is None:
        return "gap"
    if not str(evidence).strip():
        return "gap"
    if require_provenance and not has_provenance(evidence):
        return "gap"
    return "pass"


def acceptance_table(rows, require_provenance=False):
    """The Criterion | Required | Observed | Evidence | Result matrix (§4)."""
    lines = ["Criterion | Required | Observed | Evidence | Result",
             "--- | --- | --- | --- | ---"]
    for row in rows or []:
        lines.append("%s | %s | %s | %s | %s" % (
            row.get("criterion", "?"),
            "yes" if row.get("required") else "no",
            {True: "yes", False: "NO", None: "—"}.get(row.get("observed"), "?"),
            (row.get("evidence") or "—").replace("|", "\\|"),
            _row_result(row, require_provenance).upper(),
        ))
    return "\n".join(lines)


def evaluate(scope, rows=(), terminal_success=(), terminal_observed=(),
             failure_observed=(), ended_by=None, regressions=(),
             require_provenance=False):
    """Decide PASS / FAIL / INCOMPLETE from observed evidence. Never infers.

    With ``require_provenance`` (device flows and E2E verdicts), an observed=True row
    whose evidence is bare prose is a gap, not a pass: it claims evidence exists without
    referencing any. Returns ``{"verdict": ..., "reasons": [...], "table": ...}``.
    """
    rows = list(rows or [])
    terminal_success = tuple(terminal_success or ())
    terminal_observed = tuple(terminal_observed or ())
    failure_observed = tuple(failure_observed or ())
    regressions = list(regressions or [])
    reasons = []
    table = acceptance_table(rows, require_provenance)

    if scope not in SCOPES:
        return {"verdict": "INCOMPLETE",
                "reasons": ["no declared scope - a result without a scope claims nothing"],
                "table": table}

    results = [_row_result(row, require_provenance) for row in rows]
    if "fail" in results:
        bad = sorted({row.get("criterion", "?") for row, res in zip(rows, results)
                      if res == "fail"})
        reasons.append("required criteria violated: %s" % ", ".join(bad))
    if regressions:
        reasons.append("unexplained regressions observed: %s" % "; ".join(regressions))
    if failure_observed:
        reasons.append("failure terminals observed: %s" % ", ".join(failure_observed))
    if ended_by == "crash":
        reasons.append("run ended in a crash")
    if reasons:
        return {"verdict": "FAIL", "reasons": reasons, "table": table}

    # No violation — but PASS still has to be earned with complete terminal evidence.
    if "gap" in results:
        missing = sorted({row.get("criterion", "?") for row, res in zip(rows, results)
                          if res == "gap"})
        if require_provenance:
            bare = sorted({row.get("criterion", "?") for row, res in zip(rows, results)
                           if res == "gap" and row.get("observed") is True
                           and str(row.get("evidence") or "").strip()})
            if bare:
                reasons.append("prose-only evidence, no artifact reference: %s"
                               % ", ".join(bare))
        reasons.append("required criteria without direct evidence: %s" % ", ".join(missing))
    if scope == "END_TO_END":
        if not terminal_success:
            reasons.append("END_TO_END with no declared terminal success states")
        else:
            missing = [s for s in terminal_success if s not in terminal_observed]
            if missing:
                reasons.append("terminal success not observed: %s" % ", ".join(missing))
        if ended_by != "natural":
            reasons.append(
                "ended_by=%s: a manual stop, timeout or missing ending cannot satisfy "
                "a natural termination requirement" % (ended_by,))
    elif scope == "INTERMEDIATE_FLOW":
        if ended_by not in ("natural", "manual", "timeout"):
            reasons.append("ended_by=%s is not a known run ending" % (ended_by,))
    if reasons:
        return {"verdict": "INCOMPLETE", "reasons": reasons, "table": table}
    return {"verdict": "PASS", "reasons": [], "table": table}


def detect_terminal_regression(samples, near_m=50.0, jump_m=500.0, arrived=False):
    """Near-terminal anomaly guard (§7), product-neutral.

    ``samples``: sequence of dicts with ``remaining_m`` (float) and optional
    ``route_id`` / ``note``. If progress comes within ``near_m`` of the terminal and a
    later sample jumps back up by more than ``jump_m`` before arrival, returns an
    anomaly record; otherwise None. Thresholds are anomaly evidence only — success must
    still come from the authoritative arrival lifecycle.
    """
    if arrived:
        return None
    approached = None
    for index, sample in enumerate(samples or []):
        try:
            remaining = float(sample["remaining_m"])
        except (KeyError, TypeError, ValueError):
            continue
        if approached is None:
            if remaining <= near_m:
                approached = {"index": index, "remaining_m": remaining,
                              "route_id": sample.get("route_id")}
            continue
        if remaining >= approached["remaining_m"] + jump_m:
            return {
                "type": "TERMINAL_REGRESSION",
                "previous_remaining_m": approached["remaining_m"],
                "new_remaining_m": remaining,
                "route_before": approached["route_id"],
                "route_after": sample.get("route_id"),
                "approach_index": approached["index"],
                "regress_index": index,
                "note": sample.get("note", ""),
            }
    return None


# ---- selftest ---------------------------------------------------------------------------------


def _obs(criterion, observed, evidence="seen"):
    return {"criterion": criterion, "required": True, "observed": observed,
            "evidence": evidence}


def selftest():
    results = []

    def check(name, ok):
        results.append((name, ok))

    # Scope gating: intermediate evidence can never claim END_TO_END.
    check("intermediate evidence may claim intermediate",
          claim_ok("INTERMEDIATE_FLOW", "INTERMEDIATE_FLOW"))
    check("intermediate evidence may claim component",
          claim_ok("INTERMEDIATE_FLOW", "COMPONENT"))
    check("intermediate evidence may NOT claim end-to-end",
          not claim_ok("INTERMEDIATE_FLOW", "END_TO_END"))
    check("component evidence may NOT claim flow or e2e",
          not claim_ok("COMPONENT", "INTERMEDIATE_FLOW")
          and not claim_ok("COMPONENT", "END_TO_END"))

    # The 0.6.4 shape: healthy mid-route run, manual stop, no arrival.
    mid_route = [_obs("navigation_start", True), _obs("route_resolved", True),
                 _obs("guidance_progress", True)]
    e2e = evaluate("END_TO_END", mid_route, terminal_success=("arrival_callback",),
                   terminal_observed=(), ended_by="manual")
    check("0.6.4 shape is not an E2E PASS", e2e["verdict"] != "PASS")
    check("0.6.4 shape is INCOMPLETE", e2e["verdict"] == "INCOMPLETE")
    check("manual stop is named in the reasons",
          any("manual stop" in r for r in e2e["reasons"]))
    flow = evaluate("INTERMEDIATE_FLOW", mid_route, ended_by="manual")
    check("same evidence IS an intermediate PASS", flow["verdict"] == "PASS")

    # Terminal success must actually be observed.
    no_terminal = evaluate("END_TO_END", mid_route, terminal_success=("arrival_callback",),
                           terminal_observed=(), ended_by="natural")
    check("natural ending without the terminal state is INCOMPLETE, not PASS",
          no_terminal["verdict"] == "INCOMPLETE")

    # A full terminal observation passes.
    full = evaluate(
        "END_TO_END",
        [_obs(c, True, "log:%s" % c) for c in MIN_CRITERIA["navigation"]],
        terminal_success=("arrival_callback", "arrival_handling",
                          "automatic_session_end"),
        terminal_observed=("arrival_callback", "arrival_handling",
                           "automatic_session_end"),
        ended_by="natural")
    check("observed terminal success passes", full["verdict"] == "PASS")

    # Explicit violations fail, gaps do not pass.
    violated = evaluate("COMPONENT", [_obs("no_crash", False, "FATAL in log")])
    check("a violated required criterion is FAIL", violated["verdict"] == "FAIL")
    gapped = evaluate("COMPONENT", [_obs("no_crash", None, "")])
    check("an unmeasured required criterion is INCOMPLETE, not PASS",
          gapped["verdict"] == "INCOMPLETE")
    no_evidence = evaluate("COMPONENT", [_obs("no_crash", True, "")])
    check("observed-but-unevidenced is INCOMPLETE, not PASS",
          no_evidence["verdict"] == "INCOMPLETE")
    check("log tag + pattern carries provenance",
          has_provenance("log:NovaVoice/nav_arrived@12:31:02"))
    check("video file carries provenance",
          has_provenance("video:nav_baseline_0_6_4.mp4@~end remaining ~15 m"))
    check("structured result + assertion id carries provenance",
          has_provenance("xml:TEST-Foo.xml#arrivalCallback observed"))
    check("named runtime artifact carries provenance",
          has_provenance("artifact:arrival-trace.json states=STARTED,ARRIVED"))
    check("bare prose carries no provenance",
          not has_provenance("arrival callback observed"))
    prose_e2e = evaluate(
        "END_TO_END", [_obs("arrival_callback", True, "arrival callback observed")],
        terminal_success=("arrival_callback",), terminal_observed=("arrival_callback",),
        ended_by="natural", require_provenance=True)
    check("observed=true + prose-only evidence cannot earn E2E PASS",
          prose_e2e["verdict"] == "INCOMPLETE"
          and any("prose-only" in r for r in prose_e2e["reasons"]))
    backed_e2e = evaluate(
        "END_TO_END",
        [_obs("arrival_callback", True, "log:NovaVoice/nav_arrived@12:31:02")],
        terminal_success=("arrival_callback",), terminal_observed=("arrival_callback",),
        ended_by="natural", require_provenance=True)
    check("artifact-backed terminal evidence can earn E2E PASS",
          backed_e2e["verdict"] == "PASS")
    check("provenance is not demanded without the flag",
          evaluate("COMPONENT", [_obs("no_crash", True, "looked, nothing bad")],
                   require_provenance=False)["verdict"] == "PASS")
    regressed = evaluate("COMPONENT", [_obs("no_crash", True)],
                         regressions=["15 m -> 3.7 km near destination"])
    check("an unexplained regression blocks PASS", regressed["verdict"] == "FAIL")
    crashed = evaluate("INTERMEDIATE_FLOW", mid_route, ended_by="crash")
    check("a crash is FAIL", crashed["verdict"] == "FAIL")

    # Near-terminal regression detector.
    hit = detect_terminal_regression(
        [{"remaining_m": 4000}, {"remaining_m": 500}, {"remaining_m": 15},
         {"remaining_m": 3700, "route_id": "r2", "note": "jump"}])
    check("15 m -> 3.7 km is flagged",
          hit is not None and hit["previous_remaining_m"] == 15
          and hit["new_remaining_m"] == 3700)
    check("monotonic progress is not flagged",
          detect_terminal_regression(
              [{"remaining_m": 4000}, {"remaining_m": 500}, {"remaining_m": 12}],
              arrived=True) is None)
    check("a run that never nears the terminal is not flagged",
          detect_terminal_regression([{"remaining_m": 4000}, {"remaining_m": 3900}]) is None)
    check("small wobble near the terminal is not flagged",
          detect_terminal_regression([{"remaining_m": 40}, {"remaining_m": 60}]) is None)

    width = max(len(n) for n, _ in results)
    for name, ok in results:
        print("  %-4s %s" % ("ok" if ok else "FAIL", name.ljust(width)))
    failed = [n for n, ok in results if not ok]
    print("\n%s (%d checks)" % ("SELFTEST PASSED" if not failed else "SELFTEST FAILED",
                               len(results)))
    return 1 if failed else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    ap.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
