"""scripts/loop_guard.py (the single Stop decision engine) and scripts/git_blocker.py.

Realistic Stop payloads (session_id, transcript_path, hook_event_name, stop_hook_active,
last_assistant_message, background_tasks), run as the real hook process.
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GUARD = os.path.join(ROOT, "scripts", "loop_guard.py")
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import git_blocker  # noqa: E402

FAKE_ARCHITECT = r'''
import json, os, sys
prompt = sys.stdin.read()
with open(os.environ["ARCH_LOG"], "a") as f:
    f.write(json.dumps({"argv": sys.argv, "prompt": prompt}) + "\n")
mode = os.environ.get("ARCH_MODE", "decided")
if mode == "crash":
    sys.exit(3)
model = "claude-sonnet-5-5" if mode == "weak" else "claude-opus-5-5"
text = "VERDICT: OWNER_DECISION_REQUIRED\nOWNER_QUESTION: x" if mode == "owner" else \
    "VERDICT: DECIDED\nDECISION: run the frontier item through an executor packet"
print(json.dumps({"type": "result", "is_error": False, "result": text, "modelUsage": {model: {}}}))
'''


def frontier(items=("AFFORDANCE-DEVICE-001", "APPS-001")):
    return {"stop": {"AUTONOMOUS_ACTION_AVAILABLE": "YES" if items else "NO", "NEXT_ACTION": "x",
                     "actionable_count": len(items)},
            "candidates": [{"item": i, "summary": "FAIL: s", "blocked_by": None} for i in items]}


def stop_event(sid="s1", active=False, msg="Done."):
    return {"session_id": sid, "transcript_path": "/nonexistent.jsonl", "cwd": ROOT,
            "permission_mode": "default", "hook_event_name": "Stop", "stop_hook_active": active,
            "last_assistant_message": msg, "background_tasks": [], "session_crons": []}


class LoopGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.arch = os.path.join(self.tmp, "arch.py")
        with open(self.arch, "w") as f:
            f.write(FAKE_ARCHITECT)
        self.arch_log = os.path.join(self.tmp, "arch.log")
        self.blockers_log = os.path.join(self.tmp, "blockers.jsonl")
        self.set_frontier(frontier())
        self.set_blockers([])
        self.snap = "s0"
        self.mode = "decided"

    def set_frontier(self, data):
        self.discover = os.path.join(self.tmp, "dw.json")
        with open(self.discover, "w") as f:
            json.dump(data, f)

    def set_blockers(self, rows):
        self.blockers = os.path.join(self.tmp, "blockers.json")
        with open(self.blockers, "w") as f:
            json.dump(rows, f)

    def run_guard(self, event=None, extra=None):
        env = dict(os.environ, NOVA_LOOP_STATE=self.tmp, NOVA_LOOP_DISCOVER=self.discover,
                   NOVA_LOOP_SNAPSHOT=self.snap, NOVA_ARCHITECT_CMD="%s %s" % (sys.executable, self.arch),
                   ARCH_LOG=self.arch_log, ARCH_MODE=self.mode, NOVA_LOOP_BLOCKERS_LOG=self.blockers_log)
        env.pop("NOVA_LOOP_GUARD", None)
        env["NOVA_LOOP_BLOCKERS"] = self.blockers  # replaces the delegation/git probes
        env["NOVA_LOOP_OBJECTIVE"] = getattr(self, "objective_path", os.path.join(self.tmp, "no-objective.json"))
        env.update(extra or {})
        p = subprocess.run([sys.executable, GUARD], input=json.dumps(event or stop_event()),
                           capture_output=True, text=True, env=env, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr)
        return json.loads(p.stdout) if p.stdout.strip() else {}

    def arch_calls(self):
        if not os.path.exists(self.arch_log):
            return []
        return [json.loads(l) for l in open(self.arch_log)]

    def state(self):
        return json.load(open(os.path.join(self.tmp, "loop-guard.json")))["s1"]

    # --- required behaviours -------------------------------------------------------------------

    def test_normal_work_continues_while_progress_is_made(self):
        for i in range(6):
            self.snap = "s%d" % i  # every stop follows a commit / tree change
            r = self.run_guard(stop_event(active=i > 0))
            self.assertEqual(r.get("decision"), "block", i)
            self.assertIn("AFFORDANCE-DEVICE-001", r["reason"])
        self.assertEqual(self.arch_calls(), [])

    def test_stale_bind_remedy_is_remeasure_not_executor_and_never_escalates(self):
        data = frontier()
        for c in data["candidates"]:
            c["summary"] = "STALE_BIND: s"
        self.set_frontier(data)
        for i in range(4):
            last = self.run_guard(stop_event(active=i > 0))
        self.assertEqual(self.arch_calls(), [])
        r = self.run_guard(stop_event(sid="s2"))
        self.assertIn("re-measure on CI", r["reason"])
        self.assertNotIn("executor packet", r["reason"])

    def test_one_failure_does_not_escalate(self):
        r = self.run_guard()
        self.assertEqual(r["decision"], "block")
        self.assertIn("attempt 1", r["reason"])
        self.assertEqual(self.arch_calls(), [])
        self.assertEqual(self.state()["fingerprints"][self.fp()]["status"], "BOUNDED_RECOVERY")

    def fp(self, item="AFFORDANCE-DEVICE-001"):
        return [k for k, v in self.state()["fingerprints"].items() if v.get("task") == item][0]

    def test_same_blocker_twice_dispatches_opus_xhigh_and_returns_verdict_to_planner(self):
        self.run_guard()
        r = self.run_guard(stop_event(active=True))  # same snapshot: no progress
        calls = self.arch_calls()
        self.assertEqual(len(calls), 1)
        self.assertIn("AFFORDANCE-DEVICE-001", calls[0]["prompt"])
        self.assertEqual(r["decision"], "block")
        self.assertIn("Decider (Opus 5.5 xhigh) verdict", r["reason"])
        e = self.state()["fingerprints"][self.fp()]
        self.assertEqual(e["status"], "PLANNER_RECOVERY")
        self.assertTrue(e["escalation"]["invoked"])
        self.assertTrue(glob.glob(os.path.join(self.tmp, "escalations", "*.json")))

    def test_default_dispatch_is_decider_on_opus_xhigh(self):
        import loop_guard
        argv = loop_guard.ARCHITECT_ARGV
        self.assertEqual(argv[argv.index("--model") + 1], "claude-opus-5-5")
        self.assertEqual(argv[argv.index("--effort") + 1], "xhigh")
        self.assertEqual(argv[argv.index("--agent") + 1], "decider")

    def test_unavailable_architect_fails_closed(self):
        self.mode = "crash"
        self.run_guard()
        r = self.run_guard(stop_event(active=True))
        e = self.state()["fingerprints"][self.fp()]
        self.assertEqual(e["status"], "ESCALATION_REQUIRED")
        self.assertFalse(e["escalation"]["invoked"])
        self.assertIn("ESCALATION_REQUIRED", r.get("systemMessage", ""))
        # independent task continues instead of the failed one
        self.assertIn("APPS-001", r.get("reason", ""))

    def test_weaker_model_is_not_accepted_as_escalation(self):
        self.mode = "weak"
        self.run_guard()
        self.run_guard(stop_event(active=True))
        e = self.state()["fingerprints"][self.fp()]
        self.assertEqual(e["status"], "ESCALATION_REQUIRED")
        self.assertIn("no weaker fallback", e["escalation"]["detail"])

    def test_unchanged_blocker_is_not_escalated_again(self):
        self.mode = "crash"
        for i in range(6):
            self.run_guard(stop_event(active=i > 0))
        prompts = [c["prompt"] for c in self.arch_calls()]
        self.assertEqual(sum("AFFORDANCE-DEVICE-001" in p for p in prompts), 1)

    def test_decided_blocker_is_not_re_escalated_after_unrelated_progress(self):
        self.run_guard()
        self.run_guard(stop_event(active=True))  # escalated, DECIDED
        self.snap = "s1"  # progress elsewhere resets the counter
        self.run_guard(stop_event(active=True))  # attempt 1 again
        r = self.run_guard(stop_event(active=True))  # repeats without progress
        self.assertEqual(len(self.arch_calls()), 1)
        self.assertIn("already decided", r["reason"])

    def test_owner_decision_marks_blocked_and_records_it(self):
        self.mode = "owner"
        self.run_guard()
        self.run_guard(stop_event(active=True))
        self.assertEqual(self.state()["fingerprints"][self.fp()]["status"], "BLOCKED")
        rows = [json.loads(l) for l in open(self.blockers_log)]
        self.assertEqual(rows[-1]["task"], "AFFORDANCE-DEVICE-001")

    def test_successful_recovery_resets_failure_state(self):
        self.run_guard()
        self.snap = "s1"  # objective progress
        r = self.run_guard(stop_event(active=True))
        self.assertIn("attempt 1", r["reason"])
        self.assertEqual(self.arch_calls(), [])
        self.assertEqual(self.state()["no_progress_blocks"], 1)

    def test_independent_task_remains_executable_after_block(self):
        self.mode = "owner"
        self.run_guard()
        r = self.run_guard(stop_event(active=True))  # first task BLOCKED -> the next one, same stop
        self.assertIn("APPS-001", r["reason"])
        self.assertIn("Owner decision required", r["systemMessage"])

    def test_repeated_stop_hook_cannot_loop_forever(self):
        self.mode = "owner"
        self.set_frontier(frontier(tuple("T-%d" % i for i in range(40))))
        blocks = 0
        for i in range(30):  # no objective progress at all
            r = self.run_guard(stop_event(active=True))
            blocks += r.get("decision") == "block"
        import loop_guard
        self.assertLessEqual(blocks, loop_guard.MAX_NO_PROGRESS)
        self.assertNotEqual(r.get("decision"), "block")
        self.assertIn("UNVERIFIED", r.get("systemMessage", ""))
        self.assertLessEqual(len(self.arch_calls()), loop_guard.MAX_ESCALATIONS)

    def test_published_history_violation_never_blocks(self):
        self.set_frontier(frontier(()))
        self.set_blockers([{"kind": "history_published", "text": "bf81876 Freeze Qwen", "actionable": False}])
        for _ in range(3):
            r = self.run_guard(stop_event(active=True))
            self.assertNotEqual(r.get("decision"), "block")
            self.assertIn("BLOCKED (not agent-actionable)", r["systemMessage"])
        self.assertEqual(len(open(self.blockers_log).readlines()), 1)  # recorded once

    def test_deterministic_blocker_skips_escalation(self):
        self.set_frontier(frontier(()))
        self.set_blockers([{"kind": "git_detached_head", "text": "HEAD (no branch)", "deterministic": True}])
        for i in range(5):
            self.run_guard(stop_event(active=i > 0))
        self.assertEqual(self.arch_calls(), [])
        self.assertEqual(list(self.state()["fingerprints"].values())[0]["status"], "BLOCKED")

    def test_no_autonomous_work_allows_stop(self):
        self.set_frontier(frontier(()))
        self.assertEqual(self.run_guard(), {})

    def set_objective(self, passes):
        self.objective_path = os.path.join(self.tmp, "objective.json")
        with open(self.objective_path, "w") as f:
            json.dump({"objective": "finish the iOS spec", "done_check": "exit %d" % (0 if passes else 1)}, f)

    def test_objective_replaces_the_frontier(self):
        self.set_objective(passes=False)
        r = self.run_guard()
        self.assertIn("Objective not done", r["reason"])
        self.assertNotIn("AFFORDANCE-DEVICE-001", r["reason"])

    def test_finished_objective_allows_stop_despite_frontier(self):
        self.set_objective(passes=True)
        self.assertEqual(self.run_guard(), {})

    def test_disabled_in_child_sessions(self):
        p = subprocess.run([sys.executable, GUARD], input=json.dumps(stop_event()), capture_output=True,
                           text=True, env=dict(os.environ, NOVA_LOOP_GUARD="0"), timeout=30)
        self.assertEqual(p.stdout.strip(), "")


class SnapshotTest(unittest.TestCase):
    def test_guard_own_record_is_not_progress(self):
        import loop_guard
        env_backup = os.environ.pop("NOVA_LOOP_SNAPSHOT", None)
        path = os.path.join(ROOT, "harness", "blockers.jsonl")
        original = open(path, "rb").read() if os.path.exists(path) else None
        try:
            before = loop_guard.snapshot()
            with open(path, "a") as f:
                f.write("{}\n")
            self.assertEqual(before, loop_guard.snapshot())
        finally:
            if original is None:
                os.remove(path)
            else:
                open(path, "wb").write(original)
            if env_backup is not None:
                os.environ["NOVA_LOOP_SNAPSHOT"] = env_backup


class GitBlockerTest(unittest.TestCase):
    def sh(self, repo, *args):
        return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True,
                              env=dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t",
                                       GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t"))

    def test_conflict_is_classified_and_existing_changes_are_preserved(self):
        repo = tempfile.mkdtemp()
        self.sh(repo, "init", "-q", "-b", "main")
        open(os.path.join(repo, "f"), "w").write("base\n")
        self.sh(repo, "add", "f"); self.sh(repo, "commit", "-qm", "base")
        self.sh(repo, "checkout", "-qb", "other")
        open(os.path.join(repo, "f"), "w").write("theirs\n")
        self.sh(repo, "commit", "-qam", "theirs")
        self.sh(repo, "checkout", "-q", "main")
        open(os.path.join(repo, "f"), "w").write("ours\n")
        self.sh(repo, "commit", "-qam", "ours")
        open(os.path.join(repo, "untracked-work"), "w").write("keep me\n")
        merge = self.sh(repo, "merge", "other")
        self.assertEqual(git_blocker.classify_text(merge.stdout + merge.stderr), "merge_conflict")
        before = (open(os.path.join(repo, "f")).read(), self.sh(repo, "status", "--porcelain").stdout)
        kinds = [k for k, _ in git_blocker.diagnose(repo)]
        self.assertIn("merge_conflict", kinds)
        after = (open(os.path.join(repo, "f")).read(), self.sh(repo, "status", "--porcelain").stdout)
        self.assertEqual(before, after)  # diagnosis is read-only
        self.assertEqual(open(os.path.join(repo, "untracked-work")).read(), "keep me\n")
        self.assertIn("ours", before[0])
        self.assertIn("theirs", before[0])

    def test_text_classes(self):
        cases = {
            "error: Your local changes to the following files would be overwritten by merge": "uncommitted_changes",
            "fatal: Need to specify how to reconcile divergent branches.": "diverged",
            "There is no tracking information for the current branch.": "no_upstream",
            "fatal: Authentication failed for 'https://github.com/x'": "auth_failure",
            "remote: Permission to x.git denied to bot. fatal: ... HTTP 403": "permission_denied",
            "fatal: unable to access: Could not resolve host: github.com": "network",
            "fatal: 'b' is already checked out at '/w'": "worktree_mismatch",
            "! [remote rejected] main (pre-receive hook declined)": "hook_rejection",
        }
        for text, kind in cases.items():
            self.assertEqual(git_blocker.classify_text(text), kind, text)

    def test_no_recovery_is_destructive(self):
        bad = re.compile(r"`git (reset --hard|push (-f|--force)|clean -f|checkout -- \.)")
        for kind, (_, recovery) in git_blocker.RECOVERY.items():
            self.assertIsNone(bad.search(recovery), kind)


class ModelHierarchyTest(unittest.TestCase):
    EXPECTED = {"executor": "low", "planner": "high", "decider": "xhigh", "reviewer": "high"}

    def test_agents_are_opus_with_the_required_effort(self):
        for name, effort in self.EXPECTED.items():
            text = open(os.path.join(ROOT, ".claude", "agents", name + ".md"), encoding="utf-8").read()
            front = text.split("---")[1]
            self.assertIn("model: claude-opus-5-5", front, name)
            self.assertIn("effort: %s" % effort, front, name)

    def test_no_sonnet_or_haiku_fallback_in_claude_config(self):
        settings = json.load(open(os.path.join(ROOT, ".claude", "settings.json")))
        self.assertEqual(settings.get("model"), "claude-opus-5-5")
        self.assertNotIn("fallbackModel", settings)
        blob = json.dumps(settings) + "".join(
            open(p, encoding="utf-8").read().split("---")[1] for p in glob.glob(os.path.join(ROOT, ".claude", "agents", "*.md")))
        self.assertIsNone(re.search(r"sonnet|haiku", blob, re.I))

    def test_one_continuation_stop_hook(self):
        settings = json.load(open(os.path.join(ROOT, ".claude", "settings.json")))
        cmds = [h["command"] for g in settings["hooks"]["Stop"] for h in g["hooks"]]
        self.assertEqual(len(cmds), 1, cmds)
        self.assertIn("loop_guard.py", cmds[0])


if __name__ == "__main__":
    unittest.main()
