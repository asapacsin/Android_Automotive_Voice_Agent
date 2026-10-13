# SURVEY-A: end-to-end realtime speech-to-speech models, ByteDance / Zhipu / StepFun / MiniMax
All sources read on 2026-10-12, without an account and without any API call. "nd" = not documented in the pages I read (I did not guess).

## 1. ByteDance Volcano Engine: Doubao end-to-end realtime voice model (豆包端到端实时语音大模型)
Source: https://www.volcengine.com/docs/6561/1594356. The page is rendered by JavaScript, so I read the Markdown through
https://www.volcengine.com/api/doc/getDocDetail?DocumentID=1594356&LibraryID=6561&type=online (page last updated 2026-09-28).
| # | Criterion | Finding |
|---|---|---|
|1| Model id / protocol | `wss://openspeech.bytedance.com/api/v3/realtime/dialogue`. This is a **proprietary binary-framed** protocol with numbered events (StartSession 100, ConversationCreate 510, ...). It is not OpenAI-Realtime compatible. `dialog.extra.model` takes `1.2.1.1` (O2.0) or `2.2.0.0` (SC2.0). The older O and SC versions are no longer maintained |
|2| Function calling | **nd.** The doc mentions no tools or function calls. It offers built-in web search, "external RAG input" (25.09.11 / 25.09.22), text queries (ChatTextQuery) and a "user query exit" signal (26.03.07) |
|3| Mandarin | "支持中文和英语两大语种". Dialect output (dongbei, sichuan, yue, beijing, ...) is available only on the 4 premium voices |
|4| China / abroad | Domestic endpoint (openspeech.bytedance.com). Access from abroad: nd |
|5| Custom voice in realtime | Yes. SC2.0 and, since 26.03.07, O2.0 accept cloned voices (`saturn_`/`S_`). You buy them in the Doubao voice-clone 2.0 product and register them per doc 6561/1305191 (Resource-Id seed-icl-2.0, model_type 4). The doc strongly recommends sending the clip's transcript with the clip. Clip length: nd here |
|6| Latency | "低延迟" only, no figure. Default end-of-speech window 1500 ms, configurable from 500 ms |
|7| Price / free quota | nd on this page (billing is TPM/QPM based; separate pricing page not read) |
|8| Data / training | nd |
|9| Limits | 60 StartSession per minute per AppID by default, plus TPM. Idle sessions are dropped after 10 min (45000003). Up to 20 QA rounds can be injected as context |
|10| SDKs / Android | Python, Go and Java demos (Java added 25.11.24). Android SDK: nd. Audio is PCM 16k int16 in 20 ms packets, or Opus |
**Verdict: partly fits.** It has a strong Mandarin voice and clone support, but the protocol is proprietary.
Biggest blocker: no documented function calling, so the claim gate and the navigation tools could not be driven by tool calls.
Next step: read the clone doc 1305191 and the pricing page.

