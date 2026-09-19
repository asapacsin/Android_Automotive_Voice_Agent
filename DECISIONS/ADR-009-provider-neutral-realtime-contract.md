# ADR-009 — Voice logic depends on a contract, not on a vendor's protocol

Status: **Accepted** (2026-09-19)
Reaffirms: [ADR-001](ADR-001-direct-provider-connection.md), [ADR-002](ADR-002-baidu-flex-default-provider.md), [ADR-008](ADR-008-single-active-realtime-provider.md)
Spec: [SPEC-007](../SPECS/SPEC-007-provider-neutral-realtime.md)
Source: [B-009](../BACKLOG.md)

## The dependency direction

```
Application / voice session / turn policy / tool dispatch
                      ↓  depends on
          RealtimeVoiceProvider  ·  DomainVoiceEvent  ·  ResponseOutcome
          ProviderCapabilities   ·  ErrorClass
                      ↑  implements
                 Provider adapters
        ┌──────────────┬───────────────┬──────────────┐
        │ Baidu Flex   │ (a future one)│ (a future one)│
        └──────────────┴───────────────┴──────────────┘
```

Arrows point at the contract from both sides. Nothing above the line names a vendor; nothing below
it is depended on by name.

## Why this needed deciding

Most of it already existed — `RealtimeVoiceProvider`, `ProviderCapabilities`, `DomainVoiceEvent`,
`ErrorClass` — and provider selection already happened at the composition boundary, in
`VoiceSessionController.openSession`. Measuring rather than assuming found **one** real breach, and
it was in the worst possible place:

`ActionClaimGuard` and `ConversationResetPolicy` are per-turn **policy** — they decide whether a
claim may be spoken, and when the conversation is reset. Both took `outputKinds: List<String>` and
asked `"function_call" in outputKinds`. Those strings came straight out of Baidu's `output[].type`.

A second provider would have had to **fabricate the literal string `"function_call"`** to reuse
policy that has nothing to do with Baidu. That is the failure this ADR exists to prevent: not a
missing abstraction, but a wire format quietly deciding product behaviour.

## What is decided

1. **Core and policy consume the contract.** `DomainVoiceEvent` for what happened,
   `ResponseOutcome` for what a completed response contained, `ProviderCapabilities` for what a
   provider can do, `ErrorClass` for what a failure means. No vendor event names, no vendor field
   names, no vendor JSON.
2. **Adapters translate, and that is their job.** Baidu's `output[].type` is read in
   `BaiduFlexClient`, where the JSON is already parsed, and the two words it cares about are named
   once in that file's companion. A provider that calls them something else translates in its own
   adapter and nothing downstream changes.
3. **Differences are capabilities, not provider names.** Where behaviour must vary, it varies on
   `capabilities.<feature>`. A `if (provider == BAIDU)` in application logic is a defect; if a
   difference genuinely cannot be expressed as a capability, that is recorded here, not worked
   around locally. There are no such cases today.
4. **Provider selection stays at the composition boundary.** One `when` in `openSession` chooses the
   implementation. Everything downstream holds the interface.
5. **Credentials stay provider-specific, accessed through one mechanism.** Keys are namespaced per
   provider (`baidu_*`, `iflytek_*`, `amap_*`) in `AndroidKeystoreCredentialStore`, so one
   provider's credentials can never overwrite another's — and [I-7](../docs/INVARIANTS.md) still
   holds: no secret in source, Gradle files, logs or the APK.
6. **Baidu is the behavioural baseline.** Its observed behaviour does not change to make the
   architecture tidier. Connection lifecycle, readiness, mic start/stop, PCM format, server VAD,
   interruption, playback, reconnect cleanup and the network-security posture are all preserved.

## Enforcement

`ProviderBoundaryTest` fails when wire vocabulary appears in core or policy, when the neutral core
imports a vendor SDK, or when the seam itself is removed. It was checked against a deliberately
reintroduced breach before being trusted: adding `listOf("function_call")` back into
`ConversationResetPolicy` failed it, and only that test.

Comments are stripped before the check, because the files that explain this rule quote the very
strings it forbids — a guard that fires on its own documentation gets deleted rather than obeyed.

## What this does not do

It does not add a provider. [ADR-008](ADR-008-single-active-realtime-provider.md) settled that
dormant vendor implementations are a liability, and nothing here revives one. The test of this
design is not that a second adapter exists; it is that writing one would not require touching voice
logic.

## What would justify revisiting

A provider whose semantics genuinely cannot be expressed through capabilities and normalized events
— for example one that cannot report turn boundaries at all, where server VAD is not a capability
but an architectural assumption. That would be a real finding, and it belongs here rather than in a
branch inside application code.
