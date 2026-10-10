# SPEC-020 — Wait cues: she says she is on it, and says why when it takes longer

Status: **Authorised 2026-10-08** by the owner: "if this require long term wait you should make something like i m currently start doing this task, and if take long you say something like base on x reason this would take longer".
Source: [docs/DEMO_REQUIREMENTS.md](../docs/DEMO_REQUIREMENTS.md), [OPEN_PROBLEMS.md](../OPEN_PROBLEMS.md) P48. This fills the "small acknowledgements: absent" row of [LIVE_CONVERSATION_ARCHITECTURE.md](../docs/LIVE_CONVERSATION_ARCHITECTURE.md) §1.
Depends on: ADR-016 / SPEC-019 (one assistant voice), I-1 (no claim before proof), SPEC-012 (`SpeechArbiter` decides whether she may speak).

## Goal

The driver is not told "I'm doing it" on every turn:
- For the first 7 seconds she stays quiet and the work starts at once. From 3 seconds the screen shows 「处理中」.
- At 7 seconds, if nothing useful has been spoken, she says one progress line.
- At 12 seconds she says a different line about the delay. She does not repeat the progress line.

## Owner

`AssistantVoiceRevoicer`. It already sees the driver's speech events, the reply boundaries, the tool calls and every reply clause it voices. The cue is fixed app wording spoken through the same `AssistantVoice`, so there is still exactly one voice. Cue audio goes out on the same playback path as reply audio, so `SpeechArbiter` and barge-in apply to it unchanged. It is not a model reply, though: see "Cue audio in the session core" below.

Clock source (deviation, 2026-10-08):
- Gemini sends no speech events of its own. The session core synthesises `SpeechStarted`/`SpeechStopped` downstream of the provider stream.
- So `GeminiLiveProvider.onLocalSpeechActivity` forwards the local onset and end of speech to `AssistantVoiceRevoicer.onDriverSpeech`.
- The uplink gate closes only after `SpeechUplinkGate.HANGOVER_MS` (1.2 s) of continuous silence. So the end-of-speech call passes that silence, and the clock is backdated to the driver's last voiced frame.
- Stream `SpeechStarted`/`SpeechStopped` (Baidu) reach the same method with no backdating.
- There is one clock per driver turn.

Not owners: the model or the prompt (the cue must hold when the model is slow or silent), `DriverTurn` (cues claim nothing, so there is nothing to judge), and the UI.

## Behaviour

A driver turn begins at his onset. From then on it remembers what the provider did: `ResponseStarted`, a tool call, reply words queued or audible. Gemini's first message or tool call often arrives before the local gate has closed.

The clock starts at his last word (see Clock source). No clock starts if her reply was already under way by then.

The clock stops at any of these:
- her real reply's words being queued for the voice (`reply_queued`) or becoming audible (`reply_audio`);
- the driver's next onset;
- a barge-in or cancel (`cancelCurrentReply`);
- an app-side cancel of the current reply (`client_cancel`), chiefly the app handling the utterance itself: a navigation pick or an on-screen control. 「闭嘴」/sleep and the wake word use the same cancel. This ends the turn's cues for good, even before its clock has started. For Gemini only app-side cancels reach `cancelAssistantResponse` (`clientResponseCancel` is false), so a barge-in never ends a new turn this way;
- a `ResponseDone` of this turn's reply when nothing of it was audible and no tool is outstanding (`turn_done`: no answer is coming, so no cue may say one is). A tool is outstanding when a tool call came and neither its result has been delivered to the model nor a `ResponseStarted` came after it. Gemini answers a result in the same response, so the delivery is what clears it.
- the session ending.

Owner timing, 2026-10-09. Each row happens at most once per driver turn. A tool call does not add a line.

