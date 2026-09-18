"""Phrases for SPEC-006 contextual voice commands, and for the P22 false-capability fix.

Same method as make_speech.py (edge-tts + ffmpeg -> 16 kHz mono PCM16); kept separate so the
original corpus stays a fixed regression set.

    python tools/speech-harness/make_context.py
"""

import asyncio
import os
import subprocess

import truststore

# Verify TLS against the Windows certificate store, as make_speech.py does.
truststore.inject_into_ssl()

import edge_tts  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "speech")

PHRASES = {
    # SPEC-006 C2 - a stated discomfort, not an API command. Must become a real adjustment.
    "ctx_too_hot": "有点热。",
    # SPEC-006 C3 - relative continuation. The model never saw the previous turn, so this only
    # works if the app carried the context.
    "ctx_cooler": "再凉一点。",
    # SPEC-006 C8 - feedback that the previous action was not enough.
    "ctx_still_hot": "还是有点热。",
    # P22 - a song this product has no way to play. Must be refused, and must NOT start the
    # bundled track while claiming to play the song that was asked for.
    "media_named_song": "放一下周杰伦那首讲晴天的歌。",
}

VOICE = "zh-CN-XiaoxiaoNeural"


async def synth(key: str, text: str) -> None:
    mp3 = os.path.join(OUT, key + ".mp3")
    pcm = os.path.join(OUT, key + ".pcm")
    await edge_tts.Communicate(text, VOICE).save(mp3)
    subprocess.run(
        ["ffmpeg", "-y", "-i", mp3, "-ar", "16000", "-ac", "1", "-f", "s16le", pcm],
        check=True,
        capture_output=True,
    )
    print("wrote %s (%d bytes)" % (os.path.relpath(pcm), os.path.getsize(pcm)))


async def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    for key, text in PHRASES.items():
        await synth(key, text)


if __name__ == "__main__":
    asyncio.run(main())
