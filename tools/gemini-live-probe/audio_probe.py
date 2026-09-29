import asyncio, json, os, sys, time, base64, aiohttp
sys.path.insert(0, os.path.dirname(__file__))
from live_probe import setup_msg, URL, KEY
SPEECH = os.environ.get("SPEECH_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "speech-harness", "speech") + os.sep)
CHUNK = 640  # 20 ms at 16 kHz s16le

async def main(timeline, extra, total_s):
    t0 = time.monotonic(); ev = lambda: round((time.monotonic()-t0)*1000)
    log = []; otx = []; itx = []
    async with aiohttp.ClientSession(trust_env=True) as sess:
        async with sess.ws_connect(URL, headers={"x-goog-api-key": KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps(setup_msg(extra)))
            ready = asyncio.Event()
            speaking = {"on": False}
            async def reader():
                first = True
                while True:
                    msg = await ws.receive()
                    if msg.type != aiohttp.WSMsgType.TEXT and msg.type != aiohttp.WSMsgType.BINARY:
                        log.append((ev(), "CLOSE", ws.close_code, str(msg.extra)[:200])); return
                    d = json.loads(msg.data)
                    if "setupComplete" in d: ready.set(); continue
                    if "serverContent" in d:
                        sc = d["serverContent"]
                        for p in sc.get("modelTurn", {}).get("parts", []):
                            if "inlineData" in p and not speaking["on"]:
                                speaking["on"] = True; log.append((ev(), "audio_start"))
                        if "inputTranscription" in sc: itx.append((ev(), sc["inputTranscription"].get("text","")))
                        if "outputTranscription" in sc: otx.append(sc["outputTranscription"].get("text",""))
                        for f in ("interrupted","turnComplete","interactionStatus"):
                            if f in sc: log.append((ev(), f, sc[f]))
                        if sc.get("turnComplete") or sc.get("interrupted"):
                            if otx: log.append((ev(), "said", "".join(otx))); otx.clear()
                            speaking["on"] = False
                    elif "toolCall" in d:
                        fcs = d["toolCall"]["functionCalls"]
                        log.append((ev(), "toolCall", [(f["name"], f.get("args")) for f in fcs]))
                        for f in fcs:
                            await asyncio.sleep(1.0)
                            await ws.send_str(json.dumps({"toolResponse": {"functionResponses": [{"id": f.get("id"), "name": f["name"], "response": {"ok": True, "status": "navigation_started", "destination": "万达广场"} if f["name"]=="navigate_to" else {"result": "模拟结果", "scheduling": "WHEN_IDLE"}}]}}))
                            log.append((ev(), "toolResponse", f["name"]))
                    elif "toolCallCancellation" in d:
                        log.append((ev(), "toolCallCancellation", d["toolCallCancellation"]))
                    elif "goAway" in d:
                        log.append((ev(), "goAway", d["goAway"]))
            rt = asyncio.create_task(reader())
            await asyncio.wait_for(ready.wait(), 10)
            start = time.monotonic()
            sched = sorted(timeline)
            silence = b"\x00" * CHUNK
            queue = []
            i = 0; n = 0
            while time.monotonic() - start < total_s and not rt.done():
                now_ms = (time.monotonic() - start) * 1000
                while i < len(sched) and sched[i][0] <= now_ms:
                    data = open(SPEECH + sched[i][1], "rb").read()
                    queue = [data[k:k+CHUNK] for k in range(0, len(data), CHUNK)]
                    log.append((ev(), "mic_start", sched[i][1], f"{len(data)/32:.0f}ms")); i += 1
                    endlog = True
                chunk = queue.pop(0) if queue else silence
                if not queue and i > 0 and 'endlog' in dir() and endlog:
                    log.append((ev(), "mic_end")); endlog = False
                await ws.send_str(json.dumps({"realtimeInput": {"audio": {"data": base64.b64encode(chunk.ljust(CHUNK, b"\x00")).decode(), "mimeType": "audio/pcm;rate=16000"}}}))
                n += 1
                await asyncio.sleep(max(0, start + n*0.02 - time.monotonic()))
            rt.cancel()
    for e in log: print(e)
    print("IN:", " | ".join(f"{t}:{x}" for t, x in itx)[:600])

if __name__ == "__main__":
    tl = [(int(a), b) for a, b in json.loads(sys.argv[1])]
    extra = {"generationConfig.thinkingConfig": {"thinkingLevel": os.environ.get("LVL", "LOW")}}
    extra.update(json.loads(sys.argv[3]) if len(sys.argv) > 3 else {})
    asyncio.run(main(tl, extra, float(sys.argv[2])))
