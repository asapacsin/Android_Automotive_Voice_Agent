# SPEC-005 — Embedded Amap navigation MVP (map-first, assistant-on-map)

Status: **In milestone — M2 (Phase 1 / first checkpoint). Blocked on one credential: an Amap Android platform key bound to the package + SHA1.**
Raised: 2026-09-16 by the product owner as a **replacement specification** — verbatim source: [DEMAND-2026-09-16-embedded-amap-v2.md](DEMAND-2026-09-16-embedded-amap-v2.md) (48 sections)
Decision: [ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md)
Backlog: [B-006](../BACKLOG.md)
Supersedes in part: [SPEC-002](SPEC-002-navigation-uplink-mute.md) (approach), [SPEC-003](SPEC-003-amap-coexistence-voice-policy.md), [SPEC-004](SPEC-004-speech-test-harness.md) — see §"What carries over"

> The verbatim document is the requirement. This spec does three things it cannot: maps it onto the code that exists, pre-fills the §46 architecture checkpoint from measured facts, and names what is unknown.

## Demand (what was actually asked for)

A map-first vehicle-style screen: Amap's navigation view fills our own Activity, the assistant (avatar, state, short bubble, action feedback) sits above it as ordinary views, and voice commands drive navigation through the embedded SDK. Never switch to the installed Amap app; never need "draw over other apps". Device actions stay mocked behind `ActionExecutor`. Voice policy is decided by semantic category against `NavigationState`. Everything is tested with real speech, with local failure records and a regression corpus.

## Why it matters

See ADR-007's table: every hard defect of the last two days traces to another app owning the screen and speaker. Embedding moves navigation state, guidance text and guidance speech *into our process*, which is where the assistant can actually coordinate with them.

## §46 architecture checkpoint — pre-filled from evidence (executor confirms, does not re-derive)

| # | Item | Answer | Evidence |
| --- | --- | --- | --- |
| 1 | Package name | `com.novadrive.app` | `app/build.gradle.kts` |
| 2 | min / target SDK | `minSdk 28`, `targetSdk 34`; test device SDK 36 | same; `PRODUCT.md` |
| 3 | UI framework | **Programmatic Android Views.** No `res/layout/`, no Compose dependency; `MainActivity` does `setContentView(ScrollView(this).apply { addView(column) })` | grep 2026-09-16 |
| 4 | Amap-related files | `NavigationAdapter.kt` (deep links), `AmapPoiClient.kt` (Web POI search — **keep**, becomes the `DestinationResolver` backend), `AmapSettings.kt` (Web key), `AmapAutoPickService.kt` + `res/xml/amap_auto_pick.xml` (accessibility auto-tap — obsolete), `AndroidManifest.xml` `<queries><package android:name="com.autonavi.minimap"/>` | grep |
| 5 | Launches external Amap? | **Yes** — `NavigationAdapter.kt:88,95` build `androidamap://keywordNavi` / `androidamap://navi` and `setPackage("com.autonavi.minimap")` at lines 27/42/63 | grep |
| 6 | Voice modules to preserve | `voice/BaiduFlexClient`, `BaiduFlexProvider`, `BaiduFlexProtocol`, `FlexFunctionCallAssembler`, `PcmAudioCapture`, `PcmAudioPlayer`, `AudioFocusController`, app `VoiceSessionController`, `ingress/**` core, `PersonaProfiles`, `wake/**` (AIKit), credential stores | tree |
| 7 | Action modules to preserve | `AndroidToolDispatcher`, `AndroidActionExecutor` + `SafeAndroidActionExecutor`, `BundledMusicPlayer`, `NavigationState` (to be redefined, not deleted) | tree |
| 8 | Test infrastructure | 90 JVM tests (JUnit 5); **`behavior-test` reviewed 2026-09-16** — pure Kotlin JVM (no Android plugin), depends on contracts/ingress/safety/vehicle/verification/feedback/orchestration/simulator but **not on `:app`**, and holds `VoiceSessionOrchestratorBehaviorTest`, `RealtimeToolDispatchBehaviorTest`, `DependencyBoundaryTest`, `SecretScanTest`; debug-only `DebugToolReceiver` + `AudioPlaybackProbe`; adb mock-location recipe (SPEC-002) | read 2026-09-16 |
| 9 | Amap key / SHA1 still missing | **Android platform key: missing** (only a Web-service key exists). Debug SHA1: see `CURRENT_MILESTONE.md` (computed from `~/.android/debug.keystore`). Release SHA1: no release keystore exists yet | this session |
| 10 | Proposed files | **Add** `nav/NavigationController.kt`, `nav/AmapNavigationController.kt`, `nav/NavigationStateStore.kt`, `nav/DestinationResolver.kt` (+ `AmapPoiDestinationResolver`), `ui/AssistantNavigationScreen` (FrameLayout host), `ui/AssistantOverlay`; **modify** `MainActivity` (host the screen), `app/build.gradle.kts` (SDK dep, BuildConfig key), `AndroidManifest.xml` (meta-data key, permissions), `NavigationState` (enum), tool declarations (`stop_navigation`, `cancel_route`, `reroute`); **retire from normal flow** `NavigationAdapter`, `AmapAutoPickService` | this spec |

