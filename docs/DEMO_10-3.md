# Demo and acceptance run — branch `claude/10-3`

One run that exercises the essential functions and everything added on `claude/10-2` and `claude/10-3`. It supersedes [DEMO_10-2.md](DEMO_10-2.md), which stays for its recorded results. Each step says what to say or do, what you should see or hear, and the log line that proves it. The script `tools/demo/check_demo_log.py` grades the log against the scene list (`tools/demo/scenes_10_3.json`) and the performance budgets below. It prints PASS, FAIL, WARN, NOT_SEEN or INFO per check, and never prints what was said (I-8).

It takes about 30 minutes on the PC emulator (`nova_api34`, see [EMULATOR_TESTING.md](EMULATOR_TESTING.md)), or on the phone. The cloud container cannot run it ([CLOUD_BUILD.md](CLOUD_BUILD.md)).

**Test policy:**
- Navigation uses only the short route 横琴创业谷 → 励骏庞都.
- Gemini is used only for voice: act C (the screen cards) needs no Gemini.

## What is covered

| Function | Source | Where in the run |
| --- | --- | --- |
| A fixed CONFIG card leaves | P44 | C1 |
| Transient status cards fade after 12 s; CONFIG never fades | B-033 | C1, C2 |
| The transcript bubble fades 10 s after the conversation goes quiet, also after a car action | B-035, 6efa047 | C3, then every turn of runs A and B |
| Gemini Live is the default; the default voice is Leda, switched once from Kore | SPEC-013/016, B-034 | run A |
| Xiaoyi (Azure) speaks every reply; Gemini's own audio is discarded | ADR-016, SPEC-019 R1–R4, R9 | run B |
| The speaking styles change the delivery, not only the words | SPEC-015 FZ-11/12/15–19, SPEC-019 R7 | run B, styles |
| Barge-in stops Xiaoyi within a clause; noise does not drop a reply | SPEC-019 R5 | run B, barge-in |
| A voice failure shows the VOICE card once; never Gemini's voice | SPEC-019 R6 | failure drill |
| Fuzzy driving requests: windows, seat, scenarios, follow-ups, refusals, a clarifying question | SPEC-015 | run B, car |
| Music from a description, honest result | SPEC-017 | run B, music |
| Guidance spoken by the assistant (developer toggle) | SPEC-018 | run B, navigation |
| An interrupted unheard reply is not synthesised; a question restarts the sleep window; turn timing logs | P45 | run B (`S-chat1`, global checks) |
| **10-3:** a session error card (state ERROR) fades after 12 s too | a19f90f (B-033) | C2 |
| **10-3:** Gemini's placeholder markup (`<no speech>{pause}`) is never read aloud | 381e446 (SPEC-019) | run B, barge-in (by ear) |
| **10-3:** Gemini VAD start LOW / end HIGH: a turn ends in room noise | 4f857e9 (P45) | run B, `MIC-1`; global stall check |
| **10-3:** the Azure connection is kept across sessions and warmed when the driver starts talking | 574e0ff (SPEC-019) | run B, `WARM-1` |
| **10-3:** a dropped chat reply is corrected in 1.5 s, not 20 s | 9696ce5 | every chat scene |
| **10-3:** a list of abilities is not an action claim | 9696ce5 | run B, `AB-1` |
| **10-9:** wait cues — silent for 7 s (label 「处理中」 from 3 s); one 「收到，正在处理。」 only if nothing useful has been spoken by 7–8 s; at 12 s a different delay line, not a repeat; the reply follows without overlap; a tool turn still completes | SPEC-020 (A6) | run B, any slow turn (navigation and music are the usual ones); checker row `SPEC-020 wait cues on time`, plus by ear: no status line before 7 s, no cue in the middle of a reply, no cue after a cough |

## Before the run

1. **Build and tests.** Run on the PC or in the cloud:
   ```powershell
   .\gradlew.bat test --rerun-tasks :app:assembleDebug
   python tools\demo\check_demo_log.py --selftest
   ```
   Expect 0 failures in the JUnit XML, and `SELFTEST OK`. Write the commit (`git rev-parse --short HEAD`) into the results table.
