# Free end-to-end realtime voice models with a cloned voice: pick-list

Purpose: the owner asked (2026-10-13) for the end-to-end realtime speech-to-speech models that also give
**free usage** and accept a **custom/cloned voice inside the realtime session**, saved so one can be
picked later. Machine-readable copy: [config/voice_clone_free_models.yaml](../config/voice_clone_free_models.yaml)
(keep the two in step).

**Owner plan (2026-10-13): Gemini first, Ayano last.** Build and demo on Gemini (free, no clone)
until the work is done; then switch to Ayano. For that switch the recommended pick is
`qwen3.5-omni-flash-realtime` (free clone, its own free quota if the console still shows it, same
adapter after a re-clone and an allow-list entry), which keeps the qwen3.8 reserve for the final take. Qwen3.5-Omni documents "adjusting volume, speaking rate, and emotion through
instructions" (qwen-omni page, 2026-10-13), which the speaking styles rely on; not yet measured with Ayano (OPEN_PROBLEMS P55).

How to read it:
- Every fact carries a source key (see "Sources", all fetched 2026-10-13 with curl, no account, no key,
  no model call, no voice created). "unverified" = no page fetched today states it; "not readable" =
  page did not render for curl.
- Rows are sorted by how ready they are **in this repository**: works today -> re-clone (+ allowlist)
  -> dialect variant -> new dialect -> new client.
- Alibaba free quota is **per model** ("Free quota is independent per model", [AL-FREE]). The owner's
  account has spent most of the qwen3.8 and qwen3.5-omni-plus quota; any model never called may still
  hold its full quota: **per-model; check the console** (Model usage > Free Quota).
- The 90 days run from the later of "you activate Alibaba Cloud Model Studio" and "the model is released"
  ([AL-FREE], checked by the planner 2026-10-13). So an older model can have expired even if it was
  never called, while a model released recently has a fresh 90 days: the console is the truth.
- AGENTS.md still binds: tests never use `qwen3.8-omni-flash-realtime`; every live Qwen run needs the
  owner's go and `NOVA_SPEND_QWEN_QUOTA=yes`; creating a clone also needs the owner's go.

## Main table: free + clone + realtime

| # | Model id | Vendor | Free usage (documented) | Clone in realtime | Function calling | Mainland China | Repo status |
|---|---|---|---|---|---|---|---|
| 1 | `qwen3.8-omni-flash-realtime` | Alibaba | 1M tokens, 90 days, Singapore (intl site) [AL-PR]; China site: 100万 Token, Beijing only [CN-PR] | Yes, `target_model` [AL-VC] | Yes [AL-FC, 10-12] | Beijing region exists (separate key/site) | **Works today** (Ayano cloned). Final-demo only; owner quota nearly used |
| 2 | `qwen3.5-omni-plus-realtime` | Alibaba | 1M tokens, 90 days, SG [AL-PR]; 100万 Token, Beijing [CN-PR] | Yes [AL-VC] | Yes [AL-FC, 10-12] | Beijing price listed [AL-PR] | **Works today** (Ayano cloned). Owner quota used up |
| 3 | `qwen3.5-omni-flash-realtime` | Alibaba | 1M tokens, 90 days, SG [AL-PR] (China-site amount not extracted: unverified) | Yes [AL-VC] | Yes [AL-FC, 10-12] | Beijing price listed [AL-PR] | **Re-clone + allowlist**: same protocol; add to `VoiceCatalog.qwenModels` |
| 4 | `qwen-audio-3.0-realtime-flash` | Alibaba | 1M tokens, 90 days, SG [AL-PR]; 100万 Token, Beijing [CN-PR] | Yes, via a different clone API [AL-QA] | Yes; not together with web search [AL-QA] | Beijing price listed [AL-PR] | **Dialect variant + new clone path** |
| 5 | `qwen-audio-3.1-realtime-plus` | Alibaba | 1M tokens, 90 days, SG [AL-PR]; 100万 Token, Beijing [CN-PR] | Yes, as row 4 [AL-QA] | Yes [AL-QA] | yes | as row 4 |
| 6 | `qwen-audio-3.0-realtime-plus` | Alibaba | 1M tokens, 90 days, SG [AL-PR]; 100万 Token, Beijing [CN-PR] | Yes, as row 4 [AL-QA] | Yes [AL-QA] | yes | as row 4 |
| 7 | `stepaudio-3-realtime-preview` | StepFun | "限时免费" (free for a limited time, end date not stated) [SF-PR][SF-S3] | **unverified**: session `voice` takes clone ids [SF-RTC], but clone support is stated only for `step-audio-2` and `stepaudio-2.5-realtime`; clone costs 9.9 CNY/voice [SF-PR] | Yes [SF-S3][SF-RTG] | mainland endpoint `api.stepfun.com` | **New RealtimeDialect** |
| 8 | Doubao 实时语音模型 3.0 (Seeduplex), `model` = `1.2.6.1` | ByteDance Volcengine | A free quota exists ("免费额度可以抵扣…") but its amount/expiry is not on the page [VE-BILL]: amount **unverified** | Yes, "同时也支持复刻音色" [VE-3]; clone voice slot 138 CNY/voice (not free) [VE-BILL] | Yes (`tools`, `response.function_call_arguments.done`) [VE-3][VE-3G] | mainland only (`openspeech.bytedance.com`) | **New RealtimeDialect** (JSON events, own auth/keep-alive) |

