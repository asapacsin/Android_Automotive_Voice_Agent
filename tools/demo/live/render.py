"""Render a JVM live-demo run (LiveDemoRun) to an mp4: a drawn car screen, captions, real audio.

    python3 tools/demo/live/render.py OUT OUT.mp4

OUT holds timeline.json, state.json, driver.wav, reply.wav (see README.md). 1920x1080, 30 fps.
Left: a drawn car screen (route bar, car state, last tool call). Right: the scene title, the driver's
line, what the model heard, her reply, and the measured latency. The wait for arrival
(cut_start..cut_end) is cut and marked. Never commit the output.
"""
import ast, json, os, re, subprocess, sys, tempfile, wave

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import imageio_ffmpeg

HERE = os.path.dirname(os.path.abspath(__file__))
RECORDER = os.path.join(HERE, "..", "recorder")
CLIPS = os.path.join(RECORDER, "clips")
W, H, FPS, SR = 1920, 1080, 30, 24000
HEADER = "JVM live run — real model, simulated car (not the app screen)"
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
]


def font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    sys.exit("no CJK font found: install fonts-wqy-zenhei or Noto Sans CJK")


def lines_text():
    src = open(os.path.join(RECORDER, "make_clips.py"), encoding="utf-8").read()
    return ast.literal_eval(re.search(r"LINES = (\{.*?\n\})", src, re.S).group(1))


def read_wav(path):
    w = wave.open(path)
    return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float32)


