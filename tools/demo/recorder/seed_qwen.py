"""Put the Qwen workspace id and API key into the debug app. Prints status codes only.

Reads DASHSCOPE_WORKSPACE_ID and DASHSCOPE_API_KEY from the environment. Writes them to a
temp file, copies that into the app's files dir, and asks the debug receiver to store them.
The key and the workspace id are never printed. The debug receiver also records the Qwen
cross-border consent, so the script needs --accept-consent: the owner accepts it by running it.

    python seed_qwen.py --accept-consent
"""
import os, subprocess, sys, tempfile

ADB = r"C:\Users\Administrator\Android\Sdk\platform-tools\adb.exe"
PKG = "com.novadrive.app"
REMOTE = "/data/local/tmp/qwen_setup.txt"


def adb(*args, timeout=30):
    return subprocess.run([ADB, "-e", *args], capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=timeout)


def main():
    if "--accept-consent" not in sys.argv[1:]:
        sys.exit("this stores the Qwen key and accepts the cross-border consent; rerun with --accept-consent")
    workspace = os.environ.get("DASHSCOPE_WORKSPACE_ID", "").strip()
    key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if not workspace or not key:
        sys.exit("need DASHSCOPE_WORKSPACE_ID and DASHSCOPE_API_KEY")
    fd, path = tempfile.mkstemp(suffix=".txt")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(workspace + "\n" + key + "\n")
        pushed = adb("push", path, REMOTE)
        if pushed.returncode != 0:
            sys.exit("push failed")
        # run-as runs as the app's uid, so the pushed file must be readable to it for the copy.
        # It lives for this one copy and is removed in the finally below.
        adb("shell", "chmod", "644", REMOTE)
        copied = adb("shell", "run-as", PKG, "cp", REMOTE, "files/qwen_setup.txt")
        if copied.returncode != 0:
            sys.exit("copy failed")
    finally:
        os.remove(path)
        adb("shell", "rm", "-f", REMOTE)
    adb("logcat", "-c")  # so an older run's result line cannot be read as this one
    try:
        adb(
            "shell", "am", "broadcast", "-n", f"{PKG}/.DebugToolReceiver",
            "-a", "com.novadrive.app.DEBUG_TOOL", "--es", "tool", "qwen_setup", "--es", "arg", "apply",
        )
    finally:
        # The receiver deletes the file; if the broadcast never reached it, do not leave the key behind.
        adb("shell", "run-as", PKG, "rm", "-f", "files/qwen_setup.txt")
    log = adb("shell", "logcat", "-d", "-s", "NovaVoice")
    line = next((l for l in reversed(log.stdout.splitlines()) if "tool=qwen_setup" in l), "")
    result = "missing"
    if "result=" in line:
        result = line.split("result=", 1)[1].split()[0]
    print(f"qwen_setup {result}")
    if result != "applied":
        sys.exit(1)


if __name__ == "__main__":
    main()
