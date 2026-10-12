# SURVEY-B: end-to-end realtime speech-to-speech models (Alibaba, Baidu, iFlytek, Tencent)

All pages read 2026-10-12 via curl (HTML stripped). No account, key or model API used.
Source keys:
- [RT] https://www.alibabacloud.com/help/en/model-studio/realtime (also zh: https://help.aliyun.com/zh/model-studio/realtime)
- [S2S] https://www.alibabacloud.com/help/en/model-studio/s2s-model (model/feature matrix)
- [FC] https://www.alibabacloud.com/help/en/model-studio/qwen-function-calling (section "Qwen-Omni-Realtime series")
- [PR] https://www.alibabacloud.com/help/en/model-studio/model-pricing
- [VC] https://www.alibabacloud.com/help/en/model-studio/qwen-omni-voice-cloning
- [QA] https://www.alibabacloud.com/help/en/model-studio/qwen-audio-realtime-user-guides
- [PV] https://www.alibabacloud.com/help/en/model-studio/privacy-notice
- [BF] https://cloud.baidu.com/doc/SPEECH/s/Wmtlcgi7c (Flex) ; [BP] https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei (Pro/Lite)
- [XF] https://www.xfyun.cn/doc/spark/sparkos_interactive.html
- repo = this repository (DECISIONS/ADR-002, ADR-013, ADR-017, docs/REALTIME_PROTOCOL_REFERENCES.md, owner facts in task packet)

Common to all Alibaba realtime models below (unless stated):
- Protocol: WebSocket `wss://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=...` (Singapore) or `...cn-beijing.maas.aliyuncs.com...` (Beijing); also WebRTC and AOQ. "Supported regions: Singapore, China (Beijing). Each region requires its own API key." [RT]
- Session: "A single WebSocket session can last up to 120 minutes." [RT]
- Privacy: "Alibaba Cloud strictly protects your data privacy and will never use your data for model training." Data from calls is stored per the related agreements. [PV]
- Free quota: Singapore only, 90 days from activation/model release, "No free quota is available in other regions." [PR]
- Latency: no number documented in [RT]/[S2S]/[QA]; [S2S] only says S2S latency is "Low -- single-model stream processing".
- SDK: DashScope Python >=1.26.5, Java >=2.22.15 (Java usable on Android, not an Android-specific SDK) [RT]; AOQ client SDK for Qwen-Audio [QA]. No Android SDK page found.
- Function calling protocol: tools in `session.update`, server emits `response.function_call_arguments.done`, client returns `function_call_output` then `response.create`; "The Qwen-Omni-Realtime series does not support the tool_choice and parallel_tool_calls parameters." [FC]

## 1. qwen3.8-omni-flash-realtime (current product model)
| # | Criterion | Finding |
|---|---|---|
| 1 | id / protocol | `qwen3.8-omni-flash-realtime`; needs workspace-specific endpoint [RT]. No dated snapshot listed in [PR]. |
| 2 | function calling | "Qwen3.8-Omni-Flash-Realtime, Qwen3.5-Omni-Plus-Realtime, and Qwen3.5-Omni-Flash-Realtime support custom Function Calling for voice conversations." [FC]; also remote MCP tools and web search [RT] |
| 3 | Mandarin | "Recognizes speech in 113 languages and dialects and generates speech in 36" [RT] |
| 4 | region | Singapore and Beijing (Beijing price listed) [RT][PR] |
| 5 | custom voice | Yes: clone 10-20 s audio via [VC] with `target_model=qwen3.8-omni-flash-realtime`; voice must be used with the same model. Repo: Ayano is cloned for this model. |
| 6 | latency | not documented |
| 7 | price | SG USD/1M tok: in text 0.23, in audio 0.93, out text 0.70, out audio 1.87; free 1M tok. Beijing: 0.212/0.848/0.636/1.696. Output audio AND its text are both billed. Audio tokens: in 7/s, out 12.5/s [PR][RT]. Owner: 36.82K of 1M left (repo). |
| 8 | privacy | see common |
| 9 | limits | 120 min session; history 100 audio turns / 600 s audio; 196,608 input tokens [RT] |
| 10 | SDK | see common |
Verdict: FITS (it is the product). Biggest blocker: free quota nearly gone; testing must move to a paid or other model.

