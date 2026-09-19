"""SPEC-008 — run the scenario set against the live model and report pass/fail.

Every scenario is a real Baidu call and spends quota. This is for before a release and after a
change to the turn machinery; the simulation benchmark is the one that runs on every build.

    python tools/speech-harness/run_scenarios.py                 # once
    python tools/speech-harness/run_scenarios.py --repeat 5      # flakiness as a rate
    python tools/speech-harness/run_scenarios.py --only S3 S11
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time

ADB = r"C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe"
PKG = "com.novadrive.app"
RECEIVER = f"{PKG}/.DebugToolReceiver"
HERE = os.path.dirname(os.path.abspath(__file__))
CLIPS = os.path.join(HERE, "speech")
REMOTE = f"/sdcard/Android/data/{PKG}/files/test_speech"


def adb(*args, timeout=60):
    return subprocess.run(
        [ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    )


def broadcast(tool, arg):
    adb("shell", "am", "broadcast", "-n", RECEIVER,
        "-a", "com.novadrive.app.DEBUG_TOOL", "--es", "tool", tool, "--es", "arg", arg)


def log_since(marker):
    """NovaVoice lines after `marker` was printed, so scenarios cannot read each other's output."""
    out = adb("logcat", "-d", "-s", "NovaVoice").stdout
    lines = [re.sub(r"^.*D NovaVoice: ", "", ln) for ln in out.splitlines() if "NovaVoice" in ln]
    for i in range(len(lines) - 1, -1, -1):
        if marker in lines[i]:
            return lines[i + 1:]
    return lines


def push_clip(name):
    local = os.path.join(CLIPS, name + ".pcm")
    if not os.path.isfile(local):
        return f"missing clip: {local}"
    adb("push", local, f"{REMOTE}/{name}.pcm")
    return None


def run_scenario(sc, wait):
    # Re-arm first. 「休眠」 is a scenario, and a sleeping session ignores everything that
    # follows - which on the first run silently invalidated the three scenarios after it.
    # start() resumes a session in SILENT_WAIT or SLEEP, is a no-op for an active one, and
    # costs no model turn.
    broadcast("voice", "start")
    # Long enough for a session opened from DEEP_IDLE to reach LISTENING: injecting during
    # setup gets the response cancelled (status=cancelled reason=client_cancelled).
    time.sleep(6)
    for step in sc.get("setup", []):
        if step.startswith("dispatch:"):
            broadcast("dispatch", step[len("dispatch:"):])
        elif step.startswith("climate:"):
            broadcast("climate", step[len("climate:"):])
        time.sleep(2)
    # A scenario may be several utterances: cross-turn context only exists if a *driver* turn
    # produced it, so 「再凉一点」 has to follow a real 「有点热」 rather than a debug state poke.
    clips = sc["say"] if isinstance(sc["say"], list) else [sc["say"]]
    for clip in clips:
        missing = push_clip(clip)
        if missing:
            return False, [missing], []
    marker = f"scenario_marker_{sc['id']}_{int(time.time() * 1000)}"
    # A log line of our own is the scenario boundary: reading the whole buffer would let one
    # scenario pass on the previous one's evidence, which is the classic way a suite like this
    # goes quietly green. Dispatching an unknown tool name is the cheapest marker that changes
    # nothing - it returns UNKNOWN_TOOL and touches no state, unlike `turn`, which would write a
    # driver utterance into DriverContext and quietly alter what the next scenario is judged on.
    broadcast("dispatch", marker)
    time.sleep(1)
    for clip in clips:
        broadcast("voice", "say:" + clip)
        time.sleep(wait)
    lines = log_since(marker)
    if lines is None:
        return False, ["scenario marker never reached the log - the app may not be running"], []
    text = "\n".join(lines)
    failures = []
    # MULTILINE so a pattern can anchor to the start of a log line. Without it, forbidding
    # `tool=` also matched the runner's own `debug_tool tool=voice ...` broadcast and reported a
    # product failure that was entirely the harness talking to itself.
    for pattern in sc.get("expect", []):
        if not re.search(pattern, text, re.M):
            failures.append(f"expected /{pattern}/")
    for pattern in sc.get("forbid", []):
        found = re.search(pattern, text, re.M)
        if found:
            failures.append(f"forbidden /{pattern}/ matched {found.group(0)!r}")
    return not failures, failures, lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--wait", type=int, default=14)
    ap.add_argument("--file", default=os.path.join(HERE, "scenarios.json"))
    args = ap.parse_args()

    with open(args.file, encoding="utf-8") as handle:
        scenarios = json.load(handle)["scenarios"]
    if args.only:
        scenarios = [s for s in scenarios if s["id"] in args.only]
    if not scenarios:
        print("no scenarios selected")
        return 2

    results = {s["id"]: [] for s in scenarios}
    details = {}
    for attempt in range(1, args.repeat + 1):
        print(f"\n=== pass {attempt}/{args.repeat} ===", flush=True)
        adb("shell", "am", "force-stop", PKG)
        adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(8)
        adb("logcat", "-c")
        broadcast("voice", "start")
        time.sleep(5)
        for sc in scenarios:
            ok, failures, lines = run_scenario(sc, args.wait)
            results[sc["id"]].append(ok)
            if not ok:
                details[sc["id"]] = (failures, lines[-25:])
            print(f"  {'PASS' if ok else 'FAIL'}  {sc['id']:5} {sc['utterance']}", flush=True)
            if not ok:
                for failure in failures:
                    print(f"          {failure}", flush=True)
        broadcast("voice", "stop")
        time.sleep(2)

    print("\n=== summary ===")
    worst = 0
    for sc in scenarios:
        passes = results[sc["id"]]
        rate = sum(passes)
        # A rate, not a verdict: the model is not deterministic and hiding that helps nobody.
        flag = "ok " if rate == len(passes) else ("FLAKY" if rate else "FAIL ")
        print(f"  {flag} {sc['id']:5} {rate}/{len(passes)}  {sc['utterance']}")
        worst = max(worst, 0 if rate == len(passes) else 1)
    if details:
        print("\n=== last failing log per scenario ===")
        for sid, (failures, tail) in details.items():
            print(f"\n-- {sid}: {'; '.join(failures)}")
            for line in tail:
                print(f"   {line}")
    return worst


if __name__ == "__main__":
    sys.exit(main())
