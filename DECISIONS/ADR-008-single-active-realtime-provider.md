# ADR-008 — One active realtime provider; keep the seam, delete the dormant implementations

Status: **Accepted** (2026-09-19, decided by the product owner)
Reaffirms: [ADR-001](ADR-001-direct-provider-connection.md), [ADR-002](ADR-002-baidu-flex-default-provider.md)
Resolves: [TECH_DEBT.md](../docs/TECH_DEBT.md) D-5
Consequence of: [ADR-007](ADR-007-embedded-amap-navigation-sdk.md) for the navigation remnants

## The decision, as given

> - Baidu Direct remains the only currently active realtime provider.
> - Preserve the provider-neutral realtime abstraction / adapter boundary so another provider can be
>   added later without redesigning the app.
> - Do NOT preserve dormant concrete provider implementations merely for hypothetical future use.
> - Delete unreachable, unreferenced, untested, or obsolete second-provider implementation paths if
>   no active SPEC, ADR, capability requirement, or test requires them.
> - Preserve genuinely provider-neutral interfaces, models, contracts, and compatibility seams.
> - Do not introduce a new second provider as part of this task.

## Why this was worth deciding

D-5 recorded the cost precisely: *"A fresh agent infers the wrong architecture from filenames — the
exact failure `AGENTS.md` warns about."* Keeping a compiling second provider is not free optionality.
It is a standing invitation to read the repository wrong, and it has to be carried through every
refactor by people who cannot tell whether it is load-bearing.

The distinction the decision draws is the right one: **the seam is the asset, the dormant
implementation is the liability.** A future provider needs `RealtimeVoiceProvider` and a neutral
session model to plug into. It does not need last year's half-finished client.

## What was removed, and on what evidence

Every item was checked for a caller, a test that exercises *production* code, and a SPEC, ADR or
capability entry that requires it. None had one.

| Removed | Evidence it was dormant |
| --- | --- |
| `QwenDirectRealtimeProvider`, `QwenRealtimeClient`, `QwenProtocol` | reachable only from `VoiceSessionController.start(settings, qwenConfig)`, which **no production code calls** |
| `BackendRealtimeProvider`, `BackendVoiceClient` | same unreachable overload |
| `QwenSettings` (+ its test) | served only the above; tested, but the thing it configured could not run |
| `LocalConnectivity` (+ its test) | used only by `BackendVoiceClient` |
| `DeveloperOptions` | referenced by nothing at all; `backendUrl()` returned `""` |
| `backend/` | the PC backend; never a Gradle module, not built, not shipped |
| `ingress` `QwenRealtimeCapabilities` | a facts table about a provider that no longer exists here |
| `QwenProtocolFixtureTest` | defined its own `object QwenProtocol` inside the test file — it tested a copy of itself, not the product |
| `NavigationAdapter` | the ADR-003 deep link, unreferenced, superseded by ADR-007 |
| `AmapAutoPickService` | an **accessibility service** that auto-picked results inside the external 高德地图 app — the ADR-003 world. Carrying an accessibility-service declaration for a path the product no longer uses is a permission surface for nothing |

## What was deliberately kept

- `RealtimeVoiceProvider`, `RealtimeSessionConfig`, `RealtimeAudioConfig`, `VoiceProviderId` — the
  adapter boundary itself. Adding a provider means writing one implementation of this interface.
- The `ingress` core: the state machine, reconnect policy, work coordinator and audio ports, none of
  which names a vendor.
- `VoiceCatalog`'s model identifiers, including the Qwen ones: a neutral catalogue, used by
  provider-neutral `ingress` tests as sample model ids. Removing them would churn tests that are not
  about Qwen at all.
- `ProviderCapabilityFacts` for the provider that exists.
- The other seams — `NavigationBackends`, `MusicBackends`, `VehicleControlProvider` — which are how
  the simulation runs without Android at all.

## What this does not do

It does not add a second provider, and it does not change any capability. The product's behaviour is
identical; `config/capabilities.yaml` is untouched by this ADR.

## What would justify revisiting

A real requirement for a second provider — a region where Baidu is unavailable, a cost ceiling, or a
capability Baidu lacks. At that point the work is one new `RealtimeVoiceProvider` implementation,
which is what keeping the seam bought. Reviving the deleted code from git history would be the wrong
move: it was never finished, and it was written against an older session model.