## What already exists — mapping

| v2 concept | Existing | Gap |
| --- | --- | --- |
| Intent / Tool Router | Flex function calls + `FlexFunctionCallAssembler.validate` whitelist | none — `navigate_to(destination)` already carries the query |
| `DestinationResolver` | `AmapPoiClient` (`place/around` biased by coarse location, fallback `place/text`) | wrap behind the interface; add ambiguity surfacing (§10, TC10) |
| `NavigationController` | none — `NavigationAdapter` fires an Intent and forgets | new |
| `NavigationState` enum | `NavigationState` object with boolean `navigating` + confirmation window | redefine as enum + `StateFlow`; keep the confirmation semantics until VoicePolicy lands |
| Assistant overlay | none — current UI is a settings-style column | new; Phase 1 is a placeholder |
| `ActionExecutor` generic + mock | typed `AndroidActionExecutor` | as SPEC-003 C4: one `device_control` tool, enumerated targets |
| VoicePolicy | P1 rule at `AndroidPlaybackPort.enqueue` + Option C VAD raise | policy object per §17–18 driven by `NavigationState`; categories by turn provenance (SPEC-003 C2) |
| Guidance-speech coordination | **nothing observable today** (SPEC-002) | SDK TTS-playing state / text callbacks (§19) — verify on the chosen SDK version |
| `stop_navigation` | `exit_navigation_mode` (only clears our mute) | replace with a real stop through the controller |

## Status of the SDK-independent domain layer (2026-09-16)

Built ahead of v2's phase order because none of it touches the Amap SDK — see `CURRENT_MILESTONE.md` for why that does not violate §44. All of it is **additive and wired into nothing**.

| v2 § | Type | State |
| --- | --- | --- |
| §12 | `nav/NavigationPhase` (7 values) + `isNavigationSessionActive` | Built, L2 |
| §10 | `nav/Destination`, `DestinationResult` (incl. `Ambiguous` for TC10), `RoutePlanResult`, `NavigationResult` | Built, L2 — `Destination` rejects out-of-range coordinates in `init`, so v2 §10's "never invent coordinates" is enforced by the type |
| §14 | `nav/NavigationStateStore` (`StateFlow`, no Amap types in its API) | Built, L2 |
| §9 | `nav/NavigationController`, `DestinationResolver` interfaces + test `FakeNavigationController` | Interfaces built; **`AmapNavigationController` awaits the key** |
| §21–23 | `action/ActionExecutor`, `ActionRequest`, `ActionResult`, `MockActionExecutor` with `DEVICE_OFFLINE` injection | Built, L2 |
| §17–18 | `voicepolicy/VoicePolicy`, `ResponseCategory`, `VoiceDecision` | Built, L2 — all 12 cells asserted individually; `decide` takes no `String` |
| §6 | `ui/AssistantUiState` | Enum only; no views |

**Naming deviation, deliberate:** v2 §12 names the enum `NavigationState`; it is `NavigationPhase` here because a different `NavigationState` object already drives the shipped speech-mute and VAD mitigation. The two merge at Phase 4. Do not create a second `NavigationState`.

**Independent review note (2026-09-16), carry into Phase 6:** `MockActionExecutor.injectDeviceOffline(target)` is a **public method on a production-source class**, not a test-only hook. It is harmless today — nothing is wired, and injection is a method call rather than a field read from `ActionRequest`, so the model cannot spoof a device failure through tool arguments (this was checked deliberately). But when a real `ActionExecutor` replaces the mock, the mock must not ship reachable from the production graph. Move it to the test source set, or gate it behind the debug variant, at the point the executor is wired in.

**Second review note, carry into Phase 5:** `NavigationStateStore.update()` writes `_phase` and `_destination` as two separate `MutableStateFlow` assignments, so they are not updated atomically. Nothing consumes the store yet, so this is currently harmless — but a UI that combines both flows could briefly render a new phase beside the previous destination (e.g. "NAVIGATING" with the old name) during a destination change. When the overlay starts reading this in Phase 5, either expose a single combined state object or collect the two flows with `combine` and accept the transient, deliberately. Do not discover this as a UI flicker.

