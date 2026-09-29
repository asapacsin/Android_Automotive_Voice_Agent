# Gemini 3.8 Live Extended Thinking: API probe, 2026-09-29

What was measured, from where, and what it may claim. Tools: `tools/gemini-live-probe/`.

- **Where:** a cloud container through its HTTPS proxy, **not the phone and not from mainland
  China**. Latencies include that path. Nothing here is device evidence (ACCEPTANCE_TESTS.md
  level: protocol/L2-equivalent observation).
- **Key:** `GEMINI_API_KEY` in the session environment (value never printed).
- **Model:** `models/gemini-3.8-live-extended-thinking`, found in the key's `models.list`
  (`bidiGenerateContent` only; input 131072 tokens, output 65536). Related models on the same
  key: `gemini-3.8-live`, `gemini-3.8-flash` (`generateContent`), `gemini-3.8-flash-tts`.
- **Audio input:** the repository's synthetic 16 kHz PCM16 recordings from
  `tools/speech-harness/speech/`, streamed in 20 ms chunks at real-time pace, with silence between
  them. There was no echo path: the reply audio was not played into the "microphone".
- **Samples:** one to two runs per row. These are indicators, not distributions.

| Probe | Result |
| --- | --- |
| Setup without `thinkingConfig` | closed 1007 "Thinking level must be specified for this model." |
| `thinkingLevel` MINIMAL / LOW / MEDIUM / HIGH | MINIMAL rejected (1007); others accepted |
| Text turn 「你好，用一句话介绍你自己。」 LOW | `setupComplete` 112 ms; first audio 573 ms after sending the turn; `audio/pcm;rate=24000`; unprompted `sessionResumptionUpdate` (resumable) at 188 ms |
| Text turn, MEDIUM / HIGH | complete in ≈5.2–5.4 s total, similar to LOW; no thought parts returned |
| NON_BLOCKING `delegate_task`, 「帮我查一下明天杭州的天气怎么样」, result after 3 s, `WHEN_IDLE` | filler audio at 591 ms (「正在为您查询杭州的天气情况」); `turnComplete` + `interactionStatus: IN_PROGRESS` at 3.9 s; `toolCall(kind=search)` at 7.5 s; result spoken once, content = payload plus 「出门记得带把伞哦」; `IDLE` at 20.6 s |
| Same with `INTERRUPT` | same shape; `toolCall` at 10.1 s; result spoken once |
| Audio 「导航到万达」, blocking `navigate_to`, result after 1 s | reply audio 1.0 s after end of speech: filler 「好的，马上为您查询路线」; `toolCall` 6.0 s after end of speech; 「已为您开启前往万达广场的导航」 only after the result |
| Audio 「你好，简单介绍一下你能做什么」 then 「闭嘴」 during the reply | reply at 0.77 s after end of speech; `interrupted` 394 ms after 「闭嘴」 onset; the model then answered 「好的，我先退下了，有需要随时唤我」 |
| Same question, then a cough (2.1 s) and a knock (1.9 s) during the reply | no `interrupted`; reply completed |
| 「导航到」 + ~300 ms gap + 「万达」, default settings | one turn (transcript 「导航到万达。」); `toolCall` 10.7 s after end of speech |
| Same with `automaticActivityDetection.silenceDurationMs = 1200` | one turn; `toolCall` 5.5 s after end of speech |
| Setup fields accepted | `automaticActivityDetection` {silenceDurationMs, prefixPaddingMs, start/endOfSpeechSensitivity LOW, disabled}, `activityHandling: NO_INTERRUPTION`, `contextWindowCompression.slidingWindow`, `sessionResumption`, `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`, `thinkingConfig.includeThoughts` |
| Setup fields rejected | `proactivity`, `enableAffectiveDialog`: 1007 unknown field |
| `tools: [{googleSearch: {}}]` | 1011 "You exceeded your current quota, please check your plan and billing details" |
| `functionDeclarations[].parameters` with the app's JSON Schema (`additionalProperties`) | 1007 "Unknown name additionalProperties" |
| Same schemas as `parametersJsonSchema` | accepted unchanged (`additionalProperties`, `minLength`/`maxLength`, `enum`, `number`) |
| An explicit `behavior: "BLOCKING"` | 1007 "BLOCKING function calls are not supported for this model"; omitting `behavior` is blocking |
| Server events observed | `setupComplete`, `sessionResumptionUpdate`, `serverContent` {`modelTurn`, `inputTranscription`, `outputTranscription`, `interrupted`, `generationComplete`, `turnComplete`, `interactionStatus`}, `toolCall`. **No speech-started/stopped event.** Not observed (not triggered): `toolCallCancellation`, `goAway` |

