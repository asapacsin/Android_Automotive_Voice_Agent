"""Grade a tryout take (NOVA_LIVE_SCENES=tryout, docs/DEMO_TRYOUT.md): per driver line, did the
real-user expectation hold. Prints one row per line, `key  verdict  time  tools  note`, then
`RESULT PASS|FAIL <keys>` and the WARN list. Never prints transcript text: the reply text in
state.json is only matched against claim patterns, never echoed.

    python3 tools/demo/live/tryout_check.py RUN       # RUN = NOVA_LIVE_OUT of the take
    python3 tools/demo/live/tryout_check.py --selftest

Exit code 0 only on PASS. Reply time is measured as in check_req.py: from the driver's last audible
word (check_req.audible_end) to her first audible reply in reply.wav.
"""
import json, os, re, statistics, sys, tempfile, wave

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "recorder"))
import check_req  # noqa: E402  (audible_end and the clips directory are reused, not copied)

ORDER = ["u_hello", "u_can", "u_hot", "u_temp", "u_window", "u_sunroof", "u_gas", "u_cancel", "u_dest",
         "u_pick2", "u_go", "u_eta", "u_music", "u_barge", "u_mstop", "u_mumble", "u_call", "u_navoff", "u_bye"]
LIMIT = {"u_hello": 3.0, "u_can": 3.0, "u_bye": 3.0, "u_hot": 3.5, "u_gas": 3.5}
ANY_REPLY_S = 5.0
BARGE_STOP_S = 1.0
SILENCE_S = 20.0
DRIVER_WINDOW = "FRONT_LEFT"
ERROR_RE = re.compile(r"state=ERROR|_PROTOCOL_ERROR|\berror=\w")
DROP_RE = re.compile(r"TURN_DROP .*reason=unproven_action_claim")
NEGATION = re.compile(r"不|没|无法|不能|暂不|抱歉|对不起")
CLAIMS = {
    "u_sunroof": re.compile(r"天窗.*(打开了|已经?打开|开好了|为你打开|帮你打开)|(打开了|已打开).*天窗"),
    "u_call": re.compile(r"(正在|已经?|这就|马上)(帮你)?(拨打|呼叫|打给|拨通|给.{0,6}打)|电话.*(打过去了|接通了)"),
    "u_mumble": re.compile(r"已经?(为你|帮你)?(打开|关闭|调|开始|播放|导航)"),
    "u_eta": re.compile(r"已经?(为你|帮你)(打开|关闭|调|开始|播放)"),
}


