# SPEC-022 — Native iOS port with explicit platform parity

Status: **Draft**
Raised: 2026-10-22 · Source: request to add an iOS-compatible Nova Drive version
Depends on: [ADR-018](../DECISIONS/ADR-018-ios-monorepo-and-kmp.md), [ADR-009](../DECISIONS/ADR-009-provider-neutral-realtime-contract.md), [ADR-015](../DECISIONS/ADR-015-car-domain-servers.md), [ADR-017](../DECISIONS/ADR-017-qwen-omni-realtime-end-to-end.md), [invariants](../docs/INVARIANTS.md), [current architecture](../docs/ARCHITECTURE.md), and [capability ground truth](../config/capabilities.yaml).

## Goal

Add a native iPhone version of Nova Drive without destabilizing the existing Android application. The two apps should implement the same product-level command and safety contracts where the platform permits, while each uses native audio, lifecycle, credential, navigation, and UI integrations. Android test evidence must never be presented as proof that the iOS implementation works.

## Scope

- Add a native Swift iPhone application under the repository's top-level ios/ directory. Preserve the current Android Gradle project and module paths at the repository root.
- Start with an explicit, user-initiated voice session. The first iOS milestone is not an always-listening wake-word service.
- Port the active realtime speech-to-speech experience and typed tool-call boundary. Verify the current production Qwen-Omni protocol against a real iOS client; do not assume an Android transport implementation is directly portable.
- Implement deterministic command validation, policy decisions, cancellation, and execution-result verification in a way that conforms to the existing contracts.
- Integrate the Amap iOS Navigation SDK only after its current SDK version, licensing, API-key requirements, privacy requirements, and App Store submission rules are checked.
- Maintain a platform-by-platform capability ledger for voice, navigation, media, saved places, live information, contacts/calling, wake word, background operation, and simulated vehicle controls.
- Treat CarPlay as a later, separate acceptance gate. Amap publishes an iOS Navigation SDK and a CarPlay integration guide; Apple entitlement approval is still required for the relevant CarPlay app categories.
- Evaluate Kotlin Multiplatform for genuinely portable modules only after an import/dependency audit. No existing Kotlin/JVM module is assumed to be iOS-compatible before that audit.

## Non-goals

- Moving or renaming the existing Android Gradle root, Android modules, scripts, evidence, or build outputs.
- Rewriting the product in Flutter or replacing the native Android implementation.
- Converting every Kotlin module to Kotlin Multiplatform before proving a small pilot.
- Promising unrestricted background microphone access, always-on wake-word behavior, arbitrary control of other iOS apps, or arbitrary vehicle hardware access.
- Enabling automatic provider fallback or changing the Android product's active provider.
- Claiming CarPlay support before entitlements, templates, and in-car behavior have been verified.

## Capability ground truth

The existing [capability registry](../config/capabilities.yaml) and Android acceptance evidence describe the Android product. They are not iOS evidence. Do not raise any iOS capability to implemented, unit-tested, device-tested, or human-verified merely because the corresponding Android capability passes.

Before iOS behavior is wired, define a platform-specific capability mapping that identifies for each existing product capability: supported, planned, unsupported on iOS, or blocked on vendor/Apple approval. Unsupported operations must be rejected honestly rather than hidden behind a successful-looking model reply.

## Behavior

1. **Explicit session lifecycle.** A voice session starts only after an explicit user action and required iOS permissions. Stop/cancel closes microphone capture, provider transport, pending tool calls, and playback; late callbacks from an old session cannot mutate a newer session.
2. **Typed action boundary.** Realtime model output may request only registered, schema-validated tools. Malformed arguments, unknown tool names, out-of-range values, and unsupported capabilities are rejected before platform execution.
3. **Deterministic policy.** Each action is evaluated by the existing product policy contract or a verified equivalent. The model's natural-language reply is never evidence that an action happened.
4. **Execution proof.** The assistant may claim success only after a native executor returns success and, where the capability requires it, a read-back/observed-state check agrees with the requested state.
5. **Provider truth.** The iOS app uses an explicitly configured, tested provider path. It must not silently fall back to another provider or voice after authentication, workspace, protocol, or quota failure.
6. **Audio arbitration.** Assistant speech, navigation guidance, system audio interruptions, phone calls, and user barge-in are coordinated by one iOS session/audio authority. They must not create overlapping speech or leave the microphone permanently muted.
7. **Secure credentials.** User-entered provider credentials are stored in iOS Keychain. Secrets do not appear in source, checked-in configuration, crash reports, or logs.
8. **Honest unsupported results.** A capability that cannot be implemented or is not permitted on iOS returns an explicit unsupported result. A simulator or mock adapter is never selected as a production implementation.
9. **Privacy-preserving diagnostics.** Logs may include event names, opaque session identifiers, status codes, durations, and validation outcomes. They must not include provider secrets, raw audio, transcripts, precise coordinates, or destination addresses.
10. **CarPlay gate.** CarPlay code is enabled only after the appropriate Apple entitlement is granted and the app meets the approved CarPlay category's UI and lifecycle requirements.

