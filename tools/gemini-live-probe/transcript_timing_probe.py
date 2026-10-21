"""VOICE-AB-002 / ADR-016: when does Gemini's output transcription arrive relative to its audio?
First audio, first transcript chunk, first complete clause, generationComplete, audio length.
Key only from GEMINI_API_KEY (header, never printed)."""
import asyncio, json, os, time, base64, aiohttp
KEY=os.environ["GEMINI_API_KEY"]; URL="wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
Q=["你能做什么","有点热","帮我介绍一下珠海横琴有什么好玩的"]
async def run(q):
    async with aiohttp.ClientSession(trust_env=True) as s:
        async with s.ws_connect(URL, headers={"x-goog-api-key":KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps({"setup":{"model":"models/gemini-3.8-live","generationConfig":{"responseModalities":["AUDIO"],"speechConfig":{"languageCode":"zh-CN"}},
              "systemInstruction":{"parts":[{"text":"你是车载助手小诺，用简短中文口语回答。"}]},"outputAudioTranscription":{}}}))
            fa=ft=None; audio=0; chunks=[]; done=None
            while True:
                m=await asyncio.wait_for(ws.receive(),timeout=40)
                if m.type not in (aiohttp.WSMsgType.TEXT,aiohttp.WSMsgType.BINARY): return {"close":str(m.extra)[:120]}
                d=json.loads(m.data)
                if "setupComplete" in d:
                    t0=time.monotonic(); await ws.send_str(json.dumps({"clientContent":{"turns":[{"role":"user","parts":[{"text":q}]}],"turnComplete":True}})); continue
                sc=d.get("serverContent",{}); now=round((time.monotonic()-t0)*1000)
                for p in sc.get("modelTurn",{}).get("parts",[]):
                    if "inlineData" in p:
                        fa=fa or now; audio+=len(base64.b64decode(p["inlineData"]["data"]))
                if "outputTranscription" in sc:
                    ft=ft or now; chunks.append((now, sc["outputTranscription"].get("text","")))
                if sc.get("generationComplete"): done=now
                if sc.get("turnComplete"): break
            first_clause=next((t for t,_ in [(t,c) for t,c in chunks] if any(x in "".join(c for tt,c in chunks if tt<=t) for x in "，。！？,")), None)
            return {"q":q,"first_audio_ms":fa,"first_tx_ms":ft,"first_clause_tx_ms":first_clause,"gen_complete_ms":done,
                    "audio_s":round(audio/48000,1),"tx_chunks":len(chunks),"chars":len("".join(c for _,c in chunks))}
async def main():
    for q in Q:
        for _ in range(2):
            try: print(json.dumps(await run(q),ensure_ascii=False))
            except Exception as e: print("ERR",str(e)[:150])
asyncio.run(main())
