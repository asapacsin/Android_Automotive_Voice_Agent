# JVM live demo (cloud container)

The commute demo of [docs/DEMO_COMMUTE.md](../../../docs/DEMO_COMMUTE.md) recorded **on the JVM**, for
containers that cannot run the emulator. The shipped provider client (Qwen-Omni Maia, or Gemini to
validate the harness), the core session, the app's uplink gate and the app's tool dispatch
(`AndroidToolDispatcher`, climate/window/seat, comfort scenarios, speaking style, music hand-off,
`query_live_info` on the real Amap REST client) talk to the **live model**. The car, the route
(`HengqinWorld`, real-time 40 km/h drive, simulated route traffic) and the music app are simulated.
It is **not** the app: no Android UI, no listening lifecycle or local picker intercept, no AEC, no
Amap SDK. A pass here is not device evidence (ACCEPTANCE_TESTS.md).

With `AMAP_WEB_KEY` set, the place search is the app's real one (`LiveDestinationCandidateSource`).
Its network time is taken out of the take (OPEN_PROBLEMS P53): the real Amap result for 横琴镇 is
fetched once before the session opens (`livedemo_search prefetched=true count=N`) and answers
「导航去横琴镇」; the candidates are real. Any other query still searches live.

Env: `NOVA_LIVE_DEMO=1` (otherwise the test is skipped), `NOVA_LIVE_OUT=<dir>` (required),
`NOVA_LIVE_PROVIDER=gemini|qwen` (default gemini: Gemini first, Ayano last, AGENTS.md 2026-10-13), `NOVA_QWEN_MODEL` (required for qwen; use `qwen3.5-omni-plus-realtime` — `qwen3.8-omni-flash-realtime` is refused unless `NOVA_ALLOW_QWEN_38=yes` on the owner's explicit say), `NOVA_LIVE_SCENES=commute_board,...` (default all
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

**Rehearse first, cheaply:** run on Gemini (`NOVA_LIVE_PROVIDER=gemini`, no Qwen quota; the harness uses the oldest working Live model, `gemini-3.1-flash-live-preview`; `NOVA_GEMINI_MODEL=gemini-3.8-live` only when the older one cannot show the point) only the scenes the change touches (`NOVA_LIVE_SCENES=...`), once, and grade them; open the one Qwen take only when the rehearsal is clean. Gemini costs too (AGENTS.md): no repeated runs to chase latency variance. Until the owner says the work is done, a voice test also runs on Gemini and says so (Gemini first, Ayano last); after that, a cloned-voice test reads the lines as text turns in one short Qwen session.

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

## The tryout scene (`NOVA_LIVE_SCENES=tryout`)

A first-time driver trying the app in their own words, not the polished script
([docs/DEMO_TRYOUT.md](../../../docs/DEMO_TRYOUT.md)): 19 lines `u_*` in one session (about 5 min),
parked at the origin until `u_go`, then 40 km/h towards 长隆海洋王国. Chat, an ability question, a
vague 「车里好热啊」, a refusal the car cannot do (天窗), a change of mind (「算了，不去了」), a barge-in
(`u_barge` is said 1.0 s into a playing reply after `play_music` was called; `livedemo_barge
playing=<bool>`), a mumble and a phone call (the harness has no telephony: `place_call` answers
honestly). It runs alone, without the drive scene's speech rules; the tool records in `state.json`
also carry the graded, non-personal arguments (`index`, `action`, `window`, ...).

**Prefetch, disclosed:** with `AMAP_WEB_KEY` the real Amap results for 横琴镇, 长隆海洋王国 and 加油站
are fetched before the session opens (one `livedemo_search prefetched=true` line each) and answer any
place query containing one of them; the candidates are real, only the cloud's network time is taken
out. `query_live_info` and any other query still go to Amap live.

```bash
PIPER_MODEL=/path/zh_CN-huayan-medium.onnx <venv>/bin/python tools/demo/live/make_clips_cloud.py tryout
NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini NOVA_LIVE_SCENES=tryout NOVA_LIVE_OUT=/tmp/tryout \
  ./gradlew --offline :app:testDebugUnitTest --tests '*LiveDemoRun*'
python3 tools/demo/live/tryout_check.py /tmp/tryout      # one row per line; exit 0 only on PASS
python3 tools/demo/live/tryout_check.py --selftest        # offline fixtures
```

The u_* clips are Piper (`zh_CN-huayan-medium`, one speaker) slowed to 0.88 so the driver sounds
lower than the g_* guidance voice; edge-tts is refused from the cloud (403).

## The style scene (`NOVA_LIVE_SCENES=style`)

The speaking style before, after 「嗲一点」 and back ([docs/DEMO_STYLE.md](../../../docs/DEMO_STYLE.md)): one
session, parked, no drive speech rules, run alone (not in the default scene list). The same question
(s_mood1..4) is asked before, after the switch, after a seat action, and after 「正常一点」. Tool calls
record their `style` argument in state.json (with the tryout's argument keys).

```bash
PIPER_MODEL=.../zh_CN-huayan-medium.onnx python3 tools/demo/live/make_clips_cloud.py style   # s_* clips
NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini NOVA_LIVE_SCENES=style NOVA_LIVE_OUT=/tmp/style \
  ./gradlew --offline :app:testDebugUnitTest --tests '*LiveDemoRun*'
python3 tools/demo/live/style_check.py /tmp/style     # graded; writes /tmp/style/prosody.json
python3 tools/demo/live/render.py /tmp/style /tmp/style.mp4
```

`prosody.py RUN` measures any run (median F0, F0 spread in semitones, CJK characters per voiced second,
per turn) and writes `prosody.json` (numbers only, never text); `render.py` shows those numbers under
each reply when the file exists, and in the style scene the change against s_mood1.
