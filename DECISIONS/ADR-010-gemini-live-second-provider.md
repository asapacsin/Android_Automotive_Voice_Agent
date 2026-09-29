# ADR-010 — Gemini Live as a second, opt-in realtime provider

Status: **Accepted** (2026-09-29, product owner: "use Gemini 3.8 extend", then "implement the plan
from this architecture")
Amends: [ADR-008](ADR-008-single-active-realtime-provider.md) (one active provider → one default
provider plus one opt-in provider)
Keeps: [ADR-001](ADR-001-direct-provider-connection.md), [ADR-002](ADR-002-baidu-flex-default-provider.md),
[ADR-009](ADR-009-provider-neutral-realtime-contract.md)
Architecture: [docs/GEMINI_LIVE_ARCHITECTURE.md](../docs/GEMINI_LIVE_ARCHITECTURE.md) ·
Plan: [docs/GEMINI_LIVE_PLAN.md](../docs/GEMINI_LIVE_PLAN.md) ·
Spec: [SPEC-013](../SPECS/SPEC-013-gemini-live-provider.md)

## Decision

1. `models/gemini-3.8-live-extended-thinking` is added as a **second `RealtimeVoiceProvider`**,
   `VoiceProviderId.GEMINI_LIVE`, written new (not revived from git history). ADR-008's revisit
   trigger is met: native thinking and native non-blocking tool calls are capabilities Baidu Flex
   lacks (probe F7/F8).
2. **Baidu Flex stays the default.** Gemini is used only when the owner enables it in developer
   settings, accepts the cross-border notice, and a Gemini key is stored. The choice is made once
   per session, at the composition boundary (`VoiceSessionController.openSession`), never mid-session.
3. **No relay** (ADR-001 unchanged). The phone connects to `generativelanguage.googleapis.com`
   directly. Whether it can, from where the product is used, is measured on the device (G-M1);
   until then the provider is not claimed to work in mainland China.
4. The provider-neutral contract grows by capability, never by provider name (ADR-009 §3):
   `serverSpeechActivityEvents` (false for Gemini: the local uplink gate supplies "driver
   speaking"), `ToolCallCancelled` and `ProviderWorkState` events. *Correction, same day (probe
   F19):* Gemini does send `voiceActivity` START/END. The flag stays false by choice — the local
   gate is faster and already device-proven for Baidu's barge-in evidence — and the adapter uses
   ACTIVITY_START only as the fallback when no local onset was seen.
5. The tool declarations, their validation and the per-turn claim gate are shared by both adapters
   (extracted from the Baidu adapter), so I-1 is enforced by one mechanism for both.

## The owner's gates, as taken

The owner authorised the build without answering G-1 … G-6 individually. Each is taken as the
architecture's recommended or most conservative option; each is one setting or one line to change:

| Gate | Taken as |
| --- | --- |
| G-1 | second provider, Baidu default |
| G-2 | **answered by the owner 2026-09-29:** the owner uses a VPN; reachability is not a product concern. If Gemini later causes problems, the owner will switch to another similar realtime model behind the same seam. No relay |
| G-3 | no search grounding (the key's quota refused it, F14) |
| G-4 | no command fast path; actuating latency (F6) accepted and recorded |
| G-5 | prebuilt voice `Kore` by default, editable |
| G-6 | opt-in with an explicit cross-border notice; off by default |

## Consequences

- Two adapters now exist; ADR-008's rule still holds for anything *dormant*: if Gemini is not used
  or cannot be reached, the owner decides whether to delete it, rather than leaving it to rot.
- Interruption under Gemini is server-owned (no client cancel). The local defence is the uplink
  gate; `activityHandling: NO_INTERRUPTION` is the documented fallback if G-M2 shows echo
  self-interruption.
- Driver audio, transcripts and the context hint leave the country when Gemini is enabled; this is
  why it is opt-in.

## Open architecture question (raised by the build, not decided here)

*Answered by a proposal, 2026-09-29:* [ADR-011](ADR-011-gemini-native-voice-path.md) (Proposed)
generalises option (b) as clause release by trait. It also found that the chosen model, not the
claim gate, causes the slow actions (probe F23/F24). The block below stays until ADR-011 is
accepted.

```text
ARCHITECTURE_REVIEW_REQUIRED
CONSTRAINT:  I-1 enforcement in DriverTurn / DriverTurnPipeline (B-014): a reply that has no
             execution proof yet is held until its response is complete (UNCLASSIFIED_CLAIM for
             conversational turns, AWAITING_EXECUTION_PROOF for action turns)
EVIDENCE:    probe report F21 and the independent review of G2.1: Gemini streams reply audio at
             real-time pace, with transcript chunks interleaved, not ahead. Holding to the end of
             the response therefore delays hearing a reply by its whole duration (a 13 s answer is
             first heard at ~14.5 s). Baidu's measured cost of the same hold is 93-515 ms (P23)
CONFLICT:    SPEC-013's goal "the same conversation as with Baidu" vs. I-1's hold-until-done rule
OPTIONS:     (a) accept: Gemini stays opt-in with slow conversational replies (action results are
                 unaffected: proof arrives before the result audio);
             (b) a capability `replyStreamsInRealTime` under which the gate releases audio
                 progressively while the transcript so far contains no action claim, holding from
                 the first claim onwards (a DriverTurn policy change; needs its own tests);
             (c) hold only turns classified as ACTION requests; release conversational turns at
                 once (changes I-1 coverage for chat turns, for both providers unless capability-gated)
DOWNSTREAM:  DriverTurn / DriverTurnPipeline, SPEC-013 A-rows, possibly I-1 wording
DECISION_REQUIRED: which of (a)/(b)/(c) for Gemini Live
```

## What would justify revisiting

G-M1 failing from the owner's network (reachability) → decide relay (reopens ADR-001) or delete the
adapter. G-M2 failing → switch the default to `NO_INTERRUPTION`. A decision to make Gemini the
default → reopens ADR-002.
