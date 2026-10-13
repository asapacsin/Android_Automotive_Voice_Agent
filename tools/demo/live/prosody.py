"""Per-turn prosody of her replies in a live-demo run: how she spoke, not what she said.

    python3 tools/demo/live/prosody.py RUN [RUN ...]     # prints a table and writes RUN/prosody.json

Per driver line (timeline.json clips), the slice of reply.wav until the next line is measured:
  f0_hz      median F0 of voiced 10 ms frames (autocorrelation, 40 ms frame, 120-500 Hz)
  spread_st  F0 spread, p90 - p10, in semitones
  pace_cps   CJK characters of her reply text / voiced seconds
prosody.json holds only these numbers per key (and the voiced seconds); never the reply text.
numpy only. Used by style_check.py and render.py.
"""
import json, os, sys, wave

import numpy as np

FRAME_S, HOP_S, F0_LO, F0_HI = 0.04, 0.01, 120, 500
MIN_VOICED = 20  # frames; fewer and the turn is not measured


def read_reply(run):
    w = wave.open(os.path.join(run, "reply.wav"))
    sr = w.getframerate()
    a = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
    if w.getnchannels() > 1:
        a = a.reshape(-1, w.getnchannels()).mean(1)
    return a, sr


def f0_track(x, sr):
    """F0 per hop (Hz), 0.0 for unvoiced or quiet frames."""
    n, h = int(FRAME_S * sr), int(HOP_S * sr)
    lo, hi = int(sr / F0_HI), int(sr / F0_LO)
    rms_all = np.sqrt(np.mean(x ** 2)) + 1e-9 if len(x) else 1.0
    out = []
    for i in range(0, len(x) - n, h):
        f = x[i:i + n] - x[i:i + n].mean()
        r = np.sqrt(np.mean(f ** 2))
        if r < 0.02 and r < 0.5 * rms_all:
            out.append(0.0)
            continue
        c = np.correlate(f, f, "full")[n - 1:]
        k = lo + int(np.argmax(c[lo:hi]))
        out.append(sr / k if c[0] > 0 and c[k] > 0.45 * c[0] else 0.0)
    return np.array(out)


def cjk_chars(text):
    return sum(1 for ch in text if "一" <= ch <= "鿿")


def measure(seg, sr, text):
    """{f0_hz, spread_st, pace_cps, voiced_s} for one reply slice, or None when too little is voiced."""
    f0 = f0_track(seg, sr)
    v = f0[f0 > 0]
    if len(v) < MIN_VOICED:
        return None
    st = 12 * np.log2(v / np.median(v))
    voiced = len(v) * HOP_S
    return {"f0_hz": round(float(np.median(v)), 1),
            "spread_st": round(float(np.percentile(st, 90) - np.percentile(st, 10)), 2),
            "pace_cps": round(cjk_chars(text) / voiced, 2),
            "voiced_s": round(voiced, 2)}


def turn_stats(run):
    """{key: stats or None} in clip order (the first turn per key)."""
    a, sr = read_reply(run)
    tl = json.load(open(os.path.join(run, "timeline.json"), encoding="utf-8"))
    st = json.load(open(os.path.join(run, "state.json"), encoding="utf-8"))
    turns = {}
    for t in (st if isinstance(st, list) else st.get("turns", [])):
        turns.setdefault(t["key"], t)
    clips, out = tl["clips"], {}
    for i, (t, key) in enumerate(clips):
        if key in out:
            continue
        nxt = clips[i + 1][0] if i + 1 < len(clips) else tl["end"]
        text = "".join(turns.get(key, {}).get("reply", []))
        out[key] = measure(a[int(t * sr):int(nxt * sr)], sr, text)
    return out


def write(run):
    stats = turn_stats(run)
    with open(os.path.join(run, "prosody.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=1)
    return stats


def fmt(s):
    return "-" if s is None else f"F0 {s['f0_hz']:5.0f} Hz  spread {s['spread_st']:4.1f} st  pace {s['pace_cps']:4.1f} ch/s"


if __name__ == "__main__":
    if not sys.argv[1:]:
        sys.exit(__doc__)
    for run in sys.argv[1:]:
        print("==", run)
        for key, s in write(run).items():
            print(f"  {key:10s} {fmt(s)}")
