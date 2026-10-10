# Qwen-Omni Realtime probe (ADR-017 gates Q-1 and Q-2)

A PC-side measurement tool. It is not part of the app. It measures Qwen-Omni Realtime with the stock
voice **Maia** against the 2026-10-08 Gemini + Xiaoyi numbers in
[ADR-017](../../DECISIONS/ADR-017-qwen-omni-realtime-end-to-end.md):

- Q-1: last word to first audio, a tool turn, and barge-in;
- Q-2: Maia WAV clips of the B-034 and SPEC-020 wait-cue lines for the owner to judge by ear.

Python 3.10+ and aiohttp only. HTTPS_PROXY is honoured.

## Environment

| Variable | Meaning |
| --- | --- |
| `DASHSCOPE_API_KEY` | Required. Sent only in the `Authorization: Bearer` header; never printed or written. |
| `DASHSCOPE_WORKSPACE_ID` | Required. The model needs the Singapore **workspace endpoint** `wss://<id>.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime`; `dashscope-intl` does not serve it. Never printed (`workspace=set`). Windows: `setx DASHSCOPE_WORKSPACE_ID <id>`, then open a new terminal. |
| `QWEN_WS_URL` | Optional full URL; overrides the workspace URL (the selftest uses it). |
| `MODEL` | Default `qwen3.8-omni-flash-realtime`. |
| `VOICE` | Default `Maia`. |
| `VAD` | `semantic` (default), `server` or `manual`. |
| `SILENCE_MS` | Default 800 (200..6000). |
| `PROMPT` | `app` (default: the app persona from PersonaProfiles.kt) or `none`. |
| `TOOL_MS` | Fake tool delay, default 300. |
| `BARGE_AFTER_MS` | Reply audio received before clip B starts, default 1500. |
| `CLIENT_CANCEL` | `1`: also send `response.cancel` on `speech_started`. |
| `SPEECH_DIR` | Default `tools/speech-harness/speech`. |
| `OUT` | Output dir: `runs.jsonl`, WAVs, `summary.json`. Default `tools/qwen-omni-probe/out` for `say`/`compare`. |
| `SHOW_TEXT` | `1` adds transcripts to the JSON line. Off by default. |

## Q-1 batch (PowerShell, from the repository root)

```powershell
$env:OUT = "tools\qwen-omni-probe\out"
.\tools\qwen-omni-probe\run_q1.ps1
```

which runs:

```powershell
$p = "tools\qwen-omni-probe\qwen_probe.py"
foreach ($c in "can_you_talk.pcm","ctx_too_hot.pcm","chat_q.pcm") { python $p latency $c 5 }
python $p tool live_weather.pcm
python $p tool ac_on.pcm
python $p text
for ($i=0; $i -lt 5; $i++) { python $p barge can_you_talk.pcm shut_up_zh.pcm }
$env:CLIENT_CANCEL = "1"
for ($i=0; $i -lt 5; $i++) { python $p barge can_you_talk.pcm shut_up_zh.pcm }
Remove-Item Env:CLIENT_CANCEL
python $p barge can_you_talk.pcm noise_cough.pcm
python $p barge can_you_talk.pcm noise_knock.pcm
python $p say                       # Q-2 WAVs + summary.json; --lines FILE for other lines
python $p compare
```

Offline check, no key: `python tools\qwen-omni-probe\qwen_probe.py --selftest` prints `SELFTEST OK`.

## Reading the output

Each run prints one JSON line (also appended to `<OUT>/runs.jsonl`): model, voice, vad, mode,
`t` (ms) and `errors` (error codes only, never messages).

- `latency`: `t` from the clip's last frame: `speech_stopped`, `response_created`, `first_audio`,
  `first_transcript`, `first_call` (+ `call_name`), `response_done`. `first_audio` is the number to
  set against "last word to first audio".
- `tool`: `followup_*_from_output` are from the moment `function_call_output` was sent;
  `spoken_result_audio` is the clip's end to the first audio of the spoken result.
- `text`: `accepted` true means a user text item produced audio; otherwise see `errors`.
- `barge`: `b_onset_to_speech_started`, `speech_started_to_done`, `last_audio_from_speech_started`
  (audio still arriving after the barge), `status` (`cancelled` = the server stopped the reply),
  `client_cancel_sent`. With the cough clip, `interrupted: true` means semantic VAD let a cough cut
  Maia off.
- `say`: `summary.json` has per line `duration_ms`, `first_audio_ms`, `verbatim` (bool) and `input`
  (`text`, or `clip_fallback` if text input was rejected).

`compare` prints p50 and max per metric, grouped by mode, VAD and CLIENT_CANCEL, followed by the
ADR-017 reference figures with their sources (chat last word to first audio 3.4-4.6 s; model first
output 0.54-0.83 s; Azure first audio 0.26-0.74 s).

## Rules

Synthetic clips and synthetic sentences only. A stock voice only (Maia); no voice cloning. Keep the
key out of files, URLs and logs.

**Quota (AGENTS.md hard rule):** every live Qwen session spends the owner's limited free quota. Prove what you can offline first; run live only with the owner's go for that run, with `NOVA_SPEND_QWEN_QUOTA=yes` set. Without it this tool refuses to open a Qwen session.
