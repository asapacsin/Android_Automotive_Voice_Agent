# Commute demo: 横琴创业谷 → 横琴镇, in the order of the 豆包座舱 review video

**Owner request, 2026-10-10:** follow the demo in the Weibo video
`https://weibo.com/tv/show/1034:5344494317404252` and build our own, simulating a usual trip such as
横琴创业谷 → 横琴镇. Its bar is [DEMO_REQUIREMENTS.md](DEMO_REQUIREMENTS.md): no laggy speech and no long
silence.

The video is 「荣威家越07，这回可厉害了！豆包座舱助手全新体验」, posted by 苑叔的V博 on 2026-09-18, 12:54 long. It
is not stored in this repository.

## What the video shows (its assistant segment, 04:26–05:41)

The presenter is parked, then drives with assisted driving and the map on screen. The assistant is used
only while parked; its subtitles give the dialogue.

| # | Driver | 豆包 | What it demonstrates |
| --- | --- | --- | --- |
| V1 | 豆包豆包 | 你好主驾 | wake word |
| V2 | 我上车之后你在跟我沟通的话，能不能说话的声音再稍微嗲一点 | 哎主人，人家知道了啦，以后跟你说话都会软软糯糯的哟 | a persistent speaking style |
| V3 | 帮我把主驾的这个座椅稍微调得高一点 | 人家这就帮你把座椅调高一点 | a seat action |
| V4 | 再调高一点 | 坐稳啦…人家再帮你调高一点点哦…坐好啦主人 | a follow-up resolved from context |
| V5 | 在车里的前风挡那儿有一只蚊子 | 有蚊子，好讨厌，人家帮你把主驾车窗打开一条小缝缝，它自己就会飞出去了 | a vague situation → an action |
| V6 | 没吹走 | 小蚊子还赖着不走…人家帮你把空调风力调大一点点…主人再等等哦 | a follow-up that picks a different action |
| V7 | 帮我放首歌，是那个谁，阿Sa的老公叫什么名我忘了，他有一首最近比较火的歌，叫什么闭目什么的 | 你说的是不是郑中基呀…他的《闭目入神》最近超火的呢…找到了，就是郑中基的闭目入神哦 | music from a vague description |

The presenter's point is "模糊的指令": she works out what is meant, acts, and can be corrected.

## Our version

The same order, as a short commute from 横琴·澳门青年创业谷 (WGS-84 22.13403, 113.53691, OpenStreetMap) to
横琴镇 (about 2.5 km). Driving is the emulator's route simulation. Each row is a line in
`tools/demo/recorder/make_clips.py`.

| Take | Clip | Driver | Expected (what makes it honest) | Video |
| --- | --- | --- | --- | --- |
| c1 board | `c_sweet` | 能不能说话再稍微嗲一点 | `set_speaking_style` sweet; tone only, the voice stays Maia (SPEC-015 FZ-11) | V2 |
| | `c_seat` | 帮我把主驾座椅稍微调高一点 | `control_seat` one step up, said after the car confirms it | V3 |
| | `c_seat2` | 再调高一点 | one more step, from `DriverContext` (FZ-05) | V4 |
| c2 depart | `c_nav` | 导航去横琴镇 | candidates on screen; the bubble stays while the list waits | — |
| | `c_pick` | 第一个 | routes appear | — |
| | `c_go` | 开始导航 | the emulated drive starts (`nav_started`), then 40 km/h | — |
| c3 mosq | `c_mosq` | 前风挡那儿有一只蚊子 | `run_scenario` mosquito: windows open halfway | V5 |
| | `c_mosq2` | 蚊子还没走 | **model-dependent:** no playbook covers it; a correct answer is a confirmed action (for example `control_climate` fan up, as in the video) or an honest question. Never a claim with no tool result | V6 |
| c4 music | `c_music` | 帮我放首歌，阿萨的老公唱的，最近很火那首，叫闭目什么的 (written 阿萨: the TTS spells 阿Sa out as letters) | `play_music` with 郑中基 / 闭目入神 worked out by the model (SPEC-017). She names the song only if playback is confirmed; otherwise 「已经让音乐 app 去找了」 | V7 |
| c5 ask | `c_traffic` | 前面堵不堵 | `query_live_info` route_traffic on the active route | — |
| | `c_weather` | 今天天气怎么样 | weather here from the session-start warm cache (SPEC-011 B4) | — |
| | `c_close` | 蚊子出去了，关上吧 | `run_scenario` mosquito_done: windows closed | — |
| c6 arrive | (`c_end` only if needed) | 结束导航 | the car arrives (`nav_arrived`); the wait is cut and marked ⏩ | — |

The wake word in the video (V1) is replaced by the recorder's wake broadcast before each line, as in every
earlier demo. Saying 「小诺」 is not part of this recording.

## How to record (owner's PC)

