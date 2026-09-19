"""Is the harness coherent? One command, deterministic answer.

    python scripts/harness_check.py

Checks only what can be checked without judgement: the files exist, the generated state matches the
repository, skills contain procedure rather than project truth, and the capability registry parses.
Exits non-zero on any failure so it can gate a commit.
"""

import json
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REQUIRED = [
    "AGENTS.md",
    "harness/CONSTITUTION.md",
    "harness/HARNESS_POLICY.md",
    "harness/SKILL_POLICY.md",
    "harness/CHANGELOG.md",
    "harness/AUDIT.md",
    "config/capabilities.yaml",
    "docs/ARCHITECTURE.md",
    "docs/INVARIANTS.md",
    "docs/CAPABILITIES.md",
    "docs/TECH_DEBT.md",
    "docs/AGENT_MAINTENANCE.md",
    "skills/start.md",
    "skills/reproduce.md",
    "skills/fix.md",
    "skills/verify.md",
    "skills/handoff.md",
]

# A skill must not hard-code project truth; it should read it. These patterns indicate a fact has
# been baked into a procedure, which is how a skill goes stale without anyone noticing.
VOLATILE_IN_SKILL = [
    re.compile(r"is (currently )?(un)?supported", re.I),
    re.compile(r"\b\d{3,} tests\b", re.I),
    re.compile(r"currently broken", re.I),
]


def carried_by_head(recorded):
    """Is `recorded` the parent of HEAD, with HEAD being the commit that carried that state?

    State is generated *before* the commit that contains it, so a freshly committed state file
    always records the parent. Treating that as stale made the check fail after every single
    commit, and "regenerate then commit" recurses forever - which is how this repository ended up
    committing a state file that was genuinely stale, and nobody noticed among the false alarms.

    So: one commit behind is correct, provided HEAD is the commit that introduced that state file.
    Two commits behind, or a parent that did not touch it, is still stale.
    """
    if not recorded:
        return False
    try:
        parent = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD~1"],
            cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout.strip()
        if parent != recorded:
            return False
        changed = subprocess.run(
            ["git", "diff", "--name-only", "HEAD~1", "HEAD"],
            cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout.split()
        return "state/PROJECT_STATE.json" in changed
    except Exception:
        return False


def main():
    failures = []

    for path in REQUIRED:
        if not os.path.isfile(os.path.join(REPO, path)):
            failures.append("missing: %s" % path)

    # Generated state must match the repository, or it is misinformation.
    state_path = os.path.join(REPO, "state", "PROJECT_STATE.json")
    if os.path.isfile(state_path):
        try:
            state = json.load(open(state_path, encoding="utf-8"))
            head = subprocess.run(
                ["git", "rev-parse", "--short", "HEAD"],
                cwd=REPO, capture_output=True, text=True, check=True,
            ).stdout.strip()
            recorded = state.get("git", {}).get("commit")
            if recorded != head and not carried_by_head(recorded):
                failures.append(
                    "state/PROJECT_STATE.json is stale (%s vs %s); run scripts/collect_state.py"
                    % (recorded, head)
                )
        except Exception as exc:
            failures.append("state/PROJECT_STATE.json unreadable: %s" % exc)
    else:
        failures.append("state/PROJECT_STATE.json missing; run scripts/collect_state.py")

    # The stop state must be internally consistent, and the scenarios behind it must still pass.
    # A run that claims a human is needed without naming what is missing is how "blocked" becomes a
    # synonym for "finished".
    try:
        sys.path.insert(0, os.path.join(REPO, "scripts"))
        import discover_work

        stored = json.load(open(state_path, encoding="utf-8")) if os.path.isfile(state_path) else {}
        frontier = stored.get("work_frontier") or {}
        if frontier.get("available") is False:
            failures.append("work_frontier could not be computed: %s" % frontier.get("reason"))
        else:
            for problem in discover_work.contradictions(frontier):
                failures.append("stop state: %s" % problem)
            for field in ("AUTONOMOUS_ACTION_AVAILABLE", "HUMAN_ACTION_REQUIRED", "STOP_REASON",
                          "BLOCKING_DEPENDENCY", "NEXT_ACTION"):
                if field not in frontier:
                    failures.append("state/PROJECT_STATE.json is missing %s" % field)
    except Exception as exc:
        failures.append("stop-state validation failed: %r" % exc)

    skills_dir = os.path.join(REPO, "skills")
    if os.path.isdir(skills_dir):
        for name in sorted(os.listdir(skills_dir)):
            if not name.endswith(".md"):
                continue
            text = open(os.path.join(skills_dir, name), encoding="utf-8").read()
            for pattern in VOLATILE_IN_SKILL:
                if pattern.search(text):
                    failures.append(
                        "skills/%s states project truth (%s); a skill must read it instead "
                        "(harness/SKILL_POLICY.md)" % (name, pattern.pattern)
                    )

    registry = os.path.join(REPO, "config", "capabilities.yaml")
    if os.path.isfile(registry):
        body = "\n".join(
            line for line in open(registry, encoding="utf-8").read().splitlines()
            if not line.lstrip().startswith("#")
        )
        levels = set(re.findall(r"verified: ([a-z]+)", body))
        known = {"implemented", "unit", "device", "human", "unsupported"}
        if levels - known:
            failures.append("unknown verification levels in capabilities.yaml: %s" % (levels - known))
        if not levels:
            failures.append("capabilities.yaml has no verified: levels")

    if failures:
        print("harness check FAILED")
        for failure in failures:
            print("  - %s" % failure)
        return 1
    print("harness check OK")
    print("  %d required files present, state current, skills carry no project truth" % len(REQUIRED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
