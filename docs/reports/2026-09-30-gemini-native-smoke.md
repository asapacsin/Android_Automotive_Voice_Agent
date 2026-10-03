# Gemini native smoke — L1–L3 cloud evidence (2026-09-30)

Plan: [GEMINI_NATIVE_PLAN.md](../GEMINI_NATIVE_PLAN.md) §4 P6. Level: **cloud** (real Gemini Live
API through the app's own `GeminiLiveClient` on the JVM). Not device evidence: no speaker, no mic,
no AEC, no phone network (`GEMINI-DEVICE-LATENCY-001` remains a device item).

Base commit: `c398ff4` (branch `gn/P6`). Test: `GeminiLiveSmokeTest.measuresL1L2L3PerModelThroughTheAppClient`.

## Method

- One session per model, 15 turns in sequence: 10 spoken commands (`ac_on ac_off temp24 temp_up
  fan_up music_on music_off volume_up nav_home nav_wanda`) then 5 conversational prompts (`hello
  chat_q intro_q what_can_you_do can_you_talk`). Audio: the synthetic 16 kHz PCM clips of
  `tools/speech-harness/speech` (gitignored; `SMOKE_SPEECH_DIR`), sent as the microphone would —
  local onset, 20 ms frames in real time, local offset, 1.5 s silence.
- End of speech = wall-clock send time of the last 20 ms frame with RMS > 500 (clips carry trailing
  silence).
- Every `ToolCall` is answered `ok=true` via `sendToolResult`. A turn ends when a `ResponseDone`
  follows the last result and the socket has been quiet 2.5 s.
- L1 = end of speech → emitted `ToolCall`. L2 = `generationComplete` frame arrival (raw-socket tap in
  the test) → first `AudioDelta` emitted in that turn, over every turn with no call (negative =
  audio streamed before `generationComplete`). L3 = `AssistantTranscript` with a completed-action
  marker emitted before the first tool result, plus raw audio parts / output-transcript frames
  arriving after `generationComplete` and before `turnComplete` (premise of settling at
  `generationComplete`).
- No client change; instrumentation is test-only. No transcript and no key are printed.

## Result (run 2, JUnit XML: tests=1 failures=0 errors=0)

| model | L1 ≤2.5 s | L1 median/max ms | L2 median/max ms (n) | L3 claims before result | audio before result | audio after settle | text after settle |
| --- | --- | --- | --- | --- | --- | --- | --- |
| gemini-3.8-live | **9/10** | 1627 / 2374 | **0 / 4 (5)** | **0** | 0 | 0 | 0 |
| gemini-3.8-live-extended-thinking | 0/10 (4 calls) | 5986 / 8056 | 0 / 1 (5) | 0 | 0 | 0 | 0 |

Thresholds (asserted for `gemini-3.8-live` only): L1 ≥ 9/10, L2 median ≤ 50 ms, L3 = 0 (all
models). Extended thinking is reported, not asserted: it misses L1 as the plan expected, and made a
call in only 4 of 10 commands.

## Limits and variance

- **L1 is at the threshold, not above it.** Run 1 (same code, harness without the quiet-period
  turn boundary) measured 8/10 for `gemini-3.8-live`: `volume_up` and `nav_home` were answered in
  speech without a call. In run 2 `volume_up` again made no call. Call-or-not is model behaviour
  and varies run to run; n = 10 per run is small.
- Run 1 also showed 2 "claims before result" for extended thinking; those were attribution
  artifacts of a slow model's late reply landing in the next turn window, removed by the 2.5 s
  quiet boundary. Run 2 shows 0.
- L2 of ~0 ms is the client's gate release on the JVM, excluding playback, device scheduling and
  network jitter to the phone.
- After-settle counts of 0 support the premise over 30 turns; they do not prove it cannot happen.
- Synthetic TTS clips, single session per model, one cloud region via the session proxy.
