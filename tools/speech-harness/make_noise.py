"""Synthetic non-speech impulses for the open-mic acceptance tests.

Unlike make_speech.py this needs no TTS and no network: the point is sound that is loud but not
speech, so the uplink gate and the phantom-turn gate can be exercised on the phone deterministically.

    python tools/speech-harness/make_noise.py
    adb push tools/speech-harness/speech/tap.pcm \
        /sdcard/Android/data/com.novadrive.app/files/test_speech/tap.pcm

Output is 16 kHz mono PCM16, the same format the harness injects.
"""

import math
import os
import random
import struct

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "speech")
RATE = 16000


def _write(name, samples):
    path = os.path.join(OUT, name + ".pcm")
    with open(path, "wb") as handle:
        handle.write(b"".join(struct.pack("<h", max(-32767, min(32767, int(s)))) for s in samples))
    print("%-14s %5d ms  %s" % (name, len(samples) * 1000 // RATE, path))


def _silence(ms):
    return [0] * (RATE * ms // 1000)


def tap(ms=40, amplitude=22000):
    """A sharp click with a fast exponential decay: a fingernail on the dashboard."""
    n = RATE * ms // 1000
    return [amplitude * math.exp(-8.0 * i / n) * random.uniform(-1, 1) for i in range(n)]


def knock(ms=90, amplitude=20000):
    """Lower, slightly longer: a knuckle on plastic."""
    n = RATE * ms // 1000
    return [
        amplitude * math.exp(-5.0 * i / n) * math.sin(2 * math.pi * 120 * i / RATE)
        for i in range(n)
    ]


def cough(ms=260, amplitude=16000):
    """Broadband burst that sustains past the onset — it is meant to reach the model."""
    n = RATE * ms // 1000
    out = []
    for i in range(n):
        envelope = math.exp(-3.5 * i / n) * (1 - math.exp(-40.0 * i / n))
        out.append(amplitude * envelope * random.uniform(-1, 1))
    return out


def chair(ms=400, amplitude=9000):
    """A scrape: sustained, mid-level, no pitch."""
    n = RATE * ms // 1000
    value = 0.0
    out = []
    for i in range(n):
        value = 0.85 * value + 0.15 * random.uniform(-1, 1)
        envelope = min(1.0, i / (0.1 * n)) * math.exp(-1.5 * i / n)
        out.append(amplitude * value * envelope * 3)
    return out


def room_tone(ms=1500, amplitude=350):
    """Quiet steady background: must never open the gate on its own."""
    n = RATE * ms // 1000
    return [amplitude * random.uniform(-1, 1) for i in range(n)]


if __name__ == "__main__":
    random.seed(20260918)  # deterministic: the same files every run
    os.makedirs(OUT, exist_ok=True)
    # Each clip is padded with silence so the gate sees a quiet room before and after.
    _write("noise_tap", _silence(300) + tap() + _silence(1500))
    _write("noise_knock", _silence(300) + knock() + _silence(1500))
    _write("noise_cough", _silence(300) + cough() + _silence(1500))
    _write("noise_chair", _silence(300) + chair() + _silence(1500))
    _write("noise_room", room_tone())
    # Several impulses in a row, as in acceptance test B.
    _write(
        "noise_burst",
        _silence(300) + tap() + _silence(400) + knock() + _silence(400) + tap(ms=30) + _silence(1500),
    )
