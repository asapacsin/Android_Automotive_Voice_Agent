# SPEC-018 — Turn-by-turn guidance spoken in 小诺's voice, Amap's voice only as a fallback

Status: **Draft 2026-09-30, revision 2** — revised after the design review (REVISE, 10 required
changes, all taken). Code may start for the L2 parts (step 1); the deadline and fallback mechanism
is fixed only after the emulator measurements (step 0).
Raised: 2026-09-30 · Source: [ADR-014](../DECISIONS/ADR-014-guidance-spoken-by-assistant.md) (owner)
Depends on: SPEC-012 (`SpeechArbiter` owns who may speak — extended, not bypassed); SPEC-016 Part A
(Gemini default); I-1, I-8, I-11, I-13; the owner's test-run policy (short route 横琴创业谷 → 励骏庞都)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

While navigating, the driver hears one voice. Every guidance sentence Amap produces is spoken by
the realtime model in 小诺's voice, word for word. When the model cannot speak it in time — or
speaks it wrong — Amap's offline voice speaks that same sentence. Safety over the one-voice rule,
the only exception. **Guidance is never silently lost, and never spoken by both voices at once.**

## Facts this rests on

From the SDK jar (11.2.100): `AMapNavi.setUseInnerVoice(boolean useInnerVoice, boolean
callbackText)`, `playTTS(String, boolean forcePlay)`, static `setTtsPlaying(boolean)`,
`AMapNaviListener.onGetNavigationText(int, String)` exist.

From the code at `20c14c7` (design review):

- Reply audio plays only in ACTIVE: `AndroidPlaybackPort(player, audioFocus) { lifecycle.speaks }`
  (`VoiceSessionController.kt:47`), non-ACTIVE chunks dropped `reason=not_active`
  (`PcmAudioPlayer.kt:680-686`).
- `VoiceSessionGateway.speak` calls `start("app_prompt")`, which wakes SLEEP and opens sessions.
- `sendText` queues in `pendingTexts` and flushes on reconnect (ingress `VoiceSessionController`).
- Gemini has no client cancel; the core ignores `ToolCallCancelled`; a text turn clears
  `listeningSuspended` (`GeminiLiveClient.kt:181`).
- A response is attributed to the current driver turn (`GeminiLiveClient.kt:460-476` →
  `DriverTurnPipeline.kt:120-135`); nothing correlates a sent prompt with its response.
- Gemini sliding-window compression and session resumption are already on
  (`GeminiLiveProtocol.kt:77-78`); goAway reconnects will be routine on a route.
- `AmapGuidanceVoice.onPlayStart` feeds the translated-ABI text panel and `HostAudioTap`.

## Step 0 — measurements before the fallback is fixed (emulator, short route)

| # | Question | Why it matters |
| --- | --- | --- |
| G-1 | Text → **played** model audio, per listening state (ACTIVE, SLEEP) | Sets `deadlineMs` |
| G-1b | Does `playTTS(text, true)` speak with the inner voice off? **If not: stop and escalate** — toggling the inner voice cannot re-speak the current sentence, so ADR-014's "same sentence" fallback cannot be met | The fallback itself |
| G-1b+ | Does `playTTS` fire `TTSPlayListener.onPlayStart/End`? | Without it the fallback bypasses the P3 uplink gate; then the relay brackets it itself |
| G-1c | Does `setTtsPlaying(true)` delay/suppress later `onGetNavigationText`? Can a stuck `true` mute everything? | Reset rules |
| G-1d | Does a `clientContent` turn interrupt Gemini generation (`interrupted`)? With a function call outstanding, does `toolCallCancellation` arrive? | B3/B4 rules |
| G-1e | Output-transcription accuracy on guidance phrases | Fidelity threshold N |
| G-1f | Does `onGetNavigationText` fire with `setUseInnerVoice(false, true)` in emulator mode; which `type` values; does CONCISE filter text; Amap's own text→speech latency (the baseline) | Whether the relay gets text at all |

## Architecture