## 2. qwen3.5-omni-plus-realtime (= snapshot qwen3.5-omni-plus-realtime-2026-03-15)
| # | Criterion | Finding |
|---|---|---|
| 1 | id | `qwen3.5-omni-plus-realtime`, "Currently equivalent to qwen3.5-omni-plus-realtime-2026-03-15" [PR] |
| 2 | FC | Supported [FC][S2S]. Repo: crashes on one specific tool when other tools are present. |
| 3 | Mandarin | yes (36 output langs, shared with 3.8) [S2S] |
| 4 | region | International (SG) price listed; Beijing price for 3.5 not found in the [PR] lines read -> not confirmed |
| 5 | voice | cloning `target_model` includes it [VC] |
| 6 | latency | not documented |
| 7 | price | SG USD/1M: in text/img 2.1, in audio 16.5, out text 12.4, out audio 62 (only audio billed for speech); free 1M [PR]. Repo: owner quota used up. ~33x the 3.8 audio-out price. |
| 9 | limits | 100 turns / 600 s audio history; 120 min [RT] |
Verdict: PARTLY. Blocker: tool crash (repo) plus highest price; quota exhausted.

## 3. qwen3.5-omni-flash-realtime (= qwen3.5-omni-flash-realtime-2026-03-15)
| # | Criterion | Finding |
|---|---|---|
| 1 | id | `qwen3.5-omni-flash-realtime`, equivalent to `-2026-03-15` snapshot [PR] |
| 2 | FC | Supported [FC][S2S]; not tested in repo |
| 3 | Mandarin | yes [S2S] |
| 4 | region | SG listed; Beijing not confirmed |
| 5 | voice | cloning `target_model` includes it [VC] (Ayano must be re-cloned for this model) |
| 6 | latency | not documented |
| 7 | price | SG USD/1M: 0.55 / audio in 4.5 / text out 3.3 / audio out 17.7; free 1M listed in [PR]; but repo: not in the owner's free-quota list (unexplained). |
| 9 | limits | 80 turns / 480 s audio history; 120 min [RT] |
Verdict: PARTLY. Blocker: about 9x the 3.8 output price and no free quota on owner's account; tool behaviour unproven.

## 4. qwen3-omni-flash-realtime-2025-09-15 (older snapshot)
| # | Criterion | Finding |
|---|---|---|
| 1 | id | `qwen3-omni-flash-realtime-2025-09-15`, WebSocket only in [S2S] |
| 2 | FC | [S2S] matrix: Function calling "--" (not supported). Not in the [FC] supported list. Repo: ignores tools in probe (consistent). |
| 3 | Mandarin | not checked in detail (multilingual) |
| 5 | voice | not in [VC] target_model list -> no cloned Ayano |
| 7 | price | SG USD/1M audio in 4.57, audio out 18.13 (other columns 0.52/0.94/1.99/3.67); free 1M; owner has 1M left (repo). Audio in and out 12.5 tok/s [PR][RT] |
Verdict: DOES NOT FIT. Blocker: no function calling, no cloned voice.

