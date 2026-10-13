"""Final cut per docs/DEMO_REQUIREMENTS.md section 3.

Dead air after her reply (before the driver's next line) shrinks to PAUSE. The wait between the
driver's line and her reply is never touched: it is the real latency. Voices are never cut.

    python final_cut.py OUT.mp4
    CUT=commute python final_cut.py OUT.mp4    # the commute demo (docs/DEMO_COMMUTE.md)
"""
import os, subprocess, sys, wave
import numpy as np

PAUSE = 0.8
MIN_SIL = 1.0
READ = 2.5

# (run, start, end, title times, explicit keeps with jump labels or None)
SCENES = [
    ("u_ability", 0.0, 3.6, [0.09], [(0.0, 3.6, None)]),           # intro card (static title)
    ("u_cue", 0.0, None, [0.06], None),
    ("u_think", 0.0, None, [0.06], None),
    ("u_ability", 4.05, None, [4.09], None),
    ("u_scenario", 0.0, None, [0.06], None),
    ("u_style", 0.0, None, [0.08], None),
    ("u_error", 22.4, None, [22.44], [(22.4, 26.0, None), (33.6, 37.0, "⏩ 断网后启动会话（略去 7.6 秒）"),
                                      (45.3, 48.8, "⏩ 12 秒后（中间略去 8.3 秒）")]),
]

# The commute demo: one take per scene, named as in docs/DEMO_COMMUTE.md. The arrival take waits for the
# simulated car; give it explicit keeps with a ⏩ label (as u_error above) once its timeline is known.
COMMUTE = [
    ("c1_board", 0.0, None, [0.06], None),
    ("c2_depart", 0.0, None, [0.06], None),
    ("c3_mosq", 0.0, None, [0.06], None),
    ("c4_music", 0.0, None, [0.06], None),
    ("c5_ask", 0.0, None, [0.06], None),
    ("c6_arrive", 0.0, None, [0.06], None),
]
if os.environ.get("CUT") == "commute":
    SCENES = COMMUTE
HEAD_RUN = "c1_board" if os.environ.get("CUT") == "commute" else "u_error"


def env(path, rate=100):
    w = wave.open(path)
    x = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float32)
    f = w.getframerate() // rate
    n = len(x) // f
    return np.sqrt((x[: n * f].reshape(n, f) ** 2).mean(1)) > 150


def duration(mp4):
    return float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", mp4],
                                capture_output=True, text=True).stdout)


def keeps(run, a, b, titles):
    drv, rep = env(f"{run}/driver.wav"), env(f"{run}/reply.wav")
    n = min(len(drv), len(rep), int(b * 100))
    out, cur, last, i = [], a, None, int(a * 100)
    while i < n:
        if drv[i] or rep[i]:
            last = "driver" if drv[i] else "reply"
            i += 1
            continue
        j = i
        while j < n and not (drv[j] or rep[j]):
            j += 1
        s, e = i / 100, j / 100
        for t in titles:
            if t <= s < t + READ or s < t + READ < e:
                s = max(s, t + READ)
        # dead air at the end of the take: keep a short pause, drop the rest
        if last == "reply" and j >= n and e - s > MIN_SIL:
            out.append((cur, s + PAUSE, None))
            return [k for k in out if k[1] - k[0] > 0.05]
        # only dead air after her reply (or before anyone spoke in the scene); never the reply wait
        if last != "driver" and e - s > MIN_SIL and j < n:
            ca, cb = s + PAUSE / 2, e - PAUSE / 2
            if ca > cur:
                out.append((cur, ca, None))
            cur = cb
        i = j
    out.append((cur, b, None))
    return [k for k in out if k[1] - k[0] > 0.05]


def main(dst):
    pieces, labels, t = [], [], 0.0
    for run, a, b, titles, explicit in SCENES:
        mp4 = run + ".mp4"
        b = b or duration(mp4) - 0.05
        ks = explicit or keeps(run, a, b, titles)
        for m, (x, y, label) in enumerate(ks):
            pieces.append((mp4, x, y, m == 0, m == len(ks) - 1))
            if label:
                labels.append((t, label))
            t += y - x
    files = sorted({p[0] for p in pieces})
    idx = {f: i for i, f in enumerate(files)}
    args = sum((["-i", f] for f in files), [])
    g = ""
    for k, (f, x, y, first, last) in enumerate(pieces):
        i, d = idx[f], y - x
        vf = f"[{i}:v]trim=start={x:.3f}:end={y:.3f},setpts=PTS-STARTPTS"
        if first:
            vf += ",fade=t=in:st=0:d=0.25"
        if last:
            vf += f",fade=t=out:st={d - 0.25:.3f}:d=0.25"
        g += vf + f"[v{k}];"
        g += (f"[{i}:a]atrim=start={x:.3f}:end={y:.3f},asetpts=PTS-STARTPTS,"
              f"afade=t=in:st=0:d=0.02,afade=t=out:st={d - 0.02:.3f}:d=0.02[a{k}];")
    g += "".join(f"[v{k}][a{k}]" for k in range(len(pieces))) + f"concat=n={len(pieces)}:v=1:a=1[vc][ac];"
    ts = lambda s: f"0:{int(s // 60):02d}:{s % 60:05.2f}"
    head = open(f"{HEAD_RUN}/demo.ass", encoding="utf-8-sig").read().split("[Events]")[0]
    ev = "\n".join(f"Dialogue: 5,{ts(s)},{ts(s + 3.0)},Cut,,0,0,0,,{{\\fad(150,300)}}{lab}" for s, lab in labels)
    open("final.ass", "w", encoding="utf-8-sig").write(
        head + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" + ev + "\n")
    g += "[vc]subtitles=final.ass[v];[ac]loudnorm=I=-16:TP=-1.5:LRA=11[a]"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *args, "-filter_complex", g, "-map", "[v]", "-map", "[a]",
                    "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", "30", "-c:a", "aac",
                    "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", dst], check=True)
    print(f"pieces={len(pieces)} total={t:.1f}s labels={[(round(s, 1), l) for s, l in labels]}")


if __name__ == "__main__":
    main(sys.argv[1])
