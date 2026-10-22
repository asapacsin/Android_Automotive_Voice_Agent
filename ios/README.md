# Nova Drive iOS

**Status: planning only. There is no runnable iOS app or Xcode project in this directory yet.**

Read these before implementing:

- [Port specification](../SPECS/SPEC-022-ios-port-foundation.md) — required behavior and acceptance evidence.
- [Architecture decision](../DECISIONS/ADR-018-ios-monorepo-and-kmp.md) — repository layout, native Swift boundary, and conditional Kotlin Multiplatform strategy.
- [Implementation plan](../docs/IOS_PORT_PLAN.md) — staged work and dependencies.
- [Current Android architecture](../docs/ARCHITECTURE.md) — canonical ownership of existing product behavior.
- [Product invariants](../docs/INVARIANTS.md) — safety, execution proof, and privacy rules.

## Intended platform boundary

The existing Android Gradle project stays at the repository root. The iOS app will live under ios/ and use a native Swift/SwiftUI shell.

Native iOS responsibilities will include:

- Audio session, microphone capture, playback, interruptions, and barge-in.
- Application lifecycle and any permitted background-audio behavior.
- Keychain-backed provider credential storage.
- iOS-native contacts/media APIs and Amap navigation integration.
- iPhone UI and, only after approval, CarPlay templates and scenes.

The shared-core decision is deliberately not implemented yet. Existing modules are Kotlin/JVM, not proven Kotlin Multiplatform modules. Follow the dependency audit and pilot in the port plan before adding KMP plugins or moving modules.

## Development prerequisites

- A supported macOS version and Xcode.
- An iPhone for real microphone, audio-session, interruption, and navigation validation.
- Amap iOS Navigation SDK access, current SDK/key requirements, and a confirmed license/App Store submission path.
- Test credentials and workspace access for the active realtime provider. Keep user-entered secrets in Keychain and build-time keys out of source control.
- Apple Developer account access for signing; CarPlay work additionally requires Apple's relevant entitlement approval.

## Current implementation status

No iOS source, Xcode project, provider adapter, audio adapter, navigation integration, or CarPlay integration exists yet. Do not report this directory as a working iOS build. Each feature becomes supported only after its own iOS tests and device evidence satisfy the SPEC-022 acceptance criteria.