| Behaviour | Owner | Not owned by |
| --- | --- | --- |
| Per prompt: who speaks it, the PENDING → ASSISTANT/AMAP claim, the deadline, one-at-a-time queue, cooldown | `GuidanceRelay` (new, `app/nav`) | the adapter, the model, `AmapGuidanceVoice` |
| Muting Amap, receiving its text, the fallback `playTTS`, `setTtsPlaying` brackets and resets | `AmapGuidanceVoice` (extended) | anything above the SDK edge |
| Whether a chunk may play — guidance or reply — in every listening state; the uplink during either; DROP for an abandoned prompt | `SpeechArbiter` (extended). The playback port's `playbackAllowed()` asks the arbiter for guidance chunks instead of `lifecycle.speaks` | `ListeningLifecycle.speaks` (for guidance), `GuidanceRelay`, the player |
| Correlating a sent prompt with the response it produces (`GUIDANCE(promptId)`) | the provider client (arms "next opened turn") + the session core (propagates the kind to pipeline and playback) | the relay's guess |
| A guidance turn is not a driver turn: no claim judgement | `DriverTurnPipeline` (extended) | the model |
| Tool calls inside a guidance turn do not execute | `AndroidToolDispatcher` via the existing rejected-call path (`RealtimeToolCatalog.rejectedCall(…, "NOT_A_DRIVER_TURN")`) | the pipeline (it never gates execution) |
| Sending a prompt without starting or waking anything | `VoiceSessionGateway.sendPrompt(text, kind)` (extends the gateway; `speak` unchanged) | a second send path |
| Staying connected while navigating, also in SLEEP and after connection loss | `ListeningLifecycle` (extended) | `GuidanceRelay` |
| Which providers can speak a verbatim prompt | `ProviderCapabilities.verbatimPromptSpeech` (Gemini true, Baidu false) | a provider-name check (I-13) |

## Correlation contract (planner decision, 2026-09-30 — step 1)

Provider-neutral, extended by capability (ADR-009 §3), in `ingress`:

- `ProviderCapabilities.verbatimPromptSpeech: Boolean = false` (Gemini true, Baidu false).
- `RealtimeVoiceProvider.sendPrompt(text: String, promptId: String): Boolean` — default `false`.
  Send now or fail: never queued, never replayed after reconnect; `false` when not connected.
- `DomainVoiceEvent.AppPromptTurn(promptId: String, phase: Phase)`, `Phase { OPENED, COMPLETED, VOIDED }`.
  The provider emits OPENED **in stream order before** the first `AudioDelta` of the response to that
  prompt; COMPLETED at its turn end; VOIDED when that response is interrupted, cut by a connection
  loss, or pre-empted by a driver onset before COMPLETED. An armed prompt whose response never
  opens is VOIDED on the next driver onset or turn.
- Core (`ingress/VoiceSessionController`): `sendPrompt` delegates to the provider only when
  `connectedNow`, else returns false (never `pendingTexts`). `AppPromptTurn` events are forwarded in
  order to `PlaybackPort.onAppPromptTurn(promptId, phase, epoch)` (default no-op) and to
  `VoiceSessionCallbacks.onAppPromptTurn` (default no-op), so audio enqueue and the marker keep
  their order in the one event loop.
- Gemini client: arms "next opened turn = GUIDANCE(promptId)" at `sendPrompt`; does **not** clear
  `listeningSuspended`; inside a GUIDANCE turn the pipeline does not open a driver turn or judge the
  reply, and any tool call is emitted as `RealtimeToolCatalog.rejectedCall(id, name,
  "NOT_A_DRIVER_TURN")`, which the dispatcher already fails without executing.
- App gateway: `VoiceSessionGateway.sendPrompt(text, promptId): Boolean` — no `start`, no
  activation; false when there is no active, non-failed session.

## Code review of step 1 (2026-09-30): REVISE — decisions taken (revision 3)

