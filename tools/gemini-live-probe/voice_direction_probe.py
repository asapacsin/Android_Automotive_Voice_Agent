"""VOICE-AB-002 / ADR-016: does a stronger 语音风格 line make Gemini sound younger in a real
conversation? App persona vs the same persona with a stronger voice line; median F0 per reply.
Key only from GEMINI_API_KEY (header, never printed)."""
import asyncio, json, os, re, sys, base64, aiohttp
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voice_ab as v
ROOT=os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
src=open(ROOT+"/app/src/main/kotlin/com/novadrive/app/PersonaProfiles.kt",encoding="utf-8").read()
persona=re.search(r'DEFAULT_INSTRUCTIONS: String = """\n(.*?)"""', src, re.S).group(1).strip()
STRONG="语音风格：十七八岁少女的声线，音调明显偏高，声音清亮通透、轻快利落，像动画里聪明又有点骄傲的少女；绝不成熟、低沉、沙哑或播音腔。吐字清晰紧凑，平稳克制。"
variants={"app":persona,"strong":"\n".join(STRONG if l.startswith("语音风格：") else l for l in persona.splitlines())}
assert variants["strong"]!=persona
async def turn(voice, instr, q):
    pcm=bytearray(); said=[]
    async with aiohttp.ClientSession(trust_env=True) as s:
        async with s.ws_connect(v.URL, headers={"x-goog-api-key":v.KEY}, max_msg_size=0) as ws:
            st=v.setup(voice); st["setup"]["systemInstruction"]["parts"][0]["text"]=instr
            await ws.send_str(json.dumps(st))
            while True:
                m=await asyncio.wait_for(ws.receive(),timeout=40)
                if m.type not in (aiohttp.WSMsgType.TEXT,aiohttp.WSMsgType.BINARY): raise RuntimeError(str(m.extra)[:100])
                d=json.loads(m.data)
                if "setupComplete" in d:
                    await ws.send_str(json.dumps({"clientContent":{"turns":[{"role":"user","parts":[{"text":q}]}],"turnComplete":True}})); continue
                sc=d.get("serverContent",{})
                for p in sc.get("modelTurn",{}).get("parts",[]):
                    if "inlineData" in p: pcm+=base64.b64decode(p["inlineData"]["data"])
                if "outputTranscription" in sc: said.append(sc["outputTranscription"].get("text",""))
                if sc.get("turnComplete"): break
    return bytes(pcm), "".join(said)
async def main():
    for voice in ["Leda","Erinome"]:
        for name,instr in variants.items():
            for q in ["你能做什么","有点热"]:
                for r in range(2):
                    try:
                        pcm,said=await turn(voice,instr,q)
                        print(json.dumps({"voice":voice,"persona":name,"q":q,"sec":round(len(pcm)/48000,1),"f0":v.median_f0(pcm),"said":said[:40]},ensure_ascii=False))
                    except Exception as e: print(voice,name,q,"ERR",str(e)[:100])
asyncio.run(main())
