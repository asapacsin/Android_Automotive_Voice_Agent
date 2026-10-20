#!/usr/bin/env python3
"""Generate two short tagged sine-tone MP3s for the VLC hand-off check (MUSIC-VLC-EMU-001).

Synthetic tones only (no copyrighted media); nothing binary is committed. Needs ffmpeg.
Usage: make_tagged_tones.py OUT_DIR
"""
import pathlib
import subprocess
import sys

TONES = [("NovaTestToneA", 440), ("NovaTestToneB", 660)]


def main() -> int:
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for title, hz in TONES:
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"sine=frequency={hz}:duration=30",
             "-metadata", f"title={title}", "-metadata", "artist=NovaTest", "-metadata", "album=NovaTest",
             "-b:a", "64k", str(out / f"{title}.mp3")],
            check=True,
        )
        print(out / f"{title}.mp3")
    return 0


if __name__ == "__main__":
    sys.exit(main())
