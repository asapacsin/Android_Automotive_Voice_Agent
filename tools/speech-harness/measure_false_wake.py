"""B-012 — how often does the wake word fire when nobody said it?

The half of wake-word reliability that does not need a person: leave the engine listening with
the car's own music playing through the speaker, and count how many times it wakes. A false accept
here means the assistant interrupts the driver unprompted, which is worse than a missed wake.

    python tools/speech-harness/measure_false_wake.py --minutes 15
"""
import argparse
import os
import re
import shutil
import subprocess
import time

# ADB env, else adb on PATH (Linux/cloud), else the Windows build PC. Device choice: ANDROID_SERIAL.
ADB = os.environ.get("ADB") or shutil.which("adb") or r"C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe"
PKG = "com.novadrive.app"
RECEIVER = f"{PKG}/.DebugToolReceiver"


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def broadcast(tool, arg):
    adb("shell", "am", "broadcast", "-n", RECEIVER,
        "-a", "com.novadrive.app.DEBUG_TOOL", "--es", "tool", tool, "--es", "arg", arg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=15.0)
    args = ap.parse_args()

    adb("shell", "am", "force-stop", PKG)
    # Clear BEFORE launching, and start the activity explicitly. The first version cleared after
    # launch and lost `wake_capture_started`, so its own liveness check reported the engine dead
    # while it was listening perfectly - a measurement that would have been thrown away for
    # nothing, which is the good failure mode for a check like this to have.
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(10)
    broadcast("wake", "on")
    time.sleep(3)
    broadcast("control_music", "play")
    print(f"listening for {args.minutes} minutes with music playing", flush=True)

    deadline = time.time() + args.minutes * 60
    while time.time() < deadline:
        time.sleep(30)
        out = adb("logcat", "-d", "-s", "NovaVoice").stdout
        wakes = len(re.findall(r"wake_detection", out))
        remaining = int(deadline - time.time())
        print(f"  {remaining:5}s left  false_wakes={wakes}", flush=True)

    out = adb("logcat", "-d", "-s", "NovaVoice").stdout
    broadcast("control_music", "stop")
    wakes = re.findall(r"wake_detection", out)
    errors = re.findall(r"wake_session_error code=(\d+)", out)
    restarts = len(re.findall(r"wake_restart", out))
    print("\n=== result ===")
    print(f"  duration        {args.minutes} min, music playing throughout")
    print(f"  false wakes     {len(wakes)}")
    print(f"  engine errors   {len(errors)} {sorted(set(errors))}")
    print(f"  engine restarts {restarts}")
    # A wake engine that quietly stopped listening would also report zero false accepts, so the
    # run is only meaningful if the engine was still alive at the end.
    alive = "wake_capture_started" in out
    print(f"  engine started  {alive}")
    return 0 if alive else 1


if __name__ == "__main__":
    raise SystemExit(main())
