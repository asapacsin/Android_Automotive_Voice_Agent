# SPEC-004 — Automated speech test harness, failure records, regressions, metrics

Status: **Revised by the v2 replacement spec — carried under [SPEC-005](SPEC-005-embedded-amap-mvp.md).** v2 §27–39 restructures the levels (A logic / B direct audio / C physical embedded-navigation), adds same-screen UI and screenshot assertions (§30), and extends the failure taxonomy with navigation stages (§35). Unchanged and still requiring owner acknowledgement: audio-level tests spend Baidu quota (C1 below), and the TTS / independent-ASR engines (C2). The suppression-metric denominator is restated verbatim in v2 §39.
Raised: 2026-09-16 by the product owner — verbatim source: [DEMAND-2026-09-16](DEMAND-2026-09-16-amap-coexistence.md) §14–32, §33.12–17, §34
Backlog: [B-005](../BACKLOG.md)
Depends on: [SPEC-003](SPEC-003-amap-coexistence-voice-policy.md) for the categories and expected policies it asserts against

## Demand (what was actually asked for)

Test the assistant **with voice, not text**: synthesise the user's phrase with TTS, push that audio through the real pipeline, capture what the assistant says back, and verify it with an independent ASR. Two levels — **A** deterministic (audio fed straight into the input pipeline) and **B** physical (played from a speaker into the phone's microphone, with Amap really navigating). Every failure writes a durable local record with the **stage** that failed; reproducible failures are promoted, by hand, into a permanent regression corpus that reruns automatically. Report latencies as median/P90/P95, and measure voice suppression with the correct denominator.

## Why it matters

Every defect this project has fixed was found by a human with a phone, and two false alarms came from misread diagnostics. Nothing today exercises the audio path automatically, and nothing records a failure in a form the next session can rerun. P3 was misclassified for a day because its effect on tool calling was never measured. This spec turns "the owner drives and reports" into something repeatable.

## What already exists — reuse

| Demand concept | Existing counterpart |
| --- | --- |
| A way to bypass the model and drive tools | `DebugToolReceiver` (debug builds, ADB broadcast) |
| A provider that needs no network | `FakeRealtimeVoiceProvider` / `MockRealtimeVoiceProvider` — `PRODUCT.md`: TEST ONLY |
| Observing playback / focus from outside | `AudioPlaybackProbe` (debug), `dumpsys audio` recipes recorded in SPEC-002 |
| Making Amap navigate with the phone on a desk | adb test-location provider recipe (SPEC-002, A′) — proven to drive a real route |
| A `behavior-test` Gradle module | exists and runs in `gradlew test`; **its contents have not been reviewed against this spec** — candidate home, verify before assuming |
| Per-event timestamps | `RealtimeEvent(SystemSessionClock.nowMs(), …)` already stamps every provider event |

## Constraints and conflicts

### C1 — Level A on the real pipeline **spends Baidu quota on every run.** Owner acknowledgement required.

In an end-to-end architecture ([ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md)) "feed PCM into the ASR/input pipeline" means feeding it up the Baidu Flex WebSocket. There is no local ASR to feed. Every Level A test case is therefore a **paid API call**, and the owner's standing rule is *do not call paid APIs* without explicit intent. This spec does not authorise it. Two-tier structure proposed:

- **A-local** — the policy, categories, action layer and failure-record machinery tested against `FakeRealtimeVoiceProvider` with scripted tool calls. Free, deterministic, runs in `gradlew test`. Covers §33.10–11, 14–17.
- **A-live** — the same cases against real Flex, **run only on demand**, with a per-run case cap and the quota cost printed in the report. Covers §33.12–13.

Level A as written in the demand is A-live. A-local is added because §15's stated purpose for Level A — "repeatability; isolate software logic from physical audio" — is better served without a network in the loop at all.

### C2 — "TTS User Simulator" and "ASR verifier" need engines this project does not have

`PRODUCT.md` non-goal: "speech recognition or speech synthesis of our own." That is a *product* non-goal; the harness is tooling and may use engines the product does not ship. But the choice is not free:

| Need | Options | Cost / caveat |
| --- | --- | --- |
| zh-CN TTS for the user's phrase | Windows SAPI zh-CN voice (offline, on the build PC); `edge-tts`; Baidu TTS | SAPI is free and deterministic; Baidu TTS is paid |
| Independent ASR for the assistant's reply | local Whisper / Vosk zh; Baidu ASR | Whisper is free but slow on CPU; must be **independent** of the model under test (§33.13) |

