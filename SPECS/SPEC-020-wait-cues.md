# SPEC-020 — Wait cues: she says she is on it, and says why when it takes longer

Status: **Authorised 2026-10-08** by the owner: "if this require long term wait you should make something like i m currently start doing this task, and if take long you say something like base on x reason this would take longer".
Source: [docs/DEMO_REQUIREMENTS.md](../docs/DEMO_REQUIREMENTS.md), [OPEN_PROBLEMS.md](../OPEN_PROBLEMS.md) P48. This fills the "small acknowledgements: absent" row of [LIVE_CONVERSATION_ARCHITECTURE.md](../docs/LIVE_CONVERSATION_ARCHITECTURE.md) §1.
Depends on: ADR-016 / SPEC-019 (one assistant voice), I-1 (no claim before proof), SPEC-012 (`SpeechArbiter` decides whether she may speak).

## Goal

The driver never waits in silence wondering whether she heard:
- When a reply is slow, she acknowledges at once.
- When it is very slow, she says why, truthfully.

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
- a `ResponseDone` of this turn's reply when nothing of it was audible and no tool is outstanding (`turn_done`: no answer is coming, so no cue may say one is). A tool is outstanding when a tool call came and no `ResponseStarted` after it.
- the session ending.

Each cue below is spoken at most once per driver turn.

| # | When nothing of her reply is audible yet | She says (exact text) | Reason code in the log |
| --- | --- | --- | --- |
| C1 | 1.0 s, and a tool call has been seen in this turn | 收到，正在处理。 | `ack_action` |
| C2 | 1.8 s, and no tool call has been seen | 嗯，我想想。 | `ack_chat` |
| C3 | 5.0 s, and no reply has started (no `ResponseStarted` since the end of speech) | 网络有点慢，请稍等。 | `provider_slow` |
| C4 | 5.0 s, and a tool call is in this turn | the tool's phrase from the table below | `tool_running` |
| C5 | 5.0 s, and a reply has started but none of it is audible (held or still being generated) | 我确认一下，马上回答你。 | `verifying` |
| C6 | 12.0 s | 还在处理，网络可能不太稳定，再等我一下。 | `still_waiting` |

Only one of C1 or C2 is spoken per turn, and only one of C3, C4 or C5.

**Turn evidence.** A cue is spoken only when something says the driver really spoke to her: a `ResponseStarted` or a tool call in this turn, or an uplink segment that `SpeechUplinkGate.Segment.isSuspicious()` does not flag.
- Without evidence (a cough, a knock, a passenger's murmur that the model ignores), no cue is spoken. The log records `wait_cue_skipped reason=no_turn_evidence` once.
- Evidence that arrives later (the model's `ResponseStarted` or tool call) makes a due acknowledgement fire on arrival, while less than 5 s have passed.

Tool phrases for C4. Any tool not listed uses the default.

| Tool | Phrase |
| --- | --- |
| `navigate_to`, `choose_navigation_option` | 正在搜索路线，稍等一下。 |
| `query_live_info` | 正在查询，稍等一下。 |
| `play_music`, `control_music` | 正在找歌，稍等一下。 |
| `describe_camera_view` | 正在看画面，稍等一下。 |
| default | 正在处理，稍等一下。 |

Rules:
- **Never claims an outcome.** Every cue text passes `ActionClaimGuard.carActionClaimMatch == null` and `claimsDone == false`, and a unit test enforces this. A cue says she is working on it, never that it is done.
- **Never two voices.** A cue is spoken only through the `AssistantVoice`. With the assistant voice off (Gemini speaks itself), no cue is spoken; the log still records `wait_cue_skipped reason=no_assistant_voice`.
- **Silent modes.** No cue in SILENT_WAIT (「闭嘴」), sleep, or while a GUIDANCE prompt is open. These states already stop the voice or hold playback. A cue must not reopen them.
- **No overlap.** If the real reply's first clause is ready while a cue is playing, the cue finishes first, then the reply follows without a gap. Cues are short (≤ 1.2 s of audio). A cue never cuts the reply, and no cue is ever spoken between a reply's clauses: once reply words are queued, the turn's cues are over.
- **A cue failure is not a reply failure.** If a cue cannot be synthesised, `wait_cue_failed` is logged and the reply still plays.
- **Cached audio.** Each cue text is synthesised once per speaking style and cached, so a cue starts within ~50 ms of its timer, not after a synthesis round trip. The cache is filled lazily; the first use may pay one synthesis.
- **Logs** hold counts and codes only: `wait_cue code=<reason> after_ms=<n>` (after_ms from his last word; logged only when the cue was actually queued), `wait_cue_cancelled reason=<…>`, `wait_cue_skipped reason=<…>` and `wait_cue_failed`. Never the driver's or the model's words (I-8).

## Acceptance

| # | Criterion | Proven by | State |
| --- | --- | --- | --- |
| A1 | C1/C2 fire at their thresholds, counted from his last word, when no reply is under way; not when the reply was queued or audible first, including events seen before his end of speech | `AssistantVoiceRevoicerWaitCueTest` (virtual clock), `WaitCueCoreIntegrationTest` | see the Status line |
| A2 | C3/C4/C5 pick the right reason from the events seen since his onset; C6 at 12 s; each at most once per turn; no cue after a `ResponseDone` with nothing audible, and none without turn evidence | same | see the Status line |
| A3 | A reply arriving during a cue plays after it, in order, with nothing lost; no cue between a reply's clauses; a cancel stops both; a cue never blocks tool-result delivery | same, plus ingress `WaitCueAudioTest` | see the Status line |
| A4 | No cue text is a claim | `ActionClaimGuardTest` over every cue string | see the Status line |
| A5 | No cue with the assistant voice off, in SILENT_WAIT, or with a guidance prompt open | `AssistantVoiceRevoicerWaitCueTest`, `GeminiLiveProviderVoiceTest` | see the Status line |
| A6 | Emulator: on every slow turn the driver hears 「收到，正在处理。」 about 1.0 s, or 「嗯，我想想。」 about 1.8 s, after his last word (+0.5 s tolerance for cue start), and the reply follows it without overlap. On a tool turn, the tool result is still delivered | `wait_cue` log lines plus the demo recorder's audio | open (device) |

A6 was first written as "within 1.5 s", which contradicted C2 = 1.8 s. It was reconciled on 2026-10-08 (planner) to the behaviour table, which is the owner-approved timing.

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

Status: A1–A5 unit and in-process integration tests pass on the JVM (2026-10-08, cloud, claude/10-8). The independent re-review is recorded in the merge commit. A6 needs the emulator.
