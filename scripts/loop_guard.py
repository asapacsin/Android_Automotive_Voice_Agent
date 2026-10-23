#!/usr/bin/env python3
"""The one Stop-hook decision engine: progress, repeated blockers, escalation to Opus xhigh, bounded.

Registered as the only continuation-deciding Stop hook in .claude/settings.json (owner 2026-10-23). It
replaces the two hooks that looped a session for 20+ turns on 2026-10-23:
  - `delegation_check.py --stop-hook` blocked every stop, with no stop_hook_active check and no limit,
    on five commits that were already published on origin/claude/10-22. Clearing those would need a
    history rewrite, which AGENTS.md forbids, so no amount of continuing could ever satisfy it.
  - `.claude/hooks/keep-going.py` blocked up to 20 times per chain on "autonomous work remains" and
    restarted its counter whenever stop_hook_active was false, with no notion of progress or of the
    same blocker repeating. The "blocked on a git pull" the session kept reporting did not exist
    (scripts/git_blocker.py: clean, tracking its upstream).

State machine per blocker fingerprint (persisted in .claude/state/loop-guard.json, gitignored):

    EXECUTING -> (verified progress) reset
              -> first failure            BOUNDED_RECOVERY  (block once with the blocker and its remedy)
              -> same blocker, no progress ESCALATION_REQUIRED -> dispatch decider (Opus 5.5, xhigh)
                     verdict DECIDED                 -> PLANNER_RECOVERY (block once with the verdict)
                     OWNER_DECISION_REQUIRED / fails -> BLOCKED (recorded in harness/blockers.jsonl)
              -> PLANNER_RECOVERY, no progress again -> BLOCKED
    BLOCKED fingerprints never block again; the hook moves to the next independent frontier item.
    Deterministic kinds (a git state with a mechanical remedy) skip escalation: 2 attempts, then BLOCKED.

Progress is objective only: HEAD moved, the working-tree diff changed, or state/reviews.log changed.
A new assistant message, a changed explanation or a repeated tool call is not progress.

Bounds: per fingerprint at most 2 blocks plus 1 recovery block; per session at most MAX_NO_PROGRESS
consecutive blocks without progress and MAX_ESCALATIONS escalations without progress; then the stop is
allowed with a systemMessage that says UNVERIFIED/BLOCKED (never success). Claude Code's own cap
(8 consecutive continuations, reset by a tool call) stays underneath.

Escalation is dispatched by this hook, not requested in prose: `claude -p --agent decider --model
claude-opus-5-5 --effort xhigh --output-format json` with NOVA_LOOP_GUARD=0 so the child cannot
recurse. It counts as invoked only if the process exits 0, the JSON result is not an error, the text
carries DECIDED or OWNER_DECISION_REQUIRED, and modelUsage shows claude-opus-5-5 served it. Anything
else keeps ESCALATION_REQUIRED (fail closed, no weaker fallback) and is not re-dispatched for the
unchanged fingerprint.

Test seams: NOVA_LOOP_STATE (state dir), NOVA_LOOP_DISCOVER (frontier JSON file), NOVA_LOOP_BLOCKERS
(JSON list of extra blockers, replaces the delegation/git probes), NOVA_ARCHITECT_CMD (command run
instead of claude; prompt on stdin), NOVA_LOOP_SNAPSHOT (fixed snapshot string). NOVA_LOOP_GUARD=0 off.
"""
import datetime
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))

MAX_NO_PROGRESS = 4
MAX_ESCALATIONS = 2
ARCHITECT_MODEL = "claude-opus-5-5"
ARCHITECT_ARGV = ["claude", "-p", "--agent", "decider", "--model", ARCHITECT_MODEL, "--effort", "xhigh",
                  "--output-format", "json", "--allowedTools", "Read", "Grep", "Glob", "Bash(git:*)"]
ARCHITECT_TIMEOUT = 780
VERDICT = re.compile(r"\b(OWNER_DECISION_REQUIRED|DECIDED)\b")


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def state_dir():
    return os.environ.get("NOVA_LOOP_STATE") or os.path.join(ROOT, ".claude", "state")


def load_state():
    try:
        with open(os.path.join(state_dir(), "loop-guard.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(state):
    os.makedirs(state_dir(), exist_ok=True)
    path = os.path.join(state_dir(), "loop-guard.json")
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=1)
    os.replace(path + ".tmp", path)


def git(*args):
    p = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, timeout=60)
    return p.stdout.strip() if p.returncode == 0 else ""


