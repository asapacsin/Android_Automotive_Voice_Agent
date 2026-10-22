# ADR-018 — Same-repository native iOS app with conditional shared core

Status: **Accepted**
Date: 2026-10-22

## Context

Nova Drive is currently a native Android/Kotlin product with deterministic tool validation, safety policy, execution-result verification, realtime provider adapters, and Amap navigation. There is no iOS project yet. The existing repository root and scripts assume the Android Gradle build lives at the root; moving it would create avoidable path churn and regressions.

The existing modules are Kotlin/JVM modules, not a Kotlin Multiplatform project. Their names and boundaries are promising candidates for sharing, but they are not proof of iOS portability. Native audio, application lifecycle, secure credential storage, navigation SDKs, and CarPlay require platform-specific implementations.

Repository size is a real cost: GitHub metadata reported 2,550,835 KB on 2026-10-22, while the recursive current tree was about 71.7 MiB and its current MP4 files about 62.6 MiB. The gap strongly suggests a large amount of historical Git data. A separate iOS repository would avoid cloning that history, but would duplicate product contracts and make cross-platform changes harder until code sharing exists. iOS-only CI should use shallow checkout; investigate historical bloat separately rather than silently rewriting public history as part of this port.

## Decision

1. **Keep the iOS version in this repository.** Create a native iPhone app under the top-level ios/ directory. Do not create a second repository solely for the iOS client.
2. **Preserve the current Android layout.** Do not move app/, contracts/, ingress/, safety/, vehicle/, verification/, orchestration/, scripts/, evidence, or build files under a new android/ directory.
3. **Use a native Swift application shell.** SwiftUI is the default for iPhone UI; use native iOS APIs for audio sessions/capture/playback, lifecycle, Keychain, contacts/media integration, and supported navigation integration.
4. **Share behavior before sharing implementation.** Treat the existing SPECs, typed tool contracts, safety invariants, and acceptance scenarios as the cross-platform source of truth. Do not claim the Android capability registry proves iOS behavior.
5. **Make Kotlin Multiplatform conditional.** Audit imports, dependency graphs, concurrency, serialization, persistence, and testability in the existing Kotlin/JVM modules. Pilot the smallest genuinely portable module first. Convert only modules whose dependency and build audit passes; implement platform adapters in Swift.
6. **Keep provider choice explicit.** Reuse the active production provider's protocol only after verifying it from an iOS client. No silent provider or voice fallback, and no Android runtime changes in the port foundation.
7. **Treat CarPlay as a separate phase.** Apple requires approval of the relevant CarPlay entitlements. Amap publishes an iOS Navigation SDK and a CarPlay integration guide, but vendor SDK availability does not replace Apple entitlement approval.
8. **Use platform-specific CI.** Keep Android tests on the existing Android workflow. Add an iOS workflow on macOS with a shallow checkout, Xcode build, Swift/KMP tests, and simulator tests as the app becomes buildable. Do not make an iOS-only workflow fetch the entire Git history unless a specific check needs it.

## Rationale

- One repository keeps product decisions, safety invariants, tool schemas, acceptance criteria, and release planning together.
- Native Swift is the lowest-friction route to iOS audio/lifecycle and CarPlay APIs; a cross-platform UI framework would not remove the hard native integrations.
- Conditional KMP sharing avoids a risky, repo-wide conversion before portability is measured.
- Shallow iOS CI avoids paying the full historical repository cost for each build.
- The existing Android project remains the working baseline and regression oracle.

## Consequences

- The repository will contain Android and iOS platform code side by side, while each platform has independent build and release pipelines.
- Until a KMP pilot passes, iOS code must not import Android-only code; shared behavior is enforced through specifications, schemas, fixtures, and equivalent tests.
- A feature is not considered cross-platform complete until each supported platform has its own evidence.
- The repository history cleanup is a separate work item. Deleting files from the current tree does not necessarily remove historical blobs from Git history. Any history rewrite needs explicit owner approval and a coordinated clone/branch migration plan.

## Evidence and references

- [Kotlin Multiplatform iOS integration](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html) documents local and remote integration options, including local integration in a monorepo.
- [Apple: Requesting CarPlay entitlements](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements) documents Apple's review and provisioning requirements.
- [Apple: Voice-based conversation entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.carplay-voice-based-conversation) identifies the capability for CarPlay apps that respond to requests and perform actions.
- [Amap: iOS Navigation SDK overview](https://lbs.amap.com/api/navigation-sdk-for-ios/summary) and [CarPlay guide](https://lbs.amap.com/api/navigation-sdk-for-ios/guide/tools/carplay_navi) document the native navigation SDK and CarPlay adaptation.

## What would justify revisiting this decision

Reconsider a separate iOS repository if a measured iOS workflow cannot use a shallow/partial checkout, if independent ownership/security boundaries emerge, or if the shared core becomes a separately versioned package with a stable release contract. Reconsider KMP scope if the pilot shows excessive Swift interop friction or JVM-only dependencies. Do not split or migrate modules based only on aesthetic preference.