- **Typed transcript path (R5, R6).** A GUIDANCE turn's output transcription is emitted as
  `DomainVoiceEvent.AppPromptTranscript(promptId, text)` — never as `AssistantTranscript` — until its
  turnComplete, including text after generationComplete. The core forwards it to
  `VoiceSessionCallbacks.onAppPromptTranscript(promptId, text)` (default no-op). Guidance text
  therefore never reaches `onTranscript`, the UI transcript, logs or the service notification (I-8).
  COMPLETED (turnComplete) follows the last transcript, so fidelity is judged at
  max(COMPLETED, drained) on the typed text; the transcript grace is deleted.
- **No prompt while any turn is open (R4).** Until G-1d is measured, `sendPrompt` returns false
  while a driver onset is outstanding or any response turn is open; `guidanceBlocker` also returns
  RESPONSE_OPEN while the UI state is THINKING.
- **A voided open prompt goes silent (R3).** The client stops emitting that turn's audio once it
  emitted VOIDED for it.
- **Every VOIDED and every session stop reach the relay (R1).** PENDING → abandon + Amap now;
  ASSISTANT not drained → cut (B2a); ASSISTANT drained → release + B2a, then pump.
- **Stop clears guidance state (R2).** `AndroidPlaybackPort.stop()` and session end clear the
  tracker, the arbiter's open/assistant guidance and the transcript collector.
- **G-2 only when it is used (R7).** The lifecycle keeps the connection in SLEEP while navigating
  only when the relay is active (toggle on) and the provider has `verbatimPromptSpeech`; otherwise
  today's timers apply unchanged.
- **Claim at playout; drained means ended (R8).** ASSISTANT is claimed when the player actually
  starts playing a guidance chunk (not at enqueue). Built approximation: the claim is made when
  queued guidance becomes audible — at enqueue while the player is not held, or as the hold lifts —
  so COMPLETED may arrive while the prompt is still PENDING; it is recorded (not claimed, deadline
  kept) and judged once claimed and drained. "Drained" = COMPLETED seen, no queued chunk of
  the prompt, and the player idle; an early idle (underrun) is not a drain.
- **Amap never over itself (G-1b+).** A fallback is spoken only when Amap is not speaking; the relay
  brackets its own `playTTS` for the arbiter (listener if G-1b+ shows it fires, else a length-based
  estimate), so no assistant prompt starts over it.

## Behaviour

- **B1. Route per prompt.** The relay handles one prompt at a time. A new prompt goes to **Amap at
  once** when any of these hold: the provider lacks `verbatimPromptSpeech`; the session is not
  connected, is reconnecting or has failed; the driver is mid-utterance; a driver response is open
  with no audio yet or tool work is pending (`hasPendingWork()`) — until G-1d shows interruption
  is harmless; Amap is itself speaking (R1); transient focus loss (R5); the relay is in cooldown
  (B2); fidelity strikes reached N (B7). Otherwise it is sent with `sendPrompt(text, GUIDANCE)`
  as 「请一字不改地朗读下面这句导航提示，不要加任何别的话：<text>」.
- **B1a. Send now or fail.** `sendPrompt` never queues (never enters `pendingTexts`), never starts
  a session, never changes the listening state, and does not clear `listeningSuspended`. It returns
  failure when there is no connected, non-failed session → Amap.
- **B2. Deadline and the claim.** Each prompt has one state, PENDING → ASSISTANT | AMAP, claimed
  atomically on one thread. ASSISTANT is claimed by the playback port when the first guidance chunk
  is actually **played** (not merely received) — as built, when queued guidance becomes audible
  (enqueue while unheld, or hold lift); see R8. If `deadlineMs` (default 1200 ms, set from G-1)
  passes first, AMAP is claimed: Amap speaks the sentence and the arbiter answers DROP for every
  chunk of that prompt's response. Same-tick races resolve to whichever claim lands first; the
  other side is a no-op. Two consecutive AMAP fallbacks → cooldown: Amap for the next 60 s, then
  the relay tries the assistant again.
- **B2a. Interrupted or lost.** A prompt claimed ASSISTANT whose audio is flushed, interrupted or
  cut by a connection loss before `turnComplete` → Amap re-speaks the whole sentence immediately.