## 5. Qwen-Audio Realtime family (new finding; separate from Omni)
Ids: `qwen-audio-3.1-realtime-plus`, `qwen-audio-3.0-realtime-plus`, `qwen-audio-3.0-realtime-flash` [QA][S2S]. [S2S] recommends 3.1-plus for "Voice assistants and customer-service conversations" and 3.0-flash for "Cost-sensitive conversations".
| # | Criterion | Finding |
|---|---|---|
| 1 | protocol | same Realtime WebSocket (`...?model=qwen-audio-3.1-realtime-plus`), WebRTC, AOQ (AOQ recommended for weak networks, built-in AEC/noise suppression) [QA] |
| 2 | FC | "Qwen-Audio supports Function Calling, which lets the model decide when to invoke external tools" [QA]; matrix "Supported" for all three [S2S]. "web search and Function Calling cannot be enabled at the same time" [QA] |
| 3 | Mandarin | zh plus 20 Chinese varieties incl. Mandarin, Cantonese, Sichuan [QA] |
| 4 | region | SG (international) and China (mainland) prices both listed [PR] |
| 5 | voice | "Cloned voices: create a cloned voice using the Voice Cloning API (set target_model to qwen-audio-3.1-realtime-plus, ...-3.0-realtime-plus, or ...-3.0-realtime-flash)"; voice only settable in first `session.update` [QA]. Ayano would need re-cloning; which cloning API (Omni vs CosyVoice) not checked. |
| 6 | latency | "low-latency" only, no number |
| 7 | price | SG USD/1M: 3.1-plus & 3.0-plus in text 0.8, in audio 6.4, out text 6.4, out audio 24; 3.0-flash 0.23/0.93/0.7/1.87 (same as 3.8 omni); each 1M free (SG). Mainland: plus 0.688/5.501/5.501/20.628, flash 0.212/0.848/0.636/1.696 [PR] |
| 9 | limits | history max 50 audio turns / 300 s (default `max_history_turns` 20) [QA]; session length not stated in [QA] |
| 10 | SDK | AOQ client SDK; WebSocket samples Python [QA] |
Verdict: PARTLY to FITS (most promising alternative). 3.0-flash matches 3.8 price, has FC, cloning and a fresh 1M free quota (if on owner's list - unverified). Blocker: untested in repo; audio-only input (no video), shorter history; needs one offline/cheap probe of tool calling.

## 6. Baidu: qianfan-realtime-flex-v1 (端到端语音语言大模型 Flex)
| # | Criterion | Finding |
|---|---|---|
| 1 | id / protocol | `qianfan-realtime-flex-v1`, `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime?model={model}`, OpenAI-Realtime-style events; API Key or access_token [BF] |
| 2 | FC | "支持音色替换及Function Call" ; `tools` in session.update, `response.function_call_arguments.done`, `function_call_output` [BF]. "本接口处于公测阶段" (public beta) [BF]. Repo: Baidu resets per tool turn (ADR-013). |
| 3 | Mandarin | yes (Chinese vendor; repo used it in zh-CN) |
| 4 | region | mainland China only (aip.baidubce.com) |
| 5 | voice | "音色定制：支持音色替换" [BF]; 大模型声音复刻 product exists but its link to Flex not read (page JS-rendered). Repo: stock voice 4196 度清影. |
| 6 | latency | Pro/Lite page: user wait cut "至1秒左右" [BP]; Flex not documented |
| 7 | price | price / free-test pages JS-rendered, not readable -> not documented here |
| 8 | privacy | not documented in pages read |
| 9 | limits | not documented in pages read |
| 10 | SDK | "端到端语音语言大模型Android SDK" page exists (cloud.baidu.com/doc/SPEECH/s/Jmap3yxsx) |
Verdict: PARTLY. Blocker: a second vendor voice (ADR-013 one-voice rule), beta status, per-tool-turn reset (repo); ADR-013 already retires it.

## 7. Baidu Pro/Lite: audio-realtime(-near/-far), audio-mini-realtime(-near/-far)
Same endpoint [BP]. Function calling: repo says Lite/Pro "do not expose function calling" (ADR-002 / provider docs); [BP] only mentions a max-output-tokens field "包括工具调用". Latency claim ~1 s [BP].
Verdict: DOES NOT FIT. Blocker: no usable function calling.

## 8. iFlytek 超拟人交互 (sparkos interactive)
| # | Criterion | Finding |
|---|---|---|
| 1 | id / protocol | `wss://sparkos.xfyun.cn/v1/openapi/chat`, iFlytek frame protocol (header/parameter/payload, iat+nlp+tts blocks), not OpenAI-style; "端到端语音交互方案" [XF] |
| 2 | FC | not documented (only prompt and 个性化知识库 `cbm_knowledge`) [XF] |
| 3 | Mandarin | yes (Chinese voices) |
| 4 | region | mainland China |
| 5 | voice | stock `vcn` x5_lingxiaoyue_flow / x5_lingfeiyi_flow; a `res_id` field exists but its meaning is not described in the read text [XF] -> cloning not documented |
| 6 | latency | not documented; VAD gap default 800 ms (`vgap` 80) [XF] |
| 7 | price | "领取/购买授权包" only; no figure [XF] |
| 9 | limits | "单个连接最长可持续30分钟"; disconnect if no data 10 s after handshake [XF] |
| 10 | SDK | Python/Java demos; header has `os_sys: android`; 数字人 SDK doc [XF] |
Verdict: DOES NOT FIT. Blocker: no documented function calling, no cloned voice.

## 9. Tencent (混元 / 腾讯云 realtime voice)
Searched: cloud.tencent.com/document/product/1729 (Hunyuan), /1729/104753, /1729/97731, /647/110584 (TRTC), cloud.tencent.com/product/hunyuan, tencentcloud.com/document/product/647/62664, doc search. Every cloud.tencent.com page returned a JavaScript anti-bot challenge (same 29 KB body), tencentcloud.com returned empty text; GitHub search is not available in this session.
Result: NONE FOUND / could not verify. No public end-to-end speech-to-speech realtime API with function calling was confirmed. (TRTC "Conversational AI" is, by name, an ASR+LLM+TTS pipeline - not verified.)
Verdict: UNKNOWN / does not fit on current evidence. Blocker: docs unreadable without a browser; re-check manually.

## Summary
- Fits: qwen3.8-omni-flash-realtime (quota nearly gone).
- Best new candidate: qwen-audio-3.0-realtime-flash (same price as 3.8, FC, cloning, own 1M SG quota) and qwen-audio-3.1-realtime-plus (higher quality, ~13x price).
- Partly: qwen3.5-omni-flash-realtime, qwen3.5-omni-plus-realtime (tool crash, price), Baidu Flex (second voice, beta).
- Does not fit: qwen3-omni-flash-realtime-2025-09-15 (no FC, no clone), Baidu Pro/Lite (no FC), iFlytek (no FC documented). Tencent: unverified.
