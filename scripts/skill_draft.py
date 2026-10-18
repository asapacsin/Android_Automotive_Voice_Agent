#!/usr/bin/env python3
"""Draft a skill (or script) candidate mechanically, so the build loop starts without anyone writing.

    python scripts/skill_draft.py --name review-packet --subject "gradlew test -> git add -> git commit"
    python scripts/skill_draft.py --from-metrics      # every NEW_SKILL/SCRIPT candidate skill_metrics flagged
    python scripts/skill_draft.py --selftest

Owner, 2026-10-18: "fully automatic skill writing ... why don't apply this". The loop is now:
skill_observe (what repeats) -> skill_metrics (what is due) -> discover_work (lists it) -> this
script (draft) -> .claude/skills/create-skill (the agent fills the draft from the evidence, runs the
with/without-skill evals) -> lands only if it beats the baseline, else is deleted and recorded.
The draft is never committed as is: it is marked DRAFT and harness_check-style review catches a
skill still carrying the marker.

Writes, for a candidate that does not exist yet:
  skills/<name>.md                         canonical text, a skeleton with the evidence pasted in
  .claude/skills/<name>/SKILL.md           Claude Code entry (name + trigger description stub)
  .claude/skills/<name>/evals/evals.json   two prompt stubs taken from the observed sequence
Existing files are never overwritten.
"""
import argparse
import json
import os
import re
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METRICS = os.path.join(REPO, "harness", "skill-metrics.json")
DRAFT_MARK = "DRAFT (scripts/skill_draft.py)"


def slug(text):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return s[:40] or "candidate"


def draft(name, subject, evidence, root=REPO):
    written = []
    canonical = os.path.join(root, "skills", "%s.md" % name)
    entry_dir = os.path.join(root, ".claude", "skills", name)
    entry = os.path.join(entry_dir, "SKILL.md")
    evals = os.path.join(entry_dir, "evals", "evals.json")
    if not os.path.exists(canonical):
        os.makedirs(os.path.dirname(canonical), exist_ok=True)
        with open(canonical, "w", encoding="utf-8", newline="\n") as f:
            f.write("# /%s — %s\n\n" % (name, DRAFT_MARK))
            f.write("Observed repeated work: `%s`.\n\n" % subject)
            f.write("## Evidence (from harness/skill-events.jsonl)\n\n%s\n\n" % evidence)
            f.write("## Steps\n\n1. (fill from the evidence: the real commands, in order, with why)\n\n")
            f.write("## Gotchas\n\n- (real failures seen while doing this work)\n")
        written.append(canonical)
    if not os.path.exists(entry):
        os.makedirs(entry_dir, exist_ok=True)
        with open(entry, "w", encoding="utf-8", newline="\n") as f:
            f.write("---\nname: %s\ndescription: %s Use whenever <fill concrete triggers>.\n---\n\n"
                    % (name, DRAFT_MARK))
            f.write("Read and follow [`skills/%s.md`](../../../skills/%s.md) from the repository root.\n"
                    % (name, name))
        written.append(entry)
    if not os.path.exists(evals):
        os.makedirs(os.path.dirname(evals), exist_ok=True)
        with open(evals, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"skill_name": name, "draft": True, "evals": [
                {"id": 1, "prompt": "We just did `%s` again by hand. Do it for the current branch." % subject,
                 "expected_output": "(fill)", "assertions": ["(fill from the evidence)"]},
                {"id": 2, "prompt": "(an edge case of the same workflow, phrased the way the owner asks)",
                 "expected_output": "(fill)", "assertions": ["(fill)"]},
            ]}, f, indent=2, ensure_ascii=False)
            f.write("\n")
        written.append(evals)
    return written


def from_metrics():
    if not os.path.isfile(METRICS):
        print("no %s; run scripts/skill_metrics.py" % os.path.relpath(METRICS, REPO))
        return []
    metrics = json.load(open(METRICS, encoding="utf-8"))
    out = []
    for action in metrics.get("suggested_actions", []):
        if not action.get("action", "").startswith("NEW_SKILL"):
            continue
        subject = action.get("subject", "")
        evidence = "- seen %s times: %s" % (action.get("seen"), action.get("why"))
        out.extend(draft(slug(subject), subject, evidence))
    return out


def selftest():
    bad = 0
    with tempfile.TemporaryDirectory() as root:
        first = draft("demo-flow", "a -> b -> c", "- seen 3 times", root)
        again = draft("demo-flow", "a -> b -> c", "- seen 3 times", root)
        if len(first) != 3 or again:
            bad += 1
            print("FAIL write-once: %r then %r" % (first, again))
        entry = open(os.path.join(root, ".claude", "skills", "demo-flow", "SKILL.md")).read()
        if not entry.startswith("---\nname: demo-flow\n") or DRAFT_MARK not in entry:
            bad += 1
            print("FAIL entry frontmatter")
        data = json.load(open(os.path.join(root, ".claude", "skills", "demo-flow", "evals", "evals.json")))
        if not data.get("draft") or len(data["evals"]) != 2:
            bad += 1
            print("FAIL evals stub")
    if slug("gradlew :app:test -> git add") != "gradlew-app-test-git-add":
        bad += 1
        print("FAIL slug")
    print("skill_draft selftest: %d problem(s)" % bad)
    return 1 if bad else 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--name")
    p.add_argument("--subject", default="")
    p.add_argument("--evidence", default="")
    p.add_argument("--from-metrics", action="store_true")
    p.add_argument("--selftest", action="store_true")
    a = p.parse_args()
    if a.selftest:
        return selftest()
    written = from_metrics() if a.from_metrics else (draft(a.name, a.subject, a.evidence) if a.name else None)
    if written is None:
        p.error("give --name or --from-metrics")
    for w in written:
        print("drafted %s" % os.path.relpath(w, REPO))
    if not written:
        print("nothing to draft")
    return 0


if __name__ == "__main__":
    sys.exit(main())
