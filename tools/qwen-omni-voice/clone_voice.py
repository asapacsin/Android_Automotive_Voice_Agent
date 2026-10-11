#!/usr/bin/env python3
"""Clone a custom voice for Qwen-Omni Realtime (Alibaba Model Studio voice enrollment).

Source: https://www.alibabacloud.com/help/en/model-studio/qwen-omni-voice-cloning (read 2026-10-11).
Stdlib only. The API key and workspace id come from the environment and are never printed.
"""
import argparse
import base64
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import wave
from http.server import BaseHTTPRequestHandler, HTTPServer

ALLOWED_MODELS = (
    "qwen3.8-omni-flash-realtime",
    "qwen3.5-omni-plus-realtime",
    "qwen3.5-omni-flash-realtime",
    "qwen3.5-omni-plus",
    "qwen3.5-omni-flash",
)
MIMES = {".wav": "audio/wav", ".mp3": "audio/mpeg", ".m4a": "audio/mp4"}
NAME_RE = re.compile(r"^[A-Za-z0-9_]{1,16}$")
MAX_BYTES = 10 * 1024 * 1024
MIN_RATE = 24000
ENROLL_MODEL = "qwen-voice-enrollment"


def fix_cmd(path):
    return f"ffmpeg -i {path} -ac 1 -ar 24000 -sample_fmt s16 out.wav"


def check(path, out=print):
    """Return True when the clip passes the hard requirements."""
    ok = True
    ext = os.path.splitext(path)[1].lower()
    if not os.path.isfile(path):
        out(f"FAIL file not found: {path}")
        return False
    if ext not in MIMES:
        out(f"FAIL extension {ext or '(none)'} not supported; use .wav (16-bit), .mp3 or .m4a")
        out(f"  fix: {fix_cmd(path)}")
        return False
    size = os.path.getsize(path)
    if size >= MAX_BYTES:
        out(f"FAIL size {size} bytes >= 10 MB")
        ok = False
    else:
        out(f"ok   size {size} bytes")
    channels = rate = dur = None
    if ext == ".wav":
        try:
            with wave.open(path, "rb") as w:
                channels, rate = w.getnchannels(), w.getframerate()
                width = w.getsampwidth()
                dur = w.getnframes() / float(rate) if rate else 0.0
        except (wave.Error, EOFError) as e:
            out(f"FAIL not a readable PCM WAV ({e})")
            out(f"  fix: {fix_cmd(path)}")
            return False
        if width != 2:
            out(f"FAIL sample width {width * 8}-bit, need 16-bit")
            ok = False
        else:
            out("ok   16-bit")
    elif shutil.which("ffprobe"):
        try:
            res = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                 "stream=channels,sample_rate:format=duration", "-of", "json", path],
                capture_output=True, text=True, timeout=30, check=True)
            info = json.loads(res.stdout)
            st = (info.get("streams") or [{}])[0]
            channels = int(st.get("channels", 0))
            rate = int(st.get("sample_rate", 0))
            dur = float(info.get("format", {}).get("duration", 0))
        except (subprocess.SubprocessError, ValueError, OSError) as e:
            out(f"FAIL ffprobe could not read the file ({e.__class__.__name__})")
            return False
    else:
        out("note ffprobe not on PATH: channels, sample rate and duration could not be checked")
        return ok
    if channels != 1:
        out(f"FAIL {channels} channels, need mono")
        ok = False
    else:
        out("ok   mono")
    if rate < MIN_RATE:
        out(f"FAIL sample rate {rate} Hz < {MIN_RATE}")
        ok = False
    else:
        out(f"ok   sample rate {rate} Hz")
    if dur < 10 or dur > 60:
        out(f"FAIL duration {dur:.1f} s, must be 10..60 s (10-20 s recommended)")
        ok = False
    elif dur > 20:
        out(f"warn duration {dur:.1f} s, 10-20 s recommended")
    else:
        out(f"ok   duration {dur:.1f} s")
    if not ok:
        out(f"  fix: {fix_cmd(path)}  (trim to 10-20 s with -t 15 if too long)")
    return ok


# ---------------------------------------------------------------- network

def _url():
    override = os.environ.get("NOVA_VOICE_ENROLL_URL")
    if override:
        return override
    ws = os.environ.get("DASHSCOPE_WORKSPACE_ID")
    if not ws:
        raise SystemExit("error: DASHSCOPE_WORKSPACE_ID is not set")
    return f"https://{ws}.ap-southeast-1.maas.aliyuncs.com/api/v1/services/audio/tts/customization"


