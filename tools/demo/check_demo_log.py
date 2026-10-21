#!/usr/bin/env python3
"""Checks a demo run (docs/DEMO_10-3.md; --scenes 10-2 for docs/DEMO_10-2.md) from the app's NovaVoice log.

Capture during the run (Windows PowerShell or Linux):
    adb logcat -c
    adb logcat -v threadtime -s NovaVoice > demo_runB.log

Check:
    python tools/demo/check_demo_log.py --run A demo_runA.log
    python tools/demo/check_demo_log.py --run B demo_runB.log --baseline demo_runA.log
    python tools/demo/check_demo_log.py --selftest

The report names scene ids, codes, counts and timings only - never what was said (I-8): the
driver's words are read to find the scene, and never printed. Exit code 1 if anything FAILs.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

SCENE_FILES = {"10-2": "scenes_10_2.json", "10-3": "scenes_10_3.json"}
SCENES = Path(__file__).with_name(SCENE_FILES["10-3"])

# Budgets: (value, source). "demo target" = no settled budget exists; proposed for this demo.
BUDGET = {
    "chat_reply_p50_ms": (2000, "GEMINI-DEVICE-LATENCY-001: chat replies start within ~2 s"),
    "action_ms": (2500, "GEMINI-DEVICE-LATENCY-001: actions within ~2.5 s in >= 9 of 10"),
    "action_share": (0.9, "GEMINI-DEVICE-LATENCY-001: 9 of 10"),
    "voice_extra_p50_ms": (1000, "ADR-016 revisit threshold: p50 extra delay <= 1.0 s"),
    "barge_cancel_ms": (500, "demo target: the voice stops within one clause (SPEC-019 R5)"),
    "onset_transcript_ms": (8000, "TTS-VOICE-EMU-001: every driver_onset_unplayed is real speech"),
    "bubble_quiet_min_ms": (10000, "B-035: TRANSCRIPT_FADE_MS"),
    "bubble_quiet_max_ms": (11600, "B-035: 10 s + one 1 s poll + main-thread slack"),
    "error_card_ms": (12000, "B-033: ERROR_CARD_FADE_MS"),
    "sleep_after_question_ms": (30000, "P45 F3: SLEEP_AFTER_INACTIVITY_MS from the last question"),
    "hold_max_ms": (20000, "P45 / 4f857e9: a turn the server VAD never ends (31.8 s seen) is a stall"),
    "chat_correction_ms": (1500, "9696ce5: GeminiCorrectionGrace.CHAT_CORRECTION_GRACE_MS"),
    "azure_cold_ms": (1000, "demo target; 574e0ff measured 426 ms after 60 s idle (was 1.8-4.5 s)"),
    "hold_p90_ms": (5000, "demo target: P45 saw 9.4 s; SPEC-014 clause release is the real fix"),
    "cue_progress_early_ms": (6500, "SPEC-020 2026-10-09: no spoken status before 6.5 s"),
    "cue_progress_late_ms": (8000, "SPEC-020 2026-10-09: the one progress line is inside 7–8 s"),
    "cue_delay_early_ms": (11500, "SPEC-020 2026-10-09: the delay line is not spoken before 11.5 s"),
    "cue_delay_late_ms": (13000, "SPEC-020 2026-10-09: the delay line is the 12 s cue"),
}
BUSY = {"USER_SPEAKING", "THINKING", "SPEAKING"}
LINE = re.compile(r"^(\d\d)-(\d\d)\s+(\d\d):(\d\d):(\d\d)\.(\d{3})\s.*?NovaVoice\s*:\s?(.*)$")


@dataclass
class Event:
    t: int
    msg: str


@dataclass
class Result:
    rows: list = field(default_factory=list)

    def add(self, check: str, verdict: str, detail: str = "") -> None:
        self.rows.append((check, verdict, detail))

    @property
    def failed(self) -> bool:
        return any(v == "FAIL" for _, v, _ in self.rows)


def parse(text: str, year: int = 2026) -> list[Event]:
    events = []
    for line in text.splitlines():
        m = LINE.match(line.strip())
        if not m:
            continue
        mo, d, h, mi, s, ms, msg = m.groups()
        stamp = dt.datetime(year, int(mo), int(d), int(h), int(mi), int(s), int(ms) * 1000)
        events.append(Event(int(stamp.timestamp() * 1000), msg.strip()))
    return events


def norm(text: str) -> str:
    return re.sub(r"[\W_]+", "", text).lower()


def field_int(msg: str, key: str) -> int | None:
    m = re.search(rf"\b{key}=(-?\d+)", msg)
    return int(m.group(1)) if m else None


def field_str(msg: str, key: str) -> str | None:
    m = re.search(rf"\b{key}=([^\s,\]]+)", msg)
    return m.group(1) if m else None


def pct(values: list[int], p: float) -> int | None:
    if not values:
        return None
    if p == 0.5:
        return int(statistics.median(values))
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p * (len(ordered) - 1))))]


def driver_lines(events: list[Event]) -> list[tuple[int, str]]:
    return [(i, e.msg.split("你:", 1)[1]) for i, e in enumerate(events) if e.msg.startswith("transcript=你:")]


def assign_scenes(events: list[Event], scenes: list[dict]) -> tuple[dict, int]:
    """scene id -> event index of its driver line; plus the count of unmatched driver lines."""
    taken, extra = {}, 0
    for idx, text in driver_lines(events):
        said = norm(text)
        scene = next((s for s in scenes if s["id"] not in taken and any(norm(p) in said for p in s["match"])), None)
        if scene is None:
            extra += 1
        else:
            taken[scene["id"]] = idx
    return taken, extra


def speech_end_index(events: list[Event], idx: int, floor: int = 0) -> int | None:
    """When the driver stopped speaking: the last THINKING / server ACTIVITY_END in the 15 s before
    the transcript line (which Gemini delivers with the start of the reply), not before [floor]."""
    t0 = events[idx].t
    for j in range(idx, floor - 1, -1):
        e = events[j]
        if t0 - e.t > 15000:
            break
        if e.msg.startswith("state=THINKING") or "type=ACTIVITY_END" in e.msg:
            return j
    return None


def turn_starts(events: list[Event]) -> dict[int, int]:
    """Driver-line index -> index where its turn begins (speech end if logged, else the line)."""
    starts, floor = {}, 0
    for idx, _ in driver_lines(events):
        j = speech_end_index(events, idx, floor)
        starts[idx] = j if j is not None else idx
        floor = idx + 1
    return starts


def scene_window(events: list[Event], idx: int, starts: dict[int, int]) -> tuple[int, int]:
    later = [v for k, v in starts.items() if k > idx]
    return starts[idx], (min(later) if later else len(events))


def check_scenes(events: list[Event], scenes: list[dict], voice_on: bool, res: Result) -> dict:
    taken, extra = assign_scenes(events, scenes)
    starts = turn_starts(events)
    chat_ms, action_ms = [], []
    for s in scenes:
        sid = s["id"]
        if sid not in taken:
            res.add(f"scene {sid}", "NOT_SEEN", s["feature"])
            continue
        a, b = scene_window(events, taken[sid], starts)
        window = events[a:b]
        start = events[a].t if a != taken[sid] else None  # None: no speech-end mark, no latency
        tools = [e.msg.split()[0].split("=", 1)[1] for e in window if e.msg.startswith("tool=")]
        heard = next((e.t for e in window if e.msg.startswith("state=SPEAKING")), None)
        styles = [field_str(e.msg, "style") for e in window if e.msg.startswith("azure_tts_first_audio")]
        problems = []
        if s["expect"] == "chat":
            if tools:
                problems.append(f"unexpected tool {tools}")
            if heard is None:
                problems.append("no reply heard")
        elif s["expect"] == "tool":
            if not set(tools) & set(s["tools"]):
                problems.append(f"expected {s['tools']}, got {tools or 'none'}")
        elif s["expect"] == "no_tool":
            if tools:
                problems.append(f"expected no tool, got {tools}")
        elif s["expect"] == "event":
            lo = next((j for j in range(a, -1, -1) if events[a].t - events[j].t > 15000), 0)
            seen = [ev for ev in s["events"] if any(ev in e.msg for e in events[lo:b])]
            if not seen:
                problems.append(f"none of {s['events']}")
        if voice_on and s.get("style") and s["expect"] != "event":
            want = s["style"]
            if not styles:
                problems.append(f"no Azure audio (style {want} expected)")
            elif any(x != want for x in styles):
                problems.append(f"style {sorted(set(styles))} != {want}")
        if s["expect"] == "chat":
            grace = [field_int(e.msg, "graceMs") for e in window if e.msg.startswith("gemini_correction_deferred")]
            want, _ = BUDGET["chat_correction_ms"]
            if any(g is not None and g > want for g in grace):
                problems.append(f"chat correction waited {max(grace)} ms (> {want})")
        if s.get("no_drop"):
            drops = [field_str(e.msg, "reason") for e in window if e.msg.startswith("TURN_DROP") and "client_cancelled" not in e.msg]
            if drops:
                problems.append(f"reply dropped by the claim gate {sorted(set(drops))}")
        if s.get("cold") and voice_on:
            first = next((field_int(e.msg, "ms") for e in window if e.msg.startswith("azure_tts_first_audio")), None)
            want, _ = BUDGET["azure_cold_ms"]
            if first is None:
                problems.append("no Azure audio")
            elif first > want:
                problems.append(f"first Azure clause {first} ms after idle (> {want})")
        timing = ""
        if start is not None:
            if s["kind"] == "chat" and heard is not None and heard >= start:
                chat_ms.append(heard - start)
                timing = f"reply {heard - start} ms"
            first_tool = next((e.t for e in window if e.msg.startswith("tool=")), None)
            if s["kind"] == "action" and first_tool is not None:
                action_ms.append(first_tool - start)
                timing = f"action {first_tool - start} ms"
        res.add(f"scene {sid}", "FAIL" if problems else "PASS", "; ".join(problems) or (timing or s["feature"]))
    if extra:
        res.add("driver turns outside the script", "INFO", f"{extra} (repeats or extra questions)")
    return {"chat": chat_ms, "action": action_ms}


def check_latency(lat: dict, base: dict | None, res: Result) -> None:
    p50 = pct(lat["chat"], 0.5)
    budget, src = BUDGET["chat_reply_p50_ms"]
    if p50 is None:
        res.add("chat reply start p50", "NOT_SEEN", "no chat turn with a speech-end mark")
    else:
        res.add("chat reply start p50", "PASS" if p50 <= budget else "FAIL", f"{p50} ms (n={len(lat['chat'])}; <= {budget}; {src})")
    if lat["action"]:
        budget, src = BUDGET["action_ms"]
        share = sum(1 for x in lat["action"] if x <= budget) / len(lat["action"])
        need = BUDGET["action_share"][0]
        res.add("action start", "PASS" if share >= need else "FAIL",
                f"{share:.0%} within {budget} ms (n={len(lat['action'])}, p50 {pct(lat['action'], 0.5)} ms; {src})")
    if base is not None:
        b50 = pct(base["chat"], 0.5)
        if p50 is None or b50 is None:
            res.add("assistant-voice extra delay", "NOT_SEEN", "needs chat latencies in both runs")
        else:
            budget, src = BUDGET["voice_extra_p50_ms"]
            extra = p50 - b50
            res.add("assistant-voice extra delay", "PASS" if extra <= budget else "FAIL", f"{extra} ms (B {p50} - A {b50}; <= {budget}; {src})")


def check_global(events: list[Event], run: str, res: Result) -> None:
    msgs = [e.msg for e in events]
    enabled = [m for m in msgs if m.startswith("assistant_voice enabled=")]
    want = "true" if run == "B" else "false"
    if not enabled:
        res.add("assistant voice setting", "NOT_SEEN", "no session start in this log")
    else:
        got = enabled[-1].split("=", 1)[1]
        res.add("assistant voice setting", "PASS" if got == want else "FAIL", f"enabled={got} (run {run} needs {want})")
    voices = {field_str(m, "voice") for m in msgs if m.startswith("gemini_connect")}
    if voices:
        res.add("Gemini voice at connect (B-034)", "PASS" if voices == {"Leda"} else "INFO", f"{sorted(v for v in voices if v)}")
    if run == "B":
        failed = [field_str(m, "code") for m in msgs if m.startswith("assistant_voice_failed")]
        res.add("assistant voice failures", "PASS" if not failed else "FAIL", f"{len(failed)} {sorted(set(failed))}")
        timeouts = sum(1 for m in msgs if "AZURE_TTS_TIMEOUT" in m)
        res.add("Azure clause timeouts", "PASS" if not timeouts else "FAIL", f"{timeouts}")
        dropped = [field_int(m, "count") or 0 for m in msgs if m.startswith("assistant_voice_provider_audio_dropped")]
        res.add("Gemini's own audio discarded", "PASS" if dropped else "FAIL",
                f"{len(dropped)} replies, {sum(dropped)} chunks (only Xiaoyi is heard)" if dropped else "no assistant_voice_provider_audio_dropped line")
        first = [field_int(m, "ms") for m in msgs if m.startswith("azure_tts_first_audio")]
        first = [x for x in first if x is not None]
        if first:
            res.add("Azure first audio per clause", "INFO", f"p50 {pct(first, 0.5)} ms, p90 {pct(first, 0.9)} ms (n={len(first)}; ADR-016 expects 0.2-1.0 s extra per reply)")
    # Barge-in: every flush while Xiaoyi spoke stops her synthesis.
    barges = [i for i, e in enumerate(events) if "playout_barge_in" in e.msg]
    if barges:
        budget, src = BUDGET["barge_cancel_ms"]
        late = 0
        for i in barges:
            cancel = next((e for e in events[i:] if e.msg.startswith("assistant_voice_cancelled")), None)
            if run == "B" and (cancel is None or cancel.t - events[i].t > budget):
                late += 1
        res.add("barge-in stops the voice", "PASS" if late == 0 else "WARN",
                f"{len(barges) - late}/{len(barges)} cancelled within {budget} ms ({src}; no line = nothing was being synthesised)")
    unplayed = [i for i, e in enumerate(events) if e.msg.startswith("assistant_voice_cancelled reason=driver_onset_unplayed")]
    if unplayed:
        budget, src = BUDGET["onset_transcript_ms"]
        lost = sum(1 for i in unplayed if not any(e.msg.startswith("transcript=你:") and 0 <= e.t - events[i].t <= budget for e in events[i:]))
        res.add("unplayed replies dropped only for real speech", "PASS" if lost == 0 else "FAIL", f"{len(unplayed) - lost}/{len(unplayed)} followed by a driver transcript ({src})")
    check_bubble(events, res)
    check_cards(events, res)
    check_guidance(msgs, res)
    check_p45(events, res)
    if run == "B":
        check_wait_cues(msgs, res)


def check_bubble(events: list[Event], res: Result) -> None:
    fades = [(i, e) for i, e in enumerate(events) if e.msg.startswith("transcript_bubble_faded")]
    shown = sum(1 for e in events if e.msg == "transcript_bubble_shown")
    if not fades:
        res.add("B-035 bubble fades", "NOT_SEEN" if shown == 0 else "FAIL", f"{shown} lines shown, no fade")
        return
    lo, src_lo = BUDGET["bubble_quiet_min_ms"]
    hi, _ = BUDGET["bubble_quiet_max_ms"]
    bad_quiet, busy_at_fade = 0, 0
    for i, e in fades:
        q = field_int(e.msg, "quiet_ms")
        if q is not None and not lo <= q <= hi:
            bad_quiet += 1
        last_state = next((x.msg.split()[0].split("=", 1)[1] for x in reversed(events[:i]) if x.msg.startswith("state=")), None)
        if last_state in BUSY:
            busy_at_fade += 1
    verdict = "PASS" if bad_quiet == 0 and busy_at_fade == 0 else "FAIL"
    res.add("B-035 bubble fades after 10 s of quiet", verdict,
            f"{len(fades)} fades; quiet outside [{lo},{hi}] ms: {bad_quiet}; while busy: {busy_at_fade}; lines shown {shown}")


def check_cards(events: list[Event], res: Result) -> None:
    faded = [e.msg for e in events if e.msg.startswith("error_card_faded")]
    want, src = BUDGET["error_card_ms"]
    if faded:
        bad = [m for m in faded if field_str(m, "code") == "CONFIG" or field_int(m, "after_ms") != want]
        res.add("B-033 transient cards fade", "PASS" if not bad else "FAIL", f"{len(faded)} faded after {want} ms; CONFIG or wrong time: {len(bad)}")
    else:
        res.add("B-033 transient cards fade", "NOT_SEEN", "run act C2 (airplane mode) to see one")
    banners = [e for e in events if e.msg.startswith("config_banner")]
    cleared = [e for e in events if e.msg == "error_card_cleared code=CONFIG"]
    if banners:
        ok = any(c.t > banners[0].t for c in cleared)
        res.add("P44 CONFIG card leaves once fixed", "PASS" if ok else "FAIL", f"{len(banners)} banner(s), {len(cleared)} cleared")
    else:
        res.add("P44 CONFIG card leaves once fixed", "NOT_SEEN", "run act C1 (untick consent) to see it")


def check_guidance(msgs: list[str], res: Result) -> None:
    routes = [m for m in msgs if m.startswith("guidance_route")]
    if not routes:
        res.add("SPEC-018 guidance in Xiaoyi's voice", "NOT_SEEN", "toggle 助手播报导航 on and drive the short route")
        return
    to_assistant = sum(1 for m in routes if "to=assistant" in m)
    to_amap = [field_str(m, "reason") for m in routes if "to=amap" in m]
    fidelity = [field_str(m, "result") for m in msgs if m.startswith("guidance_fidelity")]
    mismatch = sum(1 for f in fidelity if f and f.startswith("mismatch"))
    res.add("SPEC-018 guidance in Xiaoyi's voice", "PASS" if mismatch == 0 and to_assistant > 0 else "FAIL",
            f"{to_assistant} to assistant, {len(to_amap)} to Amap {sorted(set(to_amap))}; fidelity mismatches {mismatch}/{len(fidelity)}")


def check_p45(events: list[Event], res: Result) -> None:
    budget, src = BUDGET["sleep_after_question_ms"]
    early = 0
    for i, e in enumerate(events):
        if "reason=inactivity_timeout" in e.msg and e.msg.startswith("listening ACTIVE->SLEEP"):
            last_q = next((x.t for x in reversed(events[:i]) if x.msg.startswith("transcript=你:")), None)
            if last_q is not None and e.t - last_q < budget - 500:
                early += 1
    res.add("P45 no sleep within 30 s of a question", "PASS" if early == 0 else "FAIL", f"{early} early sleeps ({src})")
    holds = {}
    spans = []
    for e in events:
        if e.msg.startswith("TURN_HOLD"):
            holds[field_int(e.msg, "epoch")] = e.t
        elif e.msg.startswith(("TURN_RELEASE", "TURN_DROP")):
            t = holds.pop(field_int(e.msg, "epoch"), None)
            if t is not None:
                spans.append(e.t - t)
    if spans:
        budget, src = BUDGET["hold_p90_ms"]
        p90 = pct(spans, 0.9)
        res.add("claim-gate hold to verdict", "PASS" if p90 <= budget else "WARN", f"p50 {pct(spans, 0.5)} ms, p90 {p90} ms, max {max(spans)} ms ({src})")
        cap, src = BUDGET["hold_max_ms"]
        stalls = sum(1 for x in spans if x > cap)
        res.add("P45 no stalled turn (VAD)", "PASS" if stalls == 0 else "FAIL", f"{stalls} holds over {cap} ms ({src})")
    done = [e.msg for e in events if e.msg.startswith("gemini_turn_done") and "max_gap_ms" in e.msg]
    if done:
        gaps = [field_int(m, "max_gap_ms") or 0 for m in done]
        unheard = sum(1 for e in events if e.msg == "gemini_interrupted_unheard")
        empty = sum(1 for e in events if e.msg.startswith("gemini_turn_empty"))
        res.add("P45 F1 Gemini stream gaps", "INFO", f"max gap p50 {pct(gaps, 0.5)} ms, p90 {pct(gaps, 0.9)} ms; interrupted unheard {unheard}; empty turns {empty}")


# Spoken before 7 s on the previous schedule. A new build must not emit them.
RETIRED_CUE_CODES = {"ack_action", "ack_chat", "provider_slow", "verifying", "tool_running", "still_waiting"}


def check_wait_cues(msgs: list[str], res: Result) -> None:
    """SPEC-020 (2026-10-09): visual at 3 s, one progress line at 7 s, a different line at 12 s."""
    cues = [(field_str(m, "code"), field_int(m, "after_ms")) for m in msgs if m.startswith("wait_cue code=")]
    if not cues:
        res.add("SPEC-020 wait cues on time", "NOT_SEEN", "no wait_cue line (no reply was slow, or the build predates SPEC-020)")
    else:
        bad = []
        for code, after in cues:
            if code in RETIRED_CUE_CODES:
                bad.append(f"retired {code}")
                continue
            if code == "visual" or after is None:
                continue
            if code == "progress":
                if after < BUDGET["cue_progress_early_ms"][0]:
                    bad.append(f"progress {after} ms early")
                elif after > BUDGET["cue_progress_late_ms"][0]:
                    bad.append(f"progress {after} ms late")
            elif code == "delay":
                if after < BUDGET["cue_delay_early_ms"][0]:
                    bad.append(f"delay {after} ms early")
                elif after > BUDGET["cue_delay_late_ms"][0]:
                    bad.append(f"delay {after} ms late")
            else:
                bad.append(f"unknown {code}")
        counts = {c: sum(1 for x, _ in cues if x == c) for c in sorted({x for x, _ in cues if x})}
        res.add(
            "SPEC-020 wait cues on time",
            "PASS" if not bad else "FAIL",
            f"{counts}; bad: {bad or 'none'} "
            f"(progress {BUDGET['cue_progress_early_ms'][0]}–{BUDGET['cue_progress_late_ms'][0]} ms, "
            f"delay {BUDGET['cue_delay_early_ms'][0]}–{BUDGET['cue_delay_late_ms'][0]} ms)",
        )
    failed = sum(1 for m in msgs if m.startswith("wait_cue_failed"))
    if failed:
        res.add("SPEC-020 wait cue synthesis", "FAIL", f"{failed} wait_cue_failed")
    reasons: dict[str, int] = {}
    for m in msgs:
        if m.startswith(("wait_cue_skipped", "wait_cue_cancelled")):
            k = f"{m.split()[0].removeprefix('wait_cue_')}:{field_str(m, 'reason')}"
            reasons[k] = reasons.get(k, 0) + 1
    if reasons:
        res.add("SPEC-020 cues skipped/cancelled", "INFO", ", ".join(f"{k} x{v}" for k, v in sorted(reasons.items())))


def report(title: str, res: Result) -> str:
    out = [title, "-" * len(title)]
    width = max((len(c) for c, _, _ in res.rows), default=10)
    for check, verdict, detail in res.rows:
        out.append(f"{verdict:8} {check:<{width}}  {detail}")
    counts = {v: sum(1 for _, x, _ in res.rows if x == v) for v in ("PASS", "FAIL", "WARN", "NOT_SEEN", "INFO")}
    out.append(" ".join(f"{k}={v}" for k, v in counts.items()))
    return "\n".join(out)


def check(run: str, text: str, baseline_text: str | None = None, scene_file: Path | None = None) -> Result:
    scenes = json.loads((scene_file or SCENES).read_text(encoding="utf-8"))["runs"]
    events = parse(text)
    res = Result()
    if not events:
        res.add("log", "FAIL", "no NovaVoice lines (capture with: adb logcat -v threadtime -s NovaVoice)")
        return res
    lat = check_scenes(events, scenes[run]["scenes"], voice_on=run == "B", res=res)
    base = None
    if baseline_text is not None:
        base = check_scenes(parse(baseline_text), scenes["A"]["scenes"], voice_on=False, res=Result())
    check_latency(lat, base, res)
    check_global(events, run, res)
    return res


# ---- selftest ------------------------------------------------------------------------------

def _log(lines: list[tuple[str, str]]) -> str:
    return "\n".join(f"10-02 {t}  100  100 D NovaVoice: {m}" for t, m in lines)


def selftest() -> int:
    run_a = _log([
        ("10:00:00.000", "assistant_voice enabled=false"),
        ("10:00:00.100", "gemini_connect resume=false voice=Leda model=gemini-3.8-live"),
        ("10:00:05.000", "state=THINKING err=-"),
        ("10:00:05.900", "transcript=你: 你能做什么？"),
        ("10:00:06.000", "state=SPEAKING err=-"),
        ("10:00:20.000", "state=THINKING err=-"),
        ("10:00:21.100", "transcript=你: 你叫什么名字"),
        ("10:00:21.200", "state=SPEAKING err=-"),
    ])
    run_b = _log([
        ("11:00:00.000", "assistant_voice enabled=true"),
        ("11:00:00.100", "gemini_connect resume=false voice=Leda model=gemini-3.8-live"),
        ("11:00:01.000", "config_banner reason=GEMINI_CONSENT_MISSING"),
        ("11:00:09.000", "error_card_cleared code=CONFIG"),
        ("11:00:10.000", "state=THINKING err=-"),
        ("11:00:10.500", "transcript=你: 你能做什么"),
        ("11:00:10.900", "azure_tts_first_audio ms=400 chars=6 style=none"),
        ("11:00:11.500", "state=SPEAKING err=-"),
        ("11:00:11.600", "transcript_bubble_shown"),
        ("11:00:14.000", "assistant_voice_provider_audio_dropped count=12"),
        ("11:00:14.100", "state=LISTENING err=-"),
        ("11:00:25.000", "transcript_bubble_faded since_line_ms=13400 quiet_ms=10500"),
        ("11:00:30.000", "state=THINKING err=-"),
        ("11:00:30.900", "transcript=你: 说话傲娇一点"),
        ("11:00:31.000", "tool=set_speaking_style args=[style] result=ok"),
        ("11:00:31.500", "azure_tts_first_audio ms=380 chars=5 style=disgruntled"),
        ("11:00:31.700", "state=SPEAKING err=-"),
        ("11:00:40.000", "gemini_voice_activity type=ACTIVITY_END"),
        ("11:00:41.000", "transcript=你: 你喜欢吃什么"),
        ("11:00:41.500", "azure_tts_first_audio ms=500 chars=5 style=none"),
        ("11:00:42.000", "state=SPEAKING err=-"),
        ("11:00:50.000", "TURN_HOLD epoch=5 reason=UNCLASSIFIED_CLAIM kind=CONVERSATION durationMs=-1"),
        ("11:00:51.000", "TURN_RELEASE epoch=5 reason=no_claim_made events=3"),
        ("11:00:51.100", "gemini_turn_done status=completed calls=0 spoke=true dur_ms=1500 first_text_ms=100 gen_ms=900 msgs=9 max_gap_ms=300"),
        ("11:00:55.000", "state=THINKING err=-"),
        ("11:00:55.800", "transcript=你: 你干什么"),
        ("11:00:56.000", "TURN_HOLD epoch=6 reason=UNCLASSIFIED_CLAIM kind=CONVERSATION durationMs=-1"),
        ("11:00:56.900", "TURN_DROP epoch=6 reason=claim kind=CONVERSATION events=4"),
        ("11:00:57.000", "gemini_correction_deferred graceMs=20000"),
        ("11:00:58.000", "state=SPEAKING err=-"),
        ("11:00:58.500", "state=THINKING err=-"),
        ("11:00:59.000", "transcript=你: 讲个冷知识"),
        ("11:00:59.100", "wait_cue code=progress after_ms=7100"),
        ("11:00:59.150", "wait_cue_cancelled reason=reply_queued"),
        ("11:00:59.200", "azure_tts_first_audio ms=2400 chars=8 style=none"),
        ("11:00:59.600", "state=SPEAKING err=-"),
        ("11:01:00.000", "error=GEMINI_LIVE_CONNECTION_FAILED x"),
        ("11:01:12.000", "error_card_faded code=GEMINI_LIVE_CONNECTION_FAILED after_ms=12000"),
        ("11:01:30.000", "listening ACTIVE->SLEEP reason=inactivity_timeout cloudStreamingMs=1"),
    ])
    res = check("B", run_b, run_a)
    rows = {c: (v, d) for c, v, d in res.rows}
    expect = {
        "scene B1": "PASS",
        "scene FZ-15": "PASS",            # set_speaking_style, reply in disgruntled
        "scene S-chat1": "FAIL",          # style none after 傲娇: must be flagged
        "assistant voice setting": "PASS",
        "Gemini's own audio discarded": "PASS",
        "B-035 bubble fades after 10 s of quiet": "PASS",
        "B-033 transient cards fade": "PASS",
        "P44 CONFIG card leaves once fixed": "PASS",
        "P45 no sleep within 30 s of a question": "PASS",   # slept 30.8 s after the last question
        "scene AB-1": "FAIL",            # dropped as a claim, 20 s correction: both flagged
        "scene WARM-1": "FAIL",          # 2400 ms cold first clause
        "P45 no stalled turn (VAD)": "PASS",
        "SPEC-020 wait cues on time": "PASS",
    }
    problems = [f"{k}: {rows.get(k, ('missing',))[0]} != {v}" for k, v in expect.items() if rows.get(k, ("missing",))[0] != v]
    # B chat 1500 (B1) and 2000 (S-chat1) -> p50 1750; A 1000 and 1200 -> p50 1100; extra 650 ms.
    if rows["assistant-voice extra delay"][0] != "PASS" or not rows["assistant-voice extra delay"][1].startswith("650 ms"):
        problems.append(f"extra delay: {rows['assistant-voice extra delay']}")
    early = check("B", run_b.replace("11:01:30.000", "11:00:50.000"))
    if dict((c, v) for c, v, _ in early.rows)["P45 no sleep within 30 s of a question"] != "FAIL":
        problems.append("an early sleep was not flagged")
    leaked = [d for _, _, d in res.rows if "喜欢" in d or "傲娇" in d or "名字" in d]
    if leaked:
        problems.append("the report leaked driver words")
    ab = rows.get("scene AB-1", ("", ""))[1]
    if "claim gate" not in ab or "20000" not in ab:
        problems.append(f"AB-1 detail: {ab}")
    stall = check("B", run_b.replace("10-02 11:00:51.000  100  100 D NovaVoice: TURN_RELEASE", "10-02 11:01:15.000  100  100 D NovaVoice: TURN_RELEASE"))
    if dict((c, v) for c, v, _ in stall.rows).get("P45 no stalled turn (VAD)") != "FAIL":
        problems.append("a 25 s hold was not flagged as a stall")
    late_cue = check("B", run_b.replace("wait_cue code=progress after_ms=7100", "wait_cue code=progress after_ms=9000"))
    if dict((c, v) for c, v, _ in late_cue.rows).get("SPEC-020 wait cues on time") != "FAIL":
        problems.append("a late wait cue was not flagged")
    early_cue = check("B", run_b.replace("wait_cue code=progress after_ms=7100", "wait_cue code=ack_chat after_ms=1810"))
    if dict((c, v) for c, v, _ in early_cue.rows).get("SPEC-020 wait cues on time") != "FAIL":
        problems.append("a retired early wait cue was not flagged")
    failed_cue = check("B", run_b.replace("wait_cue_cancelled reason=reply_queued", "wait_cue_failed"))
    if dict((c, v) for c, v, _ in failed_cue.rows).get("SPEC-020 wait cue synthesis") != "FAIL":
        problems.append("a failed wait cue was not flagged")
    if parse("garbage\n"):
        problems.append("parsed a non-log line")
    print(report("selftest run B", res))
    print("SELFTEST " + ("OK" if not problems else "FAILED: " + "; ".join(problems)))
    return 0 if not problems else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log", nargs="?", help="NovaVoice logcat capture of the run")
    ap.add_argument("--run", choices=["A", "B"], default="B")
    ap.add_argument("--baseline", help="run A capture, for the assistant-voice extra delay")
    ap.add_argument("--scenes", choices=sorted(SCENE_FILES), default="10-3", help="which demo's scene list")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if not args.log:
        ap.error("a log file is required (or --selftest)")
    text = Path(args.log).read_text(encoding="utf-8", errors="replace")
    base = Path(args.baseline).read_text(encoding="utf-8", errors="replace") if args.baseline else None
    scene_file = Path(__file__).with_name(SCENE_FILES[args.scenes])
    scenes = json.loads(scene_file.read_text(encoding="utf-8"))["runs"]
    res = check(args.run, text, base, scene_file)
    print(report(scenes[args.run]["title"], res))
    return 1 if res.failed else 0


if __name__ == "__main__":
    sys.exit(main())
