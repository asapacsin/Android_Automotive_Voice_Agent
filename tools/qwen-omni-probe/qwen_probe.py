"""ADR-017 gate Q-1 / Q-2 probe for Qwen-Omni Realtime (stock voice Maia). PC-side; not part of the app.

  python qwen_probe.py latency <clip> [repeats]   last word -> speech_stopped / created / audio / done
  python qwen_probe.py tool <clip>                tool round trip with a canned ok=true result
  python qwen_probe.py text                       is a user text message item accepted?
  python qwen_probe.py barge [clipA] [clipB]      barge-in (CLIENT_CANCEL=1 also sends response.cancel)
  python qwen_probe.py say [--lines FILE]         Maia reads fixed lines -> <OUT>/<i>_<slug>.wav (Q-2)
  python qwen_probe.py compare [runs.jsonl]       p50/max per metric next to the Gemini + Xiaoyi reference
  python qwen_probe.py --selftest                 offline, against a local mock server

The key comes only from DASHSCOPE_API_KEY and is sent only in the Authorization header; it is never
printed or written. The workspace id is never printed. Transcripts only with SHOW_TEXT=1 (synthetic
clips only). Times are ms, relative to the end of the clip (its last speech frame) unless stated.
"""
import asyncio, base64, contextlib, io, json, os, re, subprocess, sys, tempfile, time, wave
import aiohttp

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CHUNK = 640  # 20 ms of 16 kHz s16le mono
OUT_RATE = 24000
PUNCT = set("，。！？、；：,.!?;: \n“”\"'…（）()")
TEXT_LINE = "今天天气怎么样？"  # fixed synthetic line for mode 3

DEFAULT_LINES = [
    "好的，空调已经调到二十二度了。前方五百米右转，然后走第二个出口。这点小事，交给我就行。",  # B-034 (voice_ab.py)
    "收到，正在处理。", "嗯，我想想。", "网络有点慢，请稍等。", "我确认一下，马上回答你。",
    "还在处理，网络可能不太稳定，再等我一下。", "正在搜索路线，稍等一下。", "正在查询，稍等一下。",
    "正在找歌，稍等一下。", "正在看画面，稍等一下。", "正在处理，稍等一下。",  # SPEC-020 WaitCues.kt
]

# ADR-017 reference table (Gemini + Xiaoyi, 2026-10-08), seconds.
REFERENCE = [
    ("last word -> first audio (chat)", "3.4-4.6 s", "ADR-017 table: Gemini + Xiaoyi device run 2026-10-08, chat"),
    ("model first output", "0.54-0.83 s", "ADR-017 table: Gemini model first output"),
    ("Azure first audio", "0.26-0.74 s", "ADR-017 table: Xiaoyi (Azure TTS) first audio"),
]


def env(name, default=None):
    return os.environ.get(name, default)


def pace():
    return float(env("PACE", "1"))


def build_url():
    """Full URL from QWEN_WS_URL, else the required Singapore workspace endpoint. Never printed."""
    if env("QWEN_WS_URL"):
        return env("QWEN_WS_URL")
    ws = env("DASHSCOPE_WORKSPACE_ID")
    if not ws:
        sys.exit("DASHSCOPE_WORKSPACE_ID (or QWEN_WS_URL) is required")
    model = env("MODEL", "qwen3.8-omni-flash-realtime")
    print("workspace=set", file=sys.stderr)
    return f"wss://{ws}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model={model}"


def speech_dir():
    return env("SPEECH_DIR", os.path.join(ROOT, "tools", "speech-harness", "speech"))


def load_clip(name):
    p = name if os.path.isabs(name) else os.path.join(speech_dir(), name)
    with open(p, "rb") as f:
        return f.read()


