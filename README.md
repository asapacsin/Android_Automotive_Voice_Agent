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
