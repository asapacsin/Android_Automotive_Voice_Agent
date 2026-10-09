# Demo requirements — what a recorded or live demo must meet

**Owner's rule (2026-10-08):** no laggy AI speech and no long silence. A demo is not finished until the measurements below pass. Fixing the lag in the app comes first; editing a video is not a fix.

These limits apply to every demo, whether recorded or live, that goes to a mentor, a judge or a competition. The emulator numbers are software-path evidence; the phone has the final say ([ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md)).

## 1. Her voice is never choppy

| Check | Limit | Measured by |
| --- | --- | --- |
| Gap between two clauses of one reply, after the first clause has started playing (app-voiced path) | ≤ 150 ms for 95 % of clause joins, none > 300 ms | `assistant_voice_gap_ms` in the NovaVoice log |
| Provider-voiced path (Qwen Maia): the player runs dry inside a reply (starvation, including a clause-release wait) | none > 300 ms | `reply_underrun ms=` in the NovaVoice log, logged by the client when a reply chunk leaves the gate later than the audio before it lasts. A pause inside the voice itself is not counted: Qwen streams about 3x faster than real time, so 0.4–0.6 s sentence pauses fall inside the response window and are not starvation (2026-10-09) |
| Voice failures (`assistant_voice_failed`) | 0 | log |
| Only one voice: the session's provider voice (Maia since ADR-017 / `e3f6faf`), never a second | always | by ear, plus `assistant_voice ignored reason=provider_speaks` |

## 2. She answers promptly

Time is measured from the end of the driver's speech to her first audible audio. In the demo recorder this is the end of the driver clip; live, it is the `ACTIVITY_END` mark.

| Turn | Limit |
| --- | --- |
| Chat or style turn, warm | p50 ≤ 2.0 s, max ≤ 3.0 s |
| Car action with a tool | p50 ≤ 2.5 s, max ≤ 3.5 s |
| First reply after the app starts, or after a sleep | ≤ the warm limit + 0.5 s; the first `azure_tts_first_audio` ≤ 600 ms |
| Any reply | never > 5 s; if one takes longer, the take is void |

## 3. No long silence in the video

- No stretch longer than 1.5 s with neither the driver nor her speaking. The exceptions:
  - a scene title (≤ 2.5 s, to read it);
  - a visual-only scene such as an error card, which must be marked as such;
  - the wait between the driver's line and her reply. That wait is **never trimmed**, because it is the latency §2 measures, and the video must show it as it was.
- A cut that skips time is marked on screen (⏩) and stated in the README. Her words and the driver's are never cut or sped up.
- Any trimmed wait is disclosed in the footer and the README, and the real-time file is kept beside the edited one.

## 4. How a demo is produced

1. Build the current commit. Run `.\gradlew.bat test --rerun-tasks :app:assembleDebug`.
2. Record on the emulator with the speech harness, sound off on the PC (`-no-audio`). Use short takes, one scene each: the host bridge drops audio in takes longer than about a minute.
3. Check each take's log against §1 and §2 before composing. A take that fails is re-recorded or reported as a finding, not hidden in the edit.
4. Compose the video. Check §3 by measuring the audio, and look at frames from every scene.
5. Store the video, its README and the scripts in `android_doc/<demo>_<date>/`. Do not store the logs: they contain `transcript=` lines.

## Status

The first new-functions demo (2026-10-08) failed §1 and §2:
- **Clause gaps:** up to 0.36 s.
- **First reply after a start or a sleep:** 1.2–1.9 s of Azure set-up, 4–5 s in total.
- **Chat replies:** held 1.0–1.65 s by the claim gate until Gemini finished generating.

The work on these is tracked in [OPEN_PROBLEMS.md](../OPEN_PROBLEMS.md) P48.
