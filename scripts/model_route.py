"""Hard-gate Cursor model routing for this repository.

    python scripts/model_route.py --eval obvious_local_fix=true
    python scripts/model_route.py --eval architecture_decision=true --action continue
    python scripts/model_route.py --action terminate-request
    python scripts/model_route.py --action terminate-review --verdict CONTINUE --next-action "…" --role grok-high
    python scripts/model_route.py --action terminate-consume --token <id>
    python scripts/model_route.py --selftest

`--eval` accepts `k=v,k=v` (PowerShell-safe) or a JSON object if the shell will not strip quotes.
`--eval-file path.json` always works for JSON.

The always-apply rule [.cursor/rules/hybrid-model-routing.mdc] tells agents they must use this
classifier. This script is the enforcement: DEFAULT may not `--action continue` through
GROK_REQUIRED, and a missing/unpinned Grok agent fails closed as BLOCKED_GROK_UNAVAILABLE.

**No agent may authorize its own termination.** Any terminal-looking state (frontier exhausted,
blocked on human/external, task/milestone done, standing by, final summary) requires a
MAX_GROK (`grok-4.7-xhigh`) hostile termination review. Only
`TERMINAL_APPROVED` + matching fingerprints + single-use consume may end a run. Anything else
continues or fails closed — never silent DEFAULT self-approval.

It does not replace AGENTS.md, architecture ownership, or the human gate. It only splits Cursor
labor. Trigger letters A–F are the hard-gate names; H1–H8 are the same conditions (not a second
list).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import uuid
from copy import deepcopy
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GROK_AGENT = os.path.join(REPO, ".cursor", "agents", "grok-high.md")
GROK_PIN = "model: grok-4.7-xhigh"
DEFAULT_PIN = "model: composer-2.5[fast=false]"
DEFAULT_AGENTS = (
    os.path.join(REPO, ".cursor", "agents", "implementer.md"),
    os.path.join(REPO, ".cursor", "agents", "repo-explorer.md"),
)
DEFAULT_LEDGER = os.path.join(REPO, ".local-agent-memory", "model_route_ledger.json")

ROUTE_DEFAULT = "DEFAULT"
ROUTE_GROK = "GROK_REQUIRED"
ROUTE_BLOCKED = "BLOCKED_GROK_UNAVAILABLE"
ROUTE_TERMINATION_REVIEW = "TERMINATION_REVIEW_REQUIRED"

STATUS_CONTINUED = "continued"
STATUS_DELEGATED = "delegated"
STATUS_BLOCKED = "blocked"
STATUS_TERMINATION_DENIED = "termination_denied"
STATUS_TERMINATION_CONTINUE = "termination_continue"
STATUS_TERMINATION_APPROVED = "termination_approved"
STATUS_TERMINATION_CONSUMED = "termination_consumed"

MODE_PLAN = "GROK_HIGH_PLAN_THEN_DEFAULT_EXECUTE"
MODE_REVIEW = "GROK_HIGH"
MODE_TERMINATION = "MAX_GROK_TERMINATION_REVIEW"

VERDICT_CONTINUE = "CONTINUE"
VERDICT_TERMINAL_APPROVED = "TERMINAL_APPROVED"
VERDICT_REVIEW_UNAVAILABLE = "REVIEW_UNAVAILABLE"
VALID_TERMINATION_VERDICTS = (
    VERDICT_CONTINUE,
    VERDICT_TERMINAL_APPROVED,
    VERDICT_REVIEW_UNAVAILABLE,
)

# Exit: 0 ok (incl. terminate-consume allowed), 1 usage, 2 GROK_REQUIRED refused continue,
# 3 Grok unavailable, 4 termination denied / fail-closed, 5 termination CONTINUE (resume DEFAULT).
EXIT_OK = 0
EXIT_USAGE = 1
EXIT_MUST_DELEGATE = 2
EXIT_GROK_UNAVAILABLE = 3
EXIT_TERMINATION_DENIED = 4
EXIT_TERMINATION_CONTINUE = 5

BOOL_FEATURES = (
    "architecture_decision",
    "major_design_choice",
    "new_subsystem",
    "interface_or_contract_change",
    "multiple_implementation_strategies",
    "cross_module_change",
    "repo_wide_refactor_correctness_risk",
    "data_migration",
    "concurrency_async_threading",
    "lifecycle_or_state_machine",
    "security_auth_permissions",
    "destructive_or_irreversible",
    "release_or_production_critical",
    "root_cause_unclear_after_inspection",
    "contradictory_or_nonlocal_symptoms",
    "likely_race_hidden_state_or_subsystem_interaction",
    "inspection_complete",
    "no_high_confidence_path",
    "requirements_ambiguous_high_rework",
    "several_plausible_hypotheses",
    "complex_algorithm_choice",
    "nontrivial_optimization",
    "difficult_performance_diagnosis",
    "complicated_dependency_interaction",
    "protocol_or_spec_reasoning",
    "scientific_statistical_reasoning",
    "critical_review_of_substantial_change",
    "acceptance_or_governance_failure",
    "mechanical_only",
    "docs_only",
    "obvious_local_fix",
    "deterministic_low_uncertainty",
    "plan_already_approved",
)

INT_FEATURES = ("failed_fix_attempts",)

# Canonical scenarios the policy must keep getting right. Used by --selftest and the
# architecture test. Feature bags are what an honest executor would pass; they are not
# scored by prompt length, file count, or runtime.
SCENARIOS = (
    (
        "simple_one_file_obvious_fix",
        {"obvious_local_fix": True, "deterministic_low_uncertainty": True},
        ROUTE_DEFAULT,
    ),
    (
        "deterministic_unit_test_repair",
        {"obvious_local_fix": True, "deterministic_low_uncertainty": True},
        ROUTE_DEFAULT,
    ),
    (
        "architecture_redesign",
        {
            "architecture_decision": True,
            "major_design_choice": True,
            "new_subsystem": True,
            "multiple_implementation_strategies": True,
        },
        ROUTE_GROK,
    ),
    (
        "bug_survives_two_fixes",
        {"inspection_complete": True, "failed_fix_attempts": 2},
        ROUTE_GROK,
    ),
    (
        "repo_wide_mechanical_rename",
        {
            "cross_module_change": True,
            "mechanical_only": True,
            "deterministic_low_uncertainty": True,
        },
        ROUTE_DEFAULT,
    ),
    (
        "concurrency_lifecycle_unclear",
        {
            "concurrency_async_threading": True,
            "lifecycle_or_state_machine": True,
            "inspection_complete": True,
            "root_cause_unclear_after_inspection": True,
            "likely_race_hidden_state_or_subsystem_interaction": True,
        },
        ROUTE_GROK,
    ),
    (
        "release_critical_contract",
        {
            "interface_or_contract_change": True,
            "release_or_production_critical": True,
        },
        ROUTE_GROK,
    ),
    (
        "long_documentation_generation",
        {"docs_only": True},
        ROUTE_DEFAULT,
    ),
)


def _truthy(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def normalize_features(raw):
    raw = raw or {}
    out = {name: False for name in BOOL_FEATURES}
    out["failed_fix_attempts"] = 0
    for key, value in raw.items():
        if key in BOOL_FEATURES:
            out[key] = _truthy(value)
        elif key in INT_FEATURES:
            try:
                out[key] = int(value)
            except (TypeError, ValueError):
                out[key] = 0
    if out["failed_fix_attempts"] < 0:
        out["failed_fix_attempts"] = 0
    return out


def _model_frontmatter_lines(body: str):
    """Yield `model:` lines from Cursor agent frontmatter (between --- fences)."""
    if not body.startswith("---"):
        return
    end = body.find("\n---", 3)
    block = body[3:end] if end != -1 else body[3:]
    for line in block.splitlines():
        stripped = line.strip()
        if stripped.startswith("model:"):
            yield stripped


def _is_fast_model_pin(model_line: str) -> bool:
    """True if the pin is any Fast / *-fast labor slug (Composer or Grok)."""
    value = model_line.split(":", 1)[-1].strip().lower()
    if "fast=false" in value:
        return False
    return value.endswith("-fast") or value == "composer-2.5-fast" or " fast" in value


def probe_no_fast_pins(repo=REPO):
    """Fail closed if any labor agent is pinned to a Fast model."""
    paths = [
        os.path.join(repo, ".cursor", "agents", "grok-high.md"),
        os.path.join(repo, ".cursor", "agents", "implementer.md"),
        os.path.join(repo, ".cursor", "agents", "repo-explorer.md"),
    ]
    for path in paths:
        if not os.path.isfile(path):
            return False, f"missing {os.path.relpath(path, repo)}"
        body = open(path, encoding="utf-8").read()
        for line in _model_frontmatter_lines(body):
            if _is_fast_model_pin(line):
                return False, f"{os.path.basename(path)} pins Fast model: {line}"
    return True, "no Fast model pins on labor agents"


def probe_grok(repo=REPO):
    path = os.path.join(repo, ".cursor", "agents", "grok-high.md")
    if not os.path.isfile(path):
        return False, "missing .cursor/agents/grok-high.md"
    body = open(path, encoding="utf-8").read()
    if GROK_PIN not in body:
        return False, "grok-high is not pinned to grok-4.7-xhigh"
    ok_fast, why_fast = probe_no_fast_pins(repo)
    if not ok_fast:
        return False, why_fast
    return True, "grok-high pinned to grok-4.7-xhigh"


def collect_triggers(features):
    """Return ordered unique trigger tokens like ('A', 'H1').

    Soft bulk (many files, mechanical rename, docs) never becomes GROK on its own.
    Hard letters still win if they are actually present.
    """
    f = features
    mechanical = (
        f["mechanical_only"]
        or f["docs_only"]
        or f["deterministic_low_uncertainty"]
        or f["obvious_local_fix"]
    )
    hits = []

    if any(
        f[k]
        for k in (
            "architecture_decision",
            "major_design_choice",
            "new_subsystem",
            "interface_or_contract_change",
            "multiple_implementation_strategies",
        )
    ):
        hits.append(("A", "H1"))

    b_hard = any(
        [
            f["repo_wide_refactor_correctness_risk"],
            f["data_migration"],
            f["security_auth_permissions"],
            f["destructive_or_irreversible"],
            f["release_or_production_critical"],
            f["concurrency_async_threading"] and not f["plan_already_approved"],
            f["lifecycle_or_state_machine"] and not f["plan_already_approved"],
            f["cross_module_change"] and not mechanical,
        ]
    )
    if b_hard:
        hits.append(("B", "H4"))

    inspected = f["inspection_complete"]
    if inspected and any(
        [
            f["root_cause_unclear_after_inspection"],
            f["contradictory_or_nonlocal_symptoms"],
            f["likely_race_hidden_state_or_subsystem_interaction"],
        ]
    ):
        hits.append(("C", "H2"))
    if f["failed_fix_attempts"] >= 2:
        hits.append(("C", "H3"))

    if any(
        f[k]
        for k in (
            "no_high_confidence_path",
            "requirements_ambiguous_high_rework",
            "several_plausible_hypotheses",
        )
    ):
        hits.append(("D", "H5"))

    if any(
        f[k]
        for k in (
            "complex_algorithm_choice",
            "nontrivial_optimization",
            "difficult_performance_diagnosis",
            "complicated_dependency_interaction",
            "protocol_or_spec_reasoning",
            "scientific_statistical_reasoning",
        )
    ):
        hits.append(("E", "H8"))

    if f["critical_review_of_substantial_change"]:
        hits.append(("F", "H7"))

    if f["acceptance_or_governance_failure"]:
        hits.append(("H6", "H6"))

    # An already-approved plan is DEFAULT work unless a *new* mid-task or governance trigger.
    if f["plan_already_approved"]:
        hits = [h for h in hits if h[0] in {"C", "F", "H6"}]

    # Docs-only generation is never Grok, even if the writer also ticked "many modules".
    if f["docs_only"]:
        hits = [h for h in hits if h[0] not in {"B"}]

    # Deduplicate while preserving order.
    seen = set()
    ordered = []
    for item in hits:
        if item not in seen:
            seen.add(item)
            ordered.append(item)
    return ordered


def _trigger_names(hits):
    names = []
    seen = set()
    for letter, hcode in hits:
        for token in (letter, hcode):
            if token not in seen:
                seen.add(token)
                names.append(token)
    return names


def _reason(hits):
    labels = {
        "A": "architecture / design",
        "B": "high-impact change",
        "C": "difficult debugging",
        "D": "high uncertainty",
        "E": "reasoning-intensive work",
        "F": "critical review",
        "H6": "acceptance / governance failure",
    }
    letters = []
    seen = set()
    for letter, _h in hits:
        if letter not in seen:
            seen.add(letter)
            letters.append(letter)
    if not letters:
        return "no hard-gate trigger"
    return "; ".join(labels.get(letter, letter) for letter in letters)


def _delegation_mode(hits):
    letters = {letter for letter, _h in hits}
    hard_plan = letters & {"A", "B", "C", "D", "E"}
    if not hard_plan and letters & {"F", "H6"}:
        return MODE_REVIEW
    return MODE_PLAN


def load_ledger(path):
    if not path or not os.path.isfile(path):
        return {"issues": {}}
    try:
        data = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError):
        return {"issues": {}}
    if not isinstance(data, dict):
        return {"issues": {}}
    issues = data.get("issues")
    if not isinstance(issues, dict):
        data["issues"] = {}
    return data


def save_ledger(path, data):
    directory = os.path.dirname(path)
    if directory and not os.path.isdir(directory):
        os.makedirs(directory, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, sort_keys=True)
        handle.write("\n")
    os.replace(tmp, path)


def _drop_handled(hits, ledger, issue_id, evidence_hash):
    """Same issue + same evidence already gated → do not re-escalate.

    A new independent trigger, or a new evidence hash, may re-open the gate.
    """
    if not issue_id:
        return hits, None
    entry = (ledger or {}).get("issues", {}).get(issue_id)
    if not entry:
        return hits, None
    status = entry.get("status")
    if status not in {"pending", "handled"}:
        return hits, None
    prior_hash = entry.get("evidence_hash") or ""
    incoming = evidence_hash or prior_hash
    if incoming != prior_hash:
        return hits, entry
    handled = set(entry.get("triggers") or [])
    remaining = [h for h in hits if h[0] not in handled and h[1] not in handled]
    return remaining, entry


def evaluate(
    features,
    grok_available=True,
    grok_reason="",
    role="default",
    already_grok=False,
    ledger=None,
    issue_id=None,
    evidence_hash="",
    action="eval",
):
    features = normalize_features(features)
    hits = collect_triggers(features)
    prior = None
    if action != "resume":
        hits, prior = _drop_handled(hits, ledger, issue_id, evidence_hash)

    # Inside grok-high: never delegate to yourself.
    if role == "grok-high":
        return {
            "route": ROUTE_DEFAULT,
            "trigger": "-",
            "triggers": [],
            "reason": "grok-high must not recursively delegate; return the contract",
            "delegate": "-",
            "status": STATUS_CONTINUED,
            "delegation_mode": "-",
            "already_gated": bool(prior),
        }

    if not hits:
        reason = "no hard-gate trigger"
        if prior and prior.get("status") == "handled":
            reason = "gate already handled for this issue; resume DEFAULT"
        elif prior and prior.get("status") == "pending":
            reason = "gate already delegated for this issue; wait for grok-high"
        return {
            "route": ROUTE_DEFAULT,
            "trigger": "-",
            "triggers": [],
            "reason": reason,
            "delegate": "-",
            "status": STATUS_CONTINUED,
            "delegation_mode": "-",
            "already_gated": bool(prior),
        }

    names = _trigger_names(hits)
    reason = _reason(hits)
    mode = _delegation_mode(hits)

    if not grok_available:
        return {
            "route": ROUTE_BLOCKED,
            "trigger": ",".join(names),
            "triggers": names,
            "reason": "%s; fail-closed (%s)"
            % (reason, grok_reason or "Grok unavailable"),
            "delegate": "-",
            "status": STATUS_BLOCKED,
            "delegation_mode": mode,
            "already_gated": False,
        }

    if already_grok:
        return {
            "route": ROUTE_GROK,
            "trigger": ",".join(names),
            "triggers": names,
            "reason": reason + " (parent is already Grok; do not launch grok-high)",
            "delegate": "this-chat",
            "status": STATUS_CONTINUED,
            "delegation_mode": mode,
            "already_gated": False,
        }

    status = STATUS_DELEGATED
    if action == "continue":
        status = STATUS_BLOCKED
    return {
        "route": ROUTE_GROK,
        "trigger": ",".join(names),
        "triggers": names,
        "reason": reason,
        "delegate": "grok-high",
        "status": status,
        "delegation_mode": mode,
        "already_gated": False,
    }


def format_text(decision):
    lines = [
        "MODEL_ROUTING",
        "route: %s" % decision["route"],
        "trigger: %s" % decision.get("trigger", "-"),
        "reason: %s" % decision["reason"],
        "delegate: %s" % decision.get("delegate", "-"),
        "status: %s" % decision["status"],
    ]
    if decision.get("termination_verdict"):
        lines.append("termination_verdict: %s" % decision["termination_verdict"])
    if decision.get("next_action"):
        lines.append("next_action: %s" % decision["next_action"])
    if decision.get("fingerprint"):
        lines.append("fingerprint: %s" % decision["fingerprint"])
    if decision.get("token"):
        lines.append("token: %s" % decision["token"])
    if decision.get("delegation_mode"):
        lines.append("delegation_mode: %s" % decision["delegation_mode"])
    return "\n".join(lines)


# ---- termination hard gate (MAX_GROK; no self-authorization) ---------------------------------


def _sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def git_head_short(repo=REPO):
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=repo,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return out or "unknown"
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def git_dirty_digest(repo=REPO):
    """Fingerprint of the working tree (tracked + untracked paths/content names only)."""
    try:
        porcelain = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=repo,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        porcelain = "UNREADABLE"
    return _sha256_text(porcelain)


def frontier_digest(frontier):
    """Stable digest of the discovered work frontier / stop snapshot."""
    if frontier is None:
        frontier = {}
    if isinstance(frontier, list):
        payload = {
            "items": [
                {
                    "item": c.get("item"),
                    "priority": c.get("priority"),
                    "blocked_by": c.get("blocked_by"),
                    "summary": c.get("summary"),
                }
                for c in frontier
            ]
        }
    else:
        payload = {
            "AUTONOMOUS_ACTION_AVAILABLE": frontier.get("AUTONOMOUS_ACTION_AVAILABLE"),
            "HUMAN_ACTION_REQUIRED": frontier.get("HUMAN_ACTION_REQUIRED"),
            "STOP_REASON": frontier.get("STOP_REASON"),
            "BLOCKING_DEPENDENCY": frontier.get("BLOCKING_DEPENDENCY"),
            "NEXT_ACTION": frontier.get("NEXT_ACTION"),
            "actionable_count": frontier.get("actionable_count"),
            "blocked_count": frontier.get("blocked_count"),
            "actionable_items": frontier.get("actionable_items") or frontier.get("items") or [],
        }
    return _sha256_text(json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str))


def compute_termination_fingerprint(head=None, dirty_digest=None, frontier=None, repo=REPO):
    """State-bound fingerprint: HEAD + dirty tree + work frontier.

    Any repository/frontier change invalidates a prior TERMINAL_APPROVED.
    """
    if head is None:
        head = git_head_short(repo)
    if dirty_digest is None:
        dirty_digest = git_dirty_digest(repo)
    f_digest = frontier_digest(frontier if frontier is not None else {})
    blob = json.dumps(
        {"head": head, "dirty": dirty_digest, "frontier": f_digest},
        sort_keys=True,
    )
    return _sha256_text(blob)


def discover_frontier_snapshot(repo=REPO):
    """Best-effort live frontier for fingerprinting. Tests inject their own."""
    scripts = os.path.join(repo, "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    try:
        import discover_work

        found = discover_work.discover()
        stop = discover_work.decide(found)
        stop = discover_work.with_gate(stop, discover_work.human_gate())
        stop["actionable_items"] = [
            c["item"] for c in found if not c.get("blocked_by")
        ]
        stop["blocked_items"] = [c["item"] for c in found if c.get("blocked_by")]
        return stop
    except Exception as exc:
        return {
            "AUTONOMOUS_ACTION_AVAILABLE": "UNKNOWN",
            "STOP_REASON": "FRONTIER_UNREADABLE",
            "actionable_count": -1,
            "error": "%s: %r" % (type(exc).__name__, exc),
        }


def _termination_section(ledger):
    section = ledger.setdefault("termination", {})
    if not isinstance(section, dict):
        section = {}
        ledger["termination"] = section
    return section


def request_termination(
    fingerprint,
    grok_available=True,
    grok_reason="",
    role="default",
):
    """DEFAULT (or anyone) asks to end the run. Never self-approves.

    Always requires MAX_GROK termination review unless this call is already inside
    an authorized consume path (handled separately).
    """
    if not grok_available:
        return {
            "route": ROUTE_BLOCKED,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "termination requires MAX_GROK; fail-closed (%s)"
            % (grok_reason or "Grok unavailable"),
            "delegate": "-",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    # Even grok-high requesting terminate must not skip the structured review+consume path.
    return {
        "route": ROUTE_TERMINATION_REVIEW,
        "trigger": "TERMINATION",
        "triggers": ["TERMINATION"],
        "reason": "no agent may authorize its own termination; invoke MAX_GROK termination review",
        "delegate": "this-chat" if role == "grok-high" else "grok-high",
        "status": STATUS_TERMINATION_DENIED,
        "delegation_mode": MODE_TERMINATION,
        "termination_verdict": "",
        "fingerprint": fingerprint,
        "next_action": "",
        "token": "",
    }, EXIT_TERMINATION_DENIED


def _is_max_grok_reviewer(role, already_grok):
    return role == "grok-high" or already_grok


def submit_termination_review(
    verdict,
    fingerprint,
    current_fingerprint,
    next_action="",
    role="default",
    already_grok=False,
    grok_available=True,
    grok_reason="",
    actionable_count=0,
    ledger=None,
    ledger_path=None,
):
    """MAX_GROK returns CONTINUE | TERMINAL_APPROVED | REVIEW_UNAVAILABLE.

    Mechanically refuses TERMINAL_APPROVED when autonomous actionable work remains.
    DEFAULT cannot submit a review. Unavailable/malformed/stale → fail closed.
    """
    ledger = ledger if ledger is not None else {"issues": {}}
    verdict = (verdict or "").strip().upper()
    next_action = (next_action or "").strip()

    if not grok_available:
        return {
            "route": ROUTE_BLOCKED,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "termination review unavailable; fail-closed (%s)"
            % (grok_reason or "Grok unavailable"),
            "delegate": "-",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    if not _is_max_grok_reviewer(role, already_grok):
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "DEFAULT may not submit termination review; only MAX_GROK (grok-high / already-grok)",
            "delegate": "grok-high",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    if verdict not in VALID_TERMINATION_VERDICTS:
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "malformed termination verdict %r; fail-closed" % (verdict,),
            "delegate": "-",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    if not fingerprint or fingerprint != current_fingerprint:
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "stale or missing termination fingerprint; fail-closed",
            "delegate": "-",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    if verdict == VERDICT_REVIEW_UNAVAILABLE:
        section = _termination_section(ledger)
        section.pop("approval", None)
        section["last_review"] = {
            "verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": fingerprint,
            "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        if ledger_path:
            save_ledger(ledger_path, ledger)
        return {
            "route": ROUTE_BLOCKED,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "MAX_GROK reported REVIEW_UNAVAILABLE; termination forbidden",
            "delegate": "-",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    if verdict == VERDICT_CONTINUE:
        if not next_action or next_action.upper() == "NONE":
            return {
                "route": ROUTE_TERMINATION_REVIEW,
                "trigger": "TERMINATION",
                "triggers": ["TERMINATION"],
                "reason": "CONTINUE requires a concrete NEXT_ACTION; fail-closed",
                "delegate": "-",
                "status": STATUS_TERMINATION_DENIED,
                "delegation_mode": MODE_TERMINATION,
                "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
                "fingerprint": fingerprint,
                "next_action": "",
                "token": "",
            }, EXIT_TERMINATION_DENIED
        section = _termination_section(ledger)
        section.pop("approval", None)  # CONTINUE invalidates any unused approval
        section["last_continue"] = {
            "next_action": next_action,
            "fingerprint": fingerprint,
            "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        section["last_review"] = {
            "verdict": VERDICT_CONTINUE,
            "fingerprint": fingerprint,
            "next_action": next_action,
            "updated": section["last_continue"]["updated"],
        }
        if ledger_path:
            save_ledger(ledger_path, ledger)
        return {
            "route": ROUTE_DEFAULT,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "MAX_GROK CONTINUE — termination forbidden; resume DEFAULT",
            "delegate": "-",
            "status": STATUS_TERMINATION_CONTINUE,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_CONTINUE,
            "fingerprint": fingerprint,
            "next_action": next_action,
            "token": "",
        }, EXIT_TERMINATION_CONTINUE

    # TERMINAL_APPROVED — mechanical proof that no autonomous work remains.
    try:
        actionable = int(actionable_count)
    except (TypeError, ValueError):
        actionable = -1
    if actionable != 0:
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": (
                "TERMINAL_APPROVED rejected: actionable autonomous work remains "
                "(actionable_count=%s); a blocker narrows the frontier, it does not end the run"
                % actionable_count
            ),
            "delegate": "-",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_CONTINUE,
            "fingerprint": fingerprint,
            "next_action": "select independent authorized work from the frontier",
            "token": "",
        }, EXIT_TERMINATION_CONTINUE

    token = "term-" + uuid.uuid4().hex
    section = _termination_section(ledger)
    section["approval"] = {
        "token": token,
        "fingerprint": fingerprint,
        "status": "unused",
        "verdict": VERDICT_TERMINAL_APPROVED,
        "created": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    section["last_review"] = {
        "verdict": VERDICT_TERMINAL_APPROVED,
        "fingerprint": fingerprint,
        "token": token,
        "updated": section["approval"]["created"],
    }
    if ledger_path:
        save_ledger(ledger_path, ledger)
    return {
        "route": ROUTE_DEFAULT,
        "trigger": "TERMINATION",
        "triggers": ["TERMINATION"],
        "reason": "MAX_GROK TERMINAL_APPROVED — single-use consume required before ending the run",
        "delegate": "-",
        "status": STATUS_TERMINATION_APPROVED,
        "delegation_mode": MODE_TERMINATION,
        "termination_verdict": VERDICT_TERMINAL_APPROVED,
        "fingerprint": fingerprint,
        "next_action": "NONE",
        "token": token,
    }, EXIT_OK


def consume_termination_approval(
    token,
    current_fingerprint,
    ledger=None,
    ledger_path=None,
):
    """Single-use consume. Stale fingerprint or missing/used token → fail closed."""
    ledger = ledger if ledger is not None else {"issues": {}}
    section = _termination_section(ledger)
    approval = section.get("approval") or {}
    if not token or approval.get("token") != token:
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "termination token missing or unknown; fail-closed",
            "delegate": "grok-high",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED
    if approval.get("status") != "unused":
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "termination approval already consumed (single-use); fail-closed",
            "delegate": "grok-high",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED
    if approval.get("fingerprint") != current_fingerprint:
        section.pop("approval", None)
        if ledger_path:
            save_ledger(ledger_path, ledger)
        return {
            "route": ROUTE_TERMINATION_REVIEW,
            "trigger": "TERMINATION",
            "triggers": ["TERMINATION"],
            "reason": "stale TERMINAL_APPROVED after repo/frontier change; fail-closed",
            "delegate": "grok-high",
            "status": STATUS_TERMINATION_DENIED,
            "delegation_mode": MODE_TERMINATION,
            "termination_verdict": VERDICT_REVIEW_UNAVAILABLE,
            "fingerprint": current_fingerprint,
            "next_action": "",
            "token": "",
        }, EXIT_TERMINATION_DENIED

    approval["status"] = "consumed"
    approval["consumed"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    section["approval"] = approval
    # Single-use: remove so it cannot be reused even if status is ignored.
    section["consumed_approvals"] = section.get("consumed_approvals") or []
    section["consumed_approvals"].append(dict(approval))
    section.pop("approval", None)
    if ledger_path:
        save_ledger(ledger_path, ledger)
    return {
        "route": ROUTE_DEFAULT,
        "trigger": "TERMINATION",
        "triggers": ["TERMINATION"],
        "reason": "TERMINAL_APPROVED consumed; termination permitted for this fingerprint only",
        "delegate": "-",
        "status": STATUS_TERMINATION_CONSUMED,
        "delegation_mode": MODE_TERMINATION,
        "termination_verdict": VERDICT_TERMINAL_APPROVED,
        "fingerprint": current_fingerprint,
        "next_action": "NONE",
        "token": token,
    }, EXIT_OK


def apply_action(decision, action, ledger, ledger_path, issue_id, evidence_hash, hits_names):
    """Mutate ledger for delegate/resume. Return (decision, exit_code)."""
    route = decision["route"]

    if action == "continue":
        if route == ROUTE_GROK and decision["delegate"] != "this-chat":
            decision = dict(decision)
            decision["status"] = STATUS_BLOCKED
            decision["reason"] = (
                decision["reason"]
                + "; ordinary executor must not continue - delegate grok-high"
            )
            return decision, EXIT_MUST_DELEGATE
        if route == ROUTE_BLOCKED:
            return decision, EXIT_GROK_UNAVAILABLE
        if route == ROUTE_TERMINATION_REVIEW:
            decision = dict(decision)
            decision["status"] = STATUS_TERMINATION_DENIED
            decision["reason"] = (
                decision.get("reason", "")
                + "; DEFAULT may not continue past termination gate"
            )
            return decision, EXIT_TERMINATION_DENIED
        return decision, EXIT_OK

    if action == "delegate":
        if route == ROUTE_BLOCKED:
            return decision, EXIT_GROK_UNAVAILABLE
        if route == ROUTE_DEFAULT:
            return decision, EXIT_OK
        if issue_id and ledger_path:
            ledger.setdefault("issues", {})[issue_id] = {
                "status": "pending",
                "triggers": hits_names,
                "evidence_hash": evidence_hash or "",
                "delegate": decision["delegate"],
                "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
            save_ledger(ledger_path, ledger)
        decision = dict(decision)
        decision["status"] = STATUS_DELEGATED
        return decision, EXIT_OK

    if action == "resume":
        if issue_id and ledger_path:
            entry = ledger.setdefault("issues", {}).get(issue_id) or {}
            prior_triggers = entry.get("triggers") or []
            entry.update(
                {
                    "status": "handled",
                    "triggers": prior_triggers or hits_names or [],
                    "evidence_hash": evidence_hash or entry.get("evidence_hash") or "",
                    "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                }
            )
            ledger["issues"][issue_id] = entry
            save_ledger(ledger_path, ledger)
        # After Grok resolves the hard part, routine work is DEFAULT unless a *new* trigger.
        resumed = evaluate(
            {},
            grok_available=True,
            role="default",
            already_grok=False,
            ledger=ledger,
            issue_id=issue_id,
            evidence_hash=evidence_hash,
            action="eval",
        )
        resumed = dict(resumed)
        resumed["reason"] = "hard part handled; resume DEFAULT for routine remainder"
        resumed["status"] = STATUS_CONTINUED
        resumed["route"] = ROUTE_DEFAULT
        resumed["delegate"] = "-"
        resumed["trigger"] = "-"
        resumed["triggers"] = []
        resumed["delegation_mode"] = "-"
        resumed["already_gated"] = True
        return resumed, EXIT_OK

    # eval
    if route == ROUTE_BLOCKED:
        return decision, EXIT_GROK_UNAVAILABLE
    if route == ROUTE_GROK and decision["delegate"] == "grok-high":
        return decision, EXIT_MUST_DELEGATE
    if route == ROUTE_TERMINATION_REVIEW:
        return decision, EXIT_TERMINATION_DENIED
    return decision, EXIT_OK


def _termination_selftest():
    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))

    head = "abc1234"
    dirty_a = _sha256_text(" M scripts/model_route.py\n")
    dirty_b = _sha256_text(" M scripts/model_route.py\n M OPEN_PROBLEMS.md\n")
    # P31 blocked by GNSS + independent autonomous task exists.
    frontier_mixed = {
        "AUTONOMOUS_ACTION_AVAILABLE": "YES",
        "HUMAN_ACTION_REQUIRED": "NO",
        "STOP_REASON": "NOT_STOPPING",
        "NEXT_ACTION": "arrival_lifecycle: implemented but unwired proof",
        "actionable_count": 1,
        "blocked_count": 1,
        "actionable_items": ["arrival_lifecycle"],
        "blocked_items": ["NAV-E2E-ARRIVAL-001"],
    }
    frontier_exhausted = {
        "AUTONOMOUS_ACTION_AVAILABLE": "NO",
        "HUMAN_ACTION_REQUIRED": "YES",
        "STOP_REASON": "BLOCKED_ON_EXTERNAL_DEPENDENCY",
        "BLOCKING_DEPENDENCY": "a live GNSS origin outdoors for Amap route calc code=3",
        "NEXT_ACTION": "NONE",
        "actionable_count": 0,
        "blocked_count": 4,
        "actionable_items": [],
    }

    fp_mixed = compute_termination_fingerprint(head, dirty_a, frontier_mixed)
    fp_exh = compute_termination_fingerprint(head, dirty_a, frontier_exhausted)
    fp_stale = compute_termination_fingerprint(head, dirty_b, frontier_exhausted)

    # Normal task completion / standing-by still requires termination review.
    req, rcode = request_termination(fp_mixed, grok_available=True, role="default")
    check(
        "term:task_done_still_requires_review",
        rcode == EXIT_TERMINATION_DENIED
        and req["route"] == ROUTE_TERMINATION_REVIEW
        and req["delegation_mode"] == MODE_TERMINATION,
    )

    # Standing-by / final-summary path cannot bypass (same request API).
    req2, rcode2 = request_termination(fp_exh, grok_available=True, role="default")
    check(
        "term:standing_by_cannot_bypass",
        rcode2 == EXIT_TERMINATION_DENIED and req2["route"] == ROUTE_TERMINATION_REVIEW,
    )

    # Reviewer unavailable → termination rejected.
    unavail, ucode = request_termination(
        fp_exh, grok_available=False, grok_reason="Task failed"
    )
    check(
        "term:reviewer_unavailable_request",
        ucode == EXIT_TERMINATION_DENIED
        and unavail["route"] == ROUTE_BLOCKED
        and unavail["termination_verdict"] == VERDICT_REVIEW_UNAVAILABLE,
    )

    handle, ledger_path = tempfile.mkstemp(suffix=".json")
    os.close(handle)
    try:
        ledger = {"issues": {}}

        # DEFAULT cannot submit TERMINAL_APPROVED.
        bad_role, brcode = submit_termination_review(
            VERDICT_TERMINAL_APPROVED,
            fingerprint=fp_exh,
            current_fingerprint=fp_exh,
            role="default",
            actionable_count=0,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check(
            "term:default_cannot_submit_review",
            brcode == EXIT_TERMINATION_DENIED
            and bad_role["termination_verdict"] == VERDICT_REVIEW_UNAVAILABLE,
        )

        # P31 blocked + independent work → CONTINUE → run continues.
        cont, ccode = submit_termination_review(
            VERDICT_CONTINUE,
            fingerprint=fp_mixed,
            current_fingerprint=fp_mixed,
            next_action="arrival_lifecycle: prove production wiring",
            role="grok-high",
            actionable_count=1,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check(
            "term:p31_blocked_independent_continue",
            ccode == EXIT_TERMINATION_CONTINUE
            and cont["termination_verdict"] == VERDICT_CONTINUE
            and cont["next_action"].startswith("arrival_lifecycle")
            and cont["route"] == ROUTE_DEFAULT,
        )

        # Blocked task + independent → TERMINAL_APPROVED mechanically rejected.
        reject, rjcode = submit_termination_review(
            VERDICT_TERMINAL_APPROVED,
            fingerprint=fp_mixed,
            current_fingerprint=fp_mixed,
            role="grok-high",
            actionable_count=1,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check(
            "term:blocked_plus_independent_rejects_approve",
            rjcode == EXIT_TERMINATION_CONTINUE
            and "actionable" in reject["reason"].lower(),
        )

        # All remaining genuinely human/external blocked → TERMINAL_APPROVED allowed.
        ok_app, okcode = submit_termination_review(
            VERDICT_TERMINAL_APPROVED,
            fingerprint=fp_exh,
            current_fingerprint=fp_exh,
            role="grok-high",
            actionable_count=0,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check(
            "term:all_human_blocked_may_approve",
            okcode == EXIT_OK
            and ok_app["termination_verdict"] == VERDICT_TERMINAL_APPROVED
            and ok_app["token"],
        )
        token = ok_app["token"]

        # Stale approval after repo change → termination rejected.
        stale, scode = consume_termination_approval(
            token, fp_stale, ledger=ledger, ledger_path=ledger_path
        )
        check(
            "term:stale_approval_rejected",
            scode == EXIT_TERMINATION_DENIED and "stale" in stale["reason"].lower(),
        )

        # Re-approve on current fingerprint, then consume once.
        ledger = load_ledger(ledger_path)
        ok_app2, okcode2 = submit_termination_review(
            VERDICT_TERMINAL_APPROVED,
            fingerprint=fp_exh,
            current_fingerprint=fp_exh,
            role="grok-high",
            already_grok=True,
            actionable_count=0,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check("term:reapprove_after_stale", okcode2 == EXIT_OK and ok_app2["token"])
        token2 = ok_app2["token"]
        consumed, cscode = consume_termination_approval(
            token2, fp_exh, ledger=ledger, ledger_path=ledger_path
        )
        check(
            "term:consume_allows_terminate",
            cscode == EXIT_OK and consumed["status"] == STATUS_TERMINATION_CONSUMED,
        )
        # Single-use: second consume fails.
        ledger = load_ledger(ledger_path)
        again, acode = consume_termination_approval(
            token2, fp_exh, ledger=ledger, ledger_path=ledger_path
        )
        check(
            "term:single_use",
            acode == EXIT_TERMINATION_DENIED,
            again.get("reason", ""),
        )

        # Malformed reviewer response → rejected.
        malformed, mcode = submit_termination_review(
            "YES_STOP",
            fingerprint=fp_exh,
            current_fingerprint=fp_exh,
            role="grok-high",
            actionable_count=0,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check(
            "term:malformed_verdict_rejected",
            mcode == EXIT_TERMINATION_DENIED
            and malformed["termination_verdict"] == VERDICT_REVIEW_UNAVAILABLE,
        )

        # REVIEW_UNAVAILABLE from MAX_GROK → fail closed.
        ru, rucode = submit_termination_review(
            VERDICT_REVIEW_UNAVAILABLE,
            fingerprint=fp_exh,
            current_fingerprint=fp_exh,
            role="grok-high",
            actionable_count=0,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check(
            "term:review_unavailable_fail_closed",
            rucode == EXIT_TERMINATION_DENIED
            and ru["termination_verdict"] == VERDICT_REVIEW_UNAVAILABLE,
        )

        # CONTINUE without NEXT_ACTION → rejected.
        bare, bcode = submit_termination_review(
            VERDICT_CONTINUE,
            fingerprint=fp_mixed,
            current_fingerprint=fp_mixed,
            next_action="",
            role="grok-high",
            actionable_count=1,
            ledger=ledger,
            ledger_path=ledger_path,
        )
        check("term:continue_needs_next_action", bcode == EXIT_TERMINATION_DENIED)

        # Final-summary path: --action continue through TERMINATION_REVIEW denied.
        cont_block, cbcode = apply_action(
            req, "continue", {"issues": {}}, None, None, "", []
        )
        check(
            "term:continue_action_cannot_bypass",
            cbcode == EXIT_TERMINATION_DENIED,
            cont_block.get("reason", ""),
        )

        # Fingerprint changes when dirty tree changes.
        check("term:fingerprint_binds_dirty", fp_exh != fp_stale)
        check("term:fingerprint_binds_frontier", fp_mixed != fp_exh)
    finally:
        for path in (ledger_path, ledger_path + ".tmp"):
            try:
                os.remove(path)
            except OSError:
                pass

    return results


def parse_eval(text):
    text = (text or "").strip()
    if not text:
        return {}
    if text.startswith("{"):
        data = json.loads(text)
        if not isinstance(data, dict):
            raise ValueError("eval JSON must be an object")
        return data
    # k=v,k=v
    raw = {}
    for part in text.split(","):
        if not part.strip():
            continue
        if "=" not in part:
            raise ValueError("feature %r is not k=v" % part)
        key, value = part.split("=", 1)
        raw[key.strip()] = value.strip()
    return raw


def selftest():
    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))

    for name, features, expected in SCENARIOS:
        decision = evaluate(features, grok_available=True)
        check(
            "scenario:%s" % name,
            decision["route"] == expected,
            "got %s expected %s (%s)" % (decision["route"], expected, decision["reason"]),
        )

    # Size / docs / tokens never trigger by themselves (those flags do not exist).
    empty = evaluate({}, grok_available=True)
    check("empty_is_default", empty["route"] == ROUTE_DEFAULT)

    # One failed fix is still DEFAULT; two is GROK_REQUIRED (mid-task).
    first = evaluate(
        {"inspection_complete": True, "failed_fix_attempts": 1, "obvious_local_fix": True},
        grok_available=True,
    )
    check("one_failed_fix_stays_default", first["route"] == ROUTE_DEFAULT)
    second = evaluate(
        {"inspection_complete": True, "failed_fix_attempts": 2},
        grok_available=True,
    )
    check("two_failed_fixes_require_grok", second["route"] == ROUTE_GROK and "H3" in second["triggers"])

    # Unclear root cause before inspection is not yet C.
    premature = evaluate(
        {"root_cause_unclear_after_inspection": True, "inspection_complete": False},
        grok_available=True,
    )
    check("unclear_before_inspection_is_default", premature["route"] == ROUTE_DEFAULT)
    after = evaluate(
        {"root_cause_unclear_after_inspection": True, "inspection_complete": True},
        grok_available=True,
    )
    check("unclear_after_inspection_requires_grok", after["route"] == ROUTE_GROK and "H2" in after["triggers"])

    # Fail closed: never downgrade GROK_REQUIRED to DEFAULT when Grok is missing.
    blocked = evaluate(
        {"architecture_decision": True},
        grok_available=False,
        grok_reason="probe failed",
    )
    check("fail_closed_blocked", blocked["route"] == ROUTE_BLOCKED)
    check("fail_closed_not_default", blocked["route"] != ROUTE_DEFAULT)

    # Ordinary executor cannot continue through GROK_REQUIRED.
    grok = evaluate({"architecture_decision": True}, grok_available=True, action="continue")
    continued, code = apply_action(grok, "continue", {"issues": {}}, None, None, "", grok["triggers"])
    check("continue_refused", code == EXIT_MUST_DELEGATE and continued["status"] == STATUS_BLOCKED)

    # already-Grok parent may continue (it *is* the gated reasoner) but must not launch grok-high.
    parent = evaluate(
        {"architecture_decision": True},
        grok_available=True,
        already_grok=True,
        action="continue",
    )
    parent_out, parent_code = apply_action(
        parent, "continue", {"issues": {}}, None, None, "", parent["triggers"]
    )
    check(
        "already_grok_may_continue",
        parent_code == EXIT_OK
        and parent_out["route"] == ROUTE_GROK
        and parent_out["delegate"] == "this-chat",
    )

    # Recursion guard.
    inner = evaluate({"architecture_decision": True}, grok_available=True, role="grok-high")
    check("grok_high_does_not_redelegate", inner["route"] == ROUTE_DEFAULT and inner["delegate"] == "-")

    # Ledger: same issue + same evidence does not loop; new trigger does re-open.
    handle, ledger_path = tempfile.mkstemp(suffix=".json")
    os.close(handle)
    try:
        ledger = {"issues": {}}
        first_hit = evaluate(
            {"architecture_decision": True},
            grok_available=True,
            issue_id="issue-1",
            evidence_hash="e1",
        )
        delegated, dcode = apply_action(
            first_hit,
            "delegate",
            ledger,
            ledger_path,
            "issue-1",
            "e1",
            first_hit["triggers"],
        )
        check("delegate_records", dcode == EXIT_OK and delegated["status"] == STATUS_DELEGATED)
        ledger = load_ledger(ledger_path)
        check("ledger_pending", ledger["issues"]["issue-1"]["status"] == "pending")

        # Re-eval same evidence while pending → DEFAULT (do not launch again).
        again = evaluate(
            {"architecture_decision": True},
            grok_available=True,
            ledger=ledger,
            issue_id="issue-1",
            evidence_hash="e1",
        )
        check("no_loop_while_pending", again["route"] == ROUTE_DEFAULT and again["already_gated"])

        resumed, rcode = apply_action(
            first_hit, "resume", ledger, ledger_path, "issue-1", "e1", first_hit["triggers"]
        )
        check("resume_is_default", rcode == EXIT_OK and resumed["route"] == ROUTE_DEFAULT)
        ledger = load_ledger(ledger_path)
        check("ledger_handled", ledger["issues"]["issue-1"]["status"] == "handled")

        same = evaluate(
            {"architecture_decision": True},
            grok_available=True,
            ledger=ledger,
            issue_id="issue-1",
            evidence_hash="e1",
        )
        check("no_loop_after_handled", same["route"] == ROUTE_DEFAULT)

        new_trig = evaluate(
            {"architecture_decision": True, "failed_fix_attempts": 2, "inspection_complete": True},
            grok_available=True,
            ledger=ledger,
            issue_id="issue-1",
            evidence_hash="e1",
        )
        check(
            "new_independent_trigger_reopens",
            new_trig["route"] == ROUTE_GROK and "H3" in new_trig["triggers"],
        )

        new_ev = evaluate(
            {"architecture_decision": True},
            grok_available=True,
            ledger=ledger,
            issue_id="issue-1",
            evidence_hash="e2",
        )
        check("new_evidence_reopens", new_ev["route"] == ROUTE_GROK)
    finally:
        try:
            os.remove(ledger_path)
        except OSError:
            pass
        try:
            os.remove(ledger_path + ".tmp")
        except OSError:
            pass

    # Probe: the pinned file in this repo must look available.
    available, why = probe_grok(REPO)
    check("probe_grok_pin", available, why)
    no_fast, no_fast_why = probe_no_fast_pins(REPO)
    check("probe_no_fast_pins", no_fast, no_fast_why)
    check(
        "fast_slug_detected",
        _is_fast_model_pin("model: composer-2.5-fast")
        and _is_fast_model_pin("model: grok-4.7-xhigh-fast")
        and not _is_fast_model_pin("model: composer-2.5[fast=false]")
        and not _is_fast_model_pin("model: grok-4.7-xhigh"),
    )

    results.extend(_termination_selftest())

    failed = [item for item in results if not item[1]]
    for name, ok, detail in results:
        print("%s  %s%s" % ("PASS" if ok else "FAIL", name, ("  " + detail) if (detail and not ok) else ""))
    if failed:
        print("model_route selftest FAILED (%d)" % len(failed))
        return 1
    print("model_route selftest OK (%d checks)" % len(results))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Hard-gate Cursor model routing")
    parser.add_argument("--eval", dest="eval_blob", help="JSON object or k=v,k=v features")
    parser.add_argument("--eval-file", help="path to JSON features")
    parser.add_argument(
        "--action",
        choices=(
            "eval",
            "continue",
            "delegate",
            "resume",
            "terminate-request",
            "terminate-review",
            "terminate-consume",
        ),
        default="eval",
        help="eval/continue/delegate/resume for labor routing; "
        "terminate-* for the MAX_GROK termination hard gate "
        "(DEFAULT may never self-authorize ending a run)",
    )
    parser.add_argument("--issue-id", default="")
    parser.add_argument("--evidence-hash", default="")
    parser.add_argument("--ledger", default=DEFAULT_LEDGER)
    parser.add_argument(
        "--grok-available",
        choices=("true", "false", "probe"),
        default="probe",
        help="false when Task grok-high cannot be invoked; never downgrade to DEFAULT",
    )
    parser.add_argument("--role", choices=("default", "grok-high"), default="default")
    parser.add_argument(
        "--already-grok",
        action="store_true",
        help="parent picker is already Grok 4.7 Extra High; reason here, do not launch grok-high",
    )
    parser.add_argument(
        "--verdict",
        choices=VALID_TERMINATION_VERDICTS,
        help="MAX_GROK termination verdict for --action terminate-review",
    )
    parser.add_argument(
        "--next-action",
        default="",
        help="required when --verdict CONTINUE",
    )
    parser.add_argument(
        "--fingerprint",
        default="",
        help="state fingerprint from terminate-request (required for review/consume match)",
    )
    parser.add_argument(
        "--token",
        default="",
        help="single-use TERMINAL_APPROVED token for --action terminate-consume",
    )
    parser.add_argument(
        "--actionable-count",
        type=int,
        default=None,
        help="override actionable frontier count for terminate-review (tests); "
        "default reads discover_work",
    )
    parser.add_argument(
        "--frontier-json",
        default="",
        help="optional JSON frontier/stop snapshot for fingerprinting (tests)",
    )
    parser.add_argument(
        "--frontier-file",
        default="",
        help="path to JSON frontier/stop snapshot (preferred over --frontier-json on Windows)",
    )
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if args.grok_available == "probe":
        grok_ok, grok_why = probe_grok(REPO)
    elif args.grok_available == "true":
        grok_ok, grok_why = True, "forced true"
    else:
        grok_ok, grok_why = False, "Task grok-high unavailable"

    ledger = load_ledger(args.ledger)

    # --- termination hard gate -------------------------------------------------
    if args.action in ("terminate-request", "terminate-review", "terminate-consume"):
        if args.frontier_file:
            frontier = json.load(open(args.frontier_file, encoding="utf-8-sig"))
        elif args.frontier_json:
            frontier = json.loads(args.frontier_json)
        else:
            frontier = discover_frontier_snapshot(REPO)
        current_fp = compute_termination_fingerprint(frontier=frontier, repo=REPO)
        actionable = args.actionable_count
        if actionable is None:
            try:
                actionable = int(frontier.get("actionable_count", 0))
            except (TypeError, ValueError, AttributeError):
                actionable = -1

        if args.action == "terminate-request":
            decision, code = request_termination(
                current_fp,
                grok_available=grok_ok,
                grok_reason=grok_why,
                role=args.role,
            )
        elif args.action == "terminate-review":
            if not args.verdict:
                print("model_route: --verdict required for terminate-review", file=sys.stderr)
                return EXIT_USAGE
            decision, code = submit_termination_review(
                args.verdict,
                fingerprint=args.fingerprint or current_fp,
                current_fingerprint=current_fp,
                next_action=args.next_action,
                role=args.role,
                already_grok=args.already_grok,
                grok_available=grok_ok,
                grok_reason=grok_why,
                actionable_count=actionable,
                ledger=ledger,
                ledger_path=args.ledger,
            )
        else:  # terminate-consume
            decision, code = consume_termination_approval(
                args.token,
                current_fingerprint=current_fp,
                ledger=ledger,
                ledger_path=args.ledger,
            )

        if args.json:
            payload = deepcopy(decision)
            payload["exit"] = code
            print(json.dumps(payload, indent=2, sort_keys=True))
        else:
            print(format_text(decision))
        return code

    # --- labor routing ---------------------------------------------------------
    raw = {}
    try:
        if args.eval_file:
            raw.update(json.load(open(args.eval_file, encoding="utf-8")))
        if args.eval_blob:
            raw.update(parse_eval(args.eval_blob))
    except ValueError as exc:
        print("model_route: %s" % exc, file=sys.stderr)
        return EXIT_USAGE

    features = normalize_features(raw)
    decision = evaluate(
        features,
        grok_available=grok_ok,
        grok_reason=grok_why,
        role=args.role,
        already_grok=args.already_grok,
        ledger=ledger,
        issue_id=args.issue_id or None,
        evidence_hash=args.evidence_hash,
        action=args.action,
    )
    decision, code = apply_action(
        decision,
        args.action,
        ledger,
        args.ledger,
        args.issue_id or None,
        args.evidence_hash,
        decision.get("triggers") or [],
    )

    if args.json:
        payload = deepcopy(decision)
        payload["exit"] = code
        print(json.dumps(payload, indent=2, sort_keys=True))
    else:
        print(format_text(decision))
    return code


if __name__ == "__main__":
    sys.exit(main())
