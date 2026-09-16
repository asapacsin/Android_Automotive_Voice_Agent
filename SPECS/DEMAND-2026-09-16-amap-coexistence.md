# DEMAND — Amap Coexistence, Voice Policy, Action Abstraction, and Audio Test Requirements

Received: 2026-09-16 from the product owner, verbatim. Instruction given with it: "update the local state."
Recorded as: [B-004](../BACKLOG.md) → [SPEC-003](SPEC-003-amap-coexistence-voice-policy.md), and [B-005](../BACKLOG.md) → [SPEC-004](SPEC-004-speech-test-harness.md).

> This file is the **unedited source**. The specs interpret it against the current architecture and name conflicts; where a spec and this file disagree on *what was asked*, this file wins. Where they disagree on *what to build*, the spec wins, because the spec has resolved the conflicts.

---

## 1. Goal

Build an Android assistant that can coexist with Amap navigation.

While Amap navigation is active:

- Amap owns normal navigation voice output.
- The assistant continues listening and processing user requests.
- Normal assistant conversation must remain silent.
- Action commands such as turn on/off are an explicit exception and SHOULD produce a short voice confirmation.
- Failures found during testing must be recorded locally and automatically reusable as regression tests.

The current system does not need real air-conditioner/car hardware integration. Device commands should use an abstract action layer with mock implementations.

## 2. Runtime Architecture

```
User Voice
    ↓
ASR
    ↓
Intent Parser
    ↓
Request Classification
    ├── Information Request
    ├── Navigation Request
    └── Action Request
             ↓
       ActionExecutor
         ├── MockActionExecutor      ← use now
         ├── AndroidActionExecutor   ← later
         ├── CarActionExecutor       ← later
         └── ExternalDeviceExecutor  ← later
             ↓
        ActionResult

All responses
    ↓
VoicePolicy
    ↓
TTS / Silent
```

The Amap-specific behavior belongs primarily in VoicePolicy, not scattered through individual commands.

## 3. Runtime States

At minimum: `NORMAL`, `AMAP_ACTIVE`.
Optional internal/transient states: `AMAP_ACTIVE`, `AMAP_SPEAKING`, `ASSISTANT_ACTION_CONFIRMATION`.
The implementation must know whether Amap navigation is active before deciding whether assistant TTS is allowed.

## 4. Voice Policy

### 4.1 Normal Mode
When Amap is not navigating: normal questions may receive spoken answers; action commands may receive spoken confirmations; normal conversational TTS is enabled.
Example — User: 现在几点？ Assistant: 现在是十点三十分。

### 4.2 Amap Navigation Mode
When Amap navigation is active: **normal assistant conversational responses must be silent.**
Examples that SHOULD NOT produce assistant speech: 现在几点？ / 附近有什么餐厅？ / 今天天气怎么样？ / 给我介绍一下珠海。
The request may still be recognized by ASR, be processed, generate internal text, update the UI — but assistant TTS must not play.

## 5. Explicit Amap Voice Exception: Action Commands

Commands that change a device/application/system state are an explicit exception. During Amap navigation, these commands SHOULD produce short spoken confirmations.
Examples: 打开空调 / 关闭空调 / 打开蓝牙 / 关闭蓝牙 / 暂停音乐 / 继续播放 / 打开灯 / 关闭灯.
Even while Amap is active — User: 打开空调 → Assistant: 空调已打开。
This behavior is intentional and must NOT be classified as an unwanted voice event.

## 6. Voice Categories

Use semantic response categories: `INFORMATIONAL_RESPONSE`, `ACTION_CONFIRMATION`, `ACTION_FAILURE`, `CRITICAL_ALERT`, `NAVIGATION_HANDOFF`.

| Category | Normal | Amap active |
| --- | --- | --- |
| INFORMATIONAL_RESPONSE | Speak | Silent |
| ACTION_CONFIRMATION | Speak | Speak briefly |
| ACTION_FAILURE | Speak | Speak briefly |
| CRITICAL_ALERT | Speak | Speak briefly |
| NAVIGATION_HANDOFF | Optional | Usually silent |

Do not implement this using text matching such as `if response contains "空调"`. The decision must use semantic metadata.

## 7. Voice Decision Interface