def app_tools():
    p = os.path.join(ROOT, "app/src/test/resources/golden/baidu_session_update.json")
    try:
        golden = json.load(open(p, encoding="utf-8"))
    except OSError:
        return []
    out = []
    for t in golden.get("session", golden)["tools"]:
        if t.get("type") == "function" and "function" in t:
            out.append(t)
        else:
            out.append({"type": "function", "function": {k: t[k] for k in ("name", "description", "parameters") if k in t}})
    return out


def app_persona():
    try:
        src = open(os.path.join(ROOT, "app/src/main/kotlin/com/novadrive/app/PersonaProfiles.kt"), encoding="utf-8").read()
        return re.search(r'DEFAULT_INSTRUCTIONS: String = """\n(.*?)"""', src, re.S).group(1).strip()
    except (OSError, AttributeError):
        return None


def session_update(instructions=None, tools=True):
    voice = env("VOICE", "Maia")
    vad = env("VAD", "semantic")
    if instructions is None:
        persona = app_persona() if env("PROMPT", "app") == "app" else None
        instructions = persona or "你是车载语音助手小诺。用简短的中文口语回答。"
    s = {
        "modalities": ["text", "audio"], "voice": voice, "audio": {"output": {"voice": voice}},
        "input_audio_format": "pcm", "output_audio_format": "pcm", "instructions": instructions,
        "input_audio_transcription": {"model": "qwen3-asr-flash-realtime"},
        "turn_detection": None if vad == "manual" else
        {"type": "semantic_vad" if vad == "semantic" else "server_vad", "threshold": 0.5,
         "silence_duration_ms": int(env("SILENCE_MS", "800"))},
    }
    if tools:
        s["tools"] = app_tools()
    return {"type": "session.update", "session": s}


def tool_output(name):
    if name == "navigate_to":
        return {"ok": True, "status": "candidates_shown", "count": 3}
    if name == "query_live_info":
        return {"ok": True, "summary": "晴，二十二度，东南风二级。"}
    return {"ok": True, "status": "done"}


def bare(s):
    return "".join(c for c in s if c not in PUNCT)