2. **Emulator:** run `.\scripts\emulator.ps1 -InstallApk`. The host audio bridge is your microphone, so use headphones to keep the PC speakers out of it (P45 was recorded with speakers).
3. **Settings, once,** in 开发者设置:
   - Gemini: key, 我已阅读并同意, 保存.
   - 小诺的声音: Azure key, region (e.g. `eastasia`), **leave 用 Azure 声音说话 unticked for run A**, 保存声音设置.
   - 助手播报导航（实验）: off for run A.
4. **Log capture,** in a second terminal, one file per run:
   ```powershell
   adb logcat -c
   adb logcat -v threadtime -s NovaVoice > demo_runA.log
   ```

## Act C — the screen cards (no Gemini)

| Step | Do | See | Log |
| --- | --- | --- | --- |
| C1 | 开发者设置 → untick 我已阅读并同意 → 保存 → back to the map. Wait 15 s. Then tick it, 保存, back | A CONFIG card. It is **still there after 15 s**. It is gone after the fix | `config_banner reason=…`; no `error_card_faded code=CONFIG`; then `error_card_cleared code=CONFIG` |
| C2 | `adb shell cmd connectivity airplane-mode enable`, tap the listening dot to start, wait 15 s, then `… airplane-mode disable` | A connection error card (e.g. `GEMINI_LIVE_CONNECTION_FAILED`, which arrives as state ERROR). It leaves by itself after about 12 s — before a19f90f it stayed over a minute — and the state line shows the real state (已断开) | `error=<code>`, then `error_card_faded code=<code> after_ms=12000` |
| C3 | `adb shell am start -n com.novadrive.app/.TranscriptFadeProbeActivity --el speaking_ms 5000 --el held_ms 15000`, then return to the app | One exchange in the bubble. It stays 15 s (held), then 10 s more, then the placeholder | `transcript_bubble_faded since_line_ms≈25000 quiet_ms≈10000` |

## Run A — Gemini's own voice (baseline)

The assistant voice is off. Start a session, then say each line and let her answer fully. Then **stay silent until the bubble clears** (about 10 s) before the next line.

| Scene | Say | Expect |
| --- | --- | --- |
| A1 | 你能做什么 | The capabilities answer in Gemini's voice (Leda) |
| A2 | 你叫什么名字 | A short answer |
| A3 | 给我讲个短笑话 | A joke |
| A4 | 有点热 | Climate cooler; she confirms |
| A5 | 把车窗打开一半 | Windows at 50 %; she confirms |

Stop the capture. These five give the baseline latency for the voice A/B. Then check the run:

```
python tools\demo\check_demo_log.py --run A demo_runA.log
```

## Run B — Xiaoyi, every new function

1. Tick 用 Azure 声音说话 and save.
2. Switch 助手播报导航 on.
3. Start a new capture into `demo_runB.log`, then start a new session.

The first line should be `assistant_voice enabled=true`.

**1. The same five as run A (B1–B5).** You should hear Xiaoyi only, never two voices. The bubble clears about 10 s after she stops, including after 有点热 and the window (the 6efa047 fix).

**1b. New on 10-3.**

| Scene | Do / say | Expect | Log |
| --- | --- | --- | --- |
| AB-1 | 你干什么 | A heard answer listing what she can do, within about 2 s. Not 20+ s of silence followed by 没听清 | no `TURN_DROP` in the turn; any `gemini_correction_deferred graceMs` ≤ 1500 |
| WARM-1 | Stay silent ≥ 60 s (let her sleep, then wake her). Say 讲个冷知识 | Xiaoyi starts without the long first-reply pause | first `azure_tts_first_audio ms` ≤ 1000 (was 1800–4500) |
| MIC-1 | **Take the headphones off**, turn on a fan or low music. Say 你喜欢什么颜色. Put the headphones back on | An answer within a few seconds; the next question also gets an answer | no `TURN_DROP`; no hold longer than 20 s in the whole run |

