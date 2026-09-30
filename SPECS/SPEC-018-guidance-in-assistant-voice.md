# SPEC-018 — Turn-by-turn guidance spoken in 小诺's voice, Amap's voice only as a fallback

Status: **Draft 2026-09-30** — design to be checked by the reviewer before any code (planner rule:
it changes the speech state machine)
Raised: 2026-09-30 · Source: [ADR-014](../DECISIONS/ADR-014-guidance-spoken-by-assistant.md) (owner)
Depends on: SPEC-012 (`SpeechArbiter` owns who may speak — extended, not bypassed); SPEC-016 Part A
(Gemini default); I-1, I-8, I-11, I-13; the owner's test-run policy (short route 横琴创业谷 → 励骏庞都)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

While navigating, the driver hears one voice. Every guidance sentence Amap produces is spoken by
the realtime model in 小诺's voice, word for word. When the model cannot speak it in time, Amap's
offline voice speaks that same sentence — safety over the one-voice rule, the only exception.

## Facts this rests on (measured from the SDK jar, 11.2.100)

`AMapNavi.setUseInnerVoice(boolean useInnerVoice, boolean callbackText)`, `playTTS(String, boolean
forcePlay)`, static `setTtsPlaying(boolean)`, `AMapNaviListener.onGetNavigationText(int, String)`
all exist. **Not yet measured:** whether `playTTS` speaks while the inner voice is off (G-1b), and
how long the model takes from text to first audio (G-1). Both are measured on the emulator on the
standard short route before the timeout and the fallback mechanism are fixed.

## Architecture

| Behaviour | Owner | Not owned by |
| --- | --- | --- |
| Who speaks the next guidance sentence (assistant or Amap), per prompt | `GuidanceRelay` (new, `app/nav`) | the adapter, the model, `AmapGuidanceVoice` |
| Muting Amap's inner voice and receiving its text; the fallback `playTTS` | `AmapGuidanceVoice` (extended) | anything above the SDK edge |
| Whether guidance audio / reply audio may play, and the uplink during either | `SpeechArbiter` (extended: new input `onAssistantGuidance`) | `GuidanceRelay`, the player |
| Guidance turns are not driver turns: no claims judged, no tools executed | `DriverTurnPipeline` (extended: `AppPromptKind.GUIDANCE`) | the model's obedience |
| The session stays connected while navigating, even in SLEEP | `ListeningLifecycle` (extended: no SLEEP → DEEP_IDLE while navigating) | `GuidanceRelay` |
| Which providers can speak a verbatim prompt | `ProviderCapabilities.verbatimPromptSpeech` (Gemini true, Baidu false) | a provider-name check (I-13) |

## Behaviour

- **B1. Route per prompt.** On a guidance text: if the provider has `verbatimPromptSpeech`, the
  session is connected and healthy, and the driver is **not** mid-utterance, the relay sends a
  verbatim prompt (「请一字不改地朗读下面这句导航提示，不要加任何别的话：<text>」) as an app-prompt
  turn of kind GUIDANCE. Otherwise it goes to Amap at once.
- **B2. Deadline.** If the first audio of that turn has not arrived within `deadlineMs` (default
  1200 ms; set from G-1), the relay marks the turn to be dropped client-side and Amap speaks the
  same sentence (`playTTS(text, true)`, or the inner voice if G-1b shows `playTTS` needs it).
  After two consecutive fallbacks the relay stays on Amap until the session next completes a
  normal turn.
- **B3. Priority.** Guidance pre-empts chatter: a reply that is playing is flushed (not resumed)
  when guidance starts; a reply that has not started is held until the guidance ends. Guidance
  audio is exempt from the P1 mute (R6) and the workload hold (R6a): it *is* the manoeuvre prompt.
- **B4. Microphone.** The uplink is closed while assistant guidance plays and for the tail after
  it, exactly as for Amap guidance (R1/R2/R3), with R0 driver-utterance protection unchanged.
- **B5. Not a driver turn.** A GUIDANCE turn has no driver epoch; `DriverTurn` does not judge its
  reply for claims; any tool call inside it is rejected with `NOT_A_DRIVER_TURN` and not executed.
- **B6. Sleep.** Guidance never changes the listening state. While navigating, SLEEP does not time
  out to DEEP_IDLE (owner G-2: stay connected for the whole navigation); the normal timer resumes
  when navigation ends.
- **B7. Fidelity.** The output transcription of each GUIDANCE turn is compared with the Amap text
  on direction words (左/右/掉头/直行/靠左/靠右/出口/匝道/环岛) and on numbers (Arabic and Chinese
  numerals normalised). A mismatch is counted and logged by kind; after one mismatch the next
  prompt goes to Amap (conservative), then assistant routing resumes.
- **B8. SDK bookkeeping.** `AMapNavi.setTtsPlaying(true/false)` brackets assistant guidance so the
  SDK knows speech is busy (effect to be measured, G-1c).
- **B9. Context growth.** GUIDANCE turns enter the model's context; the Gemini setup enables
  sliding-window context compression if not already on, so a long session cannot exhaust it.

## Non-goals

Proactive suggestions (「前方拥堵，换路线吗？」) — B-026 still excludes them. Rewording guidance —
it is spoken verbatim. Baidu sessions — they keep Amap's voice (`verbatimPromptSpeech=false`).

## Failure behaviour

| Case | Driver hears |
| --- | --- |
| No session / session failed / provider without the capability | Amap's voice, every prompt |
| Model slower than the deadline | Amap's voice for that prompt; the late model audio is dropped |
| Driver mid-utterance | Amap's voice for that prompt; the driver's turn is not polluted |
| Model rewords a direction or number | It is counted; the next prompt goes to Amap |
| Model calls a tool in a guidance turn | Nothing executes (`NOT_A_DRIVER_TURN`) |

## Observability

`guidance_route to=<assistant|amap> reason=<code> waited_ms=<n>`,
`guidance_fidelity result=<match|mismatch_direction|mismatch_number|unknown>`. **Never the
guidance text** (it names roads and places — I-8).

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Relay routing table B1/B2 incl. two-strike stay-on-Amap | functional | `GuidanceRelayTest` (fake clock, fake session, fake SDK edge) | not built |
| A2 | Arbiter: guidance PLAY outside P1 window and in the workload zone; chatter HOLD/flush; uplink closed + tail | functional | `SpeechArbiterGuidanceTest` + existing `SpeechRulesCharacterizationTest` unchanged | not built |
| A3 | GUIDANCE turns: no claim judgement, tool calls rejected | negative | `DriverTurnPipelineTest` (+cases) | not built |
| A4 | Lifecycle: no DEEP_IDLE while navigating; guidance never activates listening | functional | `ListeningLifecycleTest` (+cases) | not built |
| A5 | Fidelity comparator | functional | `GuidanceFidelityTest` | not built |
| A6 | Capability flag, no provider-name branch | architectural | `ArchitectureRulesTest` | not built |
| A7 | G-1 latency, G-1b `playTTS` with inner voice off, G-1c `setTtsPlaying` | measurement (emulator, short route) | `GUIDANCE-EMU-001` | not earned |
| A8 | One voice on the short route; fallback audible when the network is cut | device | `GUIDANCE-DEVICE-001` (HUMAN_PHYSICAL) | not earned |
| A9 | APK builds | artifact | `:app:assembleDebug` | not built |

## Open product decisions

None: the owner decided the fallback (ADR-014) and G-2. The deadline is a measured tunable.

## Implementation status

| Area | State | Proof |
| --- | --- | --- |
| Design review | not done | — |
| Relay, arbiter, pipeline, lifecycle | not built | — |
