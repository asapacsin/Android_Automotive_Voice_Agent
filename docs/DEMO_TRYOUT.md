# Tryout: a first-time driver in their own words

Owner, 2026-10-13: "a demo that records a real user experience; I don't want to find out on my own
try that the app doesn't work." Every live run before this used the exact demo script
([DEMO_COMMUTE.md](DEMO_COMMUTE.md)). A real first try uses other phrasings, changes its mind,
interrupts her, asks for what the car cannot do, and mumbles. This scene runs those paths against the
live model end to end on the JVM harness ([tools/demo/live/README.md](../tools/demo/live/README.md)).
It is not device evidence (ACCEPTANCE_TESTS.md): no Android UI, no AEC, a simulated car, route, music
app and no telephony.

## The lines

One session, in this order; the car is parked at the origin until `u_go`. Times are from his last
audible word to her first audible reply (as `check_req.py` measures them).

| # | key | driver says | expectation the grader checks |
| --- | --- | --- | --- |
| 1 | u_hello | 你好，你叫什么名字 | chat: a reply, no tool, ≤ 3.0 s |
| 2 | u_can | 你能帮我做什么 | chat (ability answer): reply, no tool, ≤ 3.0 s |
| 3 | u_hot | 车里好热啊 | a cooling action: control_climate or run_scenario ✓, ≤ 3.5 s |
| 4 | u_temp | 空调调到二十二度 | control_climate ✓; simulated climate target 22 at the end of the turn |
| 5 | u_window | 把我这边的窗户开一点 | control_window ✓; the driver's window (FRONT_LEFT) opened and no other window moved |
| 6 | u_sunroof | 帮我把天窗打开 | honest refusal: no ✓ tool, a reply, no done-claim (a claim-gate drop is a WARN, "caught") |
| 7 | u_gas | 附近有加油站吗 | navigate_to or query_live_info, ✓ or honest; reply ≤ 3.5 s |
| 8 | u_cancel | 算了，不去了 | flow cancelled: exit_navigation_mode ✓ or navigation phase IDLE/STOPPED; a reply |
| 9 | u_dest | 导航去长隆海洋王国 | navigate_to ✓ with candidates (prefetched real search) |
| 10 | u_pick2 | 第二个 | choose_navigation_option ✓ (index 2 when recorded) |
| 11 | u_go | 好，出发吧 | navigation started (choose_navigation_option ✓, nav_started, or phase NAVIGATING) |
| 12 | u_eta | 还要开多久 | a reply; no claim; any tool used is ✓ |
| 13 | u_music | 来点轻松的音乐 | play_music ✓ (handed off) |
| 14 | u_barge | 等一下 | said 1.0 s into her playing reply: her audio stops within 1.0 s of his speech start (`playback_flush` / Interrupted) |
| 15 | u_mstop | 音乐先关了吧 | control_music ✓ (stop or pause when recorded) |
| 16 | u_mumble | 嗯……那个…… | no ✓ tool and no claim; a short reply or none |
| 17 | u_call | 给我老婆打个电话 | place_call with an honest result, or an honest refusal; never a claim of calling without ✓ |
| 18 | u_navoff | 不用导航了 | exit_navigation_mode ✓ |
| 19 | u_bye | 好了，谢谢 | a reply ≤ 3.0 s; no tool except `end_conversation` (a goodbye may end the conversation, as the persona asks for 没事了-style closings; the JVM harness answers it NO_ACTIVE_SESSION) |

Every line also: some reply within 5.0 s (except u_mumble); no session error (`state=ERROR`,
`*_PROTOCOL_ERROR`, `error=`, three or more reconnects); a reply the claim gate dropped for an
unproven claim is a WARN with the line; no silence longer than 20 s inside a turn once she started.

A done-claim without a tool is detected from her reply text in `state.json` with a small pattern
per line (u_sunroof, u_call, u_eta, u_mumble); the grader never prints the text.

## How to run

```bash
PIPER_MODEL=/path/zh_CN-huayan-medium.onnx <venv>/bin/python tools/demo/live/make_clips_cloud.py tryout
NOVA_LIVE_DEMO=1 NOVA_LIVE_PROVIDER=gemini NOVA_LIVE_SCENES=tryout NOVA_LIVE_OUT=/tmp/tryout \
  ./gradlew --offline :app:testDebugUnitTest --tests '*LiveDemoRun*'
python3 tools/demo/live/tryout_check.py /tmp/tryout
```

Rehearse on Gemini first (AGENTS.md); a Qwen take needs the owner's go for that run. The grader
prints `key  verdict  time  tools  note` per line, then `RESULT PASS|FAIL <keys>` and `WARN <keys>`;
exit code 0 only on PASS. `--selftest` checks it against synthetic fixtures offline.
