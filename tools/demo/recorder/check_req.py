"""Grade a record_demo.py take against docs/DEMO_REQUIREMENTS.md sections 1 and 2. Prints numbers only.

    python check_req.py RUN [RUN ...]
"""
import json, os, re, statistics, sys, wave
import numpy as np

CLIPS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "clips")
ACTION_KEYS = {"weather", "nav", "music", "stuffy", "hot", "mosq", "mosq_out", "seat_high"}


def audible_end(key):
    """Seconds from the clip start to the last audible 10 ms frame (edge-tts pads ~1 s of silence)."""
    x = np.fromfile(os.path.join(CLIPS, key + ".pcm"), "<i2").astype(float)
    f = 160
    e = np.sqrt((x[: len(x) // f * f].reshape(-1, f) ** 2).mean(1))
    return (np.nonzero(e > 300)[0][-1] + 1) * 0.01


def grade(run):
    tl = json.load(open(os.path.join(run, "timeline.json"), encoding="utf-8"))
    lines = open(os.path.join(run, "logcat.txt"), encoding="utf-8", errors="replace").read().splitlines()
    stamp = lambda l: float(l.split()[0])
    dev_wakes = [stamp(l) for l in lines if "debug_tool tool=voice arg=wake" in l]
    pairs = list(zip(dev_wakes, tl["wakes"]))
    offset = statistics.median(d - p for d, p in pairs) if pairs else 0.0
    vt = lambda l: stamp(l) - offset - tl["t0"]
    firsts = [vt(l) for l in lines if "assistant_voice_first_audio" in l]
    cues = [(vt(l), re.search(r"code=(\w+)", l).group(1)) for l in lines if "wait_cue code=" in l]
    w0 = wave.open(os.path.join(run, "reply.wav"))
    xr = np.frombuffer(w0.readframes(w0.getnframes()), "<i2").astype(float)
    fr = w0.getframerate() // 100
    heard_on = np.sqrt((xr[: len(xr) // fr * fr].reshape(-1, fr) ** 2).mean(1)) > 200
    fails = []
    print(f"== {run}")
    clips = tl["clips"]
    for i, (t, key) in enumerate(clips):
        end = t + audible_end(key)
        nxt_clip = clips[i + 1][0] if i + 1 < len(clips) else 1e9
        nxt = [f for f in firsts if end < f < nxt_clip]
        limit = 3.5 if key in ACTION_KEYS else 3.0
        on = np.nonzero(heard_on[int(end * 100):int(min(nxt_clip, end + 40) * 100)])[0]
        h = on[0] / 100 if len(on) else None
        # Qwen speaks itself, so there is no assistant_voice_first_audio line. The captured reply is the measurement.
        if not nxt and h is None:
            print(f"  {key:10s} NO REPLY")
            fails.append(key)
            continue
        d = (nxt[0] - end) if nxt else h
        cue = [f"{c}@{ct - end:.1f}" for ct, c in cues if end - 1.5 < ct < nxt_clip]
        ok = (h if h is not None else d) <= limit
        hs = f"{h:4.1f}" if h is not None else " -- "
        print(f"  {key:10s} hears her {hs} s, real reply {d:4.1f} s  cues {cue or '-'}  {'PASS' if ok else 'FAIL'} (max {limit})")
        if not ok:
            fails.append(key)
    gaps = [int(m.group(1)) for l in lines for m in [re.search(r"assistant_voice_gap_ms ms=(\d+)", l)] if m]
    if gaps:
        g = sorted(gaps)
        p95 = g[min(len(g) - 1, int(0.95 * len(g)))]
        ok = p95 <= 150 and g[-1] <= 300
        print(f"  clause gaps (app log) n={len(g)} p95={p95} max={g[-1]} ms  {'PASS' if ok else 'FAIL'}")
        if not ok:
            fails.append("gaps")
    # the same from the captured audio: silences of 60-500 ms inside a reply
    w = wave.open(os.path.join(run, "reply.wav"))
    x = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(float)
    f = w.getframerate() // 100
    n = len(x) // f
    on = np.sqrt((x[: n * f].reshape(n, f) ** 2).mean(1)) > 200
    # A provider that speaks itself (Qwen) sends a reply's audio faster than real time. A silence
    # played after that reply's audio had all arrived is a pause in the voice itself (between
    # sentences), not starvation; only a silence while the audio was still arriving is choppy.
    created = [vt(l) for l in lines if "type=response.created" in l]
    done = [vt(l) for l in lines if "type=response.audio.done" in l]

    def starved(t):
        start = max((c for c in created if c < t), default=None)
        return start is not None and not any(start < d < t for d in done)

    holes, starved_holes, i = [], [], 0
    while i < n:
        if not on[i]:
            j = i
            while j < n and not on[j]:
                j += 1
            d = (j - i) / 100
            if 0.15 < d <= 0.6 and i > 30 and on[i - 30:i].mean() > 0.6 and j + 30 < n and on[j:j + 30].mean() > 0.6:
                holes.append(round(d, 2))
                if starved(i / 100):
                    starved_holes.append(round(d, 2))
            i = j
        else:
            i += 1
    print(f"  audio holes 150-600 ms inside replies: {holes}; inside a response window: {starved_holes} (info)")
    # The provider-voiced path: the app logs when its player would have run dry (reply_underrun).
    # A pause inside the voice itself is not one; a response window can hold natural pauses because
    # Qwen streams about 3x faster than real time.
    underruns = [int(m.group(1)) for l in lines for m in [re.search(r"reply_underrun ms=(\d+)", l)] if m]
    provider_voiced = any("reason=provider_speaks" in l for l in lines)
    if provider_voiced:
        bad = [u for u in underruns if u > 300]
        print(f"  reply underruns (app log) ms={underruns}  {'FAIL' if bad else 'PASS'} (none > 300 ms)")
        if bad:
            fails.append("gaps")
    elif not gaps and any(h > 0.30 for h in starved_holes):
        print("  clause gaps (captured audio) FAIL a hole > 300 ms while the reply was still arriving")
        fails.append("gaps")
    azure = [int(m.group(1)) for l in lines for m in [re.search(r"azure_tts_first_audio ms=(\d+)", l)] if m]
    warm = [l.split("azure_warm", 1)[1].strip() for l in lines if "azure_warm" in l]
    print(f"  azure first audio ms: first={azure[:1]} all-max={max(azure) if azure else None}  warm-ups={warm[:4]}")
    fails += ["voice_failed"] * sum("assistant_voice_failed" in l for l in lines)
    print(f"  RESULT {'PASS' if not fails else 'FAIL ' + ','.join(fails)}")
    return not fails


if __name__ == "__main__":
    ok = all([grade(r) for r in sys.argv[1:]])
    sys.exit(0 if ok else 1)