Summary of what is actually free: Alibaba gives the realtime **tokens** free (per model, 90 days) **and**
1,000 free Omni voice creations within 90 days of activation (then USD 0.01 / 0.01 CNY each) [AL-VC][CN-VC].
StepFun gives the **model** free while in preview, but each clone costs 9.9 CNY. Doubao's free quota
size is undocumented and a cloned voice costs 138 CNY.

Older Doubao O2.0/SC2.0 (`1.2.1.1` / `2.2.0.0`, binary protocol on `/api/v3/realtime/dialogue`) also
take cloned voices but document no function calling [VE-O2]; superseded here by row 8.

## How to pick each one

### 1. qwen3.8-omni-flash-realtime — works today, final demo only
- Set: app developer settings model = `qwen3.8-omni-flash-realtime` (or `NOVA_QWEN_MODEL` in tools).
  Ayano's id for it is already in `QwenAppSettings.DEFAULT_CUSTOM_VOICES`.
- Free: 1M tokens per model, 90 days from activation/model release (later date), Singapore only on the
  intl site [AL-PR][AL-FREE]. Owner reports little left: check console.
- Caveat: never for tests (AGENTS.md); needs `NOVA_ALLOW_QWEN_38=yes` on the owner's per-run say.
  Bills both output audio and its text [AL-PR].

### 2. qwen3.5-omni-plus-realtime — works today
- Set: model = `qwen3.5-omni-plus-realtime`; Ayano already cloned for it.
- Free: 1M tokens SG [AL-PR]; owner's quota is used up. The dated snapshot
  `qwen3.5-omni-plus-realtime-2026-03-15` has its **own** free quota ([AL-FREE]: dated snapshots are
  independent models) but is not in the clone `target_model` list [AL-VC], so Ayano on the snapshot is
  **unverified** and the app's allowlist rejects it.
- Caveat: highest price (SG audio out USD 62/1M) [AL-PR].

### 3. qwen3.5-omni-flash-realtime — re-clone + allowlist
- Clone: `python tools/qwen-omni-voice/clone_voice.py create clip.wav --name ayano --model qwen3.5-omni-flash-realtime --yes --out voices.json`
  (the tool already allows this model; 10-20 s clip, <=60 s, mono, >=24 kHz [AL-VC]; one of the 1,000
  free creations).
- Repo change needed: add the id to `VoiceCatalog.qwenModels`
  (`ingress/.../realtime/VoiceCatalog.kt`), otherwise `QwenSettingsValidator` returns
  `QWEN_MODEL_INVALID`; then paste the voice id in developer settings. Same Qwen-Omni realtime
  protocol and endpoint as rows 1-2, so `QwenOmniDialect` is expected to work as-is (unproven).
- Free: 1M tokens SG, 90 days [AL-PR]. Earlier survey noted it did not show in the owner's free-quota
  list: check console.
- Caveat: SG audio out USD 17.7/1M, about 9x qwen3.8 [AL-PR].