def audible_end(key):
    x = np.fromfile(os.path.join(CLIPS, key + ".pcm"), "<i2").astype(float)
    e = np.sqrt((x[: len(x) // 160 * 160].reshape(-1, 160) ** 2).mean(1))
    return (np.nonzero(e > 300)[0][-1] + 1) * 0.01


def kept_spans(events, end):
    """Source intervals that stay in the video: everything but the arrival wait (1 s of it kept)."""
    cuts, start = [], None
    for e in sorted(events, key=lambda e: e["t"]):
        if e.get("cut_start"):
            start = e["t"]
        elif e.get("cut_end") and start is not None:
            if e["t"] - start > 2:
                cuts.append((start + 1.0, e["t"]))
            start = None
    spans, t = [], 0.0
    for a, b in cuts:
        spans.append((t, a))
        t = b
    spans.append((t, end))
    return spans, cuts


def at(items, t, key="t"):
    best = None
    for it in items:
        if it[key] <= t:
            best = it
        else:
            break
    return best


def wrap(draw, text, fnt, width):
    out, cur = [], ""
    for ch in text:
        if draw.textlength(cur + ch, font=fnt) > width:
            out.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        out.append(cur)
    return out


def main(run, mp4):
    tl = json.load(open(os.path.join(run, "timeline.json"), encoding="utf-8"))
    st = json.load(open(os.path.join(run, "state.json"), encoding="utf-8"))
    texts = lines_text()
    end = tl["end"]
    reply = read_wav(os.path.join(run, "reply.wav"))
    driver = read_wav(os.path.join(run, "driver.wav"))
    n = max(len(reply), len(driver))
    mix = np.zeros(n, np.float32)
    mix[: len(reply)] += reply
    mix[: len(driver)] += driver
    fr = SR // 100
    heard = np.sqrt((reply[: len(reply) // fr * fr].reshape(-1, fr) ** 2).mean(1)) > 200

    clips = tl["clips"]
    turns = [t for t in st["turns"] if t["key"] != "arrive"]
    info = []
    for i, (t, key) in enumerate(clips):
        e = t + audible_end(key)
        nxt = clips[i + 1][0] if i + 1 < len(clips) else end
        on = np.nonzero(heard[int(e * 100): int(min(nxt, e + 40) * 100)])[0]
        first = e + on[0] / 100 if len(on) else None
        turn = turns[i] if i < len(turns) and turns[i]["key"] == key else {}
        info.append({"t": t, "key": key, "end": e, "first": first, "next": nxt, "turn": turn})
    titles = [e for e in tl["events"] if "title" in e]
    track = st["track"]
    tools = sorted([dict(x, key=t["key"]) for t in st["turns"] for x in t["tools"]], key=lambda x: x["t"])

    spans, cuts = kept_spans(tl["events"], end)
    total = sum(b - a for a, b in spans)
    f_head, f_big, f_mid, f_small = font(30), font(46), font(34), font(26)

    tmp = tempfile.mkdtemp()
    audio = os.path.join(tmp, "mix.wav")
    parts = [mix[int(a * SR): int(b * SR)] for a, b in spans]
    with wave.open(audio, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(np.clip(np.concatenate(parts) * 0.9, -32768, 32767).astype("<i2").tobytes())

    ff = imageio_ffmpeg.get_ffmpeg_exe()
    proc = subprocess.Popen([ff, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                             "-r", str(FPS), "-i", "-", "-i", audio, "-c:v", "libx264", "-pix_fmt", "yuv420p",
                             "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "160k", "-shortest",
                             "-movflags", "+faststart", os.path.abspath(mp4)], stdin=subprocess.PIPE)

    def source_time(v):
        acc = 0.0
        for a, b in spans:
            if v < acc + (b - a):
                return a + (v - acc)
            acc += b - a
        return spans[-1][1]

    for k in range(int(total * FPS)):
        t = source_time(k / FPS)
        img = Image.new("RGB", (W, H), (16, 20, 28))
        d = ImageDraw.Draw(img)
        d.rectangle([0, 0, W, 56], fill=(120, 30, 30))
        d.text((24, 10), HEADER, font=f_head, fill=(255, 255, 255))
        d.text((W - 360, 10), f"provider: {st.get('provider', '?')}   t={t:6.1f}s", font=f_small, fill=(255, 220, 220))
        if any(b <= t < b + 2.5 for _, b in cuts):
            d.text((W - 520, 70), ">> 等待到达已剪掉 (cut)", font=f_mid, fill=(255, 210, 80))

        # ---- left: the car screen ----
        d.rounded_rectangle([30, 80, 930, 1050], radius=24, fill=(28, 34, 46), outline=(70, 80, 100), width=2)
        sample = at(track, t)
        car = (sample or {"car": {}})["car"]
        d.text((60, 100), "模拟车机 · simulated car", font=f_mid, fill=(150, 170, 200))
        prog = float(car.get("progress", 0) or 0)
        d.text((60, 170), "创业谷", font=f_mid, fill=(230, 230, 230))
        d.text((760, 170), car.get("destination") or "横琴镇", font=f_mid, fill=(230, 230, 230))
        d.rounded_rectangle([60, 230, 900, 262], radius=16, fill=(55, 62, 78))
        d.rounded_rectangle([60, 230, 60 + int(840 * prog), 262], radius=16, fill=(60, 170, 110))
        d.ellipse([60 + int(840 * prog) - 20, 226, 60 + int(840 * prog) + 20, 266], fill=(240, 240, 240))
        phase = car.get("nav_phase", "IDLE")
        d.text((60, 285), f"导航 {phase}   剩余 {car.get('remaining_m', 0)} m   {car.get('speed_kmh', 0):.0f} km/h",
               font=f_small, fill=(190, 200, 215))
        win = car.get("windows", {})
        rows = [
            ("座椅高度 (主驾)", f"{car.get('seat_height', '-')} 档"),
            ("车窗 %", f"左前 {win.get('FRONT_LEFT', 0)}  右前 {win.get('FRONT_RIGHT', 0)}  左后 {win.get('REAR_LEFT', 0)}  右后 {win.get('REAR_RIGHT', 0)}"),
            ("风量", f"{car.get('fan', '-')} 档"),
            ("空调", ("开" if car.get("ac_on") else "关") + f"  {car.get('temp_c', '-')}°C"),
            ("音乐", (car.get("music") or "—") + ("  (已交给音乐 app，未确认播放)" if car.get("music") else "")),
            ("说话风格", car.get("style", "default")),
        ]
        y = 360
        for label, value in rows:
            d.text((60, y), label, font=f_small, fill=(140, 150, 170))
            for j, line in enumerate(wrap(d, value, f_mid, 560)[:2]):
                d.text((330, y - 4 + j * 42), line, font=f_mid, fill=(240, 240, 240))
            y += 92
        last = at(tools, t)
        if last:
            ok = last.get("ok")
            colour = (230, 200, 90) if last.get("pending") else (110, 220, 140) if ok else (240, 110, 110)
            d.text((60, 930), "上一个工具调用 (last tool call)", font=f_small, fill=(140, 150, 170))
            # The CJK font has no check-mark glyph: the mark is drawn.
            if last.get("pending"):
                d.text((60, 970), "…", font=f_big, fill=colour)
            elif ok:
                d.line([(62, 1000), (78, 1018), (106, 980)], fill=colour, width=7)
            else:
                d.line([(64, 982), (100, 1018)], fill=colour, width=7)
                d.line([(100, 982), (64, 1018)], fill=colour, width=7)
            d.text((124, 970), last["name"] + ("" if last.get("pending") or ok else "  失败"), font=f_big, fill=colour)

        # ---- right: scene and captions ----
        title = at(titles, t)
        if title:
            d.text((980, 90), title["title"], font=f_big, fill=(255, 255, 255))
            d.text((980, 150), title.get("sub", ""), font=f_small, fill=(170, 180, 200))
        cur = None
        for it in info:
            if it["t"] <= t:
                cur = it
        if cur and t < cur["next"] + 0.1:
            y = 240
            d.text((980, y), "司机 (driver line)", font=f_small, fill=(140, 150, 170)); y += 40
            for line in wrap(d, texts.get(cur["key"], cur["key"]), f_mid, 880)[:3]:
                d.text((980, y), line, font=f_mid, fill=(255, 230, 150)); y += 46
            heard_txt = "".join(cur["turn"].get("driver_heard", []))
            if heard_txt and t >= cur["end"]:
                y += 10
                d.text((980, y), "模型听到 (model heard)", font=f_small, fill=(140, 150, 170)); y += 40
                for line in wrap(d, heard_txt, f_small, 880)[:2]:
                    d.text((980, y), line, font=f_small, fill=(200, 200, 200)); y += 36
            if cur["first"] is not None and t >= cur["first"]:
                y += 16
                d.text((980, y), "小诺", font=f_small, fill=(140, 150, 170)); y += 40
                for line in wrap(d, "".join(cur["turn"].get("reply", [])) or "（说话中）", f_mid, 880)[:6]:
                    d.text((980, y), line, font=f_mid, fill=(150, 220, 255)); y += 46
            if t >= cur["end"]:
                lat = cur["first"] - cur["end"] if cur["first"] is not None else None
                txt = f"首音延迟 {lat:.1f} s" if lat is not None else "等待回复…"
                d.text((980, 980), txt, font=f_mid, fill=(255, 255, 255) if lat is None or lat <= 3.5 else (255, 120, 120))
        proc.stdin.write(img.tobytes())
    proc.stdin.close()
    proc.wait()
    print(f"wrote {mp4} {total:.1f} s")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
