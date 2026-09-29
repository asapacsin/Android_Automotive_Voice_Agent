"""Latency and ordering probe for the Gemini-native architecture (docs/GEMINI_NATIVE_ARCHITECTURE.md).

Streams one recorded 16 kHz utterance in real time with the app's real tool catalogue and persona,
answers every tool call with an ok=true result after TOOL_MS, and prints ONE JSON line per run with
times relative to the end of the utterance:

  vad_end      server ACTIVITY_END (auto VAD only)
  in_tx        first input transcription
  audio1       first reply audio of the first model turn
  call         first toolCall (name only)
  status       interactionStatus transitions with times
  turns        per model turn: first audio, turnComplete, audio ms, text chars, and the
               transcript-vs-audio lead/lag at every transcription chunk (see `lag` below)

`lag` per turn: at each outputTranscription arrival, (audio ms received so far) minus (chars
received so far x this turn's ms-per-char). Positive = the audio is ahead of its transcript by that
many ms. Its maximum is what a streaming claim gate must wait for.

Prints times, counts and tool names only. Transcript text only with SHOW_TEXT=1 (synthetic clips).
The key comes only from GEMINI_API_KEY and is never printed.

  MODEL=models/gemini-3.8-live LVL=LOW VAD=auto python latency_probe.py ac_on.pcm [seconds]
  VAD=manual HANGOVER_MS=300 python latency_probe.py nav_wanda.pcm
  PROMPT=none|app|callfirst   (default app: the app persona + CALL_FIRST_HINT)
"""
import asyncio, base64, json, os, re, sys, time
import aiohttp

KEY = os.environ["GEMINI_API_KEY"]
MODEL = os.environ.get("MODEL", "models/gemini-3.8-live-extended-thinking")
URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SPEECH = os.environ.get("SPEECH_DIR", os.path.join(ROOT, "tools", "speech-harness", "speech"))
CHUNK = 640
SHOW = os.environ.get("SHOW_TEXT") == "1"
TOOL_MS = int(os.environ.get("TOOL_MS", "300"))
PUNCT = set("，。！？、；：,.!?;: \n“”\"'…（）()")


def app_tools():
    golden = json.load(open(os.path.join(ROOT, "app/src/test/resources/golden/baidu_session_update.json"), encoding="utf-8"))
    tools = golden.get("session", golden)["tools"]
    return [{"name": t["name"], "description": t["description"], "parametersJsonSchema": t["parameters"]} for t in tools]


def app_persona():
    src = open(os.path.join(ROOT, "app/src/main/kotlin/com/novadrive/app/PersonaProfiles.kt"), encoding="utf-8").read()
    persona = re.search(r'DEFAULT_INSTRUCTIONS: String = """\n(.*?)"""', src, re.S).group(1).strip()
    proto = open(os.path.join(ROOT, "app/src/main/kotlin/com/novadrive/app/voice/GeminiLiveProtocol.kt"), encoding="utf-8").read()
    hint = re.search(r'CALL_FIRST_HINT = "(.*?)"\n', proto).group(1)
    return persona, hint


def setup():
    persona, hint = app_persona()
    mode = os.environ.get("PROMPT", "app")
    text = {"none": "你是车载语音助手小诺。用简短的中文口语回答。", "app": persona + "\n" + hint, "persona": persona}[mode]
    activity = {}
    if os.environ.get("VAD", "auto") == "manual":
        activity["disabled"] = True
    if os.environ.get("SILENCE_MS"):
        activity["silenceDurationMs"] = int(os.environ["SILENCE_MS"])
    s = {
        "model": MODEL,
        "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"languageCode": "zh-CN", "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}}},
        "systemInstruction": {"parts": [{"text": text}]},
        "tools": [{"functionDeclarations": app_tools()}],
        "realtimeInputConfig": {"automaticActivityDetection": activity},
        "inputAudioTranscription": {}, "outputAudioTranscription": {},
    }
    if "thinking" in MODEL or os.environ.get("LVL"):
        s["generationConfig"]["thinkingConfig"] = {"thinkingLevel": os.environ.get("LVL", "LOW")}
    return {"setup": s}


def tool_output(name, args):
    if name == "navigate_to":
        return {"ok": True, "tool": name, "status": "candidates_shown", "count": 3, "next": "请说第几个"}
    if name == "control_climate":
        return {"ok": True, "tool": name, "power_on": True, "temperature_c": 24}
    return {"ok": True, "tool": name, "status": "done"}