**Still absent and blocking the MVP:** every Amap-touching piece — `AmapNavigationController`, `AMapNaviView` hosting, the callback adapter, lifecycle forwarding — plus all wiring of the above into the tool dispatcher, playback path and UI.

## Constraints and conflicts

- **C1 — ADR-003 → ADR-007.** Decided by the owner; recorded. Not open.
- **C2 — Two different Amap keys.** The Web-service key in Keystore (`amap_web_key`) is *not* an Android platform key. The SDK key is bound to package + SHA1 and is read by the SDK from manifest meta-data / `MapsInitializer` — it cannot live in Keystore the way runtime credentials do. Inject via `local.properties` → `BuildConfig` → manifest placeholder; `local.properties` is already git-ignored (**verified 2026-09-16: `.gitignore:9`**). **Never commit it.** Note this is necessary but not sufficient — see C11: the automated secret scan would not currently catch the key if it were placed in the manifest or a Gradle file instead.
- **C3 — Privacy compliance.** Since 2021 the Amap SDKs refuse to initialise until `MapsInitializer.updatePrivacyShow(ctx, true, true)` and `updatePrivacyAgree(ctx, true)` (and the Navi equivalents) are called. Must precede any SDK use; must reflect a real consent UI before release.
- **C4 — §3.10 vs the microphone FGS.** The FGS was added purely so MIUI would not kill the socket when Amap took the foreground. With our Activity foreground that reason is gone. It may stay for screen-off listening, but no design may rely on background survival. Do not remove it in Phase 1.
- **C5 — SPEC-002's Option C remains valid but its Phase 2 target changes.** Guidance still leaves the speaker and enters the mic; VAD raise still helps. But the precise gate is now Phase 7 of v2 using the SDK's speaking state, not playback forensics.
- **C6 — Target form factor.** v2 says "vehicle-style tablet UI"; the only test device is a phone (1220×2712). Phase 1 renders on the phone; tablet layout is a later concern. Owner to confirm.
- **C7 — E2E model and navigation intents.** Fine: intents are tool calls. New tools `stop_navigation`, `cancel_route`, `reroute` (§24). "Do not let the LLM invent coordinates" already holds — coordinates come only from the resolver.
- **C8 — Dependency coordinates and minSdk.** `com.amap.api:navi-3dmap` (bundles the 3D map). Confirm the exact artifact for 11.2.100 and that its minSdk ≤ 28. Unverified until Gradle resolves it.
- **C9 — Commercial terms.** Unverified whether a vehicle product needs an Amap enterprise agreement. Open; not blocking the MVP.
- **C10 — APK size.** Already 26 MB after AIKit. Navi SDK adds native libs for both ABIs. Measure and report; the owner has an open ABI decision.
- **C11 — `SecretScanTest` does NOT cover the two credentials about to arrive.** Read on 2026-09-16. It matches four literal prefixes (`BAIDU_SECRET_KEY=`, `BAIDU_API_KEY=`, `DASHSCOPE_API_KEY=`, `OPENAI_API_KEY=`) plus a Baidu `wss://…access_token=` URL, scanning only `app/src`, `ingress/src`, `contracts/src`, `orchestration/src`. It would therefore **not** catch:
  - an Amap Android key in `AndroidManifest.xml` meta-data (`com.amap.api.v2.apikey`) — the SDK's normal placement;
  - an iFlytek `appId`/`apiKey`/`apiSecret` in `res/values/strings.xml` — **exactly the pattern the AIKit vendor demo ships**, so a copy-paste integration passes the scan silently;
  - anything in `app/build.gradle.kts`, which is **not under `app/src`** and is never scanned.

  Given the product owner's hardest standing rule is that credentials never enter source control, the scan must be extended **before** either key is added: cover Gradle files and `local.properties`, and match the Amap and iFlytek key shapes and XML/meta-data forms rather than only `NAME=` prefixes. Tracked as a prerequisite of M2, not of the MVP.

  **✅ CLOSED 2026-09-16.** `SecretScanTest` now also scans root/`settings`/per-module `build.gradle.kts`, matches `com.amap.api.v2.apikey` meta-data and `<string name="appId|apiKey|apiSecret">`, and asserts `.gitignore` covers `local.properties` plus `*.jks`/`*.keystore` (the keystore patterns were genuinely absent and were added). Placeholders (`@…`, `${…}`, `BuildConfig…`, `YOUR_`, `PLACEHOLDER`, `TODO`, `xxx`, empty) are allowed. Failure messages are `path:line` only — asserted, including that an 8-character prefix or suffix of the secret never appears. Each matcher is proven to fire against synthetic values in `@TempDir` fixtures, so the suite is not merely passing because the tree is clean. Markdown is excluded, so the public debug SHA1 in the control docs cannot trip it.

  **Residual gaps found by independent review — do not treat the scan as complete:**
  1. **Indirect string-resource reference is not caught.** `android:value="@string/amap_key"` is correctly allowed as a placeholder, but the real key then living in `res/values/strings.xml` as `<string name="amap_key">…</string>` matches nothing: the Amap matcher keys on `<meta-data>` tags and on `amap`-containing identifiers in *assignments*, and XML element **text** is neither. The iFlytek `<string>` rule only covers the three names `appId`/`apiKey`/`apiSecret`. This is a realistic shape, since `@string/…` is idiomatic Android.
  2. **`HEX32` is lowercase-hex only** (`^[a-f0-9]{32}$`). The manifest path does not depend on it (any non-placeholder value fails there), but the **Gradle-property** path does — so a key containing an uppercase letter assigned to `val amapKey = "…"` in a Gradle file would pass. Verify the real key's character set when it arrives, and widen the pattern if it is not lowercase hex.

  Neither gap blocks M2. Both should be closed when the Amap key is actually wired, and **`local.properties` remains the only sanctioned home for it**.
