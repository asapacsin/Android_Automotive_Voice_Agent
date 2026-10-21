# TTS audition (B-034 / ADR-016)

The owner uses this to hear candidate Chinese TTS voices read 小诺's own sentences, before any of
them is integrated. It is not part of the app and is never packaged.

```powershell
$env:OUT = 'D:\桌面\android_doc\tts_audition'
$env:VOLC_APP_ID = '...'; $env:VOLC_ACCESS_TOKEN = '...'; python tools/tts-audition/audition.py volc
$env:MINIMAX_API_KEY = '...';  python tools/tts-audition/audition.py minimax
python tools/tts-audition/audition.py minimax-design      # new synthetic voices from a description
$env:BAIDU_TTS_AK = '...'; $env:BAIDU_TTS_SK = '...'; python tools/tts-audition/audition.py baidu
python tools/tts-audition/audition.py volc <voice_id> ... # other ids from the vendor's voice list
```

- Python 3 standard library only. Keys come only from the environment and are never printed or
  written.
- Each voice is one row, `{vendor, voice, ms, file | error}`, printed and saved in
  `summary-<vendor>.json`. A rejected voice id does not stop the rest.
- `MINIMAX_HOST=api.minimax.io` uses MiniMax's international endpoint (default: `api.minimaxi.com`).
- Stock or designed voices only: never clone or imitate a real character's or voice actor's voice.