### 4-6. qwen-audio-3.x-realtime-{flash,plus} — dialect variant + new clone path
- Endpoint: same `wss://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=…`,
  OpenAI-style events (`session.update`, `response.audio.delta`) [AL-QA].
- Differences from the Omni adapter: turn detection is `server_vad` / `smart_turn` / push-to-talk
  (the page does not mention `semantic_vad`, our default) [AL-QA]; voice settable only in the first
  `session.update`; web search and function calling cannot both be on; history max 50 audio turns /
  300 s [AL-QA, 10-12].
- Clone: [AL-QA] says set `target_model` to the qwen-audio realtime model in the "Voice Cloning API",
  which links to the CosyVoice/Qwen-Audio-TTS API (`model=voice-enrollment`, `action=create_voice`,
  audio by **public URL**, review status DEPLOYING/OK) [AL-VCD]. That page's own `target_model` text
  names only TTS models, and `clone_voice.py` speaks the Omni API (`qwen-voice-enrollment`, data URI):
  the tool needs a second mode. Clone price/free count for this API: **unverified**.
- Repo change: allowlist ids, map VAD choice, and extend the clone tool. Small, but a design choice
  (a variant of `QwenOmniDialect` vs a new dialect) is for the planner.
- Free: 1M tokens each, SG, 90 days [AL-PR]; China site 100万 Token each, Beijing [CN-PR].
- Price: 3.0-flash equals qwen3.8 (SG audio out USD 1.87/1M); plus models USD 24/1M [AL-PR].

### 7. stepaudio-3-realtime-preview (StepFun) — new RealtimeDialect
- Endpoint `wss://api.stepfun.com/v1/realtime`, Bearer key, `model` query/field; OpenAI-style events,
  `tools` with `function`, `retrieval`, `web_search` [SF-RTC][SF-RTG]. Voice cannot change mid-session.
- Clone: `POST https://api.stepfun.com/v1/audio/voices` with an uploaded file (purpose=storage, mp3/wav,
  5-10 s) and `model` one of `stepaudio-2.5-tts`, `step-tts-2`, `step-tts-mini` [SF-VOICE]; 9.9 CNY per
  voice, charged on success [SF-PR]. Whether a clone works on the 3-preview: **unverified** (test with one
  clone before relying on it).
- Free: model "限时免费"; the preview will be withdrawn and replaced by a paid version [SF-S3]; end date not
  stated. No new-account credit documented [SF-BILL]. Personal accounts need real-name verification; V0
  limits 5 concurrent / 100 RPM / 500K TPM [SF-RL].
- Repo: needs a new `RealtimeDialect` on `OpenAiRealtimeClient` (URL, auth header, session fields,
  event names), plus a clone tool. Also a second-vendor decision (ADR-013/017) — owner/ADR, not a wave.

### 8. Doubao 实时语音模型 3.0 / Seeduplex (Volcengine) — new RealtimeDialect
- Endpoint `wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue`, header `X-Api-Key`,
  `session.create`/`session.update`, `model` fixed `1.2.6.1`, output 24 kHz, pure JSON text frames with
  base64 audio [VE-3][VE-3G]. Must keep sending audio or `input_audio_mute.commit` [VE-3G].
- Clone: buy in 豆包声音复刻模型2.0, register per doc 6561/1305191 (Resource-Id `seed-icl-2.0`) [VE-O2];
  doc 1305191 itself was "not online" when fetched [VE-CLONE]. Voice slot 138 CNY (pre/post-paid,
  first 50) [VE-BILL]; trial voices deleted if unused 7 days [VE-BILL].
- Free: free quota mentioned, amount and expiry **unverified** (console). Paid: audio in 80, audio out
  300 CNY/1M tokens; QPM 60, TPM 100,000 [VE-BILL].
- Repo: event names are close to OpenAI's but auth, model, keep-alive differ: new `RealtimeDialect`
  (perhaps on `OpenAiRealtimeClient`, unproven). Second-vendor decision as row 7.

## Excluded and why

