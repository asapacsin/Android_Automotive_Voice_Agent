# Nova Drive iOS port plan

This plan implements [SPEC-022](../SPECS/SPEC-022-ios-port-foundation.md) under [ADR-018](../DECISIONS/ADR-018-ios-monorepo-and-kmp.md). It is staged to preserve the working Android app and to measure platform portability before committing to a repo-wide migration.

## Phase 0 — Contract and architecture

**Current PR.** Record the iPhone behavior contract, same-repository decision, native boundaries, CarPlay dependency, and acceptance gates. No runtime behavior is changed and no iOS implementation is claimed.

Exit criteria:
- SPEC-022 and ADR-018 are linked from the repository's canonical indexes.
- Android root paths and existing source/build files remain unchanged.
- The next implementation work is discoverable and has explicit verification criteria.

## Phase 1 — Dependency and feature audit

Create two inventories before implementing the runtime:

1. **Kotlin portability inventory.** For contracts, ingress, safety, verification, feedback, and orchestration, record source imports, dependency graph, serialization, threading/coroutines, filesystem/crypto APIs, and JVM-only assumptions. Classify each unit as KMP candidate, Swift reimplementation, or platform adapter. Do not enable KMP plugins until the inventory and a minimal compile/test pilot support it.
2. **Capability parity inventory.** Map each Android capability to an iOS adapter, an iOS-only implementation, unsupported behavior, or an external dependency. Include voice session, navigation candidate selection/start/stop, arrival, saved places, live information, media, contacts/calling, speaking style, wake word, background audio, and simulated vehicle controls. Keep Android and iOS verification evidence separate.

Exit criteria:
- Every capability has an explicit iOS status and failure behavior.
- Every proposed shared module has evidence that its dependencies can target iOS.
- No Android capability is presented as iOS-verified.

## Phase 2 — Minimal native iPhone shell

Create an Xcode project under ios/ with SwiftUI, signing/build settings, safe configuration loading, and a macOS CI job. Start with an explicit user-initiated session; do not begin with always-on wake word or CarPlay.

Exit criteria:
- A clean checkout builds on a supported macOS/Xcode runner.
- The app launches on an iPhone and reports permission-denied states clearly.
- The iOS workflow uses a shallow checkout unless a test specifically requires history.

## Phase 3 — Realtime voice session

Implement a provider client over an iOS-compatible WebSocket transport and native audio session/capture/playback. Verify the current active Qwen-Omni protocol; do not revive dormant Android providers or assume wire compatibility from class names alone.

Required behaviors:
- Secure credential entry/storage in Keychain.
- Typed provider events and tool-call assembly.
- Explicit session start, cancellation, teardown, reconnect budget, and stale-event protection.
- Audio interruption, navigation guidance, and user barge-in coordination.
- Redacted event-level diagnostics.

Exit criteria:
- Mock protocol tests pass for complete turns, tool calls, malformed events, disconnects, cancellation, and late callbacks.
- A real iPhone completes a provider turn with observed timing and no secrets/transcripts in logs.
- Failure to authenticate or connect never silently switches providers.

## Phase 4 — Deterministic command execution

Implement a single iOS dispatch path from validated tool request through policy, native executor, result verification, and response release. Share the same contract fixtures and adversarial cases as Android. If a Kotlin Multiplatform pilot is successful, move only approved portable logic into the shared module; otherwise keep the contract portable and implement the policy in Swift with cross-platform parity tests.

Exit criteria:
- Unknown/malformed/out-of-range calls cannot execute.
- ALLOW / CONFIRM / DENY semantics match the spec.
- A success claim is impossible without an executor result and required read-back.
- Unsupported features return typed unsupported outcomes.

## Phase 5 — Amap navigation and audio arbitration

Audit the current Amap iOS Navigation SDK version, license, API-key requirements, privacy compliance, supported deployment targets, and route/guidance callbacks. The official SDK includes a native iOS navigation surface; do not substitute an unverified deep-link flow if the requirement is embedded navigation.

Exit criteria:
- Route preview, route selection, start, guidance, cancellation, and arrival are observed on an iPhone.
- Assistant audio and Amap guidance do not overlap or leave capture muted.
- Navigation failure is reported only from native SDK results, not model wording.

## Phase 6 — Feature parity and lifecycle hardening

Port remaining capabilities one at a time using the parity inventory: saved places, live information, media, contacts/calling, settings, and speaking style. Wake-word and background behavior are separate high-risk work items; prove iOS lifecycle and microphone constraints on a real device before enabling them. Never assume the Android foreground-service model has an iOS equivalent.

Exit criteria:
- Each feature has a platform-specific acceptance test, explicit failure result, and regression protection.
- A capability is marked supported only after the evidence required by SPEC-022 exists.

## Phase 7 — CarPlay

Start only after the Apple Developer account can request and receives the relevant entitlement(s). The voice-based conversation and navigation entitlements are separate capabilities; request only those justified by the product. Integrate the Amap CarPlay example and conform to the approved CarPlay category's UI/lifecycle rules.

External dependency:
- **BLOCKED_BY:** Apple's approval of the required CarPlay entitlements for the app's identifier.

Exit criteria:
- The app is visible in the CarPlay simulator with the granted entitlement.
- Voice control, navigation templates, guidance, interruption, and return-to-phone behavior are verified in the simulator and then in a supported vehicle.
- No CarPlay support is claimed before this evidence exists.

## Repository size and CI policy

Observed on 2026-10-22 from GitHub repository metadata and the recursive tree:

- Repository metadata reports 2,550,835 KB (about 2.43 GiB).
- The current tracked tree is about 71.7 MiB.
- Current MP4 files total about 62.6 MiB.

The large difference suggests that historical Git objects account for much of the reported repository size. Removing old videos from the current tree alone will not necessarily shrink a full clone. For iOS CI, use a shallow checkout and avoid downloading unrelated Android artifacts. Analyze historical large blobs separately; any public-history rewrite needs explicit owner approval and a coordinated clone/branch migration plan.

## Immediate next work after this plan

1. Produce the Kotlin dependency/import portability inventory and capability parity matrix from the existing source.
2. Check out the repository on macOS with Xcode and build a minimal native SwiftUI app before integrating any realtime provider.
3. Prototype the realtime provider against a mocked WebSocket first, then verify the real provider on an iPhone.
4. Acquire/verify Amap iOS SDK access and privacy/Store requirements before navigation implementation.