def snapshot():
    """Objective progress evidence: HEAD, the working-tree diff, the review log."""
    fixed = os.environ.get("NOVA_LOOP_SNAPSHOT")
    if fixed is not None:
        return fixed
    h = hashlib.sha1()
    h.update(git("rev-parse", "HEAD").encode())
    own = ":(exclude)harness/blockers.jsonl"  # the guard's own record is not the agent's progress
    h.update(git("status", "--porcelain=v1", "--", ".", own).encode())
    h.update(git("diff", "HEAD", "--", ".", own).encode())
    try:
        with open(os.path.join(ROOT, "state", "reviews.log"), "rb") as f:
            h.update(f.read())
    except OSError:
        pass
    return h.hexdigest()[:16]


def fingerprint(kind, text):
    norm = re.sub(r"\b\d+ (actionable|commit\(s\))", "N", text.lower())
    norm = re.sub(r"\s+", " ", norm).strip()
    return "%s:%s" % (kind, hashlib.sha1(norm.encode()).hexdigest()[:12])


def blocker(kind, text, task=None, deterministic=False, actionable=True, remedy=""):
    return {"kind": kind, "text": text, "task": task, "deterministic": deterministic,
            "actionable": actionable, "remedy": remedy, "fp": fingerprint(kind, task or text)}


def published(sha):
    """Is the commit already on the branch's upstream? Then only a history rewrite could remove it."""
    up = git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
    if not up:
        return False
    return subprocess.run(["git", "merge-base", "--is-ancestor", sha, up], cwd=ROOT,
                          capture_output=True).returncode == 0


def probe_blockers():
    """Delegation/review gate and git state as blockers. Fails open per probe."""
    env = os.environ.get("NOVA_LOOP_BLOCKERS")
    if env:
        with open(env, encoding="utf-8") as f:
            return [blocker(**b) for b in json.load(f)]
    found = []
    try:
        import delegation_check as dc
        local, history = [], []
        for line in dc.violations():
            (history if published(line.split()[0]) else local).append(line)
        missing, _, invalid = dc.review_findings()
        if history:
            found.append(blocker(
                "history_published", "module source committed outside an executor merge, already published "
                "on the upstream branch: " + "; ".join(history), actionable=False,
                remedy="Only a history rewrite could remove these and AGENTS.md forbids it; recorded, not "
                       "blocking. A reviewed BASELINE change in scripts/delegation_check.py is the owner's call."))
        if local:
            found.append(blocker("delegation", "unpublished commits wrote module source outside an executor "
                                 "merge: " + "; ".join(local),
                                 remedy="Move the change to an exec/* branch, review it, merge it with the "
                                        "review record. Do not rewrite published history."))
        if missing or invalid:
            found.append(blocker("review_record", "executor merges without a valid review record: "
                                 + "; ".join(missing + invalid),
                                 remedy="Run the reviewer on the branch tip, then record PASS (or WAIVED with "
                                        "a reason) via scripts/review_packet.py record."))
    except Exception:
        pass
    try:
        import git_blocker
        for kind, detail in git_blocker.diagnose(ROOT):
            if kind in ("merge_conflict", "detached_head"):
                det, remedy = git_blocker.RECOVERY[kind]
                found.append(blocker("git_" + kind, detail, deterministic=det, remedy=remedy))
    except Exception:
        pass
    return found


def frontier():
    path = os.environ.get("NOVA_LOOP_DISCOVER")
    if path:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    out = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "discover_work.py"), "--json"],
                         capture_output=True, text=True, timeout=100, cwd=ROOT).stdout
    return json.loads(out)


def frontier_blockers(front, blocked):
    """The first few frontier candidates that are not BLOCKED: independent work continues past a blocked task."""
    stop = (front or {}).get("stop") or {}
    if stop.get("AUTONOMOUS_ACTION_AVAILABLE") != "YES":
        return []
    out = []
    for c in (front.get("candidates") or []):
        if c.get("blocked_by"):
            continue
        summary = c.get("summary", "") or ""
        remedy = ("Stale evidence, nothing to implement: re-measure on CI with a manual workflow dispatch on the "
                  "working branch (docs/CI.md), then test_matrix --bind on PASS or set FAIL; take independent "
                  "work while it runs." if summary.startswith("STALE_BIND")
                  else "Follow skills/continue.md: executor packet, verify, hand off.")
        b = blocker("frontier", "%s: %s" % (c.get("item"), summary), task=c.get("item"), remedy=remedy)
        if b["fp"] not in blocked:
            out.append(b)
        if len(out) >= 5:
            break
    return out