## 2. Zhipu: GLM-Realtime (Flash / Air)
Source: https://docs.bigmodel.cn/cn/guide/models/sound-and-video/glm-realtime
| # | Criterion | Finding |
|---|---|---|
|1| Model id / protocol | `wss://open.bigmodel.cn/api/paas/v4/realtime`. Event names follow the **OpenAI Realtime** set (session.update, input_audio_buffer.append, response.function_call_arguments.done, ...). The example session shows `"model":"glm-realtime"`; the product tiers are GLM-Realtime-Flash and GLM-Realtime-Air. Exact model strings per tier: nd |
|2| Function calling | **Yes.** "GLM-Realtime 进一步支持 Function Calling 功能". `tools` is "工具定义触发 Function Calling，目前只支持语音通话" |
|3| Mandarin | Chinese in and out (default voice tongtong). No quality figures |
|4| China / abroad | Domestic endpoint open.bigmodel.cn. Abroad: nd on this page |
|5| Custom voice | **No.** Only a fixed list of voices (xiaochen, tongtong, female-tianmei, ...). No cloning documented |
|6| Latency | No figure ("通过流式推理降低视频通话延时") |
|7| Price | Flash: audio 0.18 CNY/min. Air: audio 0.3 CNY/min (video costs more). Free quota: nd |
|8| Data / training | nd |
|9| Limits | Call memory "长达 2 分钟". Audio context 8K tokens, "预计 20 轮". Max 1K output tokens. Concurrency 5 to 20 by account tier |
|10| SDKs | Python, Go and TypeScript Realtime SDKs plus a front-end sample. Android: nd. Has a far-field noise-reduction option |
**Verdict: does not fit as the main session (could only be a cheap fallback).** It has tools and the OpenAI event set, but only stock voices.
Biggest blocker: no custom/cloned voice (we need Ayano), and only 2 minutes / about 20 turns of context.
Also seen: GLM-4-Voice (https://docs.bigmodel.cn/cn/guide/models/sound-and-video/glm-4-voice) is an end-to-end voice model at 80 CNY per million tokens with 8K context. Its page describes an HTTP chat call, not a realtime WebSocket. Tools: nd.

## 3. StepFun: Step-Audio realtime family
Sources: https://platform.stepfun.com/docs/api-reference/realtime/chat ; https://platform.stepfun.com/docs/zh/guides/models/stepaudio-3-realtime.md ; https://platform.stepfun.com/docs/zh/guides/pricing/details.md ; https://platform.stepfun.com/docs/zh/api-reference/audio/create-voice.md
| # | Criterion | Finding |
|---|---|---|
|1| Model id / protocol | `wss://api.stepfun.com/v1/realtime`, Bearer key. Models: `stepaudio-3-realtime-preview`, `stepaudio-2.5-realtime`, `step-1o-audio`, `step-audio-2`, `step-audio-2-mini`, `step-audio-r1.5`. Event names are **OpenAI-Realtime style** (session.update, input_audio_buffer.*, conversation.item.create, response.*) |
|2| Function calling | **Yes.** `tools` accepts types `function` and `retrieval`, with built-in web_search. StepAudio 3: "Voice Agent 可以在意图明确后调用工具或执行后端任务，任务运行期间对话仍可继续" |
|3| Mandarin | Chinese-first vendor. StepAudio 3 claims full duplex, backchannel detection and thinking while speaking. No numeric quality claims |
|4| China / abroad | Domestic api.stepfun.com. Abroad: nd |
|5| Custom voice | **Yes.** The session `voice` accepts a cloned voice id from 音色复刻: a 5 to 10 s mp3/wav clip uploaded with purpose=storage, costing 9.9 CNY per voice. For stepaudio-2.5-realtime the clone is made with the StepAudio 2.5 TTS model. step-audio-2 also supports cloning. Whether stepaudio-3-realtime accepts clones: nd |
|6| Latency | "极低延迟" only, no figure. Server VAD silence default is 100 ms |
|7| Price (CNY per million tokens, input / cached input / output) | stepaudio-3-realtime-preview: **free for a limited time** (the preview will be withdrawn and replaced by a paid version). stepaudio-2.5-realtime: 10 / 2 / 70. step-audio-2: 10 / 2 / 70. step-1o-audio: 25 / 5 / 60. New-account free quota: nd |
|8| Data / training | nd |
|9| Limits | Session length: nd. Rate limits are on a separate page, not read. Audio format pcm16 only. Voice cannot change mid-session |
|10| SDKs | Open-source Step-Realtime-Console demo (web). Android: nd |
**Verdict: fits best of the four.** It has OpenAI-style realtime events, function tools and cloned voices in session, so it is the closest to our Qwen seam.
Biggest blocker: clone support is confirmed only on stepaudio-2.5-realtime / step-audio-2, and the free StepAudio 3 is a preview that will be withdrawn.
Needs a voice-quality check of an Ayano clone (5 to 10 s clip) before any commitment.

## 4. MiniMax
Source: https://platform.minimaxi.com/docs/llms.txt (the full docs index). The reference page download was reset by the proxy.
| # | Criterion | Finding |
|---|---|---|
|1-10| all | **No end-to-end realtime speech-to-speech model found.** The index lists only ASR (speech-to-text), TTS (HTTP, WebSocket and bidirectional-streaming WebSocket T2A), voice cloning and voice design, async TTS, and music. Nothing in it is audio-in to audio-out. Searched the index for 实时对话 / realtime / omni / 语音通话 and found only the "实时播报" TTS line. Voice clones expire unless used within 7 days and require identity verification |
**Verdict: does not fit as an end-to-end model.** It could serve only as a TTS voice in a cascaded design, which ADR-017 rules out.
Biggest blocker: no speech-to-speech realtime API exists.

## Cross-cutting gaps (none of the pages read stated these)
Data and training terms, access from abroad and Android SDKs were undocumented for all three realtime vendors. Each needs its privacy policy read before a choice.
