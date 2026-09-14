# Architecture — Nova Drive / 小诺

## Required runtime flow

```
microphone
  → audio preprocessing
  → configurable Chinese wake word / PTT / VAD
  → provider-neutral speech-native S2S
  → typed structured function call
  → task manager
  → deterministic safety policy
  → skill / tool router
  → provider / vehicle abstraction
  → simulator or real AAOS adapter
  → observed-state verification
  → typed result
  → concise zh-CN spoken / visual feedback
```

Checkpoint 1 implements the **structured-command → policy → adapter → verify → zh-CN feedback** slice.

The active CURRENT_TASK keeps Checkpoint 1, Qwen Flash as the product default, and optional Qwen Plus / GPT-Live / Baidu / Fake adapters:

```
Android microphone
  → Nova Drive backend (no secrets on device)
  → selected realtime provider (Qwen Flash default; Qwen Plus / GPT-Live / Baidu / Fake optional)
  → domain events
  → VoiceSessionController (JVM core)
  → Android playback / UI states
```

Wake-word hardware tuning remains later. Baidu custom Function Calling stays blocked. Qwen and GPT-Live tools follow their official docs. Bluetooth SCO / hardware AEC are **manual-test-only**.

## Decision split

| Layer | Decides | Checkpoint 1 type |
| --- | --- | --- |
| S2S / NLU | *What* the driver meant | `StructuredCommand` only. No NL parser. |
| `VoiceSessionOrchestrator` | *Whether / when / how* to run | validation → contact resolution → policy → optional confirm → router |
| `SafetyPolicy` | `ALLOW` / `CONFIRM` / `DENY` | pure function of command + `VehicleSafetySnapshot` |
| `VehiclePort` | Hardware / provider I/O | Fake simulator now; AMap / Baidu / OEM / VHAL later |
| `ObservedStateVerifier` | *Whether it worked* | compares requested change to independently observed state |
| `ZhCnFeedbackRenderer` | What the driver hears / sees | short Simplified Chinese |

LLM output cannot call `VehiclePort.execute`. The only public ingress is `StructuredCommandIngress.submit`.

## Module direction

```
ingress --------→ contracts
safety ---------→ contracts
vehicle --------→ contracts
verification --> contracts, vehicle
feedback ------→ contracts
orchestration → contracts, ingress, safety, vehicle, verification, feedback
simulator -----→ contracts, vehicle
demo / app ----→ orchestration + simulator (wiring only)
```

Forbidden edges (enforced by Gradle `implementation`/`api` plus `DependencyBoundaryTest`):

- `ingress` ↛ `simulator`, `vehicle` implementations, AAOS, VHAL, AMap, Baidu, GMS
- `orchestration` ↛ `simulator`
- `safety` ↛ adapters
- core ↛ provider SDK types

`SkillRouter` is `internal` to `orchestration`. Tests and the demo talk to `VoiceSessionOrchestrator`, not to adapters, for policy-gated actions.

## China-first location and navigation

`GeoCoordinate` always carries `CoordinateSystem` (`WGS84`, `GCJ02`, `PROVIDER_DEFINED`).

Conversion is **not** implemented in core. Future AMap / Baidu / OEM adapters own transforms at their boundary. `NavigationProviderKind` is a seam identifier, not an imported SDK class.

Default preferred system for Chinese POIs in this product is GCJ-02. Core still accepts WGS-84 as metadata; it does not silently convert.

## Phone identity

`ContactQuery` accepts spoken name, pinyin, alias, and number. `PhoneProvider.resolve` returns `UNIQUE`, `AMBIGUOUS`, or `NONE`. Ambiguous Chinese names (e.g. two 张伟 entries) stop before policy execution and return `CLARIFICATION_NEEDED`.

## Simulator

`InMemoryVehicleSimulator` implements `VehiclePort` plus the four domain ports. It keeps canonical state and a separately published observed snapshot so tests can inject:

- `failNextExecution`
- `desyncNextObservation` (mutate internally, leave observed state stale)

Success is never taken from the execute return value alone.

## Android Automotive

`app` is a thin Activity shell that runs the same structured-command path **and** a backend-proxied realtime voice session. `android.hardware.type.automotive` is declared `required=false` until Checkpoint 7. The module is included only when `platforms/android-34` exists so JVM tests remain runnable on a cmdline-tools-only SDK.

Permanent provider keys are backend-only (`backend/.env`). The Android client speaks a provider-neutral JSON session to `ws://<backend>/v1/voice/realtime`. Core Kotlin modules still must not import `com.baidu`, `com.openai`, DashScope SDKs, simulator types, AAOS, or VHAL. UI code consumes `VoiceSessionController` + `DomainVoiceEvent` only.
