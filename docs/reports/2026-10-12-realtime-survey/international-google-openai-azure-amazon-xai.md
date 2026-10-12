# SURVEY-C: End-to-end realtime speech-to-speech models outside Alibaba (read 2026-10-12)

Method: I fetched official docs through the proxy and stripped the HTML. I made no sign-up, used no key and called no model API.
"ND" means the fetched docs do not say. "NC" means I did not check it in this pass. Facts marked "repo" come from the task packet.

## 1. Google Gemini Live API (gemini-3.8-live, gemini-3.8-live-extended-thinking)
| # | Finding |
|---|---|
| 1 | `gemini-3.8-live` is the default Live model (Stable). `gemini-3.8-live-extended-thinking` is the high-reasoning audio-to-audio model. Legacy: `gemini-3.1-flash-live-preview`, `gemini-2.5-flash-native-audio-preview-12-2025` (2.5 access is limited to past users). Separate: `gemini-3.5-live-translate-preview` (translation only) and `gemini-3.5-transcribe-live` (speech to text only). Protocol: stateful WSS. Input PCM16 16 kHz; output PCM16 24 kHz. [models, live] |
| 2 | Yes. The Live docs have a "Tool use" section [live nav]. Our harness has working tools (repo). |
| 3 | Yes. zh-Hans and zh-Hant are among the 99 Live languages. Native-audio models choose the language themselves, with no language code [live-guide]. |
| 4 | No. Mainland China is not on the available-regions list ("China" gets 0 hits; Taiwan is listed) [available-regions]. Google is blocked in China (repo). There is no China-region offering. |
| 5 | Not usable in Live as documented. "Voice replication" (source clip plus a spoken consent statement) is shown only with `model: gemini-3.8-flash-tts`. I found no Live model id on that page [voice-replication]. The owner already rejected Google stock voices (B-034, repo). |
| 6 | No figure for 3.8 Live. "Sub-second native audio streaming" is claimed for the 2.5 Live model [models]. Repo: free-tier median 2.3 s to first audio, worst case 11.6 s. |
| 7 | Paid tier per 1M tokens: audio in $3.00 (about $0.005/min), audio out $12.00 (about $0.018/min), text in $0.75, text out $4.50. A free tier exists [pricing]. Owner's console: unlimited RPM/RPD, 65K TPM (repo). The public rate-limits page does not list the Live models. |
| 8 | Free tier: "Used to improve our products: Yes". Paid tier: No [pricing]. The Unpaid Services terms allow human review and say not to submit personal data [terms]. |
| 9 | Without compression, audio sessions last 15 min. A connection lasts about 10 min, and session resumption or context compression makes the session unlimited. Context window is 128k tokens for native audio [live-session, live-guide]. Concurrency: ND. |
| 10 | GenAI SDK (Python/JS) and raw WebSockets; ephemeral tokens for client-to-server; LiveKit, Pipecat and Fishjam integrations [live]. Android Kotlin SDK: NC. Our app already has a Gemini adapter (repo). |
Sources: ai.google.dev/gemini-api/docs/{models,live,live-guide,live-session,pricing,rate-limits,available-regions,voice-replication,terms}.
**Verdict: partly fits.** Tools and Mandarin work, and the free tier is generous. Biggest blockers: no access from mainland China, no custom voice in Live, and free-tier data is used for training.

## 2. OpenAI Realtime API (gpt-realtime-2.1, -2.1-mini, gpt-realtime-2, gpt-realtime-1.5, gpt-live-1)
| # | Finding |
|---|---|
| 1 | Realtime models listed: GPT-Live 1 ("premier ... expressive voice conversations"), GPT-Realtime-2.1 and 2.1 Mini (reasoning with tool use), GPT-Realtime-2, GPT-Realtime-1.5, GPT-Realtime Mini (deprecated). Transport: WebRTC, WebSocket or SIP [models, realtime guide nav]. |
| 2 | Yes. The 2.1, 2.1 Mini and 2 models are listed as "with tool use", and the guide has "Tools and MCP". GPT-Live has "Delegation and tools" [models]. |
| 3 | Mandarin: the model pages are rendered by JavaScript, so I could not extract a language list (ND). |
| 4 | No. China and Hong Kong are not on the supported-countries list (0 hits) [supported-countries]. |
| 5 | "Custom voices" work with the Realtime API but are "limited to eligible customers. Contact our sales team". They need a consent recording plus a sample [custom-voices]. Whether a zh consent phrase exists: ND (extraction gave nothing). |
| 6 | ND (no number found). |
| 7 | gpt-realtime-2.1 audio: in $32, cached in $0.40, out $64 per 1M tokens. 2.1-mini audio: in $10, cached in $0.30, out $20. gpt-live-1: $0.05/min, billed per second, with backend and tool usage charged separately [pricing]. Free tier: ND. |
| 8 | API data: abuse-monitoring logs are kept for up to 30 days by default. Zero Data Retention is available [your-data]. |
| 9 | A Realtime session lasts at most 60 min [realtime-conversations]. Concurrency: ND. |
| 10 | WebRTC (usable on Android), WebSocket, SIP, Agents SDK. No official Android SDK named: ND. |
Sources: platform.openai.com/docs/{models,pricing,guides/realtime,guides/realtime-conversations,guides/custom-voices,guides/your-data,supported-countries}. The openai.com pricing and help pages returned 403.
**Verdict: does not fit.** Strong tool use, but it is unsupported in China and custom voice needs sales approval. Biggest blocker: no service in mainland China.

