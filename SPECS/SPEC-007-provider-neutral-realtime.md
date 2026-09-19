# SPEC-007 — The realtime provider contract

Status: **Implemented 2026-09-19 for the contract, the boundary and its guards.** The shared
adapter-contract suite runs against the in-repo providers; no second vendor adapter exists, by
[ADR-008](../DECISIONS/ADR-008-single-active-realtime-provider.md).
Raised: 2026-09-19 · Source: [B-009](../BACKLOG.md) · Decision: [ADR-009](../DECISIONS/ADR-009-provider-neutral-realtime-contract.md)
Depends on: [I-1, I-7, I-9](../docs/INVARIANTS.md) · [capabilities.yaml](../config/capabilities.yaml)

> Reaching Done here does not end the run. Reconcile the registry, the backlog row and the state,
> then `python scripts/discover_work.py` and take the next item
> ([CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12).

## Goal

Adding a realtime voice provider should mean writing one adapter — not editing voice logic. The
test of that is not that a second adapter exists; it is that nothing above the boundary would have
to change if one did.

## Scope

The contract between application logic and a realtime speech provider: its surface, the events it
emits, the errors it reports, the capabilities it declares, and what a new implementation must
satisfy before it is wired in.

## Non-goals

| Not in this phase | Why |
| --- | --- |
| A second concrete provider | ADR-008: dormant vendor implementations are a liability |
| A plugin framework, reflection-based registration, dynamic modules | One `when` at the composition boundary is the whole registry. This is an app, not a platform |
| Changing Baidu's observed behaviour | It is the baseline; the architecture bends around it, not the other way |
| Abstracting anything no provider here needs | An abstraction with one implementation and no second candidate is a guess |

## The contract

`RealtimeVoiceProvider` (`ingress`), as it already stands. A new adapter implements it:

| Concern | Surface |
| --- | --- |
| identity, feature set | `providerId`, `capabilities` |
| lifecycle | `connect(config)`, `disconnect()`, `close()` |
| audio in | `sendAudio(pcm16le)`, `commitInputAudio()`, `discardPendingAudio()` |
| turn control | `cancelAssistantResponse()`, `cancelActiveResponse()`, `resumeListening()` |
| tools | `sendToolResult(result)`, `injectWorkResult(result)` |
| text in | `sendText(text)` |
| output | `events(): Flow<RealtimeEvent>`, carrying `DomainVoiceEvent` |

The names are the ones already in the codebase. They were not renamed to match a template: churn
across every call site buys nothing, and the existing shape already says what it does.

## Normalized events

`DomainVoiceEvent` is what application logic consumes. A vendor event name must never reach it.

| Event | Means |
| --- | --- |
| `SessionReady(model, interruptResponse)` | the session is usable; what it supports |
| `SpeechStarted` / `SpeechStopped` | the driver's turn boundaries, however the provider detects them |
| `UserTranscript(text, final)` | what the driver said, streaming or final |
| `AssistantTranscript(text, final)` | what the assistant is saying |
| `AudioDelta(pcm16leBase64)` / `AudioDone` | reply audio |
| `ToolCall(...)` | an action was requested |
| `ResponseDone(status, reason)` | a response finished |
| `Interrupted(reason)` | a reply was cut short |
| `Error(code, message)` | something failed; see the taxonomy below |
| `Closed` / `Reconnecting` | connection lifecycle |

**`ResponseOutcome`** carries what a *completed* response contained — `spoke`, `toolCallIds`,
`unidentifiedToolCalls` — because that is what turn policy needs and it is the exact place the old
breach happened. Baidu's `output[].type` is translated into it inside `BaiduFlexClient`.

## Normalized errors

`ErrorClass` — `RETRYABLE`, `TERMINAL`, `AUTH`, `RATE_LIMIT`, `MALFORMED`, `CANCELLED` — via
`classifyVoiceError(code)`. Six classes, because those are the six that change what the app *does*:
retry, give up, re-authenticate, back off, fix the call, or say nothing because the driver cancelled.

A finer taxonomy (`AudioFormatError`, `SessionExpiredError`, …) was considered and rejected: the app
would treat each of them identically to one of the six, and a distinction nothing acts on is a
distinction that rots. The provider's own code and message are preserved in
`VoiceProviderException(code, safeMessage)` for logs and diagnosis — `wake_session_error code=200061`
is exactly why that matters.

## Capabilities

`ProviderCapabilities` declares what a provider can do, so behaviour branches on the feature rather
than on the vendor:

`customTools` · `serverVadInterrupt` · `clientResponseCancel` · `optionalInputCommit` ·
`workResultInjection` · `unknownEventTolerance` · `requiresCredentials` · `realtimeAudio`

Only capabilities this product actually varies on are listed. `emotion`, `configurableVoice` and
`textInput` were considered and left out: nothing branches on them here, and a flag no code reads is
documentation pretending to be a mechanism. Adding one is cheap when a second provider needs it.

## Invariants

| | |
| --- | --- |
| **P-1** | Application and policy code consume `DomainVoiceEvent`, `ResponseOutcome`, `ProviderCapabilities` and `ErrorClass` — never a vendor event name, field name or JSON object. |
| **P-2** | The `ingress` core imports no vendor SDK. |
| **P-3** | Behaviour varies on a capability, not on a provider name. An exception must be recorded in ADR-009, not worked around locally. |
| **P-4** | Provider selection happens at the composition boundary only. |
| **P-5** | Credentials are namespaced per provider; one provider's credentials cannot overwrite another's. |

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Turn policy decides from `ResponseOutcome`, not from wire strings | functional | `ConversationResetPolicyTest`, `ActionClaimGuardTest` | **met** |
| A2 | The adapter translates its own wire format | production wiring | `BaiduFlexClient.toOutcome`; the live runs of 2026-09-19 | **met** |
| A3 | Wire vocabulary in core or policy fails the build | architectural | `ProviderBoundaryTest.coreAndPolicyDoNotSpeakAVendorWireFormat`, checked against a reintroduced breach | **met** |
| A4 | The neutral core imports no vendor SDK | architectural | `ProviderBoundaryTest.theProviderNeutralCoreNamesNoVendorSdk` | **met** |
| A5 | The seam cannot be silently removed | architectural | `ProviderBoundaryTest.theAdapterBoundaryStillExists` | **met** |
| A6 | Every provider satisfies one shared behavioural contract | contract | `RealtimeProviderContractTest` | **met** |
| A7 | An unsupported capability fails explicitly, never silently | contract | `RealtimeProviderContractTest` unsupported cases | **met** |
| A8 | Baidu's observed behaviour is unchanged | regression | full suite + the live runs recorded in [ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md) | **met** |
| A9 | Registry, backlog and state agree | reconciliation | `harness_check.py` | **met** |

## What adding a provider costs

1. implement `RealtimeVoiceProvider`, translating its wire format inside the adapter;
2. declare its `ProviderCapabilities` and its credential keys;
3. pass `RealtimeProviderContractTest`;
4. add one branch at the composition boundary.

No change to turn policy, tool dispatch, the session state machine, or the UI. If a provider forces
one, that is a finding for ADR-009 — the contract was wrong, not the provider.
