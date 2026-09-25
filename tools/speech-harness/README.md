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
# When using `powershell -File`, prefer a single string (CJK-safe):
powershell -File tools\speech-harness\harness.ps1 -StepList "launch,nav_route:十字门,sleep:12,tap:推荐,nav_start:emulator"
```

`make_speech.py` needs `edge-tts`, `truststore` and `ffmpeg` on the build PC; the phrases are
harmless test commands.

**Linux / cloud.** `pip install edge-tts truststore imageio-ffmpeg`; the generators use `ffmpeg` on
PATH, else the static binary from `imageio-ffmpeg`. `run_scenarios.py` and `measure_false_wake.py`
resolve adb as `$ADB`, else `adb` on PATH, else the Windows SDK path. With several devices or
emulators attached, pick one with `ANDROID_SERIAL=emulator-5556` — adb itself honours it, so no
flag is needed. Run `make_speech.py`, `make_extra.py`, `make_context.py` and `make_noise.py` once;
clips land in `speech/` (gitignored).

## What it established (2026-09-17)

- Baidu server VAD ignores speech peaking below ~2700 and hears ~3800 → `MicInputGain` (3x).
- One long session: empty replies and actions executed a turn late from about the third tool
  turn; one conversation per command: 8/8 → `ConversationResetPolicy`.

## The scenario suite (SPEC-008)

`harness.ps1` drives a list of steps and prints a log for a person to read. `run_scenarios.py`
runs the declared scenario set and says **pass or fail**, so the same checks can be repeated after
a change instead of re-read.

```powershell
python tools\speech-harness\run_scenarios.py                 # once, all scenarios
python tools\speech-harness\run_scenarios.py --repeat 5      # flakiness as a rate
python tools\speech-harness\run_scenarios.py --only S3 S11   # just these
```

Scenarios live in `scenarios.json` as data: what the driver says, which clip carries it, and what
must (or must not) appear in the app's own log afterwards. Assertions are on **tool calls and
state, never on the reply's wording** — the model rewords freely, and every attempt to match
phrasing in this project has lost.

Exit code is non-zero if any scenario fails, so it can gate a release.

**It spends quota**, one Baidu turn per scenario. Run it before a release and after a change to the
turn machinery; the simulation benchmark is the one for every build.

### If you add a scenario

Check it fails for the right reason before trusting it. Point an `expect` at something that cannot
happen and confirm the run goes red — the runner's own negative control was done exactly that way,
and two of the first twelve assertions turned out to be testing a mechanism rather than a result.

## False wake measurement (B-012)

```powershell
python tools\speech-harness\measure_false_wake.py --minutes 16
```

Leaves the wake engine listening with the car's own music playing and counts how many times it
wakes. A false accept is worse than a missed wake: the assistant interrupts a driver who never
asked for it.

It also reports whether the engine was **alive at the end**, and that line matters more than the
count — an engine that quietly stopped listening reports zero false accepts too. The first run said
`engine started False` and the measurement was discarded; the cause turned out to be the script
clearing logcat *after* launching, so the evidence of a healthy engine had been thrown away rather
than never produced.

This one spends no Baidu quota. It does hold the microphone and play audio, so run it when the
phone is somewhere the noise does not matter.

