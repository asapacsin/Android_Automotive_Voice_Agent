#!/usr/bin/env python3
"""Observe which command sequences agents repeat, from the transcripts the agent tool already keeps.

    python scripts/skill_observe.py                  # repeated sequences since the last record
    python scripts/skill_observe.py --since 2026-10-17T00:00:00
    python scripts/skill_observe.py --selftest

Why (owner, 2026-10-17, "automatic skill creation"): the skill loop depended on agents remembering
what they did by hand, and it went dormant for four weeks. Community practice (everything-claude-code
continuous-learning-v2, after "homunculus") observes deterministically instead of relying on the model
to notice its own patterns; it uses PreToolUse/PostToolUse hooks. Here the same observation is read
from the Claude Code session transcripts (~/.claude/projects/<repo>/*.jsonl) at handoff, so no hook
or settings change is needed. scripts/skill_event.py stores the repeated sequences in the committed
handoff record; scripts/skill_metrics.py turns a sequence seen in 3 records into a SCRIPT/SKILL
candidate (harness/SKILL_POLICY.md).

A signature keeps only the program and the repository script or Gradle task it ran, so no
credential, coordinate, address or transcript text can be stored (AGENTS.md hard rule):
    python3 scripts/test_matrix.py --gate      ->  scripts/test_matrix.py --gate
    ./gradlew :app:testDebugUnitTest --tests X ->  gradlew :app:testDebugUnitTest
    git worktree add -b x ../nova-wt/T abc     ->  git worktree
    curl -H "Authorization: ..." https://...   ->  curl
"""
import argparse
import collections
import datetime
import glob
import json
import os
import re
import shlex
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EVENTS = os.path.join(REPO, "harness", "skill-events.jsonl")
SAFE_MODES = {"--gate", "--work", "--validate", "--selftest", "--structural", "--verdict", "--bind",
              "--apply-stale", "--dry-run", "--stop-hook", "--rerun-tasks", "--report"}
NOISE = {"cd", "echo", "true", "sleep", "export", "set", "cat", "ls", "head", "tail", "sed", "grep",
         "wc", "awk", "sort", "uniq", "tr", "cut", "find", "date", "test", "[", "mkdir", "rm", "cp",
         "mv", "for", "do", "done", "if", "then", "fi", "until", "while", "python3 -", "other"}
# Other programs worth seeing in a workflow; anything else (shell builtins, text tools, words that
# only look like a command) is dropped.
TOOLS = {"curl", "gh", "adb", "emulator", "avdmanager", "sdkmanager", "unzip", "zip", "tar", "ffmpeg",
         "ffprobe", "node", "npm", "npx", "java", "jq", "make", "docker", "ssh", "scp", "rsync",
         "bash", "sh", "pwsh", "powershell", "claude", "codex"}
WINDOW = 3          # length of the command sequences compared
MIN_REPEATS = 2     # a sequence must recur within the observed work to be reported


def signature(part):
    """Signature of one simple command, or '' when it is noise."""
    try:
        words = shlex.split(part, posix=True)
    except ValueError:
        words = part.split()
    while words and re.match(r"^[A-Z_][A-Z0-9_]*=", words[0]):  # env assignments
        words = words[1:]
    if not words:
        return ""
    while words and os.path.basename(words[0]) in ("timeout", "nohup", "env", "time", "exec"):
        words = [w for w in words[1:] if not re.match(r"^(-\w+|\d+[smh]?|[A-Z_][A-Z0-9_]*=.*)$", w)] \
            if os.path.basename(words[0]) in ("timeout", "env") else words[1:]
    if not words:
        return ""
    prog = os.path.basename(words[0])
    rest = words[1:]
    if prog.startswith("python"):
        script = next((w for w in rest if w.endswith(".py")), None)
        if script is None:
            return ""
        if os.path.isabs(script):
            rel = os.path.relpath(script, REPO)
            script = os.path.basename(script) if rel.startswith("..") else rel
        modes = [w for w in rest if w in SAFE_MODES]
        return " ".join([script] + modes[:2])
    if prog in ("gradlew", "gradlew.bat"):
        tasks = [w for w in rest if re.match(r"^(:[\w:-]+|test|assembleDebug|build|clean|check|lint)$", w)]
        return " ".join(["gradlew"] + tasks[:3])
    if prog == "git":
        sub = next((w for w in rest if not w.startswith("-") and re.match(r"^[a-z-]+$", w)), "")
        if sub in ("status", "log", "diff", "show", "branch", "rev-parse"):
            return ""
        return ("git " + sub).strip()
    if prog.endswith(".sh"):
        return prog
    return prog if prog in TOOLS else ""


HEREDOC = re.compile(r"<<-?\s*['\"]?(\w+)['\"]?[^\n]*\n.*?^\s*\1\s*$", re.S | re.M)


