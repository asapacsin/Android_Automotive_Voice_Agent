# SPEC-013 — Talk to 小诺 through Gemini Live, opt-in, with the same truth rules as Baidu

Status: **Partly implemented 2026-09-29** — code and cloud evidence; device rows A7/A8 not earned
Raised: 2026-09-29 · Source: [B-027](../BACKLOG.md)
Depends on: [ADR-010](../DECISIONS/ADR-010-gemini-live-second-provider.md), ADR-009, I-1, I-7, I-8, I-13

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

The owner chose Gemini 3.8 Live Extended Thinking for its native reasoning and non-blocking tools.
The driver must be able to hold the same conversation with it as with Baidu Flex — same tools, same
rule that nothing is claimed before it happened, same listening lifecycle — with Baidu untouched
as the default.

## Scope

- A Gemini Live adapter behind `RealtimeVoiceProvider` (architecture:
  [GEMINI_LIVE_ARCHITECTURE.md](../docs/GEMINI_LIVE_ARCHITECTURE.md) §3).
- Opt-in selection in developer settings with a cross-border notice; key in the Keystore.
- Shared tool catalogue and per-turn claim gate for both adapters.
- "Driver speaking" from the local uplink gate when the provider has no speech events.

## Non-goals

- Native delegation (`delegate_task`, `NON_BLOCKING`): needs D-E/D-F; next phase of the plan.
- A command fast path for actuating tools (G-4): an ownership decision, not taken.
- Automatic fallback to Baidu when Gemini is unreachable: a second decision; G-M1 first.
- Search grounding (F14).

## Capability ground truth

`config/capabilities.yaml` gains a `gemini_live` provider block at `code` level only. Nothing about
Gemini is claimed at device level until G-M1/G-M2 pass.

## Behaviour

1. With Gemini disabled, not consented, or without a key, every session uses Baidu Flex exactly as
   before.
2. With all three, the session opens `gemini-3.8-live-extended-thinking` with the persona, the
   shared tools (as `parametersJsonSchema`, no explicit `behavior`), zh-CN speech, the chosen
   voice, thinking level (default LOW) and input/output transcription. The key travels only in
   the `x-goog-api-key` header.
3. Reply audio (24 kHz) and its subtitle pass through the same claim gate as Baidu's: a reply that
   claims an action is held until an `ok=true` execution result (I-1).
4. Tool calls are validated by the shared catalogue before dispatch; results go back as
   `toolResponse`, and Gemini continues on its own (no `response.create` equivalent).
5. `interrupted` from the server flushes playback; the uplink gate's onset/offset stands in for
   speech-started/stopped (R0 utterance protection, turn epochs, barge-in candidate).
6. 「闭嘴」 and the other listening commands behave as with Baidu (`VoiceCommandRouter`).

## Failure behaviour

- Setup rejected (1007), key refused (1008/401/403), quota (1011 with "quota"): the session fails
  with an honest error code; quota is not retried in a loop.
- `goAway`: reported as reconnecting; the existing reconnect owner reopens the session.
- `toolCallCancellation`: a call not yet executed is not executed; an executed one is not undone.
- Thought parts, if any arrive, are dropped in the adapter and never logged or spoken.

## Known limitations (measured 2026-09-29, cloud, not the device)

- **Conversational replies are heard only when complete.** The shared claim gate holds an unproven
  reply to the end of its response; Gemini streams at real-time pace, so the delay equals the
  reply's length. Escalated in [ADR-010](../DECISIONS/ADR-010-gemini-live-second-provider.md)
  "Open architecture question"; until decided, option (a) applies.
- **Actuating commands are slow.** Spoken commands reached their tool call 5–9 s after the end of
  speech in the app client, with a filler turn first (probe F20).
- **Late calls after the correction grace.** A claim correction waits up to 20 s for Gemini's own
  late call; a call later than that could follow the correction (double actuation risk for
  non-idempotent tools; the dispatcher's same-turn duplicate guard covers climate/music/live info).
- **Typed turns are unreliable** (F22): 2 of 3 typed 「把空调打开」 never called the tool and then
  reported a failure that did not happen. App prompts sent as text may be affected.

## Observability

Event types, ids, sizes, durations and close codes only. Never the key, a transcript, an argument
value, a coordinate or an address (I-7, I-8).

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Setup and every server message in G§3.2 translate correctly | functional | `GeminiLiveProtocolTest` | built (L2, 2026-09-29) |
| A2 | A Gemini choice reaches `GeminiLiveProvider`; the default still reaches Baidu | production wiring | `VoiceProviderChoiceTest`, wiring test | built (L2, 2026-09-29) |
| A3 | A claimed action is held until execution proof, under Gemini | negative | `GeminiLiveClientTest` | built (L2, 2026-09-29) |
| A4 | Baidu's `session.update` is byte-identical after the tool catalogue moved | regression protection | `RealtimeToolCatalogTest` | built (L2, 2026-09-29) |
| A5 | No vendor vocabulary outside the adapters; no provider-name branch | architectural | `ProviderBoundaryTest` | built (L2, 2026-09-29) |
| A6 | The APK builds with the adapter | artifact | `:app:assembleDebug` | built (L2, 2026-09-29) |
| A7 | The phone opens a Gemini session on its normal network (G-M1) | device | `GEMINI-DEVICE-REACH-001` | not earned |
| A8 | Reply through the speaker does not self-interrupt (G-M2) | device | `GEMINI-DEVICE-DUPLEX-001` | not earned |
