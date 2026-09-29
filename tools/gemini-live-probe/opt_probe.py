import asyncio, json, sys, aiohttp
from live_probe import setup_msg, URL, KEY
async def one(extra):
    async with aiohttp.ClientSession(trust_env=True) as s:
        async with s.ws_connect(URL, headers={"x-goog-api-key": KEY}) as ws:
            m = setup_msg({"generationConfig.thinkingConfig": {"thinkingLevel": "LOW"}, **extra})
            await ws.send_str(json.dumps(m))
            try:
                msg = await asyncio.wait_for(ws.receive(), 8)
            except asyncio.TimeoutError:
                return "timeout"
            if msg.type in (aiohttp.WSMsgType.TEXT, aiohttp.WSMsgType.BINARY) and "setupComplete" in (msg.data if isinstance(msg.data,str) else msg.data.decode()): return "ACCEPTED"
            return f"REJECTED {ws.close_code} {str(msg.extra)[:160]}"
opts = {
 "vad_silence": {"realtimeInputConfig": {"automaticActivityDetection": {"silenceDurationMs": 800, "prefixPaddingMs": 200, "startOfSpeechSensitivity": "START_SENSITIVITY_LOW", "endOfSpeechSensitivity": "END_SENSITIVITY_LOW"}}},
 "vad_disabled": {"realtimeInputConfig": {"automaticActivityDetection": {"disabled": True}}},
 "no_interruption": {"realtimeInputConfig": {"activityHandling": "NO_INTERRUPTION"}},
 "context_compression": {"contextWindowCompression": {"slidingWindow": {}}},
 "session_resumption": {"sessionResumption": {}},
 "proactive_audio": {"proactivity": {"proactiveAudio": True}},
 "affective": {"enableAffectiveDialog": True},
 "voice_name": {"generationConfig.speechConfig": {"languageCode": "zh-CN", "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}}},
 "google_search": {"tools": [{"googleSearch": {}}]},
 "search_plus_functions": {"tools": [{"googleSearch": {}}, {"functionDeclarations": [{"name": "navigate_to", "description": "导航", "parameters": {"type": "OBJECT", "properties": {"destination": {"type": "STRING"}}}}]}]},
 "include_thoughts": {"generationConfig.thinkingConfig": {"thinkingLevel": "LOW", "includeThoughts": True}},
}
async def main():
    for k, v in opts.items():
        print(k, "->", await one(v)); await asyncio.sleep(2)
asyncio.run(main())
