# Qwen-Omni voice cloning tool

`clone_voice.py` turns a short clip into a custom voice for Qwen-Omni Realtime, so the app can use it
instead of the stock voice Maia. A cloned voice works with **one** omni model only, so make one per
model: `qwen3.5-omni-plus-realtime` for tests and `qwen3.8-omni-flash-realtime` for the final demo.

Source: Alibaba Model Studio, "Qwen-Omni voice cloning",
https://www.alibabacloud.com/help/en/model-studio/qwen-omni-voice-cloning (read 2026-10-11).

## Rights

Only a voice you own or have the speaker's permission for: a voice you designed yourself, or speech
recorded by someone who agrees. Never a real person's, singer's, actor's or game/anime character's
voice without consent. No songs or singing.

## Audio requirements

WAV (16-bit), MP3 or M4A; mono; sample rate at least 24 kHz; 10-20 s recommended, 60 s maximum;
under 10 MB; clear speech with no music, noise or other voices. To convert:
`ffmpeg -i in.mp3 -ac 1 -ar 24000 -sample_fmt s16 -t 15 out.wav`

## Steps

Set `DASHSCOPE_API_KEY` and `DASHSCOPE_WORKSPACE_ID` (Singapore region) in your environment. The tool
never prints them.

1. `python tools/qwen-omni-voice/clone_voice.py check clip.wav` (offline).
2. `python tools/qwen-omni-voice/clone_voice.py create clip.wav --name nova_voice --model qwen3.5-omni-plus-realtime --model qwen3.8-omni-flash-realtime --yes --out voices.json`
   Prints one `<model> <voice id>` line per model. Each creation uses one of the 1000 free
   creations (90 days); failed creations are not billed.
3. Paste each id into the app's developer settings field for that model.
4. Listen test, one short text-only session on the cheap model (never 3.8 for a test, see AGENTS.md):
   `MODEL=qwen3.5-omni-plus-realtime VOICE=<id> NOVA_SPEND_QWEN_QUOTA=yes python tools/qwen-omni-probe/qwen_probe.py say`

Other commands: `list`, `delete VOICE`, and `selftest` (offline, against a local mock server).