```
VoiceDecision { SPEAK_NORMAL, SPEAK_SHORT, SILENT }
VoicePolicy.decide(amapActive, responseCategory, actionResult, criticality)
```
Amap = true + ACTION_CONFIRMATION → SPEAK_SHORT. Amap = true + INFORMATIONAL_RESPONSE → SILENT.

## 8. Action Abstraction Layer

Do not build real air-conditioner integration yet. Create a generic action abstraction:

```kotlin
data class ActionRequest(val target: String, val action: String, val value: String? = null)
data class ActionResult(val success: Boolean, val message: String, val errorCode: String? = null)
interface ActionExecutor { suspend fun execute(request: ActionRequest): ActionResult }
```
Current implementation: `MockActionExecutor`. Future: `CarActionExecutor`, `AndroidActionExecutor`, `BluetoothActionExecutor`, `HomeAssistantExecutor`, `ExternalApiExecutor`.
The rest of the assistant must not need to change when the mock implementation is replaced.

## 9. Mock Action Behaviour

Input 打开空调 → Intent target=air_conditioner, action=turn_on → MockActionExecutor: success=true, message=空调已打开. No real hardware. The point is to validate voice → ASR → intent → action abstraction → result → voice policy → TTS.

## 10. Mock Failure Cases

The mock layer must support controlled failures, e.g. `{"success": false, "errorCode": "DEVICE_OFFLINE", "message": "空调连接失败"}`. While Amap is active this SHOULD still produce short voice: 空调连接失败。

## 11. Amap Integration

导航去珠海站 → Intent NAVIGATE destination=珠海站 → Amap launch navigation → runtime state AMAP_ACTIVE. Amap remains responsible for maps, route calculation, traffic, navigation UI, turn-by-turn guidance, navigation speech. The assistant should not recreate these.

## 12. Audio Behaviour During Amap

When assistant speech is suppressed: TTS playback must not begin; playback audio focus should not be requested unnecessarily; Amap audio should not be paused; Amap should not be unnecessarily ducked; silent audio should not be played as a workaround. The assistant must continue listening where Android permits it.

## 13. Action Confirmation During Amap Speech

Amap may itself be speaking (前方两百米右转) when the user says 打开空调. Execute the action immediately; if Amap is currently speaking, queue the short confirmation; play it after Amap speech ends. **Do not delay the actual action just to delay the TTS confirmation.**

## 14. Automated Speech Test Harness

Test the real speech pipeline: Test phrase → TTS User Simulator → Audio → Assistant ASR → Intent → Action / Query → VoicePolicy → Assistant audio → Capture → ASR verifier. Test behaviour using voice rather than only inserting text.

## 15. Two Test Levels

**Level A — Deterministic Audio Test.** Generate TTS audio and feed PCM directly into the ASR/input pipeline. Purpose: repeatability, regression, isolate software logic from physical audio.
**Level B — Physical End-to-End Test.** Play generated user TTS from a speaker and capture through the device microphone, while Amap is actually active. Purpose: Android audio policy, microphone availability, audio focus, echo, speaker leakage, background execution, real Amap coexistence.
Both levels are required.

## 16–21. Core Test Cases

- **TC01** Amap OFF, 现在几点？ → ASR ok, intent ok, normal spoken response.
- **TC02** Amap ACTIVE, 现在几点？ → ASR ok, processed, assistant voice = NONE.
- **TC03** Amap ACTIVE, 打开空调 → action intent, mock success, short confirmation 空调已打开 (expected; NOT a suppression failure).
- **Turn OFF during Amap** 关闭空调 → 空调已关闭 (voice expected).
- **Action failure during Amap** 打开空调 + DEVICE_OFFLINE → 空调连接失败 (short failure voice allowed).
- **Mixed workload** while Amap active: 现在几点？ 打开空调 附近有什么餐厅？ 暂停音乐 今天天气怎么样？ 关闭空调 → spoken: the three actions; silent: the three questions.
- **Amap concurrent speech** — Amap speaking, inject 关闭空调 → action executes immediately, Amap session not destroyed, no overlap where avoidable, confirmation queued and played after Amap voice ends.
- **Amap exit** — AMAP_ACTIVE, end navigation, then 现在几点？ → normal voice resumes.

## 22. Local Failure Record

Every failed automated test must produce a persistent local failure record:
```
test-results/latest/report.json, report.md
test-results/failures/<date>/<TC>_<n>/failure.json, input.wav, output.wav, logs.txt
```
Do not overwrite historical failure records.

