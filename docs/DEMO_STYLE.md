# Demo: speaking style, before and after

What the owner asked for (2026-10-13): the speaking-style function visibly changing — on the screen
(说话风格) and in her delivery — before 「嗲一点」, after it, and back after 「正常一点」.

Run: `NOVA_LIVE_SCENES=style` (tools/demo/live/README.md). One session, car parked.

| key | driver says | expected |
| --- | --- | --- |
| s_mood1 | 你今天心情怎么样 | baseline chat reply; style default |
| s_sweet | 能不能说话再稍微嗲一点 | set_speaking_style ✓ style=sweet; style sweet |
| s_mood2 | 你今天心情怎么样 | the same question, now sweet |
| s_seat | 帮我把主驾座椅稍微调高一点 | control_seat ✓; still sweet |
| s_mood3 | 你今天心情怎么样 | still sweet two turns later |
| s_normal | 正常一点，别嗲了 | set_speaking_style ✓ style=default; style default |
| s_mood4 | 你今天心情怎么样 | back to baseline |

Grader: `tools/demo/live/style_check.py RUN`. Every reply within 5.0 s, no session error. A turn
"sounds sweet" (GRADER v3, decider 2026-10-20) against s_mood1 (tools/demo/live/prosody.py) when the
median F0 rose ≥ +15 % alone, or rose ≥ +8 % and also F0 spread ≥ +2.0 st or pace ≤ −10 %. Pitch is
necessary; spread and pace only confirm it. Why: spread and pace swing as much between two default turns
as the thresholds — s_mood4 vs s_mood1 spread −1.7..+2.4 st, pace 0..−13 %, while F0 stays within +4 % —
so the old "2 of 3" rule could grade a default turn sweet on spread and pace alone. Thresholds unchanged.
The CI style summary carries `grader=v3`. s_mood2 and s_mood3 must sound sweet, s_mood4 must not; s_seat is
reported only. Sweet wording markers (嘛 呀 啦 哟 呢 人家 哦) are counted for information.

Reference (planner, earlier Gemini commute runs): default ≈ 205 Hz, 7 st, 7.5 字/秒; right after a
sweet switch ≈ 245 Hz, 12 st, 5.9 字/秒, then fading back — the fade is what s_mood3 catches.

A pass is JVM live-harness evidence, not device evidence (ACCEPTANCE_TESTS.md).