## 3. Microsoft Azure OpenAI / Foundry realtime
| # | Finding |
|---|---|
| 1 | Same OpenAI models hosted by Azure: gpt-realtime (2025-08-28 GA), gpt-realtime-mini (2025-10-06, 2025-12-15), gpt-realtime-1.5 (2026-02-23), gpt-realtime-2 (2026-05-07), gpt-realtime-2.1 and 2.1-mini (2026-07-07). Input 32k tokens, output 4,096 [Azure models]. gpt-live-1 is not listed. |
| 2 | Yes, the same Realtime protocol (WebRTC/WebSocket) [realtime-audio-quickstart]. |
| 3 | ND. |
| 4 | Global regions only. The China (21Vianet) docs URL returned 404, and I found no realtime model offered in Azure China. |
| 5 | ND for Azure custom voice in realtime. |
| 6–7 | ND (I did not fetch the Azure pricing page). |
| 8 | Azure enterprise terms; NC. |
| 9 | ND. |
| 10 | Foundry SDKs plus WebRTC; NC for Android. |
Sources: learn.microsoft.com/en-us/azure/ai-foundry/openai/{concepts/models,realtime-audio-quickstart}; docs.azure.cn 404.
**Verdict: does not fit.** Same models as OpenAI, with no China-region realtime model found. Biggest blocker: no mainland availability or custom voice.

## 4. Amazon Nova Sonic / Nova 2 Sonic (Bedrock)
| # | Finding |
|---|---|
| 1 | `amazon.nova-2-sonic-v1:0` (Nova 2 Sonic) and Nova Sonic (v1). Protocol: Bedrock bidirectional streaming API with JSON events [nova2 userguide]. |
| 2 | Yes: function calling, RAG, and asynchronous tool handling while it keeps speaking. |
| 3 | No. Nova 2 voices cover English (US, UK, IN, AU), French, Italian, German, Spanish, Portuguese and Hindi. v1 covers five languages. No Chinese. |
| 4 | Bedrock China regions: NC. Not material because there is no Mandarin. |
| 5 | ND (built-in voices only are documented). |
| 6 | "Low-latency"; no number. |
| 7 | The Bedrock pricing page renders prices with JavaScript, so I could not extract them. |
| 8 | NC. |
| 9 | Connection limit of 8 min, renewable with session continuation. |
| 10 | AWS SDKs; Android: ND. |
Sources: docs.aws.amazon.com/nova/latest/userguide/speech.html, nova2-userguide/using-conversational-speech.html, sonic-getting-started.html.
**Verdict: does not fit.** Biggest blocker: no Mandarin.

## 5. xAI Grok Voice (Speech to Speech)
| # | Finding |
|---|---|
| 1 | `grok-voice-latest`, an alias of `grok-voice-think-fast-2.0`. WSS `wss://api.x.ai/v1/realtime` with OpenAI-style events (session.update). |
| 2 | Yes: function, mcp, web_search, x_search and file_search tools. |
| 3 | "Chinese (Simplified) zh" is in the language-hint list for input transcription. Mandarin speech output quality: ND. |
| 4 | ND in the fetched pages; x.ai returned 403. No China-region offering found. |
| 5 | The `voice` field accepts "a built-in voice or a custom voice ID", and there is a "Custom Voices" doc. Its URL 404'd, so cloning requirements are ND. |
| 6 | "Sub-second" latency. |
| 7 | $0.08/min ($4.80/hr) for speech to speech. Free tier: ND. |
| 8 | ND (data-privacy URL 404). |
| 9 | Session resumption can be enabled; history is dropped after 30 min of inactivity. Max session and concurrency: ND. |
| 10 | Python/JS samples; ephemeral tokens for client apps; codecs pcm, pcmu, pcma, opus. Android SDK: ND. |
Sources: docs.x.ai/docs/guides/voice, guides/voice/agent, pricing.
**Verdict: partly fits on paper.** Tools, zh input and custom voice IDs exist. Biggest blocker: no documented mainland China access and unverified Mandarin output.

## 6. Open-source / self-hosted
- Kyutai Moshi (`kyutai/moshiko-pytorch-bf16`): full-duplex, about 200 ms in practice, language English, CC-BY. The model card says it "cannot access tools" [huggingface.co/kyutai/moshiko-pytorch-bf16]. The GitHub repo returned 403.
- I searched for no other open-source candidates in this pass. Open-weight Qwen-Omni variants belong to Alibaba and are out of scope here.
**Verdict: does not fit.** Biggest blocker: English only and no tool use.

## Overall
Only Alibaba Qwen-Omni (current) meets the hard requirement of mainland access plus a Mandarin custom voice. Of the others, Gemini 3.8 Live and Grok Voice come closest technically, but both fail on China reachability.
