"""G-M7: ordering of transcripts, audio and tool calls within one Gemini Live turn.

Streams one recorded 16 kHz utterance in real time and prints, relative to the end of the
utterance, when each server part arrived: input transcription chunks, the first and last audio
chunk, output transcription chunks, tool calls, generationComplete and turnComplete.
Prints event kinds, times and character counts only (no transcript text unless SHOW_TEXT=1).

  SPEECH_DIR=... python order_probe.py ac_on.pcm [seconds]
"""
import asyncio, base64, json, os, sys, time
import aiohttp
sys.path.insert(0, os.path.dirname(__file__))
from live_probe import setup_msg, URL, KEY

SPEECH = os.environ.get("SPEECH_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "speech-harness", "speech") + os.sep)
CHUNK = 640
SHOW = os.environ.get("SHOW_TEXT") == "1"
CLIMATE = {"name": "control_climate", "description": "控制车内空调。「打开空调」action=power_on。",
           "parametersJsonSchema": {"type": "object", "properties": {"action": {"type": "string", "enum": ["power_on", "power_off", "set_temperature"]}, "value": {"type": "number"}}, "required": ["action"], "additionalProperties": False}}
NAV = {"name": "navigate_to", "description": "导航到指定地点。",
       "parametersJsonSchema": {"type": "object", "properties": {"destination": {"type": "string", "minLength": 1, "maxLength": 120}}, "required": ["destination"], "additionalProperties": False}}


async def main(clip, total_s):
    extra = {} if os.environ.get("LVL") == "none" else {"generationConfig.thinkingConfig": {"thinkingLevel": os.environ.get("LVL", "LOW")}}
    extra.update({
             "tools": [{"functionDeclarations": [CLIMATE, NAV]}]})
    if os.environ.get("CALL_FIRST") == "1":
        extra["systemInstruction"] = {"parts": [{"text": "你是车载语音助手小诺。用简短的中文口语回答。用户要求执行操作（空调、导航等）时，必须先调用对应工具，拿到结果之后再说话；调用之前不要说任何话，也不要说“马上”“这就”。"}]}
    rows = []
    end_at = {"t": None}
    t0 = time.monotonic()
    rel = lambda: round((time.monotonic() - (end_at["t"] or t0)) * 1000)
    async with aiohttp.ClientSession(trust_env=True) as sess:
        async with sess.ws_connect(URL, headers={"x-goog-api-key": KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps(setup_msg(extra)))
            ready = asyncio.Event()

            async def reader():
                audio_n = 0
                while True:
                    msg = await ws.receive()
                    if msg.type not in (aiohttp.WSMsgType.TEXT, aiohttp.WSMsgType.BINARY):
                        rows.append((rel(), "CLOSE", ws.close_code)); return
                    d = json.loads(msg.data)
                    if "setupComplete" in d:
                        ready.set(); continue
                    if "toolCall" in d:
                        for f in d["toolCall"]["functionCalls"]:
                            rows.append((rel(), "toolCall", f["name"]))
                            await asyncio.sleep(0.5)
                            await ws.send_str(json.dumps({"toolResponse": {"functionResponses": [
                                {"id": f.get("id"), "name": f["name"], "response": {"ok": True, "tool": f["name"]}}]}}))
                            rows.append((rel(), "toolResponse", f["name"]))
                        continue
                    sc = d.get("serverContent")
                    if not sc:
                        continue
                    for p in sc.get("modelTurn", {}).get("parts", []):
                        if "inlineData" in p:
                            audio_n += 1
                            if audio_n == 1:
                                rows.append((rel(), "audio_first"))
                        if p.get("thought"):
                            rows.append((rel(), "THOUGHT_PART"))
                    if "inputTranscription" in sc:
                        t = sc["inputTranscription"].get("text", "")
                        rows.append((rel(), "in_tx", len(t), t if SHOW else ""))
                    if "outputTranscription" in sc:
                        t = sc["outputTranscription"].get("text", "")
                        rows.append((rel(), "out_tx", len(t), f"audio_chunks_so_far={audio_n}", t if SHOW else ""))
                    for f in ("generationComplete", "turnComplete", "interrupted"):
                        if sc.get(f):
                            rows.append((rel(), f, f"audio_chunks={audio_n}"))
                            if f == "turnComplete":
                                audio_n = 0

            rt = asyncio.create_task(reader())
            await asyncio.wait_for(ready.wait(), 10)
            data = open(SPEECH + clip, "rb").read()
            chunks = [data[k:k + CHUNK] for k in range(0, len(data), CHUNK)]
            start = time.monotonic(); n = 0
            while time.monotonic() - start < total_s and not rt.done():
                if chunks:
                    chunk = chunks.pop(0)
                    if not chunks:
                        end_at["t"] = time.monotonic() + 0.02
                        rows.append((0, "mic_end"))
                else:
                    chunk = b""
                await ws.send_str(json.dumps({"realtimeInput": {"audio": {"data": base64.b64encode(chunk.ljust(CHUNK, b"\x00")).decode(), "mimeType": "audio/pcm;rate=16000"}}}))
                n += 1
                await asyncio.sleep(max(0, start + n * 0.02 - time.monotonic()))
            rt.cancel()
    for r in rows:
        print(r)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 20))
