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
   `serverSpeechActivityEvents` (Gemini sends no speech-started/stopped events; the local uplink
   gate supplies them), `ToolCallCancelled` and `ProviderWorkState` events.
5. The tool declarations, their validation and the per-turn claim gate are shared by both adapters
   (extracted from the Baidu adapter), so I-1 is enforced by one mechanism for both.

## The owner's gates, as taken

The owner authorised the build without answering G-1 … G-6 individually. Each is taken as the
architecture's recommended or most conservative option; each is one setting or one line to change:

| Gate | Taken as |
| --- | --- |
| G-1 | second provider, Baidu default |
| G-2 | direct connection, no relay; reachability measured by G-M1 |
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

## What would justify revisiting

G-M1 failing from the owner's network (reachability) → decide relay (reopens ADR-001) or delete the
adapter. G-M2 failing → switch the default to `NO_INTERRUPTION`. A decision to make Gemini the
default → reopens ADR-002.