async def main(clip, total_s):
    t0 = time.monotonic()
    end = {"t": None}
    rel = lambda: round((time.monotonic() - (end["t"] or t0)) * 1000)
    out = {"clip": clip, "model": MODEL.split("/")[-1], "lvl": os.environ.get("LVL", "LOW"), "vad": os.environ.get("VAD", "auto"),
           "prompt": os.environ.get("PROMPT", "app"), "status": [], "calls": [], "turns": [], "text": []}
    manual = os.environ.get("VAD", "auto") == "manual"
    hangover = int(os.environ.get("HANGOVER_MS", "300"))
    turn = None
    results_sent_at = []
    done = asyncio.Event()

    def new_turn():
        return {"audio1": None, "audio_ms": 0.0, "chars": 0, "points": [], "complete": None, "calls": 0}

    async with aiohttp.ClientSession(trust_env=True) as sess:
        async with sess.ws_connect(URL, headers={"x-goog-api-key": KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps(setup()))
            ready = asyncio.Event()

            async def reader():
                nonlocal turn
                while True:
                    msg = await ws.receive()
                    if msg.type not in (aiohttp.WSMsgType.TEXT, aiohttp.WSMsgType.BINARY):
                        out["close"] = [ws.close_code, str(msg.extra)[:160]]
                        done.set(); return
                    d = json.loads(msg.data)
                    if os.environ.get("SHOW_KEYS") == "1":
                        sc_keys = list(d.get("serverContent", {}).keys())
                        raw_len = len(msg.data)
                        out.setdefault("keys", []).append([rel(), list(d.keys()), sc_keys, raw_len, str(msg.type), (repr(msg.data[:80]) if not d else "")])
                    if "setupComplete" in d:
                        ready.set(); continue
                    if "voiceActivity" in d:
                        kind = d["voiceActivity"].get("voiceActivityType") or d["voiceActivity"].get("type")
                        if kind and "END" in str(kind) and "vad_end" not in out:
                            out["vad_end"] = rel()
                        continue
                    if "toolCall" in d:
                        for f in d["toolCall"].get("functionCalls", []):
                            out["calls"].append([rel(), f["name"]])
                            if "call" not in out:
                                out["call"] = rel(); out["call_name"] = f["name"]
                            if turn is not None:
                                turn["calls"] += 1
                            await asyncio.sleep(TOOL_MS / 1000)
                            await ws.send_str(json.dumps({"toolResponse": {"functionResponses": [
                                {"id": f.get("id"), "name": f["name"], "response": tool_output(f["name"], f.get("args"))}]}}))
                            results_sent_at.append(rel())
                        continue
                    sc = d.get("serverContent")
                    if not sc:
                        continue
                    if "interactionStatus" in sc:
                        out["status"].append([rel(), sc["interactionStatus"]])
                    if "inputTranscription" in sc and "in_tx" not in out:
                        out["in_tx"] = rel()
                    for p in sc.get("modelTurn", {}).get("parts", []):
                        if "inlineData" in p:
                            if turn is None:
                                turn = new_turn()
                            if turn["audio1"] is None:
                                turn["audio1"] = rel()
                                if "audio1" not in out:
                                    out["audio1"] = rel()
                            turn["audio_ms"] += len(base64.b64decode(p["inlineData"]["data"])) / 48.0
                    if "outputTranscription" in sc:
                        text = sc["outputTranscription"].get("text", "")
                        if turn is None:
                            turn = new_turn()
                        turn["chars"] += sum(1 for c in text if c not in PUNCT)
                        turn["points"].append([rel(), round(turn["audio_ms"]), turn["chars"]])
                        if SHOW:
                            out["text"].append(text)
                    if sc.get("interrupted"):
                        out.setdefault("interrupted", rel())
                    if sc.get("turnComplete"):
                        if turn is not None:
                            turn["complete"] = rel()
                            out["turns"].append(turn)
                        turn = None
                        # finished: a turn completed after a tool result was sent, or no call and a spoken turn
                        if results_sent_at and rel() > results_sent_at[-1]:
                            done.set(); return

            rt = asyncio.create_task(reader())
            await asyncio.wait_for(ready.wait(), 15)
            if clip.startswith("text:"):
                # A typed turn (the app's corrections and prompts travel this way); t=0 is the send.
                end["t"] = time.monotonic()
                await ws.send_str(json.dumps({"clientContent": {"turns": [{"role": "user", "parts": [{"text": clip[5:]}]}], "turnComplete": True}}))
                clip_data = b""
            else:
                clip_data = None
            data = clip_data if clip_data is not None else open(os.path.join(SPEECH, clip), "rb").read()
            chunks = [data[k:k + CHUNK] for k in range(0, len(data), CHUNK)]
            if manual and data:
                await ws.send_str(json.dumps({"realtimeInput": {"activityStart": {}}}))
            start = time.monotonic(); n = 0
            ended_activity = False
            while time.monotonic() - start < total_s and not done.is_set():
                if not data:
                    chunk = None
                elif chunks:
                    chunk = chunks.pop(0)
                    if not chunks:
                        end["t"] = time.monotonic() + 0.02
                elif manual:
                    if not ended_activity and rel() >= hangover:
                        await ws.send_str(json.dumps({"realtimeInput": {"activityEnd": {}}}))
                        out["activity_end_sent"] = rel(); ended_activity = True
                    chunk = None
                else:
                    chunk = b""
                if chunk is not None:
                    await ws.send_str(json.dumps({"realtimeInput": {"audio": {"data": base64.b64encode(chunk.ljust(CHUNK, b"\x00")).decode(), "mimeType": "audio/pcm;rate=16000"}}}))
                n += 1
                await asyncio.sleep(max(0, start + n * 0.02 - time.monotonic()))
            rt.cancel()
    if turn is not None:
        out["turns"].append(turn)
    for t in out["turns"]:
        pts = t.pop("points")
        if t["chars"] and t["audio_ms"]:
            mpc = t["audio_ms"] / t["chars"]
            lags = [a - c * mpc for (_, a, c) in pts]
            t["ms_per_char"] = round(mpc)
            t["lag_max"] = round(max(lags)) if lags else None
            t["lag_first"] = round(lags[0]) if lags else None
            t["tx_chunks"] = len(pts)
        t["audio_ms"] = round(t["audio_ms"])
    if not SHOW:
        out.pop("text")
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 40))
