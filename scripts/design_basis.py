"""Best-practice design-basis gate for non-trivial engineering work.

    python scripts/design_basis.py --validate
    python scripts/design_basis.py --selftest
    python scripts/design_basis.py --eval '{"triggers":{"audio_media":true},"status":"PASS",...}'
    python scripts/design_basis.py --check DB-001

A gated task may not be treated as implementation-cleared until this script says so.
Enforcement: harness_check.py, discover_work.py, and the always-apply Cursor rule
.cursor/rules/design-basis-gate.mdc.

See harness/proposals/HARNESS_PROPOSAL_004-design-basis-gate.md.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REGISTRY = os.path.join(REPO, "harness", "design_basis.yaml")

EXIT_OK = 0
EXIT_USAGE = 1
EXIT_NOT_CLEARED = 2

TRIGGER_KEYS = frozenset({
    "new_subsystem",
    "concurrency",
    "async_lifecycle",
    "networking",
    "audio_media",
    "persistence",
    "security",
    "hardware",
    "nonobvious_sdk",
    "prior_failure",
    "suppresses_required_behavior",
    "new_mechanism_beside_owner",
    "declared_uncertain",
})

STATUSES = frozenset({"NOT_REQUIRED", "PASS", "NEEDS_RESEARCH", "NOVEL_REVIEW"})
ESTABLISHED_BASIS = frozenset({"repo", "platform_sdk", "vendor", "industry", "adaptation"})
ALL_BASIS = ESTABLISHED_BASIS | {"custom"}


def _nonempty(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _triggers(entry: dict) -> dict:
    raw = entry.get("triggers") or {}
    if not isinstance(raw, dict):
        return {}
    return {k: bool(v) for k, v in raw.items()}


def gate_applies(entry: dict) -> bool:
    return any(_triggers(entry).values())


def load_registry(path: str | None = None) -> dict:
    path = path or REGISTRY
    if not os.path.isfile(path):
        return {"schema": 1, "entries": []}
    doc = yaml.safe_load(open(path, encoding="utf-8")) or {}
    if not isinstance(doc, dict):
        raise ValueError("registry root must be a mapping")
    entries = doc.get("entries")
    if entries is None:
        doc["entries"] = []
    elif not isinstance(entries, list):
        raise ValueError("entries must be a list")
    return doc


def entry_problems(entry: dict, prefix: str = "", require_id: bool = True) -> list[str]:
    problems: list[str] = []
    tag = "%s: " % prefix if prefix else ""

    entry_id = entry.get("id")
    if require_id and not _nonempty(entry_id):
        problems.append("%smissing or empty id" % tag)
        return problems

    triggers = _triggers(entry)
    raw_triggers = entry.get("triggers")
    if raw_triggers is not None and not isinstance(raw_triggers, dict):
        problems.append("%striggers must be a mapping" % tag)
    for key in triggers:
        if key not in TRIGGER_KEYS:
            problems.append("%sunknown trigger %r" % (tag, key))

    status = entry.get("status")
    if status not in STATUSES:
        problems.append("%sstatus must be one of %s" % (tag, sorted(STATUSES)))

    applies = gate_applies(entry)
    if not applies and status != "NOT_REQUIRED":
        problems.append("%sstatus %s requires at least one trigger" % (tag, status))
    if applies and status == "NOT_REQUIRED":
        problems.append("%sNOT_REQUIRED is illegal when any trigger is true" % tag)

    suppresses = bool(entry.get("suppresses_required_behavior"))
    if applies and "suppresses_required_behavior" not in entry:
        problems.append("%ssuppresses_required_behavior must be set when gated" % tag)

    req_changed = bool(entry.get("requirement_changed"))
    req_change = entry.get("requirement_change")
    if suppresses and not req_changed and status == "PASS":
        problems.append("%sPASS is illegal when suppresses_required_behavior is true" % tag)
    if req_changed and not _nonempty(req_change):
        problems.append("%srequirement_changed is true but requirement_change is empty" % tag)

    basis = entry.get("selected_basis")
    if basis is not None and basis not in ALL_BASIS:
        problems.append("%sselected_basis must be one of %s" % (tag, sorted(ALL_BASIS)))

    if not applies:
        return problems

    if status == "PASS":
        if basis not in ESTABLISHED_BASIS:
            problems.append("%sPASS requires selected_basis in %s" % (tag, sorted(ESTABLISHED_BASIS)))
        if not _nonempty(entry.get("selected_pattern")):
            problems.append("%sPASS requires selected_pattern" % tag)
        if not _nonempty(entry.get("evidence")):
            problems.append("%sPASS requires evidence" % tag)
        if suppresses:
            problems.append("%sPASS cannot clear when suppresses_required_behavior is true" % tag)

    elif status == "NEEDS_RESEARCH":
        pass  # uncleared by design

    elif status == "NOVEL_REVIEW":
        if basis != "custom":
            problems.append("%sNOVEL_REVIEW requires selected_basis custom" % tag)
        for field in ("justification", "risks", "smallest_experiment"):
            if not _nonempty(entry.get(field)):
                problems.append("%sNOVEL_REVIEW requires %s" % (tag, field))
        if suppresses and not req_changed:
            problems.append(
                "%sNOVEL_REVIEW with suppression requires requirement_changed and requirement_change"
                % tag
            )

    return problems


def implementation_cleared(entry: dict, require_id: bool = True) -> bool:
    if entry_problems(entry, require_id=require_id):
        return False
    if not gate_applies(entry):
        return True
    status = entry.get("status")
    if status == "NOT_REQUIRED":
        return True
    if status == "PASS":
        return True
    if status == "NOVEL_REVIEW":
        suppresses = bool(entry.get("suppresses_required_behavior"))
        req_changed = bool(entry.get("requirement_changed"))
        if suppresses and not req_changed:
            return False
        return True
    return False


def validate_registry(doc: dict | None = None) -> list[str]:
    doc = doc if doc is not None else load_registry()
    problems: list[str] = []
    entries = doc.get("entries") or []
    seen: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict):
            problems.append("each entry must be a mapping")
            continue
        entry_id = entry.get("id")
        prefix = str(entry_id) if entry_id else "<no-id>"
        if entry_id in seen:
            problems.append("%s: duplicate id" % prefix)
        seen.add(entry_id)
        problems.extend(entry_problems(entry, prefix))
        if gate_applies(entry) and not implementation_cleared(entry):
            problems.append("%s: implementation not cleared (status=%s)" % (prefix, entry.get("status")))
    return problems


def discover_gaps(doc: dict | None = None) -> list[dict]:
    """Uncleared gated entries for discover_work.py."""
    doc = doc if doc is not None else load_registry()
    out = []
    for entry in doc.get("entries") or []:
        if not isinstance(entry, dict):
            continue
        if not gate_applies(entry):
            continue
        if implementation_cleared(entry):
            continue
        entry_id = entry.get("id") or "<no-id>"
        status = entry.get("status") or "?"
        problem_class = entry.get("problem_class") or "unspecified"
        out.append({
            "id": entry_id,
            "status": status,
            "problem_class": problem_class,
            "summary": "design basis %s: establish pattern before implementation (%s)" % (
                status, problem_class,
            ),
        })
    return out


def eval_entry(payload: dict) -> dict:
    require_id = _nonempty(payload.get("id"))
    problems = entry_problems(payload, require_id=require_id)
    applies = gate_applies(payload)
    cleared = implementation_cleared(payload, require_id=require_id) if not problems else False
    return {
        "gate_applies": applies,
        "implementation_cleared": cleared,
        "status": payload.get("status"),
        "problems": problems,
    }


def find_entry(doc: dict, entry_id: str) -> dict | None:
    for entry in doc.get("entries") or []:
        if isinstance(entry, dict) and entry.get("id") == entry_id:
            return entry
    return None


def _parse_json(s: str) -> dict:
    try:
        return json.loads(s)
    except json.JSONDecodeError as exc:
        raise SystemExit("invalid JSON for --eval: %s" % exc) from exc


def selftest() -> int:
    failures: list[str] = []

    def check(name: str, cond: bool, detail: str = ""):
        if not cond:
            failures.append("%s%s" % (name, (": %s" % detail) if detail else ""))

    # 1. No triggers → cleared without a record
    bare = {"triggers": {}, "status": "NOT_REQUIRED"}
    r = eval_entry(bare)
    check("no triggers cleared", r["implementation_cleared"] and not r["gate_applies"])

    # 2. Gated entry missing pattern fields → not cleared
    missing = {
        "id": "DB-self-missing",
        "problem_class": "audio",
        "triggers": {"audio_media": True},
        "status": "PASS",
        "suppresses_required_behavior": False,
    }
    r = eval_entry(missing)
    check("gated missing fields not cleared", not r["implementation_cleared"])
    check("gated missing fields has problems", len(r["problems"]) > 0)

    # 3. Established pattern PASS → cleared
    pass_ok = {
        "id": "DB-self-pass",
        "problem_class": "voice barge-in",
        "triggers": {"audio_media": True, "declared_uncertain": True},
        "status": "PASS",
        "suppresses_required_behavior": False,
        "selected_basis": "platform_sdk",
        "selected_pattern": "duplex capture with AEC; interrupt on local playout clock",
        "evidence": "Android VOICE_COMMUNICATION + AcousticEchoCanceler; realtime APIs use playback-active barge-in",
    }
    r = eval_entry(pass_ok)
    check("PASS established cleared", r["implementation_cleared"])

    # 4. NEEDS_RESEARCH → not cleared
    research = {
        "id": "DB-self-research",
        "problem_class": "unknown",
        "triggers": {"networking": True},
        "status": "NEEDS_RESEARCH",
        "suppresses_required_behavior": False,
    }
    r = eval_entry(research)
    check("NEEDS_RESEARCH not cleared", not r["implementation_cleared"])

    # 5. NOVEL incomplete → not cleared
    novel_bad = {
        "id": "DB-self-novel-bad",
        "problem_class": "novel",
        "triggers": {"new_subsystem": True},
        "status": "NOVEL_REVIEW",
        "suppresses_required_behavior": False,
        "selected_basis": "custom",
        "justification": "only partial",
    }
    r = eval_entry(novel_bad)
    check("NOVEL incomplete not cleared", not r["implementation_cleared"])

    # 6. NOVEL complete → cleared
    novel_ok = {
        "id": "DB-self-novel-ok",
        "problem_class": "novel",
        "triggers": {"new_subsystem": True},
        "status": "NOVEL_REVIEW",
        "suppresses_required_behavior": False,
        "selected_basis": "custom",
        "justification": "no vendor pattern fits",
        "risks": "regression in turn-taking",
        "smallest_experiment": "unit test with fake clock",
    }
    r = eval_entry(novel_ok)
    check("NOVEL complete cleared", r["implementation_cleared"])

    # 7. Live empty registry validates
    empty_doc = {"schema": 1, "entries": []}
    check("empty registry validates", not validate_registry(empty_doc))

    # 8. Discover surfaces uncleared entry
    doc = {"entries": [research]}
    gaps = discover_gaps(doc)
    check("discover lists uncleared", len(gaps) == 1 and gaps[0]["id"] == "DB-self-research")

    # Barge-in anti-pattern: suppress required behaviour with PASS
    suppress_pass = {
        "id": "DB-self-suppress",
        "problem_class": "voice barge-in",
        "triggers": {"audio_media": True, "suppresses_required_behavior": True},
        "status": "PASS",
        "suppresses_required_behavior": True,
        "selected_basis": "repo",
        "selected_pattern": "mute the microphone during playback",
        "evidence": "none",
    }
    r = eval_entry(suppress_pass)
    check("suppress PASS not cleared", not r["implementation_cleared"])
    check("suppress PASS flagged", any("suppress" in p.lower() for p in r["problems"]))

    # Barge-in good pattern
    barge_ok = copy.deepcopy(pass_ok)
    barge_ok["id"] = "DB-self-barge-ok"
    barge_ok["triggers"] = {"audio_media": True, "prior_failure": True}
    r = eval_entry(barge_ok)
    check("barge established PASS cleared", r["implementation_cleared"])

    if failures:
        print("design_basis selftest FAILED")
        for failure in failures:
            print("  - %s" % failure)
        return 1
    print("design_basis selftest OK (%d scenarios)" % 10)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Design-basis gate")
    parser.add_argument("--validate", action="store_true", help="validate harness/design_basis.yaml")
    parser.add_argument("--selftest", action="store_true", help="run built-in scenarios")
    parser.add_argument("--eval", metavar="JSON", help="evaluate one entry payload")
    parser.add_argument("--check", metavar="ID", help="check clearance for a registry entry id")
    args = parser.parse_args(argv)

    if args.selftest:
        return selftest()

    if args.validate:
        problems = validate_registry()
        if problems:
            print("design_basis validate FAILED")
            for problem in problems:
                print("  - %s" % problem)
            return EXIT_NOT_CLEARED
        print("design_basis validate OK")
        return EXIT_OK

    if args.eval:
        result = eval_entry(_parse_json(args.eval))
        print(json.dumps(result, indent=2))
        return EXIT_OK if result["implementation_cleared"] else EXIT_NOT_CLEARED

    if args.check:
        doc = load_registry()
        entry = find_entry(doc, args.check)
        if entry is None:
            print("design_basis check: no entry %s (not gated — cleared)" % args.check)
            return EXIT_OK
        result = eval_entry(entry)
        print(json.dumps(result, indent=2))
        return EXIT_OK if result["implementation_cleared"] else EXIT_NOT_CLEARED

    parser.print_help()
    return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main())