## Failure behavior

| Failure | Required behavior |
| --- | --- |
| Microphone permission denied/revoked | Do not start capture; show a clear permission state and keep the app usable. |
| Provider key/workspace/authentication failure | End or refuse session startup with a specific safe error; no provider fallback. |
| WebSocket disconnect or provider timeout | Stop claiming progress as success; expose a recoverable connection state and bound retries. |
| Malformed or unknown tool call | Reject before execution; return a typed error to the model and log a redacted validation code. |
| Native action fails or read-back disagrees | Report failure/uncertainty accurately; never synthesize a success result. |
| User cancels or iOS interrupts audio | Cancel or suspend the active turn deterministically; discard stale callbacks and restore a known audio state. |
| Amap SDK key, permission, privacy, or route error | Do not claim navigation started; surface a typed navigation failure. |
| CarPlay entitlement absent | Do not expose CarPlay entry points or claim CarPlay compatibility. |
| Feature has no legal/supported iOS adapter | Return unsupported and preserve the remaining session if safe. |

## Observability

Every action needs a traceable sequence from user-turn/session start through provider event, typed tool validation, policy decision, native execution, observed result, and spoken response. Use redacted event IDs and enums. Follow [I-8](../docs/INVARIANTS.md): do not log coordinates, addresses, or transcripts. Tests must assert that unsupported, denied, failed, cancelled, and unverified actions cannot be reported as successful.

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Native iOS target builds on a supported macOS/Xcode runner | artifact | iOS CI xcodebuild result | not built |
| A2 | Explicit start/stop handles permission denial and cancellation without stale-session effects | functional | iOS lifecycle tests | not built |
| A3 | Active realtime provider completes a real voice turn on an iPhone | end-to-end | device trace and recorded test evidence | not built |
| A4 | Unknown, malformed, and out-of-range tool calls never reach a native executor | negative | Swift/KMP contract tests | not built |
| A5 | Success claims require a real executor result and required observed-state verification | architectural | adversarial tool-result tests | not built |
| A6 | Navigation starts, updates, stops, and handles arrival/error callbacks through the Amap iOS SDK | end-to-end | iPhone navigation evidence | not built |
| A7 | Audio interruption, navigation guidance, and barge-in restore a valid audio/microphone state | end-to-end | iPhone audio tests and human listening check | not built |
| A8 | Provider credentials are stored in Keychain and never emitted to logs | security | storage tests and secret/log scan | not built |
| A9 | Every capability without an iOS adapter fails honestly as unsupported | negative | platform capability matrix and tests | not built |
| A10 | The existing Android build/tests remain green and Android source/configuration is unchanged by the initial iOS foundation | regression | Android CI on the change | not built |
| A11 | CarPlay navigation/voice UI is enabled only after Apple entitlements are granted | external approval | entitlement inspection plus CarPlay simulator/device evidence | blocked |
| A12 | All relative links in the iOS spec, ADR, and plan resolve | documentation | repository link check | not built |

## Open product decisions

- **First iOS milestone default:** explicit user-initiated voice session, with navigation and the highest-value tool capabilities added behind test gates. Always-on wake word is deferred until iOS lifecycle and microphone behavior are proven.
- **CarPlay:** `BLOCKED_BY: Apple approval for the required CarPlay voice-based conversation and navigation entitlements`. Continue iPhone work while approval is pending.
- **Kotlin Multiplatform scope:** decide only after the dependency audit and a small compile/test pilot; do not migrate the full module graph in advance.

## Implementation status

| Area | Status | Evidence |
| --- | --- | --- |
| iOS project and app shell | not built | No Xcode project exists in the repository yet. |
| KMP portability audit | not done | Existing modules are Kotlin/JVM. |
| Realtime provider adapter | not built | Protocol and iOS transport not tested on device. |
| Native audio/lifecycle adapter | not built | No iOS implementation or device evidence. |
| Amap navigation | not built | No iOS SDK integration in the repository. |
| CarPlay | blocked | Requires Apple entitlement approval and later in-car verification. |
