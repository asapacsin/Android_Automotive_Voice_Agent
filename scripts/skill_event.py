"""Append one skill-review record to harness/skill-events.jsonl at handoff.

    python scripts/skill_event.py --task T06-name-selection --outcome PASS \
        --skills start,reproduce,fix,verify --candidate NONE --note "ordinal + name verified"

The point of the file is to make the *procedures* measurable: which skills were used, where they
were deviated from, what had to be repeated by hand, and what the human had to ask for twice.
Repeated evidence — not taste — is what justifies changing the skill library
(harness/SKILL_POLICY.md).

Facts that can be derived are derived: the commit and the test result come from the repository, not
from whoever runs this. Judgements (deviations, missing steps, reminders) are supplied by the agent
doing the review, because nothing else can observe them.
"""

import argparse
import datetime
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import skill_observe  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVENTS = os.path.join(REPO, "harness", "skill-events.jsonl")
STATE = os.path.join(REPO, "state", "PROJECT_STATE.json")

CANDIDATES = [
    "NONE",
    "SCRIPT_CANDIDATE",
    "INVARIANT_CANDIDATE",
    "SKILL_MODIFICATION",
    "NEW_SKILL",
    "SKILL_MERGE",
    "SKILL_DELETE",
]


def git(*args):
    try:
        return subprocess.run(
            ["git", *args], cwd=REPO, capture_output=True, text=True, check=True
        ).stdout.strip()
    except Exception:
        return None


def tests_from_state():
    """Test outcome from the generated state file, so it cannot be asserted by hand."""
    if not os.path.isfile(STATE):
        return None
    try:
        tests = json.load(open(STATE, encoding="utf-8")).get("tests", {})
        if not tests.get("available"):
            return None
        total = tests["total"]
        return {"tests": total["tests"], "failures": total["failures"], "skipped": total["skipped"]}
    except Exception:
        return None


def split(value):
    return [v.strip() for v in (value or "").split(",") if v.strip()]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--task", required=True, help="short task id, e.g. T06-name-selection")
    p.add_argument("--outcome", required=True, choices=["PASS", "FAIL", "PARTIAL"])
    p.add_argument("--skills", default="", help="comma-separated skills used")
    p.add_argument("--candidate", default="NONE", choices=CANDIDATES)
    p.add_argument("--deviations", default="", help="comma-separated: skill steps not followed")
    p.add_argument("--missing-steps", default="", help="comma-separated: steps a skill should have had")
    p.add_argument("--human-reminders", default="", help="comma-separated: what the human had to repeat")
    p.add_argument("--manual-work", default="", help="comma-separated: multi-step work done by hand")
    p.add_argument("--stale-assumptions", default="", help="comma-separated: stale paths/commands hit")
    p.add_argument("--retries", type=int, default=0, help="attempts before the change was right")
    p.add_argument("--note", default="", help="one line of context")
    # Time efficiency (owner, 2026-10-17): every finished piece of work records what it cost and
    # whether that was a time-efficient way to do it; an inefficient one names its improvement.
    p.add_argument("--minutes", type=int, required=True,
                   help="wall-clock minutes from starting the task to its verified push")
    p.add_argument("--attempts", type=int, required=True,
                   help="rounds needed: dispatches, builds or runs repeated before it was right")
    p.add_argument("--efficient", required=True, choices=["yes", "no"],
                   help="was this a time-efficient approach, judged against the cheapest one in hindsight")
    p.add_argument("--waste", default="",
                   help="comma-separated causes of lost time, short and reusable (e.g. vendor-files-missing)")
    p.add_argument("--improvement", default="",
                   help="what was changed (or proposed) so the waste does not recur; required when --efficient no")
    args = p.parse_args()
    if args.efficient == "no" and not args.improvement.strip():
        p.error("--efficient no needs --improvement: find a way to do it faster next time")

    # Observed, not remembered: repeated command sequences since the previous record, read from the
    # agent transcripts (scripts/skill_observe.py). Names only, never arguments.
    try:
        observed = skill_observe.repeated_sequences(
            skill_observe.commands_since(skill_observe.last_record_time()))
    except Exception:
        observed = {}

    record = {
        "task_id": args.task,
        "timestamp": datetime.datetime.now().isoformat(timespec="seconds"),
        "commit": git("rev-parse", "--short", "HEAD"),
        "skills_used": split(args.skills),
        "deviations": split(args.deviations),
        "missing_steps": split(args.missing_steps),
        "human_reminders": split(args.human_reminders),
        "manual_repeated_work": split(args.manual_work),
        "failed_skill_assumptions": split(args.stale_assumptions),
        "retries": args.retries,
        "minutes": args.minutes,
        "attempts": args.attempts,
        "efficient": args.efficient == "yes",
        "waste": split(args.waste),
        "improvement": args.improvement,
        "observed_sequences": observed,
        "task_outcome": args.outcome,
        "tests": tests_from_state(),
        "candidate_action": args.candidate,
        "note": args.note,
    }

    os.makedirs(os.path.dirname(EVENTS), exist_ok=True)
    with open(EVENTS, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    print("appended to %s" % os.path.relpath(EVENTS, REPO))
    print("  %s  %s  candidate=%s  %d min  %d attempts  efficient=%s" % (
        record["task_id"], record["task_outcome"], record["candidate_action"],
        args.minutes, args.attempts, args.efficient))
    for seq, n in observed.items():
        print("  observed %dx  %s" % (n, seq))
    if args.candidate != "NONE":
        print("  -> thresholds and next step: harness/SKILL_POLICY.md")
    # Keep harness/skill-metrics.json current, so discover_work.py sees a due candidate at once.
    subprocess.run([sys.executable, os.path.join(REPO, "scripts", "skill_metrics.py")],
                   cwd=REPO, capture_output=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