def _ctx():
    cafile = os.environ.get("REQUESTS_CA_BUNDLE") or os.environ.get("SSL_CERT_FILE")
    return ssl.create_default_context(cafile=cafile) if cafile else None


def call(body):
    key = os.environ.get("DASHSCOPE_API_KEY")
    if not key:
        raise SystemExit("error: DASHSCOPE_API_KEY is not set")
    req = urllib.request.Request(
        _url(), data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60, context=_ctx()) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            j = json.loads(e.read().decode("utf-8", "replace"))
        except ValueError:
            j = {}
        raise SystemExit(f"error: HTTP {e.code} code={j.get('code')} message={j.get('message')}")
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, "reason", e)
        raise SystemExit(f"error: request to <enrollment endpoint> failed ({type(reason).__name__})")


def data_uri(path):
    mime = MIMES[os.path.splitext(path)[1].lower()]
    with open(path, "rb") as f:
        return f"data:{mime};base64," + base64.b64encode(f.read()).decode("ascii")


def cmd_create(a):
    if not NAME_RE.match(a.name):
        print("error: --name must be 1-16 letters, digits or underscores")
        return 2
    bad = [m for m in a.model if m not in ALLOWED_MODELS]
    if bad:
        print(f"error: unsupported --model {', '.join(bad)}; allowed: {', '.join(ALLOWED_MODELS)}")
        return 2
    if not check(a.file):
        print("error: clip failed the check; nothing was sent")
        return 1
    if not a.yes:
        print("create spends one of the 1000 free voice creations per model; rerun with --yes")
        return 1
    uri = data_uri(a.file)
    result = {}
    for m in a.model:
        body = {"model": ENROLL_MODEL, "parameters": {"voice_clone_mode": "normal"},
                "input": {"action": "create", "target_model": m, "preferred_name": a.name,
                          "audio": {"data": uri}}}
        voice = (call(body).get("output") or {}).get("voice")
        if not voice:
            print(f"error: {m}: response had no output.voice")
            return 1
        result[m] = voice
        print(f"{m} {voice}")
        if m == "qwen3.8-omni-flash-realtime":
            print("note: USING this voice in a session spends the qwen3.8 quota; "
                  "only the owner allows that, per run")
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)
    return 0


def cmd_list(a):
    body = {"model": ENROLL_MODEL,
            "input": {"action": "list", "page_size": a.page_size, "page_index": a.page_index}}
    for v in (call(body).get("output") or {}).get("voice_list") or []:
        print(f"{v.get('voice')} {v.get('target_model')} {v.get('gmt_create')}")
    return 0


def cmd_delete(a):
    call({"model": ENROLL_MODEL, "input": {"action": "delete", "voice": a.voice}})
    print(f"deleted {a.voice}")
    return 0


# ---------------------------------------------------------------- selftest

def _write_wav(path, rate, channels, seconds):
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * channels * int(rate * seconds))


