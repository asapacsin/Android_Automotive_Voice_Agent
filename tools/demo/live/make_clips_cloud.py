"""Cloud (Linux) twin of tools/demo/recorder/make_clips.py: the commute demo's c_* driver lines.

edge-tts through the environment's own proxy (HTTPS_PROXY), the imageio-ffmpeg binary, and the same
LINES and voice as make_clips.py (read with ast, never duplicated). Output: recorder/clips/<key>.pcm,
16 kHz mono s16le. The clips directory is gitignored; generated audio is never committed.

    python3 tools/demo/live/make_clips_cloud.py [key ...]     # default: every c_* line
"""
import ast, asyncio, os, re, subprocess, sys

import edge_tts
import imageio_ffmpeg

HERE = os.path.dirname(os.path.abspath(__file__))
RECORDER = os.path.join(HERE, "..", "recorder")
OUT = os.path.join(RECORDER, "clips")


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


async def main(keys):
    lines, voice = recorder_constants()
    os.makedirs(OUT, exist_ok=True)
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or None
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    for k in keys or [k for k in lines if k.startswith("c_")]:
        if os.path.exists(os.path.join(OUT, k + ".pcm")):
            continue
        await make(k, lines[k], voice, ffmpeg, proxy)
        print("ok", k, flush=True)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