The cloud container cannot run the emulator. Prerequisites are the same as `QWEN-EMU-001`:
- the build from `claude/10-9`;
- `seed_qwen.py --accept-consent` run once;
- the Amap Web key set in the app (route traffic and weather need it).

```powershell
cd tools\demo\recorder
python make_clips.py                                  # adds the c_* driver lines (edge-tts)
python record_demo.py c1_board  commute_board         # restarts the app; sets the GPS fix to 创业谷 first
$env:KEEP=1                                           # the next takes keep the app and the drive running
python record_demo.py c2_depart commute_depart
python record_demo.py c3_mosq   commute_mosq
python record_demo.py c4_music  commute_music
python record_demo.py c5_ask    commute_ask
python record_demo.py c6_arrive commute_arrive
Remove-Item Env:KEEP
python check_req.py c1_board c2_depart c3_mosq c4_music c5_ask c6_arrive
foreach ($r in "c1_board","c2_depart","c3_mosq","c4_music","c5_ask","c6_arrive") { $env:NO_LAT=1; python compose.py $r "$r.mp4" }
$env:CUT="commute"; python final_cut.py commute_demo.mp4
```

- Record the takes back to back. The drive runs at 40 km/h (the slowest Amap allows), so the route lasts
  about 4 minutes; the arrival take raises it to 120 km/h.
- A take that fails `check_req.py` is re-recorded, from c1 if the drive has ended.
- Store the result in `android_doc/commute_demo_<date>/` with its README, never the logs (they hold
  `transcript=` lines).

## Recording in the cloud (no emulator)

`tools/demo/live/` runs the same six scenes in one session on the JVM:
- **Real:** the shipped voice stack (provider client, claim gate, core session, tool dispatch) against the live model, and live Amap weather.
- **Simulated:** the car, the 横琴 route and the music app.

It writes the recorder's artifacts, so `check_req.py` grades it unchanged, and `render.py` draws a video headed "JVM live run — not the app screen". See its README. A Qwen run needs `DASHSCOPE_API_KEY` and `DASHSCOPE_WORKSPACE_ID` in the environment's secrets.

### Cloud takes, 2026-10-10 (Qwen + Maia, JVM harness)

The owner approved two live sessions. Both ran the six scenes in one session each:

| | Take 1: qwen3.5-omni-plus-realtime | Take 2: qwen3.8-omni-flash-realtime |
| --- | --- | --- |
| `check_req.py` | PASS, 2.4–3.1 s | FAIL `c_sweet` 3.3 s (limit 3.0); the rest 2.5–3.2 s |
| 嗲一点 | agreed, no `set_speaking_style` call (style stayed default) | `set_speaking_style` sweet, held for the whole drive |
| 蚊子还没走 | re-ran mosquito, said 「车窗又开了一半」 with nothing changed | said 「那再开大点窗」 and called **nothing** |
| vague song | heard 「RSA」 (the clip's TTS spelled 阿Sa out) | wrong artist (陈伟霆); the reply stayed honest |

Fixed after the takes, with offline tests (not re-recorded; that needs another approved session):
- The mosquito scenario answers `already_open` when the windows are already half open (74d8ef8).
- The claim gate now treats a verb-first window promise with a bare 「窗」 (「开大点窗」「关窗」) as a claim, so
  that reply is no longer released when no tool ran.
- The `c_music` clip is written 阿萨.

Still open: the style turn is a tool call plus a second response, about 0.33 s per network hop from the
cloud container to DashScope. On the owner's PC in China the hops are shorter; measure it there before
deciding anything.

## Pass criteria

- `check_req.py` passes every take:
  - chat and style turns: ≤ 3.0 s;
  - every tool turn: ≤ 3.5 s;
  - no reply over 5 s;
  - no `reply_underrun` over 300 ms.
- Only Maia is heard. Amap's own guidance voice is the one known exception until SPEC-021's guidance
  prompt exists (ADR-017).
- Every action she announces has a matching `ok=true` tool result in the log.
- The video has no unmarked silence over 1.5 s (DEMO_REQUIREMENTS §3).

## Known risks before the first take

- `c_mosq2` and `c_music` depend on the model's choice. Record what she does; a wrong but honest answer
  is a finding, a claim with no result is a defect.
- With no music app on the emulator, the music hand-off cannot be confirmed, so she should say it was
  handed off, not that it is playing.
- The weather line uses the GPS fix the recorder sets before the app starts. A take started without the
  fix (KEEP=1 on a fresh app) asks a cold Amap lookup and may exceed 3.5 s.
- 横琴镇 is a town, so the place search may list several candidates; 第一个 takes the top one.

**Quota (AGENTS.md hard rule):** every live Qwen session spends the owner's limited free quota. Prove what you can offline first; run live only with the owner's go for that run, with `NOVA_SPEND_QWEN_QUOTA=yes` set. Without it this tool refuses to open a Qwen session.