- **B3. Priority.** One guidance at a time; a prompt that arrives while another plays waits (FIFO)
  and its deadline runs from enqueue; if it expires while waiting it goes to Amap **after** the
  current one ends, never over it. Guidance pre-empts chatter: a driver reply that is playing is
  flushed and **lost** (the chip stays on screen; the driver can ask again). Guidance chunks are
  exempt from the P1 mute (R6) and the workload hold (R6a): they *are* the manoeuvre prompt.
- **B4. Microphone.** The uplink is closed while assistant guidance plays and for the tail after
  it, exactly as for Amap guidance (R1/R2/R3), with R0 driver-utterance protection unchanged. If
  G-1b+ shows `playTTS` fires no listener, the relay brackets the fallback for the arbiter itself.
- **B5. Not a driver turn.** The client arms "next opened turn = GUIDANCE(promptId)" at send; a
  driver onset, an interrupted or unrelated turn voids the mark (voiding → Amap, B2a). A GUIDANCE
  turn has no driver epoch and its reply is not judged for claims. Any tool call in it is turned
  into `rejectedCall(…, "NOT_A_DRIVER_TURN")` by the client; the dispatcher returns failed without
  executing, with `next` 「这是导航播报，不需要回应」 (no speech wanted).
- **B6. Listening states.** Guidance is not a reply: 「闭嘴」「休眠」 and the wake word do not cancel
  it (if one does cut it, B2a re-speaks via Amap). Guidance plays in ACTIVE, SILENT_WAIT and SLEEP
  (the arbiter's exemption) and never changes the listening state. While navigating: SLEEP does
  not time out to DEEP_IDLE, and a connection lost in SLEEP reconnects instead of going to
  DEEP_IDLE (owner G-2). Navigation that **starts** while already in DEEP_IDLE uses Amap until the
  driver next wakes 小诺; from then on the session stays connected until navigation ends.
- **B7. Fidelity.** The output transcription of each GUIDANCE turn is compared with the Amap text
  on direction words (左/右/掉头/直行/靠左/靠右/出口/匝道/环岛) and numbers (Arabic and Chinese
  numerals normalised). A mismatch → Amap re-speaks the correct sentence **immediately** (the
  wrong one was already heard). `unknown` (no transcript) → no re-speak, but it counts as a strike.
  After N strikes in one navigation (default 2, set from G-1e), Amap speaks for the rest of it.
- **B8. SDK bookkeeping.** `AMapNavi.setTtsPlaying(true/false)` brackets assistant guidance if
  G-1c shows it helps; it is reset to false on fallback, session loss and navigation end.
- **B9. Context.** Compression is already on; a protocol-shape test pins it.
- **B10. Collateral.** The text panel (`textReceiver`) and `HostAudioTap.guidanceSink` move from
  `onPlayStart` to the `onGetNavigationText` path, so they keep working with Amap muted. The text
  is never logged.

## Non-goals

Proactive suggestions (「前方拥堵，换路线吗？」) — B-026 still excludes them. Rewording guidance —
it is verbatim. Baidu sessions — they keep Amap's voice (`verbatimPromptSpeech=false`). Opening a
session by itself when navigation starts in DEEP_IDLE (it would open the microphone unasked).

## Failure behaviour

| Case | Driver hears |
| --- | --- |
| No/failed/reconnecting session, provider without the capability, DEEP_IDLE at navigation start | Amap, every prompt |
| Model not playing by the deadline | Amap for that prompt; the model's late audio is dropped |
| Model guidance flushed/interrupted/connection lost mid-sentence | Amap re-speaks the whole sentence |
| Driver mid-utterance, driver answer or tool work pending | Amap for that prompt |
| Model rewords a direction or number | Amap re-speaks the correct sentence at once; N strikes → Amap for the route |
| Model calls a tool in a guidance turn | Nothing executes (`NOT_A_DRIVER_TURN`) |
| 「闭嘴」/「休眠」/wake word during guidance | Guidance continues; if cut, Amap re-speaks it |
| G-1b fails (`playTTS` silent with inner voice off) | Work stops; escalated to the owner (ADR-014's fallback cannot be met) |

## Documents amended with the code

SPEC-012's non-goal "re-speaking Amap's guidance ourselves"; ADR-014 §2 ("through
`VoiceSessionGateway.speak`" → `sendPrompt`); `docs/ARCHITECTURE.md` guidance-voice and arbiter
rows; the `ListeningLifecycle` KDoc ("replies are spoken only in ACTIVE" — guidance is not a reply).

## Observability

`guidance_route prompt=<id> to=<assistant|amap> reason=<code> waited_ms=<n>`,
`guidance_claim prompt=<id> side=<assistant|amap>`, `guidance_fidelity prompt=<id>
result=<match|mismatch_direction|mismatch_number|unknown>` — joinable with `speech_arbiter` lines.
**Never the guidance text** (roads and places — I-8).

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A0 | G-1 … G-1f measured on the short route | measurement (emulator) | `GUIDANCE-EMU-001` | not earned |
| A1 | Relay: B1 routing table, FIFO, deadline from enqueue, cooldown, same-tick claim both ways | functional | `GuidanceRelayTest` (fake clock/session/SDK) | not built |
| A2 | Arbiter: guidance plays in every listening state and outside P1/R6a; DROP for an AMAP-claimed prompt; chatter flushed; guidance vs R1 and R5 | functional | `SpeechArbiterGuidanceTest`; `SpeechRulesCharacterizationTest` unchanged | not built |
| A3 | Correlation and turn semantics: GUIDANCE turn not judged; voided by driver onset; tool call → `NOT_A_DRIVER_TURN`, not executed | negative | `GeminiLiveClientTest`, `DriverTurnPipelineTest`, `AndroidToolDispatcherTest` (+cases) | not built |
| A4 | Lifecycle: no DEEP_IDLE and reconnect-in-SLEEP while navigating; 闭嘴/休眠/wake word do not cancel guidance; `sendPrompt` never starts/wakes/queues/clears suspension | functional | `ListeningLifecycleTest`, `VoiceSessionGatewayTest` (+cases) | not built |
| A5 | Fidelity comparator and strike rules | functional | `GuidanceFidelityTest` | not built |
| A6 | Capability flag, no provider-name branch; compression pinned | architectural | `ArchitectureRulesTest`, `GeminiLiveProtocolTest` | not built |
| A7 | Text panel and host tap fed from the text callback | regression protection | `AmapGuidanceVoiceTest` | not built |
| A8 | One voice on the short route; fallback audible with the network cut | device | `GUIDANCE-DEVICE-001` (HUMAN_PHYSICAL) | not earned |
| A9 | APK builds | artifact | `:app:assembleDebug` | not built |

## Implementation order

Step 1 (cloud, L2): correlation + `sendPrompt` + dispatcher rejection + arbiter rows + lifecycle +
relay state machine with an injectable deadline + collateral move — behind a relay switch that stays
**off** (Amap voice as today) until step 0 passes. Step 2: measurements (emulator). Step 3: set the
deadline/N, switch the relay on, device row.

## Open product decisions

None: the owner decided the fallback (ADR-014) and G-2. "Navigation starting in DEEP_IDLE uses Amap
until the driver wakes 小诺" is a planner decision (not opening the microphone unasked). G-1b failing
would reopen ADR-014 and is escalated, not decided here.

## Implementation status

| Area | State | Proof |
| --- | --- | --- |
| Design review | done — REVISE, all 10 changes taken (revision 2) | reviewer report 2026-09-30 |
| Step 1 | step 1 built (L2), behind the developer toggle (off); review fixes applied; re-review I1 fixed | 68635ac (contract), 5e0d909 (speech owners), 144f3fb (relay, fidelity, Amap edge); code review in progress |

**2026-10-10:** the toggle moved into the developer screen's collapsed 诊断工具 as 「助手播报导航（实验）」. A Qwen session cannot relay guidance (`verbatimPromptSpeech` is false), but a stored `true` still mutes Amap's inner voice and routes prompts through the relay's Amap-TTS fallback (`AmapGuidanceVoice.kt:70,79`), so the switch stays reachable.

