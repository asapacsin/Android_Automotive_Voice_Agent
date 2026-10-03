"""B-034: A/B the Gemini Live prebuilt voices with the same Chinese sentences.

For each voice, one Live session reads the same fixed test lines; the 24 kHz PCM is saved as a WAV
and the median pitch (F0) is measured, so a deep/"old" voice can be told from a young, bright one
before anyone listens. The owner still picks by ear (BACKLOG B-034); the numbers only shortlist.

    OUT=D:\\桌面\\android_doc\\voice_ab python voice_ab.py              # default candidates
    OUT=./voice_ab python voice_ab.py Leda Zephyr Kore                  # chosen voices

Key only from GEMINI_API_KEY (header, never printed). Output is synthetic test sentences only.
Voices are Google's prebuilt voices selected by name; nothing is cloned or imitated.
"""
import asyncio, base64, json, math, os, struct, sys, wave
import aiohttp

KEY = os.environ["GEMINI_API_KEY"]
MODEL = os.environ.get("MODEL", "models/gemini-3.8-live")  # the app default (VoiceCatalog.GEMINI_LIVE_FAST)
URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
OUT = os.environ.get("OUT", "voice_ab")
RATE = 24_000

# Female prebuilt voices worth hearing for a young, clear, poised voice; Kore is the current default.
CANDIDATES = ["Kore", "Leda", "Zephyr", "Autonoe", "Erinome", "Laomedeia", "Pulcherrima", "Aoede", "Despina", "Callirrhoe"]

LINES = "好的，空调已经调到二十二度了。前方五百米右转，然后走第二个出口。这点小事，交给我就行。"
INSTRUCTION = "你是朗读测试。只把用户给的中文原样朗读一遍，语气自信、清脆、利落，不要增加或删减任何字，不要回答问题。"


def bare(text):
    """Characters only: the transcript's punctuation and spacing are not the voice's doing."""
    return "".join(c for c in text if c.isalnum())


def setup(voice):
    return {"setup": {
        "model": MODEL,
        "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {
            "languageCode": "zh-CN",
            "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}}}},
        "systemInstruction": {"parts": [{"text": INSTRUCTION}]},
        "outputAudioTranscription": {},
    }}


async def record(voice, wait=40):
    pcm = bytearray(); said = []
    async with aiohttp.ClientSession(trust_env=True) as sess:
        async with sess.ws_connect(URL, headers={"x-goog-api-key": KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps(setup(voice)))
            loop = asyncio.get_running_loop(); deadline = loop.time() + wait
            while loop.time() < deadline:
                try:
                    msg = await asyncio.wait_for(ws.receive(), timeout=max(0.1, deadline - loop.time()))
                except asyncio.TimeoutError:
                    break
                if msg.type != aiohttp.WSMsgType.TEXT and msg.type != aiohttp.WSMsgType.BINARY:
                    raise RuntimeError(f"{voice}: socket {msg.type} {ws.close_code} {str(msg.extra)[:200]}")
                d = json.loads(msg.data)
                if "setupComplete" in d:
                    await ws.send_str(json.dumps({"clientContent": {
                        "turns": [{"role": "user", "parts": [{"text": LINES}]}], "turnComplete": True}}))
                    continue
                sc = d.get("serverContent", {})
                for p in sc.get("modelTurn", {}).get("parts", []):
                    if "inlineData" in p:
                        pcm += base64.b64decode(p["inlineData"]["data"])
                if "outputTranscription" in sc:
                    said.append(sc["outputTranscription"].get("text", ""))
                if sc.get("turnComplete"):
                    break
    return bytes(pcm), "".join(said)


def median_f0(pcm):
    """Median pitch in Hz over voiced 40 ms frames (autocorrelation on a 8 kHz copy)."""
    samples = struct.unpack(f"<{len(pcm) // 2}h", pcm[: len(pcm) // 2 * 2])
    x = [sum(samples[i:i + 3]) / 3 for i in range(0, len(samples) - 2, 3)]  # 24 kHz -> 8 kHz
    sr, frame = 8000, 320
    lo, hi = sr // 500, sr // 70  # 70..500 Hz
    peak = max((abs(v) for v in x), default=0) or 1
    f0s = []
    for start in range(0, len(x) - frame - hi, frame // 2):
        f = x[start:start + frame + hi]
        energy = sum(v * v for v in f[:frame]) / frame
        if math.sqrt(energy) < 0.08 * peak:
            continue
        best, lag_best = 0.0, 0
        for lag in range(lo, hi):
            r = sum(f[i] * f[i + lag] for i in range(frame))
            if r > best:
                best, lag_best = r, lag
        if lag_best and best / (energy * frame) > 0.5:
            f0s.append(sr / lag_best)
    f0s.sort()
    return round(f0s[len(f0s) // 2]) if f0s else None


async def main(voices):
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for voice in voices:
        try:
            pcm, said = await record(voice)
        except Exception as failure:  # one bad voice must not stop the comparison
            rows.append({"voice": voice, "error": str(failure)[:200]}); print(json.dumps(rows[-1])); continue
        path = os.path.join(OUT, f"{voice}.wav")
        with wave.open(path, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm)
        rows.append({"voice": voice, "seconds": round(len(pcm) / 2 / RATE, 1), "median_f0_hz": median_f0(pcm),
                     "verbatim": bare(said) == bare(LINES), "file": path})
        print(json.dumps(rows[-1], ensure_ascii=False))
    with open(os.path.join(OUT, "summary.json"), "w", encoding="utf-8") as f:
        json.dump({"model": MODEL, "text": LINES, "voices": rows}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:] or CANDIDATES))
