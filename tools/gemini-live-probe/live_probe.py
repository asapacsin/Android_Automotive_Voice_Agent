import asyncio, json, os, sys, time, base64, aiohttp
KEY = os.environ["GEMINI_API_KEY"]
MODEL = os.environ.get("MODEL", "models/gemini-3.8-live-extended-thinking")
URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

def setup_msg(extra):
    s = {"model": MODEL,
         "generationConfig": {"responseModalities": ["AUDIO"],
                              "speechConfig": {"languageCode": "zh-CN"}},
         "systemInstruction": {"parts": [{"text": "你是车载语音助手小诺。用简短的中文口语回答。需要查询实时信息或复杂问题时，调用 delegate_task，并先简短告诉用户你在查。"}]},
         "inputAudioTranscription": {}, "outputAudioTranscription": {},
         "tools": [{"functionDeclarations": [
             {"name": "delegate_task", "description": "把需要查询或较长思考的问题交给后台处理，结果稍后返回。",
              "behavior": "NON_BLOCKING",
              "parameters": {"type": "OBJECT", "properties": {"task": {"type": "STRING"}, "kind": {"type": "STRING", "enum": ["knowledge", "search", "plan"]}}, "required": ["task", "kind"]}},
             {"name": "navigate_to", "description": "开始导航到目的地。",
              "parameters": {"type": "OBJECT", "properties": {"destination": {"type": "STRING"}}, "required": ["destination"]}}]}]}
    for k, v in extra.items():
        cur = s
        parts = k.split(".")
        for p in parts[:-1]: cur = cur.setdefault(p, {})
        cur[parts[-1]] = v
    return {"setup": s}

async def run(extra, turns, tool_delay=3.0, scheduling="WHEN_IDLE", wait=25):
    t0 = time.monotonic(); ev = lambda: round((time.monotonic()-t0)*1000)
    out = {"events": []}
    async with aiohttp.ClientSession(trust_env=True) as sess:
        async with sess.ws_connect(URL, headers={"x-goog-api-key": KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps(setup_msg(extra)))
            audio_bytes = 0; first_audio=None; otx=[]; itx=[]; thoughts=0
            sent = False; pending_tool=[]
            deadline = time.monotonic()+wait
            while time.monotonic() < deadline:
                try:
                    msg = await asyncio.wait_for(ws.receive(), timeout=max(0.1, deadline-time.monotonic()))
                except asyncio.TimeoutError:
                    break
                if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.CLOSING):
                    out["events"].append((ev(), "CLOSE", ws.close_code, str(msg.extra)[:300])); break
                if msg.type == aiohttp.WSMsgType.ERROR:
                    out["events"].append((ev(), "ERROR", str(msg.data)[:300])); break
                d = json.loads(msg.data)
                keys = list(d.keys())
                if "setupComplete" in d:
                    out["events"].append((ev(), "setupComplete"))
                    for t in turns:
                        await ws.send_str(json.dumps({"clientContent": {"turns": [{"role": "user", "parts": [{"text": t}]}], "turnComplete": True}}))
                    out["events"].append((ev(), "sent_turns", len(turns)))
                    continue
                if "serverContent" in d:
                    sc = d["serverContent"]
                    for p in sc.get("modelTurn", {}).get("parts", []):
                        if "inlineData" in p:
                            if first_audio is None:
                                first_audio = ev(); out["events"].append((first_audio, "first_audio", p["inlineData"].get("mimeType")))
                            audio_bytes += len(base64.b64decode(p["inlineData"]["data"]))
                        elif p.get("thought"):
                            thoughts += 1
                        elif "text" in p:
                            out["events"].append((ev(), "text_part", p["text"][:80]))
                        else:
                            out["events"].append((ev(), "part", list(p.keys())))
                    if "outputTranscription" in sc: otx.append(sc["outputTranscription"].get("text", ""))
                    if "inputTranscription" in sc: itx.append(sc["inputTranscription"].get("text", ""))
                    for flag in ("turnComplete", "interrupted", "generationComplete", "waitingForInput", "turnCompleteReason"):
                        if flag in sc: out["events"].append((ev(), flag, sc[flag]))
                    if "interactionStatus" in sc: out["events"].append((ev(), "interactionStatus", sc["interactionStatus"]))
                    other = [k for k in sc if k not in ("interactionStatus","modelTurn","outputTranscription","inputTranscription","turnComplete","interrupted","generationComplete","waitingForInput","turnCompleteReason")]
                    if other: out["events"].append((ev(), "serverContent_other", other))
                    continue
                if "toolCall" in d:
                    fcs = d["toolCall"].get("functionCalls", [])
                    out["events"].append((ev(), "toolCall", [(f.get("name"), f.get("args"), f.get("id")) for f in fcs]))
                    for f in fcs:
                        if f["name"] == "delegate_task":
                            await asyncio.sleep(tool_delay)
                            resp = {"toolResponse": {"functionResponses": [{"id": f.get("id"), "name": f["name"], "response": {"result": "查询结果：明天杭州多云转小雨，气温18到24度，东北风3级。来源：模拟数据。", "scheduling": scheduling}}]}}
                            await ws.send_str(json.dumps(resp)); out["events"].append((ev(), "sent_toolResponse", scheduling))
                        else:
                            resp = {"toolResponse": {"functionResponses": [{"id": f.get("id"), "name": f["name"], "response": {"ok": True, "status": "navigation_started"}}]}}
                            await ws.send_str(json.dumps(resp)); out["events"].append((ev(), "sent_toolResponse_blocking"))
                    continue
                (out["events"].append((ev(), "other", keys, json.dumps(d, ensure_ascii=False)[:300])) if d else None)
            out["audio_bytes"] = audio_bytes; out["thought_parts"] = thoughts
            out["output_transcript"] = "".join(otx); out["input_transcript"] = "".join(itx)
    return out

if __name__ == "__main__":
    extra = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {}
    turns = json.loads(sys.argv[2]) if len(sys.argv) > 2 else ["你好，用一句话介绍你自己。"]
    sched = sys.argv[3] if len(sys.argv) > 3 else "WHEN_IDLE"
    r = asyncio.run(run(extra, turns, scheduling=sched))
    for e in r["events"]: print(e)
    print("audio_bytes", r["audio_bytes"], "thought_parts", r["thought_parts"])
    print("OUT:", r["output_transcript"])
