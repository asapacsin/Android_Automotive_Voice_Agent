"""Extra phrases for the product owner's checklist rows T02/T03/T04/T06/T11/T12.

Same method as make_speech.py (edge-tts + ffmpeg -> 16 kHz mono PCM16); kept separate so the
original corpus stays a fixed regression set.

    python tools/speech-harness/make_extra.py
"""

import asyncio
import os
import shutil
import subprocess

import truststore

# Verify TLS against the OS certificate store (Windows store; system CAs on Linux), as make_speech.py does.
truststore.inject_into_ssl()

import edge_tts  # noqa: E402


def _ffmpeg():
    """ffmpeg on PATH, else the static binary shipped by imageio-ffmpeg (Linux cloud containers)."""
    found = shutil.which("ffmpeg")
    if found:
        return found
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "speech")

PHRASES = {
    # T02 — ordinary conversation
    "intro_q": "你好，简单介绍一下你能做什么。",
    # T03 — the demo's core chain
    "nav_zhuhai_station": "导航去珠海站。",
    "start_nav_now": "开始导航。",
    # T04 — nearby brand
    "nav_nearby_mcd": "导航去附近的麦当劳。",
    # T06 — pick a candidate by its concrete name
    "pick_by_name_mcd": "选择麦当劳珠海站店。",
    "pick_by_name_gongbei": "就去拱北口岸。",
    # T11 — come back after the map takes the screen
    "continue_nav": "继续刚才的导航。",
    # T12 — closing question after several commands
    "what_can_you_do": "你现在还能做什么？",
    # T08 — stop talking
    "stop_talking": "停止说话。",
    "can_you_talk": "你能说话吗？",
    # FUZZY-DEVICE-001 (SPEC-015 FZ-01..FZ-05/FZ-09/FZ-10) - vague comfort requests
    "fz_mosquito": "有蚊子。",
    "fz_mosquito_done": "好了，关上吧。",
    "fz_seat_high": "座位有点高。",
    "fz_lower_more": "再低一点。",
    "fz_drowsy": "好困啊。",
    # DEMO_SPEC pilot (FZ-14): the driver's answer to 「是座椅还是温度？」
    "fz_answer_seat": "座椅。",
    # WAKE-SYNTH-001 - the wake phrase, injected through the debug wake path
    "wake_phrase": "你好小诺。",
    # The navigation demo (scripts/github_nav_demo.sh): questions asked while driving
    "eta_q": "还要多久到？",
    "gas_nearby": "附近有加油站吗？",
}


# Spoken slower: at the default rate the recognizer heard 「再低一点」 as 「第一点」 or 「第」 in runs
# 298, 06e5006 and 317, so the FZ-14 guard had no relative words to hold on.
RATES = {"fz_lower_more": "-25%"}


async def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    for name, text in PHRASES.items():
        mp3 = os.path.join(OUT, name + ".mp3")
        pcm = os.path.join(OUT, name + ".pcm")
        await edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural", rate=RATES.get(name, "+0%")).save(mp3)
        subprocess.run(
            [_ffmpeg(), "-loglevel", "error", "-y", "-i", mp3, "-ac", "1", "-ar", "16000", "-f", "s16le", pcm],
            check=True,
        )
        print("%-22s %7d bytes" % (name, os.path.getsize(pcm)))


asyncio.run(main())