def audible_start(key):
    """Seconds from the clip start to its first audible 10 ms frame (the same threshold as check_req)."""
    x = np.fromfile(os.path.join(check_req.CLIPS, key + ".pcm"), "<i2").astype(float)
    f = 160
    e = np.sqrt((x[: len(x) // f * f].reshape(-1, f) ** 2).mean(1))
    on = np.nonzero(e > 300)[0]
    return on[0] * 0.01 if len(on) else 0.0


def claimed(key, replies):
    """A done-claim in her reply text without a negation. Internal only: the text is never printed."""
    pat = CLAIMS.get(key)
    return bool(pat) and any(pat.search(r) and not NEGATION.search(r) for r in replies)


def load(run):
    tl = json.load(open(os.path.join(run, "timeline.json"), encoding="utf-8"))
    lines = open(os.path.join(run, "logcat.txt"), encoding="utf-8", errors="replace").read().splitlines()
    state = json.load(open(os.path.join(run, "state.json"), encoding="utf-8"))
    w = wave.open(os.path.join(run, "reply.wav"))
    x = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(float)
    fr = w.getframerate() // 100
    heard = np.sqrt((x[: len(x) // fr * fr].reshape(-1, fr) ** 2).mean(1)) > 200
    stamp = lambda l: float(l.split()[0])
    dev_wakes = [stamp(l) for l in lines if "debug_tool tool=voice arg=wake" in l]
    pairs = list(zip(dev_wakes, tl["wakes"]))
    offset = statistics.median(d - p for d, p in pairs) if pairs else 0.0
    events = []
    for l in lines:
        try:
            events.append((stamp(l) - offset - tl["t0"], l))
        except (ValueError, IndexError):
            pass
    return tl, events, state, heard


def first_on(heard, start, stop):
    a, b = int(max(0, start) * 100), int(max(0, stop) * 100)
    on = np.nonzero(heard[a:b])[0]
    return (a + on[0]) / 100 if len(on) else None


def longest_gap(heard, start, stop):
    """Longest silence between two reply frames inside [start, stop), seconds."""
    on = np.nonzero(heard[int(start * 100):int(stop * 100)])[0]
    return float(np.diff(on).max()) / 100 if len(on) > 1 else 0.0


def grade(run):
    tl, events, state, heard = load(run)
    clips = [(t, k) for t, k in tl["clips"] if k in ORDER]
    turns = {}
    for turn in state.get("turns", []):
        turns.setdefault(turn["key"], turn)
    keys_seen = [k for _, k in clips]
    fails, warns, rows = [], [], []
    prev_car = {}
    for key in ORDER:
        if key not in keys_seen:
            rows.append((key, "FAIL", None, "-", "not spoken"))
            fails.append(key)
            continue
        i = keys_seen.index(key)
        t = clips[i][0]
        nxt = clips[i + 1][0] if i + 1 < len(clips) else tl.get("end", t + 60)
        end = t + check_req.audible_end(key)
        win = [l for vt, l in events if t - 0.5 <= vt < nxt]
        turn = turns.get(key, {})
        tools = turn.get("tools", [])
        car = turn.get("car", {})
        ok_names = [x["name"] for x in tools if x.get("ok")]
        replies = turn.get("reply", [])
        notes, bad = [], False

        # reply time, from his last audible word (after the flush, for the barge-in)
        start = end
        if key == "u_barge":
            flushes = [vt for vt, l in events if t <= vt < nxt and ("playback_flush" in l or "Interrupted" in l)]
            if flushes:
                start = max(end, flushes[0])
        first = first_on(heard, start, nxt)
        d = None if first is None else first - end
        limit = LIMIT.get(key, ANY_REPLY_S)
        if key != "u_mumble":
            if d is None:
                bad = True; notes.append("no reply")
            elif d > limit:
                bad = True; notes.append(f"slow (max {limit})")
        if first is not None and longest_gap(heard, first, nxt) > SILENCE_S:
            bad = True; notes.append(f"silent > {SILENCE_S:.0f} s in the turn")
        if any(ERROR_RE.search(l) for l in win):
            bad = True; notes.append("session error")
        if any(DROP_RE.search(l) for l in win):
            warns.append(key); notes.append("claim gate dropped a reply (WARN)")

        def need(names, what):
            nonlocal bad
            if not any(n in ok_names for n in names):
                bad = True; notes.append(f"missing {what}")

        if key in ("u_hello", "u_can", "u_bye"):
            if tools:
                bad = True; notes.append("tool on a chat line")
        elif key == "u_hot":
            need(["control_climate", "run_scenario"], "cooling action")
        elif key == "u_temp":
            need(["control_climate"], "control_climate")
            if car.get("temp_c") is not None and car.get("temp_c") != 22:
                bad = True; notes.append("climate target is not 22")
        elif key == "u_window":
            need(["control_window"], "control_window")
            now_w = (car.get("windows") or {}).get(DRIVER_WINDOW)
            was_w = (prev_car.get("windows") or {}).get(DRIVER_WINDOW, 0)
            if now_w is not None and not now_w > was_w:
                bad = True; notes.append("driver window did not open")
        elif key in ("u_sunroof", "u_mumble"):
            if ok_names:
                bad = True; notes.append("a tool succeeded on a line the car cannot or should not do")
            if claimed(key, replies):
                bad = True; notes.append("claimed an action")
            if key == "u_sunroof" and not replies and d is None:
                bad = True; notes.append("no refusal")
        elif key == "u_gas":
            called = [x for x in tools if x["name"] in ("navigate_to", "query_live_info")]
            if not called:
                bad = True; notes.append("no search")
        elif key == "u_cancel":
            if "exit_navigation_mode" not in ok_names and car.get("nav_phase") not in ("IDLE", "STOPPED"):
                bad = True; notes.append("flow not cancelled")
        elif key == "u_dest":
            need(["navigate_to"], "navigate_to")
            counts = [int(m.group(1)) for l in win for m in [re.search(r"nav_resolve_candidates count=(\d+)", l)] if m]
            if car.get("nav_phase") != "AWAITING_DESTINATION_SELECTION" and not any(counts):
                bad = True; notes.append("no candidates")
        elif key == "u_pick2":
            need(["choose_navigation_option"], "choose_navigation_option")
            idx = [str(x.get("args", {}).get("index")) for x in tools
                   if x["name"] == "choose_navigation_option" and "index" in x.get("args", {})]
            if idx and "2" not in idx:
                bad = True; notes.append("not index 2")
        elif key == "u_go":
            if "choose_navigation_option" not in ok_names and not any("nav_started" in l for l in win) \
                    and car.get("nav_phase") != "NAVIGATING":
                bad = True; notes.append("navigation not started")
        elif key == "u_eta":
            if any(not x.get("ok") for x in tools):
                bad = True; notes.append("a tool failed")
            if claimed(key, replies):
                bad = True; notes.append("claimed an action")
        elif key == "u_music":
            need(["play_music"], "play_music")
        elif key == "u_barge":
            if not any("livedemo_barge playing=true" in l for l in win):
                bad = True; notes.append("nothing playing to interrupt")
            onset = t + audible_start(key)
            stops = [vt for vt, l in events if onset <= vt <= onset + BARGE_STOP_S and ("playback_flush" in l or "Interrupted" in l)]
            if not stops:
                bad = True; notes.append(f"her audio did not stop within {BARGE_STOP_S} s")
        elif key == "u_mstop":
            need(["control_music"], "control_music")
            acts = [str(x.get("args", {}).get("action")) for x in tools
                    if x["name"] == "control_music" and "action" in x.get("args", {})]
            if acts and not any(a in ("stop", "pause") for a in acts):
                bad = True; notes.append("not stop")
        elif key == "u_call":
            if "place_call" not in ok_names and claimed(key, replies):
                bad = True; notes.append("claimed a call without a result")
        elif key == "u_navoff":
            need(["exit_navigation_mode"], "exit_navigation_mode")

        if car:
            prev_car = car
        tool_txt = ",".join(f"{x['name']}{'✓' if x.get('ok') else '✗'}" for x in tools) or "-"
        rows.append((key, "FAIL" if bad else "PASS", d, tool_txt, "; ".join(notes)))
        if bad:
            fails.append(key)

    storm = sum("reconnect" in l.lower() for _, l in events)
    outside = [l for vt, l in events if ERROR_RE.search(l)]
    if storm >= 3:
        fails.append("reconnect_storm")
    if outside and not any(r[4].find("session error") >= 0 for r in rows):
        fails.append("session_error")
    print(f"== {run}")
    for key, verdict, d, tool_txt, note in rows:
        ts = f"{d:4.1f}s" if d is not None else "  -- "
        print(f"  {key:10s} {verdict:4s} {ts}  {tool_txt:40s} {note}")
    print(f"RESULT {'PASS' if not fails else 'FAIL ' + ','.join(fails)}")
    print(f"WARN {','.join(warns) if warns else '-'}")
    return not fails, fails, warns


# ---- selftest: synthetic logcat + timeline fixtures, no audio model, no network ----

T0 = 1000.0
RATE = 16000


def _fixture(root, mutate=None):
    """A passing take; [mutate] edits the dict of parts before they are written."""
    clips_dir = os.path.join(root, "clips")
    os.makedirs(clips_dir, exist_ok=True)
    tone = (np.sin(np.arange(RATE) * 2 * np.pi * 220 / RATE) * 8000).astype("<i2")
    pcm = np.zeros(RATE, "<i2")
    pcm[int(0.2 * RATE):int(0.8 * RATE)] = tone[: int(0.6 * RATE)]
    for k in ORDER:
        pcm.tofile(os.path.join(clips_dir, k + ".pcm"))
    tools = {
        "u_hot": [{"name": "control_climate", "ok": True}],
        "u_temp": [{"name": "control_climate", "ok": True}],
        "u_window": [{"name": "control_window", "ok": True, "args": {"window": "driver"}}],
        "u_gas": [{"name": "navigate_to", "ok": True}],
        "u_cancel": [{"name": "exit_navigation_mode", "ok": True}],
        "u_dest": [{"name": "navigate_to", "ok": True}],
        "u_pick2": [{"name": "choose_navigation_option", "ok": True, "args": {"index": "2"}}],
        "u_go": [{"name": "choose_navigation_option", "ok": True}],
        "u_music": [{"name": "play_music", "ok": True}],
        "u_mstop": [{"name": "control_music", "ok": True, "args": {"action": "stop"}}],
        "u_call": [{"name": "place_call", "ok": False}],
        "u_navoff": [{"name": "exit_navigation_mode", "ok": True}],
    }
    parts = {"step": 12.0, "reply_after": {k: 1.5 for k in ORDER}, "tools": tools, "log": [],
             "replies": {k: ["好的"] for k in ORDER}, "barge_flush": 0.5, "barge_playing": True}
    if mutate:
        mutate(parts)
    clips, wakes, log, spans, turns = [], [], [], [], []
    temps = {"u_temp": 22}
    for i, k in enumerate(ORDER):
        t = 5.0 + i * parts["step"]
        clips.append([t, k])
        wakes.append(T0 + t - 0.8)
        log.append((t - 0.8, "debug_tool tool=voice arg=wake"))
        end = t + 0.8
        if k == "u_barge":
            if parts["barge_flush"] is not None:
                spans[-1] = (spans[-1][0], max(spans[-1][0], t + parts["barge_flush"]))
            log.append((t - 0.1, f"livedemo_barge playing={'true' if parts['barge_playing'] else 'false'}"))
            if parts["barge_flush"] is not None:
                log.append((t + parts["barge_flush"], "playback_flush epoch=7"))
                end = max(end, t + parts["barge_flush"])
        if k == "u_music":
            spans.append((end + parts["reply_after"][k], end + parts["reply_after"][k] + 20))
        elif parts["reply_after"].get(k) is not None:
            spans.append((end + parts["reply_after"][k], end + parts["reply_after"][k] + 2.0))
        if k == "u_dest":
            log.append((t + 1.2, "nav_resolve_candidates count=5 ms=40"))
        for tool in parts["tools"].get(k, []):
            log.append((t + 1.0, f"tool={tool['name']} args=[] result={'ok' if tool['ok'] else 'NO_TELEPHONY'}"))
        car = {"temp_c": temps.get(k, 22 if i > ORDER.index("u_temp") else 26),
               "windows": {"FRONT_LEFT": 30 if i >= ORDER.index("u_window") else 0},
               "nav_phase": "NAVIGATING" if i >= ORDER.index("u_go") else "IDLE"}
        turns.append({"key": k, "t": t + 10, "tools": parts["tools"].get(k, []), "reply": parts["replies"][k], "car": car})
    log += parts["log"]
    total = 5.0 + len(ORDER) * parts["step"] + 5
    audio = np.zeros(int(total * RATE), "<i2")
    long_tone = np.tile(tone, int(total) + 1)
    for a, b in spans:
        a_i, b_i = int(a * RATE), min(int(b * RATE), len(audio))
        audio[a_i:b_i] = long_tone[: b_i - a_i]
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
    def no_tool(p): p["tools"].pop("u_temp")
    def refusal_claimed(p): p["replies"]["u_sunroof"] = ["好的，天窗已经打开了"]
    def refusal_tool(p): p["tools"]["u_sunroof"] = [{"name": "control_window", "ok": True}]
    def slow(p): p["reply_after"]["u_hello"] = 4.0
    def no_stop(p): p["barge_flush"] = None
    def error(p): p["log"].append((5.0 + ORDER.index("u_gas") * p["step"] + 2.0, "state=ERROR err=GEMINI_PROTOCOL_ERROR"))
    def dropped(p): p["log"].append((5.0 + ORDER.index("u_sunroof") * p["step"] + 2.0,
                                     "TURN_DROP epoch=3 reason=unproven_action_claim kind=reply"))
    cases = [
        ("pass", None, True, [], []),
        ("missing tool", no_tool, False, ["u_temp"], []),
        ("refusal that claimed", refusal_claimed, False, ["u_sunroof"], []),
        ("refusal with a ✓ tool", refusal_tool, False, ["u_sunroof"], []),
        ("slow reply", slow, False, ["u_hello"], []),
        ("missing barge-in stop", no_stop, False, ["u_barge"], []),
        ("session error", error, False, ["u_gas"], []),
        ("claim drop is a WARN", dropped, True, [], ["u_sunroof"]),
    ]
    saved = check_req.CLIPS
    bad = []
    try:
        for name, mutate, want_ok, want_fails, want_warns in cases:
            with tempfile.TemporaryDirectory() as root:
                run, clips_dir = _fixture(root, mutate)
                check_req.CLIPS = clips_dir
                ok, fails, warns = grade(run)
                good = ok == want_ok and fails == want_fails and warns == want_warns
                print(f"selftest {name}: {'ok' if good else 'WRONG'} (fails={fails} warns={warns})")
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
