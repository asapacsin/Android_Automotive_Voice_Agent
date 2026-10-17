#!/usr/bin/env python3
"""PreToolUse(Bash) guard: only an executor writes <module>/src/** (AGENTS.md hard rule, owner 2026-10-16).

The main/planner agent's Bash commands that look like they write module source in the main checkout
are denied. Commands touching only /home/user/nova-wt/ worktrees, and pure reads, pass. Fails open
on unparsable input so a broken hook never blocks all work.
"""
import json
import re
import sys

MODULES = ("app", "behavior-test", "contracts", "demo", "evaluation", "feedback", "ingress",
           "orchestration", "safety", "simulator", "vehicle", "verification")
WORKTREE_ROOT = "/home/user/nova-wt/"
REASON = ("Production/test source in the main checkout is written by an executor in its own "
          "worktree (AGENTS.md hard rule, owner 2026-10-16). Dispatch an executor.")

# A module src path not preceded by a path segment that is inside a nova-wt worktree.
SRC_RE = re.compile(r"(?P<pre>[^\s'\"=<>|;&()]*)(?<![\w-])(?:%s)/src(?:/|\b)"
                    % "|".join(re.escape(m) for m in MODULES))
WRITE_RES = [re.compile(p) for p in (
    r"\bsed\s+(?:-\w*\s+)*-i", r"\bsed\s+-i", r"(?<![0-9&<>-])>>?(?!&)", r"\btee\b", r"\bcp\s",
    r"\bmv\s", r"\bcat\s*>", r"open\([^)]*['\"][wa]b?\+?['\"]", r"write_text|write_bytes",
    r"\bgit\s+apply\b", r"\bpatch\b", r"\bgit\s+checkout\b[^;&|]*\s--\s", r"\bgit\s+restore\b",
    r"\brm\s", r"\btruncate\b", r"\binstall\s",
)]


def touches_main_src(cmd):
    for m in SRC_RE.finditer(cmd):
        if WORKTREE_ROOT.rstrip("/") not in m.group("pre"):
            return True
    return False


def decide(data):
    """Return a deny reason or None."""
    if not isinstance(data, dict) or data.get("tool_name") != "Bash":
        return None
    if data.get("agent_type") == "executor":
        return None
    cmd = (data.get("tool_input") or {}).get("command")
    if not isinstance(cmd, str) or not touches_main_src(cmd):
        return None
    # Drop "2>/dev/null"-style redirections and fd duplications before looking for writes.
    scrubbed = re.sub(r"\d?>>?\s*/dev/null|\d>&\d", " ", cmd)
    if any(r.search(scrubbed) for r in WRITE_RES):
        return REASON
    return None


def selftest():
    def j(cmd, agent=None):
        d = {"tool_name": "Bash", "tool_input": {"command": cmd}}
        if agent:
            d["agent_type"] = agent
        return d
    py = "python3 - <<'PY'\nopen('app/src/test/java/X.kt','w').write('x')\nPY"
    cases = [
        (j("sed -i 's/a/b/' app/src/main/java/A.kt", "executor"), False),
        (j("sed -i 's/a/b/' app/src/main/java/A.kt", "planner"), True),
        (j("sed -i 's/a/b/' /home/user/Android_Automotive_Voice_Agent/app/src/main/A.kt"), True),
        (j(py), True),
        (j("python3 -c \"import pathlib; pathlib.Path('safety/src/main/A.kt').write_text('')\""), True),
        (j("echo x > vehicle/src/main/A.kt"), True),
        (j("git checkout -- app/src/main/A.kt"), True),
        (j("grep -rn foo app/src/main 2>/dev/null"), False),
        (j("cat app/src/main/A.kt"), False),
        (j("sed -n 1,20p app/src/main/A.kt"), False),
        (j("sed -i 's/a/b/' /home/user/nova-wt/T1/app/src/main/A.kt"), False),
        (j("cd /home/user/nova-wt/T1 && cat > /home/user/nova-wt/T1/app/src/test/B.kt <<'E'\nE"), False),
        (j("git diff HEAD~1 -- app/src/main"), False),
        (j("git show HEAD:app/src/main/A.kt; git log -- app/src"), False),
        (j("./gradlew :app:testDebugUnitTest"), False),
        (j("echo hi > docs/x.md"), False),
        ({"tool_name": "Edit"}, False),
    ]
    bad = 0
    for data, want in cases:
        got = decide(data) is not None
        if got != want:
            bad += 1
            print("FAIL want deny=%s: %r" % (want, data))
    print("guard-source-writes selftest: %d/%d ok" % (len(cases) - bad, len(cases)))
    return 1 if bad else 0


def main():
    if "--selftest" in sys.argv:
        return selftest()
    try:
        data = json.load(sys.stdin)
        reason = decide(data)
    except Exception:
        return 0
    if reason:
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse",
                                                 "permissionDecision": "deny",
                                                 "permissionDecisionReason": reason}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