**The verifier must not be Baidu Flex itself**, or a wrong reply that transcribes itself consistently passes. Owner picks; Q1.

### C3 — Level B mechanics, from things already measured

Playing the user's phrase **on the phone** is impractical (memory: opening a `.wav` lands in 米音乐 with permission prompts and ads). Level B must play from the **PC's speaker** into the phone's microphone. Capturing the assistant's reply: for Level A, a debug tap at `AndroidPlaybackPort` writing the PCM that *would* be played (or was suppressed) to a file is cleaner than acoustic re-capture and also proves §33.14 directly; for Level B, capture acoustically with a PC microphone, because the point of B is the physical path.

### C4 — the metrics assume distinctions the E2E stack partly blurs

§27 asks for "ASR completes / intent completes / TTS begins" timestamps. On Flex the observable events are: user speech end (server VAD), `response.output_item.added` (a tool call or a reply), `function_call_output` sent, `response.audio.delta` first frame. Map the metric names onto those events and say so in the report, rather than inventing stages that do not exist. "CER" is measurable only in A-live/B via the independent ASR on the *caption transcript* — it measures Flex's captioning, not an ASR stage.

### C5 — the suppression metric must count what P1's rule actually does

§28 is right and matters here: during navigation the current rule drops *all* frames outside the confirmation window, so a naive "any assistant audio during Amap" metric would count expected confirmations as failures. `unwanted_voice` is asserted only where SPEC-003's `VoicePolicy` expected `SILENT`. Until SPEC-003 lands, "expected policy" comes from the 10 s window semantics, and the report must label which policy definition it used.

### C6 — repository hygiene

`test-results/` is generated and must be ignored by git; `tests/regressions/` is source and is committed. Audio files in failure records can be large — keep 16 kHz mono PCM/WAV, and never store anything containing credentials (logs must go through the same sanitisation as `DebugVoiceLog`).

## Scope

**P0 (§34):** A-local harness; the failure record (§22–23) with stage classification (§24 — the demand lists it P1, but a record without a stage is the "TEST FAILED" the demand calls useless, so it ships with the record); the six core cases (§16–21) expressed as data, runnable against A-local now and A-live/B later.

**P1:** A-live with cap and cost reporting; Level B runner; regression promotion workflow (§25–26) — promotion is a **human** step, the harness only reruns `tests/regressions/`; output-ASR verification; P50/P90/P95 (§27); coexistence counters (§30); final report (§32).

**P2:** noise/accent suite, long-duration stress.

## Out of scope

- Deciding SPEC-003's policy — this spec only asserts against it.
- Any change to the product's runtime to make tests pass, other than the debug-only playback tap.
- Automatic promotion of failures to regressions (§26 forbids it).

## Open questions — must be answered before building

| # | Question | Who |
| --- | --- | --- |
| Q1 | Which TTS and which independent ASR engine? (C2) | Owner |
| Q2 | Is A-live authorised, and with what per-run cap? (C1) | Owner |
| Q3 | Does `behavior-test` already hold a harness skeleton worth extending, or is it unrelated? | Architect — read the module |
| Q4 | Test cases as JSON (§31 shape) under `tests/cases/`, with regressions being the same shape under `tests/regressions/`? | Architect — recommended yes; one schema |
| Q5 | Level B needs a PC speaker and mic near the phone, and Amap navigating via the mock-location recipe. Is that rig acceptable, or must B be a real drive? | Owner |

## Acceptance (per `ACCEPTANCE_TESTS.md`)

| §33 | Item | Level |
| --- | --- | --- |
| 12 | TTS-generated user speech exercises the pipeline | A-live **L6** (paid) or B **L5**; A-local does not satisfy this and must not be reported as doing so |
| 13 | Output audio independently verified by ASR | **L6**/**L5** with an engine that is not the model under test |
| 14 | Unwanted voice rate 0% | asserted per §28's denominator; reported per policy definition (C5) |
| 15 | Every failure creates a useful local record | L2 — a deliberately failing case must produce a complete `failure.json` with a non-`UNKNOWN` stage |
| 16 | Reproducible bugs promotable to regressions | L2 — `tests/regressions/*.json` rerun in `gradlew test` |
| 17 | Latency distributions, not averages | L2 — report contains median/P90/P95 |

**Nothing here closes on A-local alone.** A-local proves the harness and the policy logic; only A-live or B proves the product.