class _Mock(BaseHTTPRequestHandler):
    errors = []
    voices = []

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        err = self.errors
        if not self.headers.get("Authorization", "").startswith("Bearer "):
            err.append("no bearer")
        if self.headers.get("Content-Type") != "application/json":
            err.append("content-type")
        if body.get("model") != ENROLL_MODEL:
            err.append("model")
        inp = body.get("input", {})
        act = inp.get("action")
        out = {}
        if act == "create":
            if body.get("parameters") != {"voice_clone_mode": "normal"}:
                err.append("parameters")
            if inp.get("target_model") not in ALLOWED_MODELS:
                err.append("target_model")
            if not NAME_RE.match(inp.get("preferred_name", "")):
                err.append("preferred_name")
            if not inp.get("audio", {}).get("data", "").startswith(_Mock.expect_prefix):
                err.append("data uri")
            if inp.get("target_model") == "bad":
                return self._send(400, {"code": "InvalidParameter", "message": "bad model"})
            v = f"qwen-omni-vc-{inp.get('preferred_name')}-{len(self.voices)}"
            self.voices.append({"voice": v, "target_model": inp["target_model"],
                                "gmt_create": "2026-10-11 00:00:00"})
            out = {"voice": v}
        elif act == "list":
            if set(inp) != {"action", "page_size", "page_index"}:
                err.append("list shape")
            out = {"voice_list": self.voices}
        elif act == "delete":
            self.voices[:] = [v for v in self.voices if v["voice"] != inp.get("voice")]
        else:
            err.append("action")
        self._send(200, {"output": out, "request_id": "x"})

    def _send(self, code, obj):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def cmd_selftest(_a):
    failures = []

    def case(name, cond):
        print(("OK   " if cond else "FAIL ") + name)
        if not cond:
            failures.append(name)

    quiet = lambda *_: None  # noqa: E731
    with tempfile.TemporaryDirectory() as d:
        p = lambda n: os.path.join(d, n)  # noqa: E731
        _write_wav(p("good.wav"), 24000, 1, 12)
        _write_wav(p("stereo.wav"), 24000, 2, 12)
        _write_wav(p("low.wav"), 16000, 1, 12)
        _write_wav(p("short.wav"), 24000, 1, 5)
        _write_wav(p("long.wav"), 24000, 1, 30)
        case("check good 24k mono 12s passes", check(p("good.wav"), quiet))
        case("check stereo fails", not check(p("stereo.wav"), quiet))
        case("check 16 kHz fails", not check(p("low.wav"), quiet))
        case("check 5 s fails", not check(p("short.wav"), quiet))
        lines = []
        case("check 30 s passes with warning",
             check(p("long.wav"), lines.append) and any(x.startswith("warn") for x in lines))
        for ext, mime in MIMES.items():
            open(p("x" + ext), "wb").write(b"abc")
            case(f"data uri {ext} -> {mime}", data_uri(p("x" + ext)).startswith(f"data:{mime};base64,"))

        srv = HTTPServer(("127.0.0.1", 0), _Mock)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        saved = {k: os.environ.get(k) for k in ("NOVA_VOICE_ENROLL_URL", "DASHSCOPE_API_KEY")}
        os.environ["NOVA_VOICE_ENROLL_URL"] = f"http://127.0.0.1:{srv.server_port}/"
        os.environ["DASHSCOPE_API_KEY"] = "selftest-dummy"
        os.environ.pop("HTTPS_PROXY", None), os.environ.pop("HTTP_PROXY", None)
        os.environ.pop("https_proxy", None), os.environ.pop("http_proxy", None)
        try:
            _Mock.expect_prefix = "data:audio/wav;base64,"
            outf = p("out.json")
            rc = main(["create", p("good.wav"), "--name", "nova_test", "--yes", "--out", outf,
                       "--model", "qwen3.5-omni-plus-realtime",
                       "--model", "qwen3.8-omni-flash-realtime"])
            res = json.load(open(outf)) if os.path.exists(outf) else {}
            case("create two models", rc == 0 and len(res) == 2 and not _Mock.errors)
            case("create without --yes refuses",
                 main(["create", p("good.wav"), "--name", "n", "--model",
                       "qwen3.5-omni-plus-realtime"]) == 1 and len(_Mock.voices) == 2)
            case("create bad name refuses", main(["create", p("good.wav"), "--name", "bad name!",
                                                  "--model", "qwen3.5-omni-plus-realtime", "--yes"]) == 2)
            case("create stereo refuses", main(["create", p("stereo.wav"), "--name", "n", "--model",
                                                "qwen3.5-omni-plus-realtime", "--yes"]) == 1)
            case("list", main(["list"]) == 0 and not _Mock.errors)
            v = _Mock.voices[0]["voice"] if _Mock.voices else ""
            case("delete", main(["delete", v]) == 0 and len(_Mock.voices) == 1 and not _Mock.errors)
            try:
                call({"model": ENROLL_MODEL, "parameters": {"voice_clone_mode": "normal"},
                      "input": {"action": "create", "target_model": "bad", "preferred_name": "n",
                                "audio": {"data": _Mock.expect_prefix}}})
                case("http error exits", False)
            except SystemExit as e:
                msg = str(e)
                case("http error shows code/message only",
                     "InvalidParameter" in msg and "127.0.0.1" not in msg and "dummy" not in msg)
            _Mock.errors.clear()
        finally:
            srv.shutdown()
            for k, val in saved.items():
                if val is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = val
    if failures:
        print(f"SELFTEST FAILED: {len(failures)} case(s)")
        return 1
    print("SELFTEST OK")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("check", help="offline clip check")
    s.add_argument("file")
    s = sub.add_parser("create", help="clone a voice, one per --model")
    s.add_argument("file")
    s.add_argument("--name", required=True)
    s.add_argument("--model", action="append", required=True)
    s.add_argument("--out")
    s.add_argument("--yes", action="store_true")
    s = sub.add_parser("list")
    s.add_argument("--page-size", type=int, default=10)
    s.add_argument("--page-index", type=int, default=0)
    s = sub.add_parser("delete")
    s.add_argument("voice")
    sub.add_parser("selftest")
    a = ap.parse_args(argv)
    if a.cmd == "check":
        return 0 if check(a.file) else 1
    return {"create": cmd_create, "list": cmd_list, "delete": cmd_delete,
            "selftest": cmd_selftest}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
