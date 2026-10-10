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
`NOVA_LIVE_PROVIDER=qwen|gemini` (default qwen), `NOVA_LIVE_SCENES=commute_board,...` (default all
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

**Quota (AGENTS.md hard rule):** every live Qwen session spends the owner's limited free quota. Prove what you can offline first; run live only with the owner's go for that run, with `NOVA_SPEND_QWEN_QUOTA=yes` set. Without it this tool refuses to open a Qwen session.
