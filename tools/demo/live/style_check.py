"""Grade a style take (NOVA_LIVE_SCENES=style, docs/DEMO_STYLE.md): 「嗲一点」 switches her to sweet, it
holds through a car action and two turns, 「正常一点」 switches her back, and her delivery follows.

    python3 tools/demo/live/style_check.py RUN        # RUN = NOVA_LIVE_OUT of the take; writes RUN/prosody.json
    python3 tools/demo/live/style_check.py --selftest

Prints `key verdict time tools style F0 spread pace markers note`, then `RESULT PASS|FAIL <keys>`.
Exit 0 only on PASS. Never prints reply text: it is only counted (CJK characters for the pace, sweet
wording markers for information).

A turn "sounds sweet" (GRADER v3) against s_mood1 when the median F0 rose >= +15 % (F0_ALONE), or when it
rose >= +8 % and also the F0 spread rose >= +2.0 st or the pace fell >= 10 %. Pitch is necessary; spread
and pace only confirm it (decider 2026-10-20: between two default turns, s_mood4 vs s_mood1, spread swings
-1.7..+2.4 st and pace 0..-13 % while F0 stays within +4 %). s_mood2 and s_mood3 must sound sweet,
s_mood4 must not; s_seat is reported only (other text).

F0_ALONE (2026-10-13): the 2-of-3 rule was set before any real run of the fix. On gemini-3.8-live the
sweet delivery was carried by pitch alone: s_mood2 +35 %, s_mood3 +33 % (about 5 semitones) with pace
unchanged, while default turns of the same voice stay within about +-12 % of each other (tryout runs,
182-235 Hz around 205). +15 % is above that spread; the before-fix run (s_mood2 +6 %, s_mood3 +1 %) still fails.
"""
import json, os, re, sys, tempfile, wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "recorder"))
import check_req  # noqa: E402  (audible_end and the clips directory)
import prosody  # noqa: E402
import tryout_check  # noqa: E402  (load: timeline, logcat aligned to it, state, reply frames; first_on)

ORDER = ["s_mood1", "s_sweet", "s_mood2", "s_seat", "s_mood3", "s_normal", "s_mood4"]
WANT_STYLE = {"s_mood1": "default", "s_sweet": "sweet", "s_mood2": "sweet", "s_seat": "sweet", "s_mood3": "sweet",
              "s_normal": "default", "s_mood4": "default"}
SWITCH = {"s_sweet": "sweet", "s_normal": "default"}
MUST_SWEET, MUST_NOT_SWEET = ("s_mood2", "s_mood3"), ("s_mood4",)
REPLY_S = 5.0
F0_UP, SPREAD_UP, PACE_DOWN = 0.08, 2.0, 0.10
F0_ALONE = 0.15
GRADER = "v3"
MARKERS = ["嘛", "呀", "啦", "哟", "呢", "人家", "哦"]
ERROR_RE = tryout_check.ERROR_RE


def sounds_sweet(s, base):
    """(sweet?, F0 change as a fraction) against the baseline; None when either is unmeasured."""
    if s is None or base is None:
        return None, None
    df = s["f0_hz"] / base["f0_hz"] - 1
    spread_hit = s["spread_st"] - base["spread_st"] >= SPREAD_UP
    pace_hit = base["pace_cps"] > 0 and s["pace_cps"] <= base["pace_cps"] * (1 - PACE_DOWN)
    return df >= F0_ALONE or (df >= F0_UP and (spread_hit or pace_hit)), df


def markers(replies):
    text = "".join(replies)
    return sum(text.count(m) for m in MARKERS)


