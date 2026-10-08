# SPEC-020 — Wait cues: she says she is on it, and says why when it takes longer

Status: **Authorised 2026-10-08** by the owner: "if this require long term wait you should make something like i m currently start doing this task, and if take long you say something like base on x reason this would take longer".
Source: [docs/DEMO_REQUIREMENTS.md](../docs/DEMO_REQUIREMENTS.md), [OPEN_PROBLEMS.md](../OPEN_PROBLEMS.md) P48. This fills the "small acknowledgements: absent" row of [LIVE_CONVERSATION_ARCHITECTURE.md](../docs/LIVE_CONVERSATION_ARCHITECTURE.md) §1.
Depends on: ADR-016 / SPEC-019 (one assistant voice), I-1 (no claim before proof), SPEC-012 (`SpeechArbiter` decides whether she may speak).

## Goal

The driver never waits in silence wondering whether she heard:
- When a reply is slow, she acknowledges at once.
- When it is very slow, she says why, truthfully.

## Owner

`AssistantVoiceRevoicer`. It already sees the driver's speech events, the reply boundaries, the tool calls and every reply clause it voices. The cue is fixed app wording spoken through the same `AssistantVoice`, so there is still exactly one voice. Cue audio goes out as ordinary reply audio on the same playback path, so `SpeechArbiter` and barge-in apply to it unchanged.

Clock source (deviation, 2026-10-08): Gemini sends no speech events of its own; the session core synthesises `SpeechStarted`/`SpeechStopped` downstream of the provider stream. So `GeminiLiveProvider.onLocalSpeechActivity` forwards the local end of speech to `AssistantVoiceRevoicer.onDriverSpeech`. Stream `SpeechStarted`/`SpeechStopped` (Baidu) reach the same method. One clock per driver turn, whichever arrives first.

Not owners: the model or the prompt (the cue must hold when the model is slow or silent), `DriverTurn` (cues claim nothing, so there is nothing to judge), and the UI.

## Behaviour

The clock starts at the driver's end of speech (`SpeechStopped`). It stops at the first audio of her real reply, at the driver's next `SpeechStarted`, at a barge-in or cancel (`cancelCurrentReply`), or when the session ends. Each cue below is spoken at most once per driver turn.

| # | When nothing of her reply is audible yet | She says (exact text) | Reason code in the log |
| --- | --- | --- | --- |
| C1 | 1.0 s, and a tool call has been seen in this turn | 收到，正在处理。 | `ack_action` |
| C2 | 1.8 s, and no tool call has been seen | 嗯，我想想。 | `ack_chat` |
| C3 | 5.0 s, and no reply has started (no `ResponseStarted` since the end of speech) | 网络有点慢，请稍等。 | `provider_slow` |
| C4 | 5.0 s, and a tool call is in this turn | the tool's phrase from the table below | `tool_running` |
| C5 | 5.0 s, and a reply has started but none of it is audible (held or still being generated) | 我确认一下，马上回答你。 | `verifying` |
| C6 | 12.0 s | 还在处理，网络可能不太稳定，再等我一下。 | `still_waiting` |

Only one of C1 or C2 is spoken per turn, and only one of C3, C4 or C5.

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
- **No overlap.** If the real reply's first clause is ready while a cue is playing, the cue finishes first, then the reply follows without a gap. Cues are short (≤ 1.2 s of audio). A cue never cuts the reply.
- **Cached audio.** Each cue text is synthesised once per speaking style and cached, so a cue starts within ~50 ms of its timer, not after a synthesis round trip. The cache is filled lazily; the first use may pay one synthesis.
- **Logs** hold counts and codes only: `wait_cue code=<reason> after_ms=<n>` and `wait_cue_cancelled reason=<…>`. Never the driver's or the model's words (I-8).

## Acceptance

| # | Criterion | Proven by | State |
| --- | --- | --- | --- |
| A1 | C1/C2 fire at their thresholds when no reply audio exists, and not when reply audio started first | `AssistantVoiceRevoicerWaitCueTest`, `WaitCueCoreIntegrationTest` | unit + integration PASS 2026-10-08 |
| A2 | C3/C4/C5 pick the right reason from the events seen; C6 at 12 s; each at most once per turn | same | unit + integration PASS 2026-10-08 |
| A3 | A reply arriving during a cue plays after it, in order, with nothing lost; a cancel stops both | same | unit + integration PASS 2026-10-08 |
| A4 | No cue text is a claim | `ActionClaimGuardTest` over every cue string | unit PASS 2026-10-08 |
| A5 | No cue with the assistant voice off, in SILENT_WAIT, or with a guidance prompt open | `AssistantVoiceRevoicerWaitCueTest`, `GeminiLiveProviderVoiceTest` | unit PASS 2026-10-08 |
| A6 | Emulator: the driver hears 「嗯，我想想。」 or 「收到，正在处理。」 within 1.5 s of his last word on every slow turn, and the reply follows it without overlap | `wait_cue` log lines plus the demo recorder's audio | open (device) |

Quiet: the app passes `lifecycle.speaks` through `RealtimeProviderFactory.build(repliesSpoken)` to `GeminiLiveProvider`, which builds the revoicer with `quiet = { !repliesSpoken() }`, so SILENT_WAIT and sleep speak no cue (`wait_cue_skipped reason=quiet`; an open GUIDANCE prompt logs `reason=guidance_prompt`). With the assistant voice off there is no revoicer; the provider logs `wait_cue_skipped reason=no_assistant_voice` at each end of speech.

Cue framing: the session core plays reply audio only inside an open reply stamp that has not completed; after a finished or interrupted reply it drops audio until the next `ResponseStarted`. So the revoicer sends `ResponseStarted` before a cue's first chunk and `AudioDone` after its last. If a provider reply is open (its `ResponseStarted` forwarded, no `AudioDone`/`ResponseDone`/`Interrupted`/`Error`/`Closed` or cancel since), it sends `ResponseStarted` once more, so the rest of that reply gets a fresh stamp. An interrupted reply is never reopened, so its stray words stay silent.
