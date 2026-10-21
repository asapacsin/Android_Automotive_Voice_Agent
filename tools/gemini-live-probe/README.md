# Gemini Live probe tools

Measurement scripts behind [docs/GEMINI_LIVE_ARCHITECTURE.md](../../docs/GEMINI_LIVE_ARCHITECTURE.md).
They talk to the real Gemini Live API (`BidiGenerateContent`) from a PC or cloud session. They are
not part of the app and are never packaged.

The key comes **only** from the environment variable `GEMINI_API_KEY`. It is sent in the
`x-goog-api-key` header, never in a URL, and never printed. Requires Python 3 with `aiohttp`, and
honours `HTTPS_PROXY`.

```bash
# text turn; optional setup overrides (dotted keys), turns, toolResponse scheduling
python live_probe.py '{"generationConfig.thinkingConfig": {"thinkingLevel": "LOW"}}' '["帮我查一下明天杭州的天气怎么样"]' WHEN_IDLE

# real-time mic simulation with the speech-harness 16 kHz recordings: [[start_ms, file], ...] seconds [setup overrides]
LVL=LOW python audio_probe.py '[[300,"intro_q.pcm"],[6500,"shut_up_zh.pcm"]]' 16

# which setup fields the model accepts
python opt_probe.py

# per-component latency with the app's own tools and persona (one JSON line per run; times from the
# end of the utterance; transcript text only with SHOW_TEXT=1). MODEL, LVL, VAD=auto|manual,
# HANGOVER_MS, PROMPT=app|persona|none; a clip of the form "text:..." sends a typed turn instead.
MODEL=models/gemini-3.8-live python latency_probe.py ac_on.pcm 30

# B-034 voice A/B: same lines per prebuilt voice -> <voice>.wav + median pitch + summary.json
OUT=voice_ab python voice_ab.py                 # default candidate list
OUT=voice_ab python voice_ab.py Leda Erinome Kore
```

Output contains synthetic test transcripts only. Do not point these tools at recordings of real
drivers (I-8). The model id defaults to `models/gemini-3.8-live-extended-thinking` (override:
`MODEL=`).