- **C12 — the dependency guard is narrower than previously stated in this repo.** `DependencyBoundaryTest` does **not** keep `ingress` "Android-free" in general; it forbids a fixed list (`com.novadrive.simulator`, `android.car`, `com.amap`, `com.baidu`, `com.google.android.gms`, `com.openai`, `com.alibaba.dashscope`, `com.dashscope`) in seven core modules' `src/main` imports, and asserts `:simulator` stays off their classpaths. A generic `android.*` import in `ingress` would pass. Two consequences: earlier claims in these specs that the test enforces Android-freedom were **overstated and are corrected here**; and usefully, `com.amap` already being on that list means the guard enforces v2 §3.9 (no Amap code scattered into unrelated modules) for free — the embedded SDK must stay in `app`.
- **C13 — `behavior-test` cannot host the Level A logic tests as it stands.** It is a JVM module with no `:app` dependency, and an Android application module cannot be depended on that way. The new domain layer (`NavigationPhase`, `VoicePolicy`, `MockActionExecutor`, `Destination`) is being built in `app`, so its tests live in `app/src/test`. If those types are later wanted in `behavior-test`, they must first be extracted into a shared pure-Kotlin module — a deliberate refactor, not a side effect.

## Scope (P0 = v2 §44 Phases 1–6, §45 P0)

Phase 1 is the current milestone. Phases 2–6 follow only after each checkpoint passes on device. Phase 7 (audio coordination) and 8 (physical E2E) are P1.

## Out of scope

v2 §42 verbatim, plus: tablet layout, release signing, real device actions, replacing the voice provider, deleting dormant deep-link code.

## Open questions — before Phase 1 code

| # | Question | Who |
| --- | --- | --- |
| Q1 | Amap Android platform key for `com.novadrive.app` + debug SHA1 | **Owner — blocking** |
| Q2 | Exact Maven artifact/version for Navi SDK ≥ 11.2.100 and its minSdk | Executor, at checkpoint |
| Q3 | Does the SDK's "internal voice" mode ship its own TTS on this device, and does the TTS-playing state query exist in this version? | Executor, Phase 7 |
| Q4 | Phone-only for now, tablet later? (C6) | Owner |
| Q5 | Keep the microphone FGS? (C4) | Architect, Phase 4 |
| Q6 | Commercial licence terms (C9) | Owner, before release |

## What carries over from SPEC-003 / SPEC-004

- **From SPEC-003:** the turn-provenance derivation of `ResponseCategory` (C2) — still the only non-text-matching source of category on an E2E model; the `device_control` tool design (C4); the audio-focus question (C5). **Resolved by v2:** C1 (§16 keeps Baidu realtime), C3 (§19 supplies the speaking signal), C6 (wake word during navigation — still owner's call, Q5 there).
- **From SPEC-004:** the paid-API constraint on audio-level tests (v2 Level B = SPEC-004 A-live); the failure-record and regression machinery; the suppression-metric denominator (§39 restates it). **Changed by v2:** three levels A/B/C, UI/screenshot assertions (§30), navigation stages in the failure taxonomy (§35).

## Acceptance

v2 §47 is M2's completion rule (see `CURRENT_MILESTONE.md`). v2 §40 and §41 are the MVP's acceptance; each row maps to `ACCEPTANCE_TESTS.md` as: builds/compiles → L3; state and policy logic → L2; anything about rendering, overlay visibility, external-app non-launch, navigation, voice → **L5** with a screenshot artefact; anything involving Baidu or the Amap network → **L6**.
