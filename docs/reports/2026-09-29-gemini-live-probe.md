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


## Third round (same day): what makes the Gemini path slow, by component

Tool: `tools/gemini-live-probe/latency_probe.py`. It uses the app's own 12 tool declarations
(`app/src/test/resources/golden/baidu_session_update.json`, the catalogue both adapters share), the
app persona plus `CALL_FIRST_HINT`, the `Kore` voice, and the recorded 16 kHz clips streamed in
real time. It answers each tool call with an `ok=true` result after 300 ms. Times are from the end
of the driver's utterance. Cloud container, not the phone. Samples are small, and they are counts,
not distributions.

| # | Fact |
| --- | --- |
| F23 | **`models/gemini-3.8-live` (same key, no thinking) calls tools at once.** Spoken commands: 20 of 20 called the right tool, 0.78–2.38 s after the end of speech (median 1.24 s). The call came before any speech, with no filler turn and no `interactionStatus`. The first audio of the spoken result came 2.0–5.9 s after the end of speech (median 3.5 s; includes the 300 ms simulated execution). Typed commands: 3 of 3 called, 0.86–1.97 s. Refusals were honest: 「抱歉，这个操作我还做不了」 for volume, and no invented news |
| F24 | **`gemini-3.8-live-extended-thinking`, same setup, 12 spoken commands:** 1 tool call, at 31.7 s. 7 runs: a filler turn, `interactionStatus: IN_PROGRESS`, then a second reply about 20 s later with **no call**. The 2 of those that were transcribed both reported a failure that never happened (「抱歉，系统出现故障，未能成功打开空调」). 4 runs: no reply within 45 s (these 4 ran as 4 concurrent sessions). The same model's earlier runs (second round) called at 7–11.5 s. So `IN_PROGRESS` does **not** predict that a call will come |
| F25 | **Correction to F21: the transcript arrives ahead of its audio.** Over 45 reply turns (both models, spoken and typed), every `outputTranscription` chunk arrived before the audio it transcribes. The smallest lead was 240 ms and the median 1.7 s; a reply's first chunk led by 1.2–2.6 s. Short replies arrive as one transcript chunk, in the same message as the first audio chunk. F21 is right that the chunks are interleaved with the audio; they are not behind it. Measured as (audio received) − (characters received × that turn's ms per character); 203–344 ms per character, median 247 |
| F26 | **Client activity detection is not faster.** `automaticActivityDetection.disabled` with `activityStart`/`activityEnd` sent by the client 300 or 600 ms after the speech ended: calls came 1.35–2.68 s after the end of speech (6 of 6), no better than server VAD (F23). Server VAD reported `ACTIVITY_END` 0.4–1.2 s after the speech ended |
| F27 | `gemini-3.8-live` **rejects `thinkingLevel`**: 1007 "Thinking level is not supported for this model." It accepts no `thinkingConfig`, or `thinkingBudget: 0`. The app's setup always sends `thinkingLevel`, so selecting this model today would fail at setup |
| F28 | Both models send empty `{}` frames between content messages. `GeminiLiveProtocol.parse` already yields an empty message for them |
| F29 | Long replies stream at real-time pace on both models: 「你好，简单介绍一下你能做什么」 got 8.6–9.8 s of audio delivered over 8.6–9.8 s. So holding a reply until it is complete costs its whole length, whichever model is used |
| F30 | `behavior: NON_BLOCKING` on `gemini-3.8-live` is accepted at setup. In 1 run it called `delegate_task` at 1.05 s without speaking first. After the `WHEN_IDLE` result it said it was looking the answer up, and did not speak the result within 20 s. Native delegation on this model is **unproven** |
| F31 | `gemini-3.8-flash` `streamGenerateContent`: HTTP 503 on all 6 attempts today, so the latency of a background thinking model is **not measured** |
| F32 | `gemini-3.8-live` with the older 2-tool probe setup: a cough and a knock during a reply did not interrupt it (1 run). Barge-in was **not measured**: in 1 run the two utterances merged into one input turn, and in 1 run there was no reply at all |

**Corrections after independent review (same day):**

- F23: 19 spoken auto-VAD runs are recorded in files; the 20th was a key-inspection run, also a
  call, at 935 ms. Over the 19, the median is 1.26 s and the first-audio median is 3.4 s.
- F24: 10 command runs are recorded in files, plus 2 runs printed to the console. The gap before
  the second reply was 0.7–24 s, not "about 20 s".
- F25 is a uniform-rate estimate, sampled only when a transcript chunk arrives; the first-chunk
  lead was 1.24–3.16 s.
- F26: `ACTIVITY_END` came 0.42–1.33 s after the speech ended.
- **F29 is wrong for `gemini-3.8-live`:** its audio arrives at about 4.4x speaking speed.
  `generationComplete` comes right after the last audio; `turnComplete` comes about the reply's
  playback length later. Extended thinking delivered at 1.0x.
- **F33:** 0 of 8 spoken commands on `gemini-3.8-live` sent a late second call within 15 s after
  the spoken result.

What this round may claim: the order of events and the relative latency of the two models from a
cloud container. What it may not claim: anything about the phone (network, echo, cabin noise), or
the quality of either model's answers beyond the transcripts shown.