| Model / vendor | Why excluded | Source |
|---|---|---|
| `qwen3-omni-flash-realtime-2025-09-15` | Free 1M, but no function calling and not a clone `target_model` | [AL-PR][AL-VC]; 10-12 survey |
| `stepaudio-2.5-realtime`, `step-audio-2`, `step-1o-audio` | Clone yes, but paid (10/2/70 CNY per 1M; step-1o 25/5/60), no free usage documented | [SF-PR][SF-RTC] |
| Zhipu GLM-Realtime | Free tier not documented; no cloned voice | 10-12 survey (not re-fetched: unverified today) |
| MiniMax | No speech-to-speech realtime API | 10-12 survey (not re-fetched) |
| Baidu qianfan-realtime-flex-v1 | "音色替换" only; clone link and price pages not readable | 10-12 survey (not readable) |
| iFlytek 超拟人交互 | No documented function calling or clone | 10-12 survey |
| Tencent | Docs not readable (anti-bot) | 10-12 survey (not readable) |
| Google Gemini Live | Free tier exists, but no custom voice; not reachable from mainland | 10-12 survey (not re-fetched) |
| OpenAI gpt-realtime / gpt-live-1 | Custom voices sales-gated; no free tier documented; not in mainland | 10-12 survey (not re-fetched) |
| Azure OpenAI realtime | No custom voice in realtime found; no mainland | 10-12 survey |
| xAI Grok Voice | Custom voice id exists, clone docs 404; free tier not documented; no mainland | 10-12 survey |
| Amazon Nova Sonic | No Mandarin, no clone | 10-12 survey |

## Sources (fetched 2026-10-13)

- [AL-FREE] https://www.alibabacloud.com/help/en/model-studio/new-free-quota
- [AL-PR] https://www.alibabacloud.com/help/en/model-studio/model-pricing
- [AL-VC] https://www.alibabacloud.com/help/en/model-studio/qwen-omni-voice-cloning (Last Updated Oct 11, 2026)
- [AL-QA] https://www.alibabacloud.com/help/en/model-studio/qwen-audio-realtime-user-guides
- [AL-VCD] https://www.alibabacloud.com/help/en/model-studio/voice-clone-design-http-api
- [CN-FREE] https://help.aliyun.com/zh/model-studio/new-free-quota (free quota only in 华北2 北京 on the China site)
- [CN-PR] https://help.aliyun.com/zh/model-studio/model-pricing
- [CN-VC] https://help.aliyun.com/zh/model-studio/qwen-omni-voice-cloning
- [SF-PR] https://platform.stepfun.com/docs/zh/guides/pricing/details.md
- [SF-S3] https://platform.stepfun.com/docs/zh/guides/models/stepaudio-3-realtime.md
- [SF-RTC] https://platform.stepfun.com/docs/zh/api-reference/realtime/chat.md
- [SF-RTG] https://platform.stepfun.com/docs/zh/guides/developer/realtime.md
- [SF-VOICE] https://platform.stepfun.com/docs/zh/api-reference/audio/create-voice.md
- [SF-BILL] https://platform.stepfun.com/docs/zh/guides/pricing/intro.md
- [SF-RL] https://platform.stepfun.com/docs/zh/guides/pricing/rate-limits.md
- [VE-3] https://www.volcengine.com/docs/6561/2549778 (read via api/doc/getDocDetail; updated 2026-10-12)
- [VE-3G] https://www.volcengine.com/docs/6561/2549732 (接入必读; updated 2026-09-28)
- [VE-O2] https://www.volcengine.com/docs/6561/1594356 (updated 2026-09-28)
- [VE-BILL] https://www.volcengine.com/docs/6561/1359370 (计费说明; updated 2026-09-29)
- [VE-CLONE] https://www.volcengine.com/docs/6561/1305191 — not readable ("document is not in online")
- [AL-FC, 10-12] and "10-12 survey": [docs/reports/2026-10-12-realtime-survey/](reports/2026-10-12-realtime-survey/), read 2026-10-12, not re-fetched today
- Repo: `ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceCatalog.kt` (`qwenModels`),
  `app/src/main/kotlin/com/novadrive/app/QwenSettings.kt` (SG host, per-model voices, validator),
  `tools/qwen-omni-voice/clone_voice.py` (`ALLOWED_MODELS`, Omni clone API).