## 23. Failure Record Contents

At minimum: test_id, timestamp, runtime_state, input_text, input_asr, expected_intent, actual_intent, expected_voice_policy, actual_voice_policy, action_success, expected_output, output_asr, failure_stage, error. Also save input audio, output audio, ASR transcript, application logs, timestamps, Amap state, audio-focus events, exception stack trace.

## 24. Failure Stage Classification

`INPUT_TTS`, `ASR`, `INTENT`, `ACTION_EXECUTION`, `VOICE_POLICY`, `TTS`, `AUDIO_FOCUS`, `AMAP_INTEGRATION`, `BACKGROUND_EXECUTION`, `OUTPUT_ASR`, `UNKNOWN`. Much more useful than "TEST FAILED".

## 25–26. Automatic Regression Corpus and Promotion

Useful failures become reusable regression cases under `tests/regressions/`. Workflow: test fails → saved automatically to failures/ → investigate → if real and reproducible, promote to tests/regressions/ → fix → must pass forever afterward. Do **not** auto-promote every transient failure (flaky mic/network must not pollute the permanent suite).

## 27. Performance Measurements

ASR: latency, CER, success rate, intent accuracy. Actions: success rate, execution latency. Voice: TTS generation latency, time to first audio, speech duration. End-to-end timestamps: user speech ends, ASR completes, intent completes, action begins, action completes, TTS begins, TTS ends. Report median, P90, P95.

## 28. Correct Voice-Suppression Metric

Do NOT count all assistant speech during Amap as failure.
**Unwanted Voice Rate = assistant voice events where VoicePolicy expected SILENT ÷ requests where VoicePolicy expected SILENT.** Target 0%. Action confirmations excluded (expected policy = SPEAK_SHORT).

## 29. Action Confirmation Metric

**Expected Action Confirmation Success Rate = correct spoken action confirmations ÷ actions requiring spoken confirmation.** Measure semantic content, latency, duration, overlap with Amap, missing confirmations, duplicate confirmations.

## 30. Amap Coexistence Metrics (recorded independently, never one PASS/FAIL)

Amap unexpectedly stopped / paused / ducked; assistant spoke unexpectedly; action confirmation missing; assistant spoke over Amap; ASR unavailable; microphone unavailable; assistant process terminated; session/WebSocket disconnected.

## 31. Structured Per-Test Result

JSON per test including amap_active, amap_speaking, input_text, input_asr, intent, target, action, action_success, response_category, voice_decision, expected_output, output_asr, unexpected_voice, amap_interrupted, asr_latency_ms, action_latency_ms, confirmation_latency_ms, result.

## 32. Final Test Report

Totals; ASR success rate; intent accuracy; action success rate; silent-required requests, unwanted voice count and rate; action confirmations expected/successful/rate; median/P90/P95 latency; Amap interruption/overlap/audio-focus conflict counts; new failure records; regression cases executed/passed/failed.

## 33. Acceptance Criteria

1. Amap can be launched for navigation.
2. Amap remains responsible for navigation.
3. Assistant remains alive during Amap operation.
4. Assistant ASR remains functional where Android permits.
5. Normal informational assistant speech is suppressed during Amap navigation.
6. Turn-on/turn-off and other action confirmations remain voiced during Amap navigation.
7. Action failures may also produce short spoken feedback during Amap navigation.
8. Action TTS is short and does not become conversational.
9. Where possible, action confirmations avoid overlapping Amap speech.
10. Mock actions work without requiring real hardware.
11. Real action implementations can later replace mocks without altering the overall assistant pipeline.
12. TTS-generated user speech exercises the ASR pipeline.
13. Output audio is independently verified using ASR.
14. Silent-required unwanted voice rate is 0%.
15. Every failed test creates a useful local failure record.
16. Reproducible bugs can be promoted into permanent regression tests.
17. Performance metrics include latency distributions rather than only averages.

## 34. Priority

**P0** — Amap coexistence; Amap voice suppression for normal responses; action-command voice exception; Mock ActionExecutor abstraction; TTS → ASR automated test; local failure recording.
**P1** — Failure-stage classification; automatic regression replay; Amap-speaking detection / confirmation queue; audio-focus monitoring; output-ASR verification; P50/P90/P95 metrics.
**P2** — Real car/device integrations; noise/accent robustness suite; large long-duration stress testing.