**2. Car (SPEC-015),** in this order. The order matters for the follow-ups.

| Scene | Say | Expect |
| --- | --- | --- |
| FZ-08 | 关窗 | Windows closed |
| FZ-01 | 有蚊子 | Windows open halfway; 「等它飞出去」 |
| FZ-02 | 蚊子出去了 | Windows closed |
| FZ-03 | 座位有点高 | Seat one step down |
| FZ-05 | 再低一点 | One more step down, taken from context |
| T1 | 温度调低一度 | Temperature −1 |
| FZ-14 | 再低一点 | **No action.** One question: 座椅还是温度？ |
| FZ-13 | 打开天窗 | **No action.** An honest 「暂时不支持」 |
| FZ-09 | 有点闷 | Air on, fan up, front windows open a little |

**3. Speaking styles** (SPEC-015 + SPEC-019 R7). Judge them by ear: the **delivery** must change, not only the words.

| Scene | Say | Expect | Azure style in the log |
| --- | --- | --- | --- |
| FZ-15 | 傲娇一点 | Proud, teasing confirmation | `disgruntled` |
| S-chat1 | 你喜欢吃什么 | Still 傲娇. Answered promptly (the P45 question) | `disgruntled` |
| FZ-11 | 说话嗲一点 | Sweet | `affectionate` |
| FZ-17 | 温柔一点 | Soft, calm | `gentle` |
| FZ-18 | 元气一点 | Bright, energetic | `cheerful` |
| FZ-19 | 说话霸道一点 | One sentence listing the styles that exist; **style unchanged** | `cheerful` |
| FZ-12 | 正常一点 | Back to normal | `none` |

**4. Barge-in** (SPEC-019 R5):
- BI-1: say 讲一个长一点的故事.
- BI-2: while she is still speaking, say 好了停一下. She stops within about one clause and listens. **She never reads out anything like 「no speech」 or 「pause」** (381e446).
- Then **cough once and knock on the desk** while no reply is playing. No reply may be lost to the noise: every `driver_onset_unplayed` must be followed by your speech.

**5. Music** (SPEC-017):
- M1: 放点梶浦由记的，空之境界里很燃的那首. The music app is opened with that request. She says only what is confirmed: a title only if it is playing, otherwise 「已经让音乐 app 去找了」.
- M2: 不是这首. Another request, excluding the first.
- M3: 停止播放. It stops, and she says so honestly.

**6. Navigation and guidance** (short route only, SPEC-018 toggle on):
- N1: 导航去励骏庞都. Candidates appear on the screen, and the bubble **stays** while the list waits.
- N2: 第一个. The routes appear.
- N3: 开始导航. Navigation starts. In a second terminal, run `adb shell am broadcast -a com.novadrive.app.DEBUG_TOOL --es tool nav_speed --es arg 60`.
- Listen to 3–5 prompts: each is spoken **once, by Xiaoyi**, with the same direction and distance as on the screen. Amap's own voice is heard only as a fallback.
- Talk over one prompt: it stops.
- N4: 结束导航.

Stop the capture and run:

```
python tools\demo\check_demo_log.py --run B demo_runB.log --baseline demo_runA.log
```

(`--scenes 10-2` grades a log against the old 10-2 script.)

### Failure drill (optional, at the end)

1. 小诺的声音: change one character of the Azure key, then save, then start a new session. Ask 你好.
   - You see the subtitle and **one VOICE card**, and there is silence: never Gemini's voice.
   - The log shows `assistant_voice_failed code=AZURE_TTS_AUTH` once for that reply.
2. Restore the key.
3. Tick the switch with the key field empty, then start a session. You see a CONFIG card `AZURE_KEY_MISSING`, and the session does not start.

## Performance budgets (what "aligned" means)

The checker applies these numbers. A "demo target" has no settled budget in the repository; it is proposed here and must not be read as a product commitment.