Not measured, and needed: everything on the phone (reachability from its network, echo
self-interruption with AEC3 at a 24 kHz render rate, cabin noise), tool-call latency
distributions, behaviour after a resumption reconnect, transcript/audio ordering, and
multi-tool-turn degradation. See G-M1…G-M7 in the architecture document.


## Second round (same day): the app's own client against the real API

`GeminiLiveSmokeTest` (opt-in: `NOVA_GEMINI_LIVE_SMOKE=1`, key from the environment) drives
`GeminiLiveClient` with the app's real persona, all 12 catalogue tools and `CALL_FIRST_HINT`,
streaming the recorded 16 kHz clips in real time with local onset/offset. Cloud container, not the
device. Times are from the start of the clip (clips are 1.9 s long).

| Clip | Heard first (held/dropped by the claim gate?) | Tool call | Spoken result |
| --- | --- | --- | --- |
| 「空调打开」 | 「马上为您打开空调」 — held and **dropped** (claim before proof) | `control_climate{power_on}` at 7.0 s | 「空调已经打开了，当前温度24度」, released after the result |
| 「风量调大」 | 「好的，风量调大」 — held and dropped | `control_climate{adjust_fan, 1}` at 9.8 s | 「风量已经调大了」 |
| 「导航到万达」 | 「正在为您查找万达广场」 — released at its turn end (not a claim) | `navigate_to{万达}` at 11.5 s | 「找到3个地点，请说第几个或直接点选」 |
| typed 「导航到万达广场」 | 「正在为您规划路线」 released | `navigate_to` at 9.3 s | 「找到3个万达广场，请说第几个或点选」 |
| typed 「把空调打开」 (3 runs) | 1 run: call at 6.1 s. 2 runs: 「这就为您打开空调」 (dropped), then **no tool call at all** and 「抱歉，系统出现故障，空调未能成功打开」 — a failure that never happened | — | — |

Setup with the full app configuration was accepted every time (0.8–1.1 s to `setupComplete`).

| # | Fact |
| --- | --- |
| F19 | **Correction to F16.** The server does send `voiceActivity {type: ACTIVITY_START / ACTIVITY_END, audioOffset}` (ACTIVITY_START about 0.35 s after onset; ACTIVITY_END together with the input transcription, about 1 s after the speech ended). The first probe only logged the fields it knew |
| F20 | Even with `CALL_FIRST_HINT`, spoken commands still got a filler turn first, then `turnComplete` with `interactionStatus: IN_PROGRESS`, then the call in a later turn 5–9 s after the end of speech. The Python probes without the hint (earlier, same day) saw calls 7.7–31 s after the end of speech; with the hint, 3/3 navigation runs called before speaking, at 8.5–27 s |
| F21 | Reply audio arrives at real-time pace (0.16–0.64 s chunks), with `outputTranscription` chunks interleaved, not ahead of the audio (independent review, two text turns) |
| F22 | Typed turns are less reliable than spoken ones: 2 of 3 typed 「把空调打开」 runs never called the tool and then reported a failure. Spoken turns called the tool 3/3 |

Encoding note for anyone repeating this: the container's JVM default charset is ASCII, so Chinese
text passed through an environment variable reaches the model as `?????`. Run with
`LC_ALL=C.UTF-8` and `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8`.