| When nothing useful has been spoken | What she does | Reason code in the log |
| --- | --- | --- |
| 0–3 s | Silent. The work starts immediately. | — |
| 3 s | Screen label 「处理中」 (`WaitCueVisual`). Not spoken. | `visual` |
| 7 s, and still before 12 s | 收到，正在处理。 | `progress` |
| 12 s | 还在处理，网络可能不太稳定，再等我一下。 | `delay` |

A wake-up that is already past 12 s says the delay line only, not the progress line and then the delay. The 3 s label still appears.

The 1.0 s / 1.8 s / 5 s lines (`ack_action`, `ack_chat`, `provider_slow`, `tool_running`, `verifying`) are not spoken. Their strings stay in `WaitCues.ALL` so the claim guard still covers them.

**Turn evidence.** A cue is spoken only when something says the driver really spoke to her: a `ResponseStarted` or a tool call in this turn, or an uplink segment that `SpeechUplinkGate.Segment.isSuspicious()` does not flag.
- Without evidence (a cough, a knock, a passenger's murmur that the model ignores), no cue is spoken. The log records `wait_cue_skipped reason=no_turn_evidence` once, at 7 s.
- Evidence that arrives later makes a due cue fire on arrival.

**Maia (product session).** There is no second voice. `WaitCueClock` runs the same decision from the server's `speech_started` / `speech_stopped`. At 7 s and at 12 s `QwenOmniDialect.progressResponse` sends one `response.create` whose instructions are that single sentence and whose `tools` array is empty. `OpenAiRealtimeClient` captures that response's audio and emits `WaitCueAudio`. It does not enter the claim gate. If a reply is already in progress, the spoken cue is skipped (`wait_cue_skipped reason=response_active`) and the label remains. The Azure revoicer path uses the same thresholds and speaks through `AssistantVoice`.

Rules:
- **Never claims an outcome.** Every cue text passes `ActionClaimGuard.carActionClaimMatch == null` and `claimsDone == false`, and a unit test enforces this. A cue says she is working on it, never that it is done.
- **Never two voices.** On the Maia path the cue is Maia's own audio, captured as `WaitCueAudio`. On the Gemini path a cue is spoken only through the `AssistantVoice`. With that voice off, no cue is spoken; the log records `wait_cue_skipped reason=no_assistant_voice`.
- **Silent modes.** No cue in SILENT_WAIT (「闭嘴」), sleep, or while a GUIDANCE prompt is open. These states already stop the voice or hold playback. A cue must not reopen them.
- **No overlap.** If the real reply's first clause is ready while a cue is playing, the cue finishes first, then the reply follows without a gap. Cues are short (≤ 1.2 s of audio). A cue never cuts the reply, and no cue is ever spoken between a reply's clauses: once reply words are queued, the turn's cues are over.
- **A cue failure is not a reply failure.** If a cue cannot be synthesised, `wait_cue_failed` is logged and the reply still plays.
- **Cached audio.** Each cue text is synthesised once per speaking style and cached, so a cue starts within ~50 ms of its timer, not after a synthesis round trip. The cache is filled lazily; the first use may pay one synthesis.
- **Logs** hold counts and codes only: `wait_cue code=<reason> after_ms=<n>` (after_ms from his last word; logged only when the cue was actually queued), `wait_cue_cancelled reason=<…>`, `wait_cue_skipped reason=<…>` and `wait_cue_failed`. Never the driver's or the model's words (I-8).

## Acceptance

| # | Criterion | Proven by | State |
| --- | --- | --- | --- |
| A1 | Nothing is spoken before 7 s. The progress line fires at 7 s, counted from his last word, when no reply is under way; not when the reply was queued or audible first, including events seen before his end of speech | `AssistantVoiceRevoicerWaitCueTest` (virtual clock), `WaitCueCoreIntegrationTest`, `WaitCuesTest` | see the Status line |
| A2 | The delay line at 12 s is a different sentence, once; a late wake-up does not also say the progress line; no cue after a `ResponseDone` with nothing audible, and none without turn evidence | same | see the Status line |
| A3 | A reply arriving during a cue plays after it, in order, with nothing lost; no cue between a reply's clauses; a cancel stops both; a cue never blocks tool-result delivery | same, plus ingress `WaitCueAudioTest` | see the Status line |
| A4 | No cue text is a claim | `ActionClaimGuardTest` over every cue string | see the Status line |
| A5 | No cue with the assistant voice off, in SILENT_WAIT, or with a guidance prompt open | `AssistantVoiceRevoicerWaitCueTest`, `GeminiLiveProviderVoiceTest` | see the Status line |
| A6 | On a slow turn the driver hears nothing before 7 s. If she is still silent, one 「收到，正在处理。」 between 6.5 s and 8 s after his last word, and the reply follows it without overlap. At 12 s the delay line is the other sentence. A tool turn still completes. The retired codes are a failure. | `wait_cue` log lines (`tools/demo/check_demo_log.py`) plus the demo recorder's audio | open (device) |

A6 was first written as "within 1.5 s", then reconciled on 2026-10-08 to 1.0 s / 1.8 s. The owner replaced that schedule on 2026-10-09 with the table above.

Quiet: the app passes `lifecycle.speaks` through `RealtimeProviderFactory.build(repliesSpoken)` to `GeminiLiveProvider`, which builds the revoicer with `quiet = { !repliesSpoken() }`, so SILENT_WAIT and sleep speak no cue (`wait_cue_skipped reason=quiet`; an open GUIDANCE prompt logs `reason=guidance_prompt`). With the assistant voice off there is no revoicer; the provider logs `wait_cue_skipped reason=no_assistant_voice` at each end of speech.

**Cue audio in the session core** (planner decision, 2026-10-08, after review). A cue is sent as one `DomainVoiceEvent.WaitCueAudio`, not as model reply audio (`AudioDelta`). Two reasons:
- **Reply audio moves the session state machine to SPEAKING.** The `WorkCoordinator` delivers tool results only at LISTENING or THINKING. In a Gemini tool turn nothing ends SPEAKING before the result is sent, so a cue sent as reply audio would block the very result it announces. This was reproduced in `WaitCueCoreIntegrationTest` case e.
- **The core plays reply audio only inside an open reply stamp that has not completed.** So cue audio sent after a finished or interrupted reply was silently dropped.

The core (`VoiceSessionController.playWaitCue`) handles the event as follows:
- It plays the cue in a stamp of its own and completes it.
- If a provider reply is still open and not completed, the core opens a fresh stamp for the rest of that reply.
- It does not change the session state, does not open or end a response, and is not counted as first reply audio (P48 latency).
- It goes to the same `PlaybackPort`, so `SpeechArbiter` and barge-in apply as for any reply.
- An interrupted reply stays closed, so its stray audio stays silent.

Known limits (accepted 2026-10-08; each fails silent, i.e. a missing cue, never a false one):
- Words of an interrupted reply that still reach the voice after the driver's next onset mark the new turn's reply as under way, so that turn gets no cue.
- A filler response that the claim gate drops ends the turn's cues (`turn_done`), so a tool turn that follows it gets none.
- The end-of-speech backdating always uses `HANGOVER_MS`. A gate closed by a capture interruption (guidance taking the microphone) was not 1.2 s silent, so its cues can come early. Playback is held during guidance anyway.
- The wake word said over her reply cancels it through the same app-side cancel. So the command that follows the wake word, which still goes to the model, gets no cue.
- An app-side cancel that lands a few milliseconds after the driver's next onset ends that newer turn's cues.
- A late tool result from an older turn can clear the current turn's outstanding tool, so `turn_done` may end its cues early.
- `DebugVoiceLog` is a no-op on the JVM. The `wait_cue_*` log lines are proven by their effects in the tests, and A6 reads them on the emulator.

Status: A1–A5 unit and in-process integration tests pass on the JVM (2026-10-08, cloud, claude/10-8). The independent review is recorded in the merge commit. A6 needs the emulator.
