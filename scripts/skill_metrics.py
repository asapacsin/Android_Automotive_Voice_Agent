"""Summarise harness/skill-events.jsonl into harness/skill-metrics.json, and say what it implies.

    python scripts/skill_metrics.py

Deliberately small: counts and the thresholds from harness/SKILL_POLICY.md applied to them. No
analytics framework, no trends, no dashboards. The output answers one question — is any part of the
skill library repeatedly failing the people using it?
"""

import collections
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVENTS = os.path.join(REPO, "harness", "skill-events.jsonl")
METRICS = os.path.join(REPO, "harness", "skill-metrics.json")

# harness/SKILL_POLICY.md
NEW_SKILL_THRESHOLD = 3
MODIFY_THRESHOLD = 2


def load():
    if not os.path.isfile(EVENTS):
        return []
    records = []
    for line in open(EVENTS, encoding="utf-8"):
        line = line.strip()
        if line:
            records.append(json.loads(line))
    return records


def main():
    events = load()
    if not events:
        print("no skill events yet: %s" % os.path.relpath(EVENTS, REPO))
        return 0

    usage = collections.Counter()
    deviations = collections.Counter()
    missing = collections.Counter()
    reminders = collections.Counter()
    manual = collections.Counter()
    stale = collections.Counter()
    outcomes = collections.Counter()
    retries = 0

    for e in events:
        outcomes[e.get("task_outcome", "UNKNOWN")] += 1
        retries += e.get("retries", 0)
        for s in e.get("skills_used", []):
            usage[s] += 1
        for d in e.get("deviations", []):
            deviations[d] += 1
        for m in e.get("missing_steps", []):
            missing[m] += 1
        for r in e.get("human_reminders", []):
            reminders[r] += 1
        for w in e.get("manual_repeated_work", []):
            manual[w] += 1
        for a in e.get("failed_skill_assumptions", []):
            stale[a] += 1

    # What the evidence now justifies, per the policy thresholds.
    actions = []
    for item, n in manual.items():
        if n >= NEW_SKILL_THRESHOLD:
            actions.append({
                "action": "NEW_SKILL or SCRIPT",
                "subject": item,
                "seen": n,
                "why": "manual workflow repeated >= %d times" % NEW_SKILL_THRESHOLD,
            })
    for item, n in list(deviations.items()) + list(missing.items()):
        if n >= MODIFY_THRESHOLD:
            actions.append({
                "action": "SKILL_MODIFICATION",
                "subject": item,
                "seen": n,
                "why": "same deviation/missing step >= %d times" % MODIFY_THRESHOLD,
            })
    for item, n in stale.items():
        actions.append({
            "action": "SKILL_MODIFICATION (immediate)",
            "subject": item,
            "seen": n,
            "why": "a skill named a stale path or command",
        })
    for item, n in reminders.items():
        if n >= MODIFY_THRESHOLD:
            actions.append({
                "action": "SKILL_MODIFICATION",
                "subject": item,
                "seen": n,
                "why": "the human repeated the same instruction; the procedure is missing it",
            })

    metrics = {
        "generated_by": "scripts/skill_metrics.py",
        "events": len(events),
        "outcomes": dict(outcomes),
        "total_retries": retries,
        "skill_usage": dict(usage),
        "deviations": dict(deviations),
        "missing_steps": dict(missing),
        "human_reminders": dict(reminders),
        "manual_repeated_work": dict(manual),
        "failed_skill_assumptions": dict(stale),
        "unused_skills": sorted(
            name.replace(".md", "")
            for name in os.listdir(os.path.join(REPO, "skills"))
            if name.endswith(".md") and name.replace(".md", "") not in usage
        ),
        "suggested_actions": actions,
        "thresholds": {"new_skill": NEW_SKILL_THRESHOLD, "modify": MODIFY_THRESHOLD},
    }

    with open(METRICS, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(metrics, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    print("wrote %s" % os.path.relpath(METRICS, REPO))
    print("  events %d  outcomes %s  retries %d" % (len(events), dict(outcomes), retries))
    if metrics["unused_skills"]:
        print("  never used: %s  (candidates for deletion once there is enough evidence)"
              % ", ".join(metrics["unused_skills"]))
    for a in actions:
        print("  -> %s: %s (seen %d) — %s" % (a["action"], a["subject"], a["seen"], a["why"]))
    if not actions:
        print("  no action justified by the evidence")
    return 0


if __name__ == "__main__":
    sys.exit(main())
