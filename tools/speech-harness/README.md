# Speech harness (SPEC-004 A-live)

Drives the **real** voice pipeline on the phone without a person speaking: synthetic Mandarin
speech is injected into the live Baidu Flex session in place of the microphone, and the result is
read from the app's debug log (transcripts, tool calls, navigation and vision events).

- Debug builds only (`DebugToolReceiver` lives in the debug source set; injection is a no-op on
  non-debuggable builds).
- **Every `say:` / `speak:` step is a real Baidu call and spends quota.** Camera questions also
  spend Qianfan vision quota.
- It bypasses the microphone, so it proves the software path, not acoustics. Room noise, distance
  and echo still need a human test.

## Use

```powershell
python tools\speech-harness\make_speech.py          # writes speech\*.pcm (16 kHz mono PCM16)
adb push tools\speech-harness\speech\ac_on.pcm /sdcard/Android/data/com.novadrive.app/files/test_speech/ac_on.pcm
tools\speech-harness\harness.ps1 -Steps @("launch","start","say:ac_on","say:temp_up","stop") -Wait 10
```

`make_speech.py` needs `edge-tts`, `truststore` and `ffmpeg` on the build PC; the phrases are
harmless test commands.

## What it established (2026-09-17)

- Baidu server VAD ignores speech peaking below ~2700 and hears ~3800 → `MicInputGain` (3x).
- One long session: empty replies and actions executed a turn late from about the third tool
  turn; one conversation per command: 8/8 → `ConversationResetPolicy`.