def signatures(command):
    # A heredoc body is data (a script or a message), not commands; keep only its opening line.
    command = HEREDOC.sub(lambda m: m.group(0).split("\n", 1)[0], command)
    out = []
    for part in re.split(r"&&|\|\||;|\||\n", command):
        sig = signature(part.strip())
        if sig:
            out.append(sig)
    return out


def transcript_dir():
    slug = re.sub(r"[^A-Za-z0-9]", "-", REPO)
    return os.path.join(os.path.expanduser("~"), ".claude", "projects", slug)


def commands_since(since):
    """(timestamp, command) of every Bash call in this repository's transcripts after `since`."""
    found = []
    for path in glob.glob(os.path.join(transcript_dir(), "**", "*.jsonl"), recursive=True):
        try:
            if datetime.datetime.fromtimestamp(os.path.getmtime(path)).isoformat() < since:
                continue
            for line in open(path, encoding="utf-8", errors="replace"):
                if '"Bash"' not in line:
                    continue
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                stamp = (entry.get("timestamp") or "")[:19]
                if stamp < since:
                    continue
                for item in (entry.get("message") or {}).get("content") or []:
                    if isinstance(item, dict) and item.get("type") == "tool_use" and item.get("name") == "Bash":
                        found.append((stamp, (item.get("input") or {}).get("command") or ""))
        except OSError:
            continue
    found.sort()
    return found


def repeated_sequences(commands, window=WINDOW, min_repeats=MIN_REPEATS):
    stream = []
    for _, command in commands:
        for sig in signatures(command):
            if not stream or stream[-1] != sig:  # a retried command is one step
                stream.append(sig)
    grams = collections.Counter(
        " -> ".join(stream[i:i + window]) for i in range(len(stream) - window + 1)
        if len(set(stream[i:i + window])) == window
        # commit-and-push is already a rule (AGENTS.md), not a skill candidate
        and not all(step.startswith("git ") for step in stream[i:i + window]))
    return {g: n for g, n in grams.most_common(10) if n >= min_repeats}


def last_record_time():
    if not os.path.isfile(EVENTS):
        return "1970-01-01T00:00:00"
    last = "1970-01-01T00:00:00"
    for line in open(EVENTS, encoding="utf-8"):
        try:
            last = max(last, json.loads(line).get("timestamp", last))
        except ValueError:
            pass
    return last


def selftest():
    bad = 0
    cases = [
        ("python3 scripts/test_matrix.py --gate", ["scripts/test_matrix.py --gate"]),
        ("NOVA_BUILD_DIR=/x ./gradlew :app:testDebugUnitTest --tests Foo", ["gradlew :app:testDebugUnitTest"]),
        ("git worktree add -b x ../nova-wt/T abc", ["git worktree"]),
        ('curl -H "Authorization: token abc" https://e.x/y', ["curl"]),
        ("cd /tmp && python3 scripts/acceptance.py --token SECRET | tail -3", ["scripts/acceptance.py"]),
        ("echo hi; git status --short", []),
        ("timeout 110 python3 scripts/harness_check.py 2>&1 | tail -5", ["scripts/harness_check.py"]),
        ("python3 - <<'PY'\nimport x\nfun()\nPY\ngit add -A", ["git add"]),
        ("git commit -q -F - <<'EOF'\nbreak things\nEOF\ngit push -u origin b", ["git commit", "git push"]),
    ]
    for cmd, want in cases:
        got = signatures(cmd)
        if got != want:
            bad += 1
            print("FAIL %r -> %r (want %r)" % (cmd, got, want))
        for secret in ("SECRET", "token abc", "Authorization", "/x", "nova-wt", "e.x"):
            if any(secret in s for s in got):
                bad += 1
                print("LEAK %r from %r" % (secret, cmd))
    seq = [("t", c) for c in ["git worktree add a", "./gradlew test", "git merge x"] * 2 + ["git push"]]
    reps = repeated_sequences(seq)
    if reps.get("git worktree -> gradlew test -> git merge") != 2:
        bad += 1
        print("FAIL sequence count %r" % reps)
    print("skill_observe selftest: %d problem(s)" % bad)
    return 1 if bad else 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--since", help="ISO time; default: the last handoff record")
    p.add_argument("--selftest", action="store_true")
    args = p.parse_args()
    if args.selftest:
        return selftest()
    since = args.since or last_record_time()
    commands = commands_since(since)
    reps = repeated_sequences(commands)
    print("%d Bash calls since %s; repeated %d-step sequences:" % (len(commands), since, WINDOW))
    for g, n in reps.items():
        print("  %dx  %s" % (n, g))
    if not reps:
        print("  none")
    return 0


if __name__ == "__main__":
    sys.exit(main())
