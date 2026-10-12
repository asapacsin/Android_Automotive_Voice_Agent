"""Cloud (Linux) twin of tools/demo/recorder/make_clips.py: the commute demo's c_* driver lines.

edge-tts through the environment's own proxy (HTTPS_PROXY), the imageio-ffmpeg binary, and the same
LINES and voice as make_clips.py (read with ast, never duplicated). Output: recorder/clips/<key>.pcm,
16 kHz mono s16le. The clips directory is gitignored; generated audio is never committed.

    python3 tools/demo/live/make_clips_cloud.py [key ...]     # default: every c_* line and g_* prompt

The drive scene's Amap-style guidance prompts (g_*, GUIDANCE below) are spoken in a voice that is
neither the driver's nor 小诺's (GUIDANCE_VOICE). The manoeuvres they name are SIMULATED (see
HengqinWorld.MANEUVERS); the keys must match HengqinWorld.promptsFor.

With PIPER_MODEL=<path to a Piper .onnx voice, e.g. zh_CN-huayan-medium> the g_* prompts are made
offline with Piper (https://github.com/rhasspy/piper; `pip install piper-tts`, voices on
huggingface.co/rhasspy/piper-voices) instead of edge-tts. 2026-10-12: edge-tts got a 403 from
speech.platform.bing.com from the cloud container for every voice and every Sec-MS-GEC version tried
(143-154); searched, no fix found, so the guidance prompts use Piper there.
"""
import ast, asyncio, os, re, subprocess, sys

import edge_tts
import imageio_ffmpeg

HERE = os.path.dirname(os.path.abspath(__file__))
RECORDER = os.path.join(HERE, "..", "recorder")
OUT = os.path.join(RECORDER, "clips")


GUIDANCE_VOICE = "zh-CN-XiaoxiaoNeural"
GUIDANCE = {
    "g_start": "开始导航，全程约两点六公里",
    "g_m1_300": "前方三百米右转，进入环岛东路",
    "g_m1_50": "前方路口右转",
    "g_m2_300": "前方三百米左转，进入港澳大道",
    "g_m2_50": "前方路口左转",
    "g_m3_300": "前方三百米直行，通过路口",
    "g_m3_50": "前方路口直行",
    "g_m4_300": "前方三百米右转，进入横琴镇",
    "g_m4_50": "前方路口右转",
    "g_arrive": "已到达目的地附近，本次导航结束",
}


def recorder_constants():
    src = open(os.path.join(RECORDER, "make_clips.py"), encoding="utf-8").read()
    lines = ast.literal_eval(re.search(r"LINES = (\{.*?\n\})", src, re.S).group(1))
    voice = re.search(r'VOICE = "([^"]+)"', src).group(1)
    return lines, voice


async def make(key, text, voice, ffmpeg, proxy):
    mp3 = os.path.join(OUT, key + ".mp3")
    await edge_tts.Communicate(text, voice, proxy=proxy).save(mp3)
    subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", mp3, "-ac", "1", "-ar", "16000", "-f", "s16le",
                    os.path.join(OUT, key + ".pcm")], check=True)
    os.remove(mp3)


def make_piper(key, text, model, ffmpeg):
    wav = os.path.join(OUT, key + ".wav")
    subprocess.run([os.path.join(os.path.dirname(sys.executable), "piper"), "-m", model, "-f", wav],
                   input=(text + "。").encode("utf-8"), check=True, capture_output=True)
    subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", wav, "-ac", "1", "-ar", "16000", "-f", "s16le",
                    os.path.join(OUT, key + ".pcm")], check=True)
    os.remove(wav)


async def main(keys):
    lines, voice = recorder_constants()
    os.makedirs(OUT, exist_ok=True)
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or None
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    for k in keys or [k for k in lines if k.startswith("c_")] + list(GUIDANCE):
        if os.path.exists(os.path.join(OUT, k + ".pcm")):
            continue
        if k in GUIDANCE and os.environ.get("PIPER_MODEL"):
            make_piper(k, GUIDANCE[k], os.environ["PIPER_MODEL"], ffmpeg)
        elif k in GUIDANCE:
            await make(k, GUIDANCE[k], GUIDANCE_VOICE, ffmpeg, proxy)
        else:
            await make(k, lines[k], voice, ffmpeg, proxy)
        print("ok", k, flush=True)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