def grade(run):
    tl, events, state, heard = tryout_check.load(run)
    pros = prosody.write(run)
    clips = [(t, k) for t, k in tl["clips"] if k in ORDER]
    keys_seen = [k for _, k in clips]
    turns = {}
    for turn in state.get("turns", []):
        turns.setdefault(turn["key"], turn)
    base = pros.get("s_mood1")
    fails, rows = [], []
    for key in ORDER:
        if key not in keys_seen:
            rows.append((key, "FAIL", None, "-", "-", None, "", "not spoken"))
            fails.append(key)
            continue
        i = keys_seen.index(key)
        t = clips[i][0]
        nxt = clips[i + 1][0] if i + 1 < len(clips) else tl.get("end", t + 60)
        end = t + check_req.audible_end(key)
        turn = turns.get(key, {})
        tools = turn.get("tools", [])
        style = (turn.get("car") or {}).get("style")
        notes, bad = [], False
        first = tryout_check.first_on(heard, end, nxt)
        d = None if first is None else first - end
        if d is None:
            bad = True; notes.append("no reply")
        elif d > REPLY_S:
            bad = True; notes.append(f"slow (max {REPLY_S})")
        if any(ERROR_RE.search(l) for vt, l in events if t - 0.5 <= vt < nxt):
            bad = True; notes.append("session error")
        if key in SWITCH:
            want = SWITCH[key]
            if not any(x["name"] == "set_speaking_style" and x.get("ok")
                       and str((x.get("args") or {}).get("style")) == want for x in tools):
                bad = True; notes.append(f"no set_speaking_style ✓ style={want}")
        if key == "s_seat" and not any(x["name"] == "control_seat" and x.get("ok") for x in tools):
            bad = True; notes.append("missing control_seat")
        if style != WANT_STYLE[key]:
            bad = True; notes.append(f"panel style {style}, want {WANT_STYLE[key]}")
        s = pros.get(key)
        sweet, df = sounds_sweet(s, base)
        if key in MUST_SWEET and not sweet:
            bad = True; notes.append("does not sound sweet")
        if key in MUST_NOT_SWEET and sweet:
            bad = True; notes.append("still sounds sweet")
        if key == "s_seat" and sweet is not None:
            notes.append(f"sounds {'sweet' if sweet else 'default'} (info)")
        tool_txt = ",".join(f"{x['name']}{'✓' if x.get('ok') else '✗'}" for x in tools) or "-"
        pr = (s, df)
        rows.append((key, "FAIL" if bad else "PASS", d, tool_txt, style or "-", pr, markers(turn.get("reply", [])), "; ".join(notes)))
        if bad:
            fails.append(key)
    outside = [l for _, l in events if ERROR_RE.search(l)]
    if outside and not any("session error" in r[7] for r in rows):
        fails.append("session_error")
    print(f"== {run}")
    print(f"GRADER {GRADER}")
    print(f"  {'key':9s} {'verd':4s} {'time':5s}  {'tools':28s} {'style':8s} {'F0':>12s} {'spread':>7s} {'pace':>6s} mk  note")
    for key, verdict, d, tool_txt, style, pr, mk, note in rows:
        ts = f"{d:4.1f}s" if d is not None else "  -- "
        s, df = pr if pr else (None, None)
        if s is None:
            f0 = sp = pc = "-"
        else:
            f0 = f"{s['f0_hz']:.0f}Hz" + (f"{df:+.0%}" if df is not None and key != "s_mood1" else "")
            sp, pc = f"{s['spread_st']:.1f}st", f"{s['pace_cps']:.1f}"
        print(f"  {key:9s} {verdict:4s} {ts}  {tool_txt:28s} {style:8s} {f0:>12s} {sp:>7s} {pc:>6s} {mk!s:>2s}  {note}")
    print(f"RESULT {'PASS' if not fails else 'FAIL ' + ','.join(fails)}")
    return not fails, fails


# ---- selftest: synthetic tones as her replies, no model, no network ----

T0, RATE, STEP = 1000.0, 16000, 12.0
DEFAULT_V = (205.0, 7.0, 7.5)   # F0 Hz, spread st, pace ch/s
SWEET_V = (245.0, 12.0, 5.9)
CHARS = 15


def _sweep(f0, spread, seconds):
    """A tone whose F0 sweeps linearly in semitones so that p90 - p10 is about [spread]."""
    n = int(seconds * RATE)
    span = spread / 0.8
    semis = np.linspace(-span / 2, span / 2, n)
    freq = f0 * 2 ** (semis / 12)
    return (np.sin(2 * np.pi * np.cumsum(freq) / RATE) * 8000).astype("<i2")


