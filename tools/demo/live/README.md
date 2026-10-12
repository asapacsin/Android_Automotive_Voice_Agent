# JVM live demo (cloud container)

The commute demo of [docs/DEMO_COMMUTE.md](../../../docs/DEMO_COMMUTE.md) recorded **on the JVM**, for
containers that cannot run the emulator. The shipped provider client (Qwen-Omni Maia, or Gemini to
validate the harness), the core session, the app's uplink gate and the app's tool dispatch
(`AndroidToolDispatcher`, climate/window/seat, comfort scenarios, speaking style, music hand-off,
`query_live_info` on the real Amap REST client) talk to the **live model**. The car, the route
(`HengqinWorld`, real-time 40 km/h drive, simulated route traffic) and the music app are simulated.
It is **not** the app: no Android UI, no listening lifecycle or local picker intercept, no AEC, no
Amap SDK. A pass here is not device evidence (ACCEPTANCE_TESTS.md).

Env: `NOVA_LIVE_DEMO=1` (otherwise the test is skipped), `NOVA_LIVE_OUT=<dir>` (required),
`NOVA_LIVE_PROVIDER=qwen|gemini` (default qwen), `NOVA_QWEN_MODEL` (required for qwen; use `qwen3.5-omni-plus-realtime` — `qwen3.8-omni-flash-realtime` is refused unless `NOVA_ALLOW_QWEN_38=yes` on the owner's explicit say), `NOVA_LIVE_SCENES=commute_board,...` (default all
six, one session), `DASHSCOPE_API_KEY` + `DASHSCOPE_WORKSPACE_ID` (qwen) or `GEMINI_API_KEY`
(gemini), `AMAP_WEB_KEY`. Outbound goes through the environment's proxy (JAVA_TOOL_OPTIONS / HTTPS_PROXY).

```bash
python3 tools/demo/live/make_clips_cloud.py                 # c_* driver clips (edge-tts, gitignored)
NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini NOVA_LIVE_OUT=/tmp/live \
  ./gradlew --offline :app:testDebugUnitTest --tests '*LiveDemoRun*'
python3 tools/demo/recorder/check_req.py /tmp/live           # graded unchanged
python3 tools/demo/live/render.py /tmp/live /tmp/live.mp4
```

Outputs (logcat.txt, timeline.json, state.json with transcripts, wavs, mp4) stay outside the
repository and must never be committed.

**Never skipped:** with `NOVA_LIVE_DEMO=1` the unit-test task is never up to date (app/build.gradle.kts), so a take always runs; before 2026-10-11 Gradle once skipped a take silently. Check that `NOVA_LIVE_OUT` holds a fresh `timeline.json` anyway.

**Rehearse first, cheaply:** run on Gemini (`NOVA_LIVE_PROVIDER=gemini`, no Qwen quota; the harness uses the low model `gemini-3.8-live`) only the scenes the change touches (`NOVA_LIVE_SCENES=...`), once, and grade them; open the one Qwen take only when the rehearsal is clean. Gemini costs too (AGENTS.md): no repeated runs to chase latency variance. A voice test (custom or cloned voice) skips Gemini and reads the lines as text turns in one short Qwen session.

**Quota (AGENTS.md hard rule):** every live Qwen session spends the owner's limited free quota. Prove what you can offline first; run live only with the owner's go for that run, with `NOVA_SPEND_QWEN_QUOTA=yes` set. Without it this tool refuses to open a Qwen session.

## The drive scene (`NOVA_LIVE_SCENES=drive`)

One continuous real-time trip 横琴创业谷 → 横琴镇 at 40 km/h with **the app's speech rules on**:
`DriveSpeechRules` wires the shipped `SpeechAuthority` / `SpeechArbiter` where the app wires them
(the reply player's DROP/HOLD/PLAY, the Amap guidance listener, the per-frame microphone gate, the
driver-speaking R0 input, and the real `VoiceCommandRouter` for the driver-request window), and
`HengqinWorld` feeds the manoeuvre distance to `NavigationState` and speaks Amap-style prompts
(`g_*.pcm`, edge-tts `zh-CN-XiaoxiaoNeural`, texts in `make_clips_cloud.py` `GUIDANCE`; the
manoeuvres and road names are simulated). Parked: c_sweet, c_seat, c_seat2; depart: c_nav, c_pick,
c_go (wait for each reply); then the driver speaks at route positions (`LiveDemoRun.DRIVE_LINES`),
whether or not she is still talking, and arrival is announced by guidance. It runs alone; the six
commute scenes stay the default and run without the speech rules, as before.

Extra outputs: `amap.wav`; `timeline.json` `amap_guidance` (key, start, end) and `holds` (reply
audio withheld, start, end, played); `state.json` per turn `speech` (the arbiter's decisions with
reasons), `spoke_over_reply`, `mic_frames_gated`. `check_req.py` prints `after_guidance` and judges
a turn whose wait met a prompt or hold from its end; `render.py` mixes the Amap voice in and shows
高德导航 / （等导航播报结束）. The wiring is proved offline by `LiveDemoDriveTest` (no network).
