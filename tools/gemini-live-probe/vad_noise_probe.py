"""P47: does Gemini's server VAD open a turn when speech sits on a steady noise bed?

Streams, in real time, a fan-like noise bed (brown + pink, steady) from t=0, with one driver clip
mixed in at 3 s, then noise until the end. Records whether the server sends voiceActivity
ACTIVITY_START / ACTIVITY_END, the first input transcription, and the first reply audio.
One session per run. Output: one JSON line per run, timings only (no transcript text).

    MODEL=models/gemini-3.1-flash-live-preview START=LOW|HIGH END=HIGH|LOW NOISE_DBFS=-32|off \
      SPEECH_DBFS=-23 python vad_noise_probe.py u_hello.pcm

The key comes only from GEMINI_API_KEY (header, never printed). Clips: tools/demo/recorder/clips
(synthetic speech, 16 kHz mono s16le).
"""
import asyncio, base64, json, os, sys, time

import aiohttp
import numpy as np

KEY = os.environ["GEMINI_API_KEY"]
MODEL = os.environ.get("MODEL", "models/gemini-3.1-flash-live-preview")
URL = ("wss://generativelanguage.googleapis.com/ws/"
       "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CLIPS = os.environ.get("CLIP_DIR", os.path.join(ROOT, "tools", "demo", "recorder", "clips"))
RATE, CHUNK_MS = 16000, 20
LEAD_S, TAIL_S = 3.0, float(os.environ.get("TAIL_S", "9"))


def dbfs_scale(x, target_dbfs, active_only=False):
    sel = x[np.abs(x) > 300] if active_only else x
    rms = np.sqrt((sel.astype(float) ** 2).mean())
    return x * (10 ** (target_dbfs / 20) * 32768 / rms)


def noise_bed(n, seed=7):
    rng = np.random.default_rng(seed)
    white = rng.standard_normal(n)
    brown = np.cumsum(white)
    brown -= np.convolve(brown, np.ones(4001) / 4001, mode="same")  # remove drift, keep the rumble
    f = np.fft.rfftfreq(n, 1 / RATE)
    spec = np.fft.rfft(rng.standard_normal(n))
    spec[1:] /= np.sqrt(f[1:])  # pink
    pink = np.fft.irfft(spec, n)
    bed = brown / brown.std() + pink / pink.std()
    return bed / bed.std()


def build_stream(clip):
    speech = np.frombuffer(open(os.path.join(CLIPS, clip), "rb").read(), dtype=np.int16).astype(float)
    speech = dbfs_scale(speech, float(os.environ.get("SPEECH_DBFS", "-23")), active_only=True)
    total = int((LEAD_S + len(speech) / RATE + TAIL_S) * RATE)
    out = np.zeros(total)
    noise = os.environ.get("NOISE_DBFS", "off")
    if noise != "off":
        out += dbfs_scale(noise_bed(total), float(noise))
    start = int(LEAD_S * RATE)
    out[start:start + len(speech)] += speech
    return np.clip(out, -32768, 32767).astype(np.int16).tobytes(), len(speech) / RATE


def setup():
    activity = {"startOfSpeechSensitivity": "START_SENSITIVITY_" + os.environ.get("START", "LOW"),
                "endOfSpeechSensitivity": "END_SENSITIVITY_" + os.environ.get("END", "HIGH")}
    return {"setup": {
        "model": MODEL,
        "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"languageCode": "zh-CN"}},
        "systemInstruction": {"parts": [{"text": "你是车载语音助手小诺。用一句简短的中文口语回答。"}]},
        "inputAudioTranscription": {}, "outputAudioTranscription": {},
        "realtimeInputConfig": {"automaticActivityDetection": activity},
    }}


async def main(clip):
    data, speech_s = build_stream(clip)
    speech_end = LEAD_S + speech_s
    out = {"clip": clip, "model": MODEL.split("/")[-1], "start": os.environ.get("START", "LOW"),
           "end": os.environ.get("END", "HIGH"), "noise_dbfs": os.environ.get("NOISE_DBFS", "off"),
           "speech_dbfs": os.environ.get("SPEECH_DBFS", "-23"), "speech_end_s": round(speech_end, 2),
           "activity_start_s": None, "activity_end_s": None, "first_input_tx_s": None,
           "first_reply_audio_s": None, "turn_complete_s": None, "error": None}
    async with aiohttp.ClientSession(trust_env=True) as sess:
        async with sess.ws_connect(URL, headers={"x-goog-api-key": KEY}, max_msg_size=0) as ws:
            await ws.send_str(json.dumps(setup()))
            first = await ws.receive()
            if "setupComplete" not in (first.data if isinstance(first.data, str) else first.data.decode()):
                out["error"] = "no setupComplete"; print(json.dumps(out)); return
            t0 = time.monotonic()
            rel = lambda: round(time.monotonic() - t0, 2)

            async def sender():
                step = RATE * 2 * CHUNK_MS // 1000
                for i in range(0, len(data), step):
                    await ws.send_str(json.dumps({"realtimeInput": {"audio": {
                        "data": base64.b64encode(data[i:i + step]).decode(), "mimeType": "audio/pcm;rate=16000"}}}))
                    target = t0 + (i + step) / (RATE * 2)
                    await asyncio.sleep(max(0, target - time.monotonic()))

            send_task = asyncio.create_task(sender())
            deadline = t0 + len(data) / (RATE * 2) + 2
            while time.monotonic() < deadline:
                try:
                    msg = await asyncio.wait_for(ws.receive(), timeout=max(0.05, deadline - time.monotonic()))
                except asyncio.TimeoutError:
                    break
                if msg.type not in (aiohttp.WSMsgType.TEXT, aiohttp.WSMsgType.BINARY):
                    out["error"] = "closed %s" % ws.close_code; break
                raw = json.loads(msg.data)
                sc = raw.get("serverContent", {})
                va = raw.get("voiceActivity") or sc.get("voiceActivity")
                if va:
                    kind = str(va.get("type", va.get("voiceActivityType", ""))).upper()
                    if kind.endswith("ACTIVITY_START") and out["activity_start_s"] is None:
                        out["activity_start_s"] = rel()
                    if kind.endswith("ACTIVITY_END") and out["activity_end_s"] is None:
                        out["activity_end_s"] = rel()
                if sc.get("inputTranscription") and out["first_input_tx_s"] is None:
                    out["first_input_tx_s"] = rel()
                parts = (sc.get("modelTurn") or {}).get("parts") or []
                if any("inlineData" in p for p in parts) and out["first_reply_audio_s"] is None:
                    out["first_reply_audio_s"] = rel()
                if sc.get("turnComplete") and out["turn_complete_s"] is None:
                    out["turn_complete_s"] = rel()
            send_task.cancel()
    out["replied"] = out["first_reply_audio_s"] is not None
    print(json.dumps(out))


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "u_hello.pcm"))