| Check | Budget | Source |
| --- | --- | --- |
| Chat reply start: end of speech → first audio, p50 | ≤ 2000 ms | `GEMINI-DEVICE-LATENCY-001` |
| Action start: end of speech → tool call | ≤ 2500 ms in ≥ 90 % | `GEMINI-DEVICE-LATENCY-001` |
| Extra delay of Xiaoyi vs Gemini's voice: p50(B) − p50(A), chat | ≤ 1000 ms | ADR-016 revisit threshold |
| Azure first audio per clause | recorded (INFO) | ADR-016 expects 0.2–1.0 s extra per reply |
| Assistant voice failures / `AZURE_TTS_TIMEOUT` | 0 / 0 | `TTS-VOICE-EMU-001` |
| Gemini's own audio discarded (`assistant_voice_provider_audio_dropped`) | present | SPEC-019 R1 |
| Barge-in: `playout_barge_in` → `assistant_voice_cancelled` | ≤ 500 ms | demo target (SPEC-019 R5 "within one clause") |
| `driver_onset_unplayed` followed by the driver's transcript | 100 % within 8 s | `TTS-VOICE-EMU-001` |
| Bubble fade quiet time | 10 000–11 600 ms; never while USER_SPEAKING, THINKING or SPEAKING | B-035 |
| Transient card fade | exactly 12 000 ms; CONFIG never | B-033 / P44 |
| No `inactivity_timeout` sooner than 30 s after a question | 0 early sleeps | P45 F3 |
| Claim-gate hold → verdict, p90 | ≤ 5000 ms (WARN above) | demo target; P45 saw 9.4 s; the real fix is SPEC-014 clause release |
| Gemini stream gaps (`max_gap_ms`), unheard interrupts, empty turns | recorded (INFO) | P45 F1 |
| Guidance fidelity mismatches | 0 | SPEC-018 |
| Any claim-gate hold | ≤ 20 000 ms (FAIL above) | P45 / 4f857e9: a stalled VAD held 31.8 s |
| Correction wait on a chat turn (`gemini_correction_deferred graceMs`) | ≤ 1500 ms | 9696ce5 `GeminiCorrectionGrace.CHAT_CORRECTION_GRACE_MS` |
| Chat replies with `no_drop` (AB-1, MIC-1) dropped by the claim gate | 0 | 9696ce5, 4f857e9 |
| First Azure clause after ≥ 60 s idle (WARM-1) | ≤ 1000 ms | demo target; 574e0ff measured 426 ms |

The checker measures latency from the logged end of speech: `state=THINKING` (the phone's uplink gate) or `gemini_voice_activity type=ACTIVITY_END` (the server's VAD, on the emulator). Audible output is `state=SPEAKING`. If neither end-of-speech mark is logged, the turn has no latency figure rather than a wrong one.

## By ear — what the log cannot judge

Tick each one during run B.

- [ ] Only one voice all the way through: Xiaoyi. Never Gemini's voice, and never two voices at once.
- [ ] 傲娇, 嗲, 温柔 and 元气 each **sound** different, not only in the words.
- [ ] The delay before Xiaoyi starts is acceptable compared with run A.
- [ ] Barge-in: she stops within about one clause.
- [ ] Guidance prompts: once each, in Xiaoyi's voice, matching the screen.
- [ ] Music: she never names a song that is not actually playing.
- [ ] The bubble never disappears while she is still speaking, or while a list or question waits.
- [ ] She never reads markup aloud (「no speech」, 「pause」, angle or curly brackets).
- [ ] No answer takes more than about 3 s, including with room noise (MIC-1) and after a long idle (WARM-1).

## Results

| Date | Commit | Device | Run A | Run B | By ear | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| | | | PASS / FAIL counts | PASS / FAIL counts | ticks | |

Keep the two logs and the checker output beside this table, in android_doc `demo_10-3_<date>/`. They hold timings and codes only, but they also contain `transcript=` lines, so do not publish them.