def dispatch_architect(b, entry):
    """Run the decider for real and verify it. Returns (ok, verdict, detail)."""
    prompt = (
        "ESCALATION from scripts/loop_guard.py (automatic; the same blocker repeated without objective "
        "progress).\nBLOCKER_KIND: %s\nTASK: %s\nBLOCKER: %s\nKNOWN_REMEDY: %s\nATTEMPTS_WITHOUT_PROGRESS: %d\n"
        "Read the binding documents yourself (AGENTS.md, docs/ARCHITECTURE.md, docs/INVARIANTS.md). Decide the "
        "recovery the planner should run, or whether this needs the owner. Never propose destructive git "
        "(reset, force-push, history rewrite) or bypassing a review gate. Answer with your usual block: "
        "VERDICT: DECIDED or VERDICT: OWNER_DECISION_REQUIRED, then the decision and the steps."
        % (b["kind"], b.get("task") or "-", b["text"][:3000], b.get("remedy") or "-", entry["count"]))
    cmd = os.environ.get("NOVA_ARCHITECT_CMD")
    argv = shlex.split(cmd) if cmd else list(ARCHITECT_ARGV)
    env = dict(os.environ, NOVA_LOOP_GUARD="0", NOVA_KEEP_GOING="0")
    try:
        p = subprocess.run(argv, input=prompt, capture_output=True, text=True, timeout=ARCHITECT_TIMEOUT,
                           cwd=ROOT, env=env)
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, None, "architect not invoked: %s" % type(e).__name__
    if p.returncode != 0:
        return False, None, "architect exited %d" % p.returncode
    try:
        res = json.loads(p.stdout)
    except ValueError:
        return False, None, "architect output is not JSON"
    if res.get("is_error"):
        return False, None, "architect returned an error result"
    models = list((res.get("modelUsage") or {}).keys())
    if not any(m.startswith(ARCHITECT_MODEL) for m in models):
        return False, None, "architect not served by %s (served: %s); no weaker fallback accepted" % (
            ARCHITECT_MODEL, ", ".join(models) or "unknown")
    text = res.get("result") or ""
    m = VERDICT.search(text)
    if not m:
        return False, None, "architect returned no DECIDED/OWNER_DECISION_REQUIRED verdict"
    os.makedirs(os.path.join(state_dir(), "escalations"), exist_ok=True)
    path = os.path.join(state_dir(), "escalations", b["fp"].replace(":", "_") + ".json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"at": now(), "fingerprint": b["fp"], "argv": argv, "models": models, "verdict": m.group(1),
                   "result": text}, f, ensure_ascii=False, indent=1)
    return True, m.group(1), text


def record_blocked(b, entry, cause):
    path = os.environ.get("NOVA_LOOP_BLOCKERS_LOG") or os.path.join(ROOT, "harness", "blockers.jsonl")
    try:
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"at": now(), "fingerprint": b["fp"], "kind": b["kind"], "task": b.get("task"),
                                "status": entry["status"], "cause": cause[:500],
                                "attempts": entry["count"]}, ensure_ascii=False) + "\n")
    except OSError:
        pass


