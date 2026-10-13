# Commute demo 横琴创业谷 → 横琴镇 — 2026-10-11

`commute_demo_qwen35plus_2026-10-11.mp4` (147 s): the commute demo of `docs/DEMO_COMMUTE.md`
(branch `claude/10-9`), in the order of the 豆包座舱 review video.

- Voice: **qwen3.5-omni-plus-realtime**, voice Maia, end to end (the model hears the driver and speaks).
- Recorded on the JVM live harness: the real voice stack, claim gate, tool dispatch and live Amap
  weather against the live model; the car, the route and the music app are simulated. It is not the
  app screen and not device evidence.
- Graded by `tools/demo/recorder/check_req.py`: PASS, every reply 2.2–3.2 s after the driver stops;
  every action she announces has a successful tool result.
- Known limit on 3.5 Plus: 「嗲一点」 is answered in words only, because that model crashes on the
  speaking-style tool; the tone does not persist. 3.8 Flash calls it.

Code at `claude/10-9` cc3b588. Logs are not published (they hold transcripts).

## Voice sample: Ayano (owner's own AI voice) — 2026-10-12

`ayano_voice_sample_35plus.wav` (34 s): eight lines from the commute demo, read by
**qwen3.5-omni-plus-realtime** with the owner's cloned voice Ayano (a synthetic voice he created).
One text-only session per line, no tools; all 8 read word for word, no errors.

## Commute demo in Ayano's voice — 2026-10-12

`commute_demo_ayano_qwen35plus_2026-10-12.mp4` (158 s): the same commute demo, now in the default
voice Ayano, on **qwen3.5-omni-plus-realtime** (JVM live harness, simulated car; not device evidence).
`check_req.py` PASS: every reply 2.6–3.5 s after the driver stops, every tool call right and successful.
Same 3.5 Plus limit: 「嗲一点」 is answered in words only. Code at `claude/10-9` 22d89f3.

## Navigation drive simulation (Gemini rehearsal): 2026-10-13

`drive_simulation_gemini_rehearsal_2026-10-13.mp4` (336 s) shows the whole commute as a drive: 横琴创业谷 → 横琴镇 with turn-by-turn guidance.
- **The voice is Gemini (`gemini-3.1-flash-live-preview`), not Ayano.** This is the free rehearsal; the Ayano take uses Qwen.
- **Real:** the app's voice stack, tool calls, claim gate and speech rules. Her replies wait while a guidance prompt plays, and she holds back near a turn. Also real: the Amap place search (the result is fetched before the session) and Amap weather.
- **Simulated:** the car, the route and the drive, and the music app. The guidance prompts are Amap-style lines in a third voice (Piper), not Amap's own voice.
- **Not shown:** the Amap SDK map screen, which runs only on the emulator or phone.
- **Result:** `check_req.py` PASS on all 12 lines, 2.1–3.2 s. The one exception is 开始导航, which waited 4.5 s by rule for the start-of-navigation prompt.

Code at `claude/10-9` 0d2d588. Logs are not published (they hold transcripts).