def _fixture(root, mutate=None):
    clips_dir = os.path.join(root, "clips")
    os.makedirs(clips_dir, exist_ok=True)
    pcm = np.zeros(RATE, "<i2")
    pcm[int(0.2 * RATE):int(0.8 * RATE)] = (np.sin(np.arange(int(0.6 * RATE)) * 2 * np.pi * 220 / RATE) * 8000).astype("<i2")
    for k in ORDER:
        pcm.tofile(os.path.join(clips_dir, k + ".pcm"))
    p = {"voice": {k: (SWEET_V if WANT_STYLE[k] == "sweet" else DEFAULT_V) for k in ORDER},
         "style": dict(WANT_STYLE),
         "tools": {"s_sweet": [{"name": "set_speaking_style", "ok": True, "args": {"style": "sweet"}}],
                   "s_seat": [{"name": "control_seat", "ok": True}],
                   "s_normal": [{"name": "set_speaking_style", "ok": True, "args": {"style": "default"}}]}}
    if mutate:
        mutate(p)
    total = 5.0 + len(ORDER) * STEP + 5
    audio = np.zeros(int(total * RATE), "<i2")
    clips, wakes, log, turns = [], [], [], []
    for i, k in enumerate(ORDER):
        t = 5.0 + i * STEP
        clips.append([t, k]); wakes.append(T0 + t - 0.8)
        log.append((t - 0.8, "debug_tool tool=voice arg=wake"))
        f0, spread, pace = p["voice"][k]
        tone = _sweep(f0, spread, CHARS / pace)
        a = int((t + 0.8 + 1.5) * RATE)
        audio[a:a + len(tone)] = tone
        turns.append({"key": k, "t": t + 10, "tools": p["tools"].get(k, []), "reply": ["好" * CHARS],
                      "car": {"style": p["style"][k]}})
    run = os.path.join(root, "run")
    os.makedirs(run, exist_ok=True)
    with wave.open(os.path.join(run, "reply.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(audio.tobytes())
    json.dump({"clips": clips, "t0": T0, "wakes": wakes, "end": total}, open(os.path.join(run, "timeline.json"), "w"))
    json.dump({"turns": turns}, open(os.path.join(run, "state.json"), "w", encoding="utf-8"), ensure_ascii=False)
    with open(os.path.join(run, "logcat.txt"), "w", encoding="utf-8") as f:
        for vt, msg in sorted(log):
            f.write(f"{T0 + vt:.3f} D NovaVoice: {msg}\n")
    return run, clips_dir


def selftest():
    def no_tool(p): p["tools"].pop("s_sweet")

    def panel_default(p):
        for k in ("s_sweet", "s_mood2", "s_seat", "s_mood3"):
            p["style"][k] = "default"

    def fade(p): p["voice"]["s_mood3"] = DEFAULT_V

    def no_back(p):
        p["tools"].pop("s_normal")
        p["style"]["s_normal"] = p["style"]["s_mood4"] = "sweet"
        p["voice"]["s_normal"] = p["voice"]["s_mood4"] = SWEET_V

    def still_sweet(p): p["voice"]["s_mood4"] = SWEET_V

    def default_swing(p): p["voice"]["s_mood4"] = (DEFAULT_V[0], DEFAULT_V[1] + 2.4, DEFAULT_V[2] * 0.87)

    def no_pitch(p): p["voice"]["s_mood2"] = (DEFAULT_V[0] * 1.02, DEFAULT_V[1] + 2.8, DEFAULT_V[2] * 0.79)

    cases = [
        ("pass", None, []),
        ("no tool at s_sweet", no_tool, ["s_sweet"]),
        ("panel stays default", panel_default, ["s_sweet", "s_mood2", "s_seat", "s_mood3"]),
        ("fade at s_mood3", fade, ["s_mood3"]),
        ("no switch back", no_back, ["s_normal", "s_mood4"]),
        ("s_mood4 still sweet", still_sweet, ["s_mood4"]),
        ("s_mood4 default swing at base pitch", default_swing, []),
        ("s_mood2 spread and pace without pitch", no_pitch, ["s_mood2"]),
    ]
    saved, bad = check_req.CLIPS, []
    try:
        for name, mutate, want in cases:
            with tempfile.TemporaryDirectory() as root:
                run, clips_dir = _fixture(root, mutate)
                check_req.CLIPS = clips_dir
                ok, fails = grade(run)
                good = ok == (not want) and fails == want
                print(f"selftest {name}: {'ok' if good else 'WRONG'} (fails={fails})")
                if not good:
                    bad.append(name)
    finally:
        check_req.CLIPS = saved
    print(f"SELFTEST {'PASS' if not bad else 'FAIL ' + ', '.join(bad)}")
    return not bad


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        sys.exit(0 if selftest() else 1)
    if not sys.argv[1:]:
        sys.exit(__doc__)
    sys.exit(0 if all([grade(r)[0] for r in sys.argv[1:]]) else 1)