class Probe:
    """One WebSocket session; a reader task timestamps every server event."""

    def __init__(self, ws):
        self.ws = ws
        self.t0 = None  # reference instant (clip end)
        self.first = {}
        self.events = []
        self.errors = []
        self.audio = bytearray()
        self.audio_ms_at = []  # (t, cumulative output-audio ms)
        self.transcript = ""
        self.in_text = ""
        self.done = asyncio.Event()
        self.created = asyncio.Event()
        self.calls = asyncio.Queue()
        self.cbs = []
        self.last_status = None
        self.closed = False

    def now(self):
        return time.monotonic()

    async def send(self, obj):
        await self.ws.send_str(json.dumps(obj, ensure_ascii=False))

    def mark(self, key, t):
        self.first.setdefault(key, t)

    async def reader(self):
        async for msg in self.ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            ev = json.loads(msg.data)
            t, ty = self.now(), ev.get("type", "")
            self.events.append((t, ty))
            self.mark(ty, t)
            if ty == "response.audio.delta":
                self.audio += base64.b64decode(ev.get("delta", ""))
                self.audio_ms_at.append((t, len(self.audio) / (OUT_RATE * 2) * 1000))
            elif ty == "response.audio_transcript.delta":
                self.transcript += ev.get("delta", "")
            elif ty == "conversation.item.input_audio_transcription.completed":
                self.in_text = ev.get("transcript", "")
            elif ty == "response.function_call_arguments.done":
                await self.calls.put((t, ev.get("name"), ev.get("call_id"), ev.get("arguments")))
            elif ty == "response.created":
                self.created.set()
            elif ty == "response.done":
                self.last_status = (ev.get("response") or {}).get("status")
                self.done.set()
            elif ty == "error":
                e = ev.get("error") or {}
                self.errors.append(e.get("code") or e.get("type") or "unknown")  # never the message
                self.done.set()
            for cb in list(self.cbs):
                await cb(t, ty, ev)
        self.closed = True
        self.done.set()

    async def stream(self, pcm):
        """Stream in real time (scaled by PACE). Returns the instant the last frame was sent."""
        step = 0.02 * pace()
        start = self.now()
        for i in range(0, len(pcm), CHUNK):
            await self.send({"type": "input_audio_buffer.append", "audio": base64.b64encode(pcm[i:i + CHUNK]).decode()})
            await asyncio.sleep(max(0.0, start + (i // CHUNK + 1) * step - self.now()))
        return self.now()

    async def silence_until(self, ev, timeout):
        step = max(0.002, 0.02 * pace())
        z = base64.b64encode(bytes(CHUNK)).decode()
        end = self.now() + timeout
        while not ev.is_set() and self.now() < end and not self.closed:
            if env("VAD", "semantic") != "manual":
                await self.send({"type": "input_audio_buffer.append", "audio": z})
            try:
                await asyncio.wait_for(ev.wait(), step)
            except asyncio.TimeoutError:
                pass
        return ev.is_set()

    async def end_turn_manual(self):
        if env("VAD", "semantic") == "manual":
            await self.send({"type": "input_audio_buffer.commit"})
            await self.send({"type": "response.create"})

    def rel(self, key, since=None):
        ref = self.t0 if since is None else since
        t = self.first.get(key)
        return None if t is None or ref is None else round((t - ref) * 1000)


@contextlib.asynccontextmanager
async def connect(instructions=None, tools=True):
    url = build_url()
    headers = {"Authorization": "Bearer " + env("DASHSCOPE_API_KEY", "")}
    async with aiohttp.ClientSession(trust_env=True) as http:
        try:
            ws = await http.ws_connect(url, headers=headers, heartbeat=30, max_msg_size=0)
        except aiohttp.WSServerHandshakeError as e:
            raise SystemExit(f"connect failed: status={e.status}")
        except aiohttp.ClientError as e:
            raise SystemExit(f"connect failed: {type(e).__name__}")
        async with ws:
            p = Probe(ws)
            task = asyncio.create_task(p.reader())
            await p.send(session_update(instructions, tools))
            try:
                yield p
            finally:
                task.cancel()
                with contextlib.suppress(BaseException):
                    await task


def base_row(mode):
    return {"model": env("MODEL", "qwen3.8-omni-flash-realtime"), "voice": env("VOICE", "Maia"),
            "vad": env("VAD", "semantic"), "silence_ms": int(env("SILENCE_MS", "800")), "mode": mode}


def emit(row):
    line = json.dumps(row, ensure_ascii=False)
    print(line, flush=True)
    if env("OUT"):
        os.makedirs(env("OUT"), exist_ok=True)
        with open(os.path.join(env("OUT"), "runs.jsonl"), "a", encoding="utf-8") as f:
            f.write(line + "\n")


def timeout_s():
    return float(env("TIMEOUT_S", "20"))


async def run_latency(clip):
    pcm = load_clip(clip)
    async with connect() as p:
        await asyncio.sleep(0.3 * pace())
        p.t0 = await p.stream(pcm)
        await p.end_turn_manual()
        ok = await p.silence_until(p.done, timeout_s())
        row = base_row("latency") | {"clip": os.path.basename(clip), "timed_out": not ok, "t": {
            "speech_stopped": p.rel("input_audio_buffer.speech_stopped"),
            "response_created": p.rel("response.created"),
            "first_audio": p.rel("response.audio.delta"),
            "first_transcript": p.rel("response.audio_transcript.delta"),
            "first_call": p.rel("response.function_call_arguments.done"),
            "response_done": p.rel("response.done")}}
        row["call_name"] = None
        if not p.calls.empty():
            row["call_name"] = p.calls.get_nowait()[1]
        row["status"] = p.last_status
        row["errors"] = p.errors
        if SHOW():
            row["in_text"], row["out_text"] = p.in_text, p.transcript
        emit(row)
        return row


def SHOW():
    return env("SHOW_TEXT") == "1"


async def run_tool(clip):
    pcm = load_clip(clip)
    delay = int(env("TOOL_MS", "300")) / 1000
    async with connect() as p:
        await asyncio.sleep(0.3 * pace())
        p.t0 = await p.stream(pcm)
        await p.end_turn_manual()
        got = asyncio.Event()
        p.cbs.append(lambda t, ty, ev: _set_if(got, ty == "response.function_call_arguments.done"))
        await p.silence_until(got, timeout_s())
        row = base_row("tool") | {"clip": os.path.basename(clip), "errors": p.errors}
        if p.calls.empty():
            row |= {"call_name": None, "status": p.last_status, "t": {"first_audio": p.rel("response.audio.delta")}}
            emit(row)
            return row
        tc, name, call_id, _args = p.calls.get_nowait()
        # wait for the first response to finish before answering
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(p.done.wait(), timeout_s())
        await asyncio.sleep(delay)
        p.done.clear()
        audio_before = len(p.events)
        p.first.pop("response.created", None)
        p.first.pop("response.audio.delta", None)
        p.first.pop("response.done", None)
        await p.send({"type": "conversation.item.create", "item": {
            "type": "function_call_output", "call_id": call_id, "output": json.dumps(tool_output(name), ensure_ascii=False)}})
        t_out = p.now()
        await p.send({"type": "response.create"})
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(p.done.wait(), timeout_s())
        row |= {"call_name": name, "status": p.last_status, "t": {
            "first_call": round((tc - p.t0) * 1000),
            "output_sent": round((t_out - p.t0) * 1000),
            "followup_created_from_output": p.rel("response.created", t_out),
            "followup_audio_from_output": p.rel("response.audio.delta", t_out),
            "spoken_result_audio": p.rel("response.audio.delta"),
            "followup_done": p.rel("response.done")}}
        row["errors"] = p.errors
        if SHOW():
            row["out_text"] = p.transcript
        emit(row)
        return row


async def _set_if(ev, cond):
    if cond:
        ev.set()


async def speak_text(p, text):
    await p.send({"type": "conversation.item.create", "item": {
        "type": "message", "role": "user", "content": [{"type": "input_text", "text": text}]}})
    p.t0 = p.now()
    await p.send({"type": "response.create"})
    with contextlib.suppress(asyncio.TimeoutError):
        await asyncio.wait_for(p.done.wait(), timeout_s())


async def run_text():
    async with connect() as p:
        await asyncio.sleep(0.3 * pace())
        await speak_text(p, TEXT_LINE)
        accepted = not p.errors and len(p.audio) > 0
        row = base_row("text") | {"accepted": accepted, "status": p.last_status, "errors": p.errors,
                                  "t": {"response_created": p.rel("response.created"),
                                        "first_audio": p.rel("response.audio.delta"),
                                        "response_done": p.rel("response.done")}}
        emit(row)
        return row


async def run_barge(clip_a, clip_b):
    a, b = load_clip(clip_a), load_clip(clip_b)
    need_ms = int(env("BARGE_AFTER_MS", "1500"))
    client_cancel = env("CLIENT_CANCEL") == "1"
    async with connect() as p:
        await asyncio.sleep(0.3 * pace())
        p.t0 = await p.stream(a)
        await p.end_turn_manual()
        enough = asyncio.Event()
        p.cbs.append(lambda t, ty, ev: _set_if(enough, ty == "response.audio.delta" and len(p.audio) >= need_ms * OUT_RATE * 2 / 1000))
        state = {"started": None, "cancel_sent": False}

        async def on_started(t, ty, ev):
            if ty == "input_audio_buffer.speech_started" and state["started"] is None and t_b["on"] is not None:
                state["started"] = t
                if client_cancel:
                    await p.send({"type": "response.cancel"})
                    state["cancel_sent"] = True
        t_b = {"on": None}
        p.cbs.append(on_started)
        await p.silence_until(enough, timeout_s())
        row = base_row("barge") | {"clip_a": os.path.basename(clip_a), "clip_b": os.path.basename(clip_b),
                                   "client_cancel": client_cancel}
        if not enough.is_set():
            row |= {"b_sent": False, "status": p.last_status, "errors": p.errors, "t": {}}
            emit(row)
            return row
        p.done.clear()
        p.last_status = None
        t_b["on"] = p.now()
        await p.stream(b)
        await p.silence_until(p.done, timeout_s())
        st = state["started"]
        last_audio = max((t for t, ty in p.events if ty == "response.audio.delta"), default=None)
        t_end = max((t for t, ty in p.events if ty == "response.done"), default=None)
        row |= {"b_sent": True, "status": p.last_status, "interrupted": st is not None,
                "client_cancel_sent": state["cancel_sent"], "errors": p.errors, "t": {
                    "b_onset_to_speech_started": None if st is None else round((st - t_b["on"]) * 1000),
                    "speech_started_to_done": None if st is None or t_end is None else round((t_end - st) * 1000),
                    "last_audio_from_speech_started": None if st is None or last_audio is None else round((last_audio - st) * 1000),
                    "audio_ms_received": round(len(p.audio) / (OUT_RATE * 2) * 1000)}}
        emit(row)
        return row


def slug(s, n=12):
    out = re.sub(r"[^\w]+", "_", s, flags=re.U).strip("_")
    return out[:n] or "line"


def write_wav(path, pcm):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(OUT_RATE)
        w.writeframes(bytes(pcm))


async def run_say(lines):
    out = env("OUT") or os.path.join(HERE, "out")
    os.makedirs(out, exist_ok=True)
    rows, fallback = [], False
    for i, line in enumerate(lines, 1):
        instr = "你是一个朗读器。只把用户给的句子一字不差地朗读出来，不要添加任何其他内容。"
        async with connect(instructions=instr, tools=False) as p:
            await asyncio.sleep(0.3 * pace())
            if not fallback:
                await speak_text(p, "请一字不差地朗读：" + line)
                if p.errors and not p.audio:
                    fallback = True
            if fallback:
                p.errors.clear()
                p.done.clear()
                p.t0 = await p.stream(load_clip(env("FALLBACK_CLIP", "can_you_talk.pcm")))
                await p.end_turn_manual()
                await p.silence_until(p.done, timeout_s())
            path = os.path.join(out, f"{i:02d}_{slug(line)}.wav")
            write_wav(path, p.audio)
            rows.append({"index": i, "file": os.path.basename(path), "duration_ms": round(len(p.audio) / (OUT_RATE * 2) * 1000),
                         "first_audio_ms": p.rel("response.audio.delta"), "verbatim": bare(p.transcript) == bare(line),
                         "errors": p.errors})
    summary = base_row("say") | {"input": "clip_fallback" if fallback else "text", "lines": rows}
    with open(os.path.join(out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=1)
    emit(base_row("say") | {"input": summary["input"], "n": len(rows),
                            "verbatim": sum(r["verbatim"] for r in rows), "errors": [e for r in rows for e in r["errors"]]})
    return summary


def compare(path):
    groups = {}
    for ln in open(path, encoding="utf-8"):
        r = json.loads(ln)
        key = f"{r.get('mode')} vad={r.get('vad')}" + (f" cancel={int(r.get('client_cancel', False))}" if r.get("mode") == "barge" else "")
        g = groups.setdefault(key, {"n": 0, "errors": 0, "m": {}})
        g["n"] += 1
        g["errors"] += len(r.get("errors") or [])
        for k, v in (r.get("t") or {}).items():
            if isinstance(v, (int, float)):
                g["m"].setdefault(k, []).append(v)
    for key, g in sorted(groups.items()):
        print(f"[{key}] runs={g['n']} error_codes={g['errors']}")
        for k, vs in g["m"].items():
            vs = sorted(vs)
            print(f"  {k:36s} p50={vs[(len(vs) - 1) // 2]:>6} ms  max={vs[-1]:>6} ms  n={len(vs)}")
    print("Reference (not measured here):")
    for name, val, src in REFERENCE:
        print(f"  {name:36s} {val:12s} [{src}]")


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[0] == "--selftest":
        return selftest()
    cmd, rest = argv[0], argv[1:]
    if cmd == "compare":
        compare(rest[0] if rest else os.path.join(env("OUT", os.path.join(HERE, "out")), "runs.jsonl"))
        return 0
    if not env("DASHSCOPE_API_KEY"):
        sys.exit("DASHSCOPE_API_KEY is required")
    if cmd == "latency":
        for _ in range(int(rest[1]) if len(rest) > 1 else 1):
            asyncio.run(run_latency(rest[0]))
    elif cmd == "tool":
        asyncio.run(run_tool(rest[0]))
    elif cmd == "text":
        asyncio.run(run_text())
    elif cmd == "barge":
        asyncio.run(run_barge(rest[0] if rest else "can_you_talk.pcm", rest[1] if len(rest) > 1 else "shut_up_zh.pcm"))
    elif cmd == "say":
        lines = DEFAULT_LINES
        if "--lines" in rest:
            lines = [l.strip() for l in open(rest[rest.index("--lines") + 1], encoding="utf-8") if l.strip()]
        asyncio.run(run_say(lines))
    else:
        print(__doc__)
        return 2
    return 0


# ---------------------------------------------------------------- selftest (offline mock server)

async def mock_handler(request):
    from aiohttp import web
    if not request.headers.get("Authorization", "").startswith("Bearer ") or len(request.headers["Authorization"]) < 8:
        return web.Response(status=401)
    scen = request.query.get("scenario", "chat")
    log = request.app["log"]
    ws = web.WebSocketResponse(max_msg_size=0)
    await ws.prepare(request)
    st = {"speech": False, "responding": None, "cancelled": False, "turns": 0, "tool_done": False, "items": []}

    async def send(o):
        if not ws.closed:
            await ws.send_str(json.dumps(o, ensure_ascii=False))

    async def respond(text, n_audio=3, call=False, gap=0.0):
        await send({"type": "response.created", "response": {"status": "in_progress"}})
        if call:
            await send({"type": "response.function_call_arguments.done", "call_id": "c1", "name": "query_live_info", "arguments": "{}"})
            await send({"type": "response.done", "response": {"status": "completed"}})
            return
        await send({"type": "response.audio_transcript.delta", "delta": text})
        for _ in range(n_audio):
            if st["cancelled"]:
                break
            await send({"type": "response.audio.delta", "delta": base64.b64encode(bytes(4800)).decode()})
            await asyncio.sleep(gap)
        if st["cancelled"]:
            await asyncio.sleep(0.03)
            await send({"type": "response.done", "response": {"status": "cancelled"}})
        else:
            await send({"type": "response.audio.done"})
            await send({"type": "response.done", "response": {"status": "completed"}})

    async for msg in ws:
        ev = json.loads(msg.data)
        ty = ev["type"]
        log.append(ty)
        if ty == "session.update":
            await send({"type": "session.updated"})
        elif ty == "input_audio_buffer.append":
            pcm = base64.b64decode(ev["audio"])
            loud = any(pcm)
            if loud and not st["speech"]:
                st["speech"] = True
                if st["responding"] is not None:
                    log.append("B_AUDIO")
                    await send({"type": "input_audio_buffer.speech_started", "audio_start_ms": 0})
                    st["cancelled"] = True
            elif not loud and st["speech"]:
                st["speech"] = False
                await send({"type": "input_audio_buffer.speech_stopped", "audio_end_ms": 0})
                await send({"type": "input_audio_buffer.committed"})
                if st["turns"] == 0:
                    st["turns"] += 1
                    if scen == "tool":
                        await respond("", call=True)
                    elif scen == "barge":
                        st["responding"] = asyncio.create_task(respond("好的", n_audio=40, gap=0.01))
                    else:
                        await respond("你好")
        elif ty == "conversation.item.create":
            st["items"].append(ev["item"])
        elif ty == "response.cancel":
            st["cancelled"] = True
        elif ty == "response.create":
            item = st["items"][-1] if st["items"] else {}
            if item.get("type") == "function_call_output":
                await respond("晴，二十二度")
            elif item.get("type") == "message":
                if scen == "reject":
                    await send({"type": "error", "error": {"type": "invalid_request_error", "code": "invalid_value", "message": "echo"}})
                else:
                    txt = item["content"][0]["text"].split("：", 1)[-1]
                    await respond(txt)
    return ws


def selftest():
    from aiohttp import web
    import socket, threading
    key = "dummy-key-0123456789abcdef"
    tmp = tempfile.mkdtemp(prefix="qwen_probe_selftest_")
    sp, out = os.path.join(tmp, "speech"), os.path.join(tmp, "out")
    os.makedirs(sp)
    import math
    tone = b"".join(int(8000 * math.sin(i / 5)).to_bytes(2, "little", signed=True) for i in range(16000 // 2))
    for n in ("a.pcm", "b.pcm", "cough.pcm", "can_you_talk.pcm"):
        open(os.path.join(sp, n), "wb").write(tone)
    log = []
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    ready = threading.Event()

    def serve():
        loop = asyncio.new_event_loop()
        app = web.Application()
        app["log"] = log
        app.router.add_get("/api-ws/v1/realtime", mock_handler)
        runner = web.AppRunner(app)
        loop.run_until_complete(runner.setup())
        loop.run_until_complete(web.TCPSite(runner, "127.0.0.1", port).start())
        ready.set()
        loop.run_forever()
    threading.Thread(target=serve, daemon=True).start()
    ready.wait(10)
    fails = []

    def check(cond, what):
        if not cond:
            fails.append(what)

    def run(args, scen, **extra):
        e = {k: v for k, v in os.environ.items() if not k.startswith(("DASHSCOPE", "QWEN", "HTTP", "HTTPS", "http"))}
        e |= {"DASHSCOPE_API_KEY": key, "PACE": "0", "SPEECH_DIR": sp, "OUT": out, "TIMEOUT_S": "5",
              "QWEN_WS_URL": f"ws://127.0.0.1:{port}/api-ws/v1/realtime?model=m&scenario={scen}", "NO_PROXY": "*"} | extra
        log.clear()
        r = subprocess.run([sys.executable, os.path.abspath(__file__)] + args, env=e, capture_output=True, text=True, timeout=40)
        check(key not in r.stdout and key not in r.stderr, f"key leaked in output of {args}")
        check(r.returncode == 0, f"{args} exit {r.returncode}: {r.stderr[-300:]}")
        rows = [json.loads(l) for l in r.stdout.splitlines() if l.startswith("{")]
        return rows, list(log), r

    def ordered(*vals):
        return all(v is not None for v in vals) and list(vals) == sorted(vals) and vals[0] >= 0

    rows, _, _ = run(["latency", "a.pcm", "2"], "chat")
    check(len(rows) == 2, "latency: two runs")
    for r in rows:
        t = r["t"]
        check(ordered(t["speech_stopped"], t["response_created"], t["first_transcript"], t["first_audio"], t["response_done"]),
              f"latency order {t}")
    rows, lg, _ = run(["tool", "a.pcm"], "tool", TOOL_MS="10")
    t = rows[0]["t"] if rows else {}
    check(rows and rows[0]["call_name"] == "query_live_info", "tool: call name")
    check(ordered(t.get("first_call"), t.get("output_sent"), t.get("spoken_result_audio")), f"tool order {t}")
    check(t.get("followup_created_from_output") is not None and t.get("followup_audio_from_output") is not None, "tool follow-up")
    i = lg.index("conversation.item.create") if "conversation.item.create" in lg else -1
    check(i >= 0 and "response.create" in lg[i + 1:], "tool: function_call_output then response.create")
    rows, _, _ = run(["text"], "chat")
    check(rows and rows[0]["accepted"] is True, "text accepted")
    rows, _, r = run(["text"], "reject")
    check(rows and rows[0]["accepted"] is False and rows[0]["errors"] == ["invalid_value"], "text reject code")
    check("echo" not in r.stdout, "error message printed")
    for cc in ("0", "1"):
        rows, lg, _ = run(["barge", "a.pcm", "b.pcm"], "barge", CLIENT_CANCEL=cc, BARGE_AFTER_MS="500")
        r0 = rows[0] if rows else {}
        check(r0.get("b_sent") and "B_AUDIO" in lg, f"barge {cc}: B sent")
        check(r0.get("status") == "cancelled" and r0.get("interrupted"), f"barge {cc}: cancel recorded {r0}")
        check(r0.get("client_cancel_sent") == (cc == "1") and (("response.cancel" in lg) == (cc == "1")), f"barge {cc}: client cancel")
        check(r0.get("t", {}).get("b_onset_to_speech_started") is not None, f"barge {cc}: timings")
    rows, _, _ = run(["barge", "a.pcm", "cough.pcm"], "barge", BARGE_AFTER_MS="500")
    check(rows and "interrupted" in rows[0], "barge cough reported")
    lines = os.path.join(tmp, "lines.txt")
    open(lines, "w", encoding="utf-8").write("收到，正在处理。\n嗯，我想想。\n")
    rows, _, _ = run(["say", "--lines", lines], "chat")
    summ = json.load(open(os.path.join(out, "summary.json"), encoding="utf-8"))
    check(summ["input"] == "text" and len(summ["lines"]) == 2 and all(l["verbatim"] for l in summ["lines"]), "say summary")
    for l in summ["lines"]:
        with wave.open(os.path.join(out, l["file"])) as w:
            check(w.getframerate() == 24000 and w.getnchannels() == 1 and w.getsampwidth() == 2 and w.getnframes() > 0, "wav header")
    run(["say", "--lines", lines], "reject")
    summ = json.load(open(os.path.join(out, "summary.json"), encoding="utf-8"))
    check(summ["input"] == "clip_fallback", "say fallback recorded")
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        compare(os.path.join(out, "runs.jsonl"))
    check("p50=" in buf.getvalue() and "3.4-4.6 s" in buf.getvalue(), "compare output")
    # URL builder
    saved = {k: os.environ.pop(k, None) for k in ("QWEN_WS_URL", "DASHSCOPE_WORKSPACE_ID", "MODEL")}
    os.environ["DASHSCOPE_WORKSPACE_ID"] = "ws-secret-777"
    err = io.StringIO()
    with contextlib.redirect_stderr(err), contextlib.redirect_stdout(err):
        u = build_url()
    check(u == "wss://ws-secret-777.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime", "url builder")
    check("ws-secret-777" not in err.getvalue() and "workspace=set" in err.getvalue(), "workspace id printed")
    os.environ.pop("DASHSCOPE_WORKSPACE_ID")
    for k, v in saved.items():
        if v is not None:
            os.environ[k] = v
    for dp, _, fs in os.walk(out):
        for f in fs:
            check(key.encode() not in open(os.path.join(dp, f), "rb").read(), f"key in {f}")
    if fails:
        for f in fails:
            print("FAIL:", f)
        return 1
    print("SELFTEST OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