def decide(event, sess, snap, blockers):
    """(block_reason or None, system_message or None). Mutates sess."""
    fps = sess.setdefault("fingerprints", {})
    progressed = sess.get("last_snapshot") not in (None, snap)
    if sess.get("last_snapshot") is None or progressed:
        sess["last_progress"] = {"at": now(), "snapshot": snap}
        sess["no_progress_blocks"] = 0
        sess["escalations_without_progress"] = 0
        for e in fps.values():  # a verified recovery resets the failure state of everything still open
            if e["status"] in ("BOUNDED_RECOVERY", "PLANNER_RECOVERY"):
                e["count"] = 0
                e["status"] = "EXECUTING"
    sess["last_snapshot"] = snap
    notes = []

    for b in blockers:
        if not b["actionable"]:
            e = fps.setdefault(b["fp"], {"status": "EXECUTING", "count": 0, "kind": b["kind"]})
            if e["status"] != "BLOCKED":
                e["status"] = "BLOCKED"
                record_blocked(b, e, b.get("remedy") or b["text"])
            notes.append("BLOCKED (not agent-actionable): %s" % b["text"][:300])

    if sess.get("no_progress_blocks", 0) >= MAX_NO_PROGRESS:
        notes.append("Loop guard: %d consecutive continuations without objective progress; stop allowed as "
                     "UNVERIFIED, not done." % sess["no_progress_blocks"])
        return None, " | ".join(notes)

    for b in blockers:
        if not b["actionable"]:
            continue
        e = fps.setdefault(b["fp"], {"status": "EXECUTING", "count": 0, "kind": b["kind"]})
        e.update(task=b.get("task"), role=event.get("agent_type") or "planner", last_seen=now())
        if e["status"] in ("BLOCKED", "ESCALATION_REQUIRED"):
            continue  # never retried unchanged; independent work goes next
        if e["status"] == "PLANNER_RECOVERY":
            if e.get("recovery_snapshot") == snap:
                e["status"] = "BLOCKED"
                record_blocked(b, e, "decider recovery applied without objective progress")
                notes.append("BLOCKED after recovery: %s" % b["text"][:200])
                continue
            e["recovery_snapshot"] = snap
            return block(sess, "Decider recovery for %s is still open. Apply it through an executor and verify "
                         "it; if it cannot be done, say so and take independent work." % b["fp"]), _join(notes)
        e["count"] = e.get("count", 0) + 1
        if e["count"] == 1:
            e["status"] = "BOUNDED_RECOVERY"
            return block(sess, "%s (attempt 1; the same blocker again without objective progress escalates "
                         "%s). Remedy: %s" % (b["text"][:1500],
                                              "to BLOCKED" if b["deterministic"] else "to the decider",
                                              b.get("remedy") or "-")), _join(notes)
        if b["deterministic"]:
            if e["count"] >= 3:
                e["status"] = "BLOCKED"
                record_blocked(b, e, "deterministic remedy did not work twice")
                continue
            return block(sess, "%s (attempt %d of 2, deterministic). Remedy: %s"
                         % (b["text"][:1500], e["count"], b.get("remedy"))), _join(notes)
        prior = e.get("escalation") or {}
        if prior.get("invoked") and prior.get("verdict") == "DECIDED":
            # already decided for this blocker: re-issue the stored verdict, never re-dispatch (unchanged blocker)
            e["status"] = "PLANNER_RECOVERY"
            e["recovery_snapshot"] = snap
            return block(sess, "Blocker %s repeats; the decider already decided it (%s). Apply that stored "
                         "verdict (.claude/state/escalations/), do not re-ask:\n%s"
                         % (b["fp"], prior.get("at"), prior.get("detail", ""))), _join(notes)
        if sess.get("escalations_without_progress", 0) >= MAX_ESCALATIONS:
            notes.append("Escalation budget without progress used; %s left at ESCALATION_REQUIRED" % b["fp"])
            e["status"] = "ESCALATION_REQUIRED"
            continue
        e["status"] = "ESCALATION_REQUIRED"
        sess["escalations_without_progress"] = sess.get("escalations_without_progress", 0) + 1
        ok, verdict, detail = dispatch_architect(b, e)
        e["escalation"] = {"at": now(), "invoked": ok, "verdict": verdict, "detail": detail[:300]}
        if not ok:
            record_blocked(b, e, "ESCALATION_REQUIRED, dispatch failed: " + detail)
            notes.append("ESCALATION_REQUIRED for %s: %s (fail closed)" % (b["fp"], detail))
            continue
        if verdict == "OWNER_DECISION_REQUIRED":
            e["status"] = "BLOCKED"
            record_blocked(b, e, "decider: OWNER_DECISION_REQUIRED")
            notes.append("Owner decision required for %s; see .claude/state/escalations/" % b["fp"])
            continue
        e["status"] = "PLANNER_RECOVERY"
        e["recovery_snapshot"] = snap
        return block(sess, "Decider (Opus 5.5 xhigh) verdict for the repeated blocker %s. Planner: apply this "
                     "recovery through an executor packet and verify it:\n%s" % (b["fp"], detail[:3000])), \
            _join(notes)
    return None, _join(notes)


def _join(notes):
    return " | ".join(notes) or None


def block(sess, reason):
    sess["no_progress_blocks"] = sess.get("no_progress_blocks", 0) + 1
    return reason


def main():
    if os.environ.get("NOVA_LOOP_GUARD") == "0":
        return 0
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    state = load_state()
    sid = str(event.get("session_id") or "nosession")
    sess = state.setdefault(sid, {})
    sess["branch"] = git("branch", "--show-current") if "NOVA_LOOP_SNAPSHOT" not in os.environ else "test"
    sess["commit"] = git("rev-parse", "--short", "HEAD") if "NOVA_LOOP_SNAPSHOT" not in os.environ else "test"
    try:
        snap = snapshot()
        blocked = {fp for fp, e in sess.get("fingerprints", {}).items()
                   if e["status"] in ("BLOCKED", "ESCALATION_REQUIRED")}
        blockers = probe_blockers()
        try:
            blockers += frontier_blockers(frontier(), blocked)
        except Exception:
            pass
        sess["blocked_dependencies"] = sorted(blocked)
        reason, msg = decide(event, sess, snap, blockers)
    except Exception as e:  # a broken guard must not wedge a session
        reason, msg = None, "loop_guard error (stop allowed, UNVERIFIED): %s" % type(e).__name__
    save_state(state)
    out = {}
    if reason:
        out.update(decision="block", reason=reason)
    if msg:
        out["systemMessage"] = msg
    if out:
        print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
