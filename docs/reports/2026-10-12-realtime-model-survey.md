# End-to-end realtime voice models against the product's needs — 2026-10-12

Owner request 2026-10-12: "look for all model that suit our need". Research only: vendor documentation
read on 2026-10-12, no account, no key, no model call. Detail and every source URL per vendor:
[china-alibaba-baidu-iflytek-tencent](2026-10-12-realtime-survey/china-alibaba-baidu-iflytek-tencent.md),
[china-doubao-zhipu-stepfun-minimax](2026-10-12-realtime-survey/china-doubao-zhipu-stepfun-minimax.md),
[international-google-openai-azure-amazon-xai](2026-10-12-realtime-survey/international-google-openai-azure-amazon-xai.md).
"Not documented" means the pages read do not say, not that the feature is absent.

**Needs:** one model hears and speaks (streaming), function calling in the session (the car's tools),
Mandarin, reachable from mainland China, a custom voice (Ayano), latency, price/free quota, privacy.

## Ranking

| # | Model | Tools | Custom voice (Ayano) | Mainland China | Verdict |
|---|---|---|---|---|---|
| 1 | Alibaba `qwen3.8-omni-flash-realtime` | yes (repo: works) | yes, cloned (repo: Ayano) | yes (Beijing region; we use Singapore) | **fits** — the product model; owner's free quota 36.82K of 1M left |
| 2 | StepFun `stepaudio-2.5-realtime` / `stepaudio-3-realtime-preview` | yes, OpenAI-style realtime events | yes, clone 5-10 s clip (9.9 CNY); documented for 2.5 only | yes | **closest alternative**; 2.5: 10 / 70 CNY per 1M tokens in/out; 3-preview free for now. Untested here |
| 3 | Alibaba `qwen-audio-3.0-realtime-flash` / `qwen-audio-3.1-realtime-plus` | yes | cloning **Beijing region only** (voice-cloning page, read 2026-10-12) — not on the owner's Singapore account | yes (Beijing) | **partly** — 3.0-flash priced like 3.8; would need a Beijing account for Ayano |
| 4 | Alibaba `qwen3.5-omni-flash-realtime` | yes | yes | yes | **partly** — not in the owner's free list; audio out ~9x 3.8's price |
| 5 | Alibaba `qwen3.5-omni-plus-realtime` | crashes on `set_speaking_style` with other tools (repo) | yes (repo: Ayano) | yes | **partly** — owner's free quota used up; most expensive |
| 6 | ByteDance Doubao end-to-end realtime (豆包, the 豆包座舱 voice) | **not documented** (web search / RAG only) | yes, cloned | yes | **partly** — proprietary binary protocol; no documented function calling |
| 7 | Google `gemini-3.8-live` (+ `-extended-thinking`) | yes (repo: works) | no (cloning only on the TTS model) | **no** | **partly** — free test bench; free-tier inputs used for training |
| 8 | xAI Grok Voice | yes | custom voice IDs | not documented | **partly on paper** — China reach and Mandarin quality unknown |
| 9 | Zhipu `GLM-Realtime` Flash/Air | yes | **no** | yes | does not fit — no custom voice; ~2 min / 20 turns memory |
| 10 | Baidu `qianfan-realtime-flex-v1` | yes (public beta) | not documented | yes | does not fit now — beta; earlier measured issues (repo) |
| — | Alibaba `qwen3-omni-flash-realtime-2025-09-15` | **no** (feature table "--"; repo probe) | no | yes | does not fit |
| — | OpenAI `gpt-realtime-2.1` family, Azure OpenAI realtime | yes | sales-only custom voices | **no** (China not supported) | does not fit |
| — | Amazon Nova 2 Sonic | yes | — | — | does not fit — no Mandarin |
| — | Baidu Pro/Lite realtime, iFlytek 超拟人交互, MiniMax, Kyutai Moshi | no / not documented | — | — | does not fit |
| — | Tencent | — | — | — | could not be read (anti-bot page); searched six URLs |

## What it means

- **Keep Qwen 3.8 + Ayano for the product.** It is the only model that meets every need *and* is proven here.
  The obstacle is money, not fit: one demo take costs ~125K tokens and 36.82K are left free.
- **Test on Gemini 3.8 Live** (free, unlimited requests on the owner's tier). It cannot test Ayano's voice.
- **Worth one probe if Qwen's cost is a problem: StepFun realtime.** OpenAI-style events (our
  `OpenAiRealtimeClient` dialect seam fits), tools, cloning, China. Needs: account, a mock-server dialect test first,
  then one short live check of tool calls and a cloned Ayano.
- **Doubao** matches the reference video, but without documented function calling it cannot run the car.
