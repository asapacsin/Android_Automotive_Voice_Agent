# SPEC-005 · Phase 1 design — embedded map proof

Status: **AMENDED 2026-09-16 by product-owner decision. Design for review; not implemented.**
Authority: [DEMAND v2](DEMAND-2026-09-16-embedded-amap-v2.md) §44 Phase 1, §46, §47 · [UI design](DEMAND-2026-09-16-ui-design.md) · [ADR-007](../DECISIONS/ADR-007-embedded-amap-navigation-sdk.md) · [SPEC-005](SPEC-005-embedded-amap-mvp.md)
Milestone: [M2](../CURRENT_MILESTONE.md)

> Written for: the product owner, to confirm the amendment; then the executor, who implements **exactly this** and nothing more.
> **Evidence discipline, preserved:** statements marked *(evidence)* were read from the repository on 2026-09-16 and cite file:line. Statements marked *(unverified)* are beliefs to be tested at build or on device. **The amendments below introduce no new *(evidence)* claims** — the owner's decisions are recorded as decisions, and their technical consequences are marked *(unverified)* where they have not been measured.

---

## ⚠️ Superseded proposals — do not implement

These appeared in the 2026-09-16 first draft and are **withdrawn by the product owner**:

| Withdrawn | Replaced by |
| --- | --- |
| **D1-B: `navigate()` returns a global `Rejected("NAVIGATION_NOT_READY")`** as the means of preventing external Amap from opening | **D1 (amended)** below — delete the external-launch path itself; keep the tool and its abstraction alive and pointed at the embedded implementation |
| **D2 "usable": a compact mic toggle + ⚙ settings bottom bar** | **D2 (amended)** below — the reference media/climate bottom bar from the UI design |
| **Treating the bottom-right control as a settings button** | It is a **front-facing camera** button. Not settings |

Rationale recorded so the reasoning is not lost: the first draft optimised for "make row 4 pass during the test". The owner's correction is that row 4 must pass because *the obsolete path is gone*, not because the tool was disabled. Those are different products.

---

## 0. Decision record — product-owner authoritative (2026-09-16)

### D1 — navigation behaviour

**Navigation lives inside our own app.** The external-Amap deep-link execution path is removed from the Phase-1 user flow.

1. `navigate_to` **must not** launch the standalone 高德地图 application.
2. The navigation tool and its abstraction **stay alive**, targeting the embedded implementation.
3. `navigate_to` is **not** globally rejected as a device for suppressing external Amap.
4. `NavigationAdapter` may remain in the tree while other code references it, but **its external-launch branch must be unreachable from the Phase-1 user flow** *(evidence that it is currently reachable: `AndroidToolDispatcher.kt:95–99` → `NavigationAdapter.openDestination()` → `NavigationAdapter.kt:88,95` deep links, `setPackage("com.autonavi.minimap")` at lines 27/42/63)*.

**How this is achieved (engineering, see E9):** `SafeAndroidActionExecutor` stops constructing and calling `NavigationAdapter` entirely; `navigate()` delegates to the project-owned `NavigationController` abstraction *(evidence: interface exists at `app/src/main/kotlin/com/novadrive/app/nav/NavigationController.kt`, built 2026-09-16, wired to nothing)*.

### D1-note — the separately documented capability gap

Phase 1 excludes destination search and route planning by the owner's own scope statement. Therefore the embedded implementation behind `NavigationController` **cannot yet produce a route**, and a `navigate_to` call in Phase 1 resolves to an honest "embedded routing not built yet" outcome.

**This is recorded here as a capability gap, not as the D1 solution.** The distinction is load-bearing:

- The **mechanism** that stops external Amap opening is the deletion of the deep-link call path.
- The **outcome** of `navigate_to` in Phase 1 is a consequence of Phase 2 not existing yet.

If the deep-link path were left in place, this gap statement would be false advertising — the app *would* navigate, just in the wrong product. Suggested status string: `EMBEDDED_ROUTING_NOT_IMPLEMENTED`, distinct from the withdrawn `NAVIGATION_NOT_READY`, so logs cannot confuse a scope gap with a suppression hack. The model then tells the driver it cannot do it yet, satisfying persona rule 3 (绝对不许谎报结果).

### D2 — UI layout

**The reference design is authoritative.** Source: [DEMAND-2026-09-16-ui-design.md](DEMAND-2026-09-16-ui-design.md).

```text
┌────────────────────────────────────────────────────────────────────────────┐
│  ╭──────╮  ╭──────────────────────────────────────────────╮               │
│  │Avatar│  │ Assistant speech / current response          │               │
│  ╰──────╯  ╰──────────────────────────────────────────────╯               │
│     ● LISTENING / PROCESSING / SPEAKING                                   │
│                           EMBEDDED AMAP                                    │
│                 ╭──────────────────────────────────╮                       │
│                 │ ✓ Action result                  │                       │
│                 ╰──────────────────────────────────╯                       │
├────────────────────────────────────────────────────────────────────────────┤
│  🎵 Music        ◀        ❚❚        ▶        30°C                 [ 📷 ]  │
└────────────────────────────────────────────────────────────────────────────┘
```

**No permanent mic button.** The assistant's voice state is conveyed by the avatar and state indicator. A manual mic control is added only if a real requirement calls for one.

---

## 1. Component responsibilities

| Component | Responsibility | Phase 1 scope |
| --- | --- | --- |
| **Embedded Amap view** | Full-screen base layer, visually dominant; owns map, position, traffic rendering | **Live.** The render proof |
| **AssistantAvatar** | Upper-left assistant representation | **Live.** Static drawable; no animation (v2 §42 excludes complex avatar systems) |
| **AssistantStateIndicator** | Shows IDLE / LISTENING / PROCESSING / SPEAKING plus success/failure | **Live**, driven by the existing session state |
| **AssistantSpeechBubble** | Assistant's current response, beside the avatar; must not obscure maneuver information | **Live**, fed by the existing transcript callback |
| **ActionFeedbackCard** | Temporary confirmation of music / AC / vehicle actions, success or failure; auto-dismisses | **Structure live, inert in Phase 1** — it has nothing to display until tools are wired at Phase 6 |
| **Bottom bar** | Compact media / climate strip, preserving the reference style | **Rendered, non-functional in Phase 1** — see OQ-2 |
| **Camera button (bottom-right)** | Opens the **front-facing** camera, live preview of the person in front of the device; closing returns to the map **without ending the assistant session** | **Live** — see E10 and R-CAM |

### State vocabulary — three names for the same thing *(evidence)*

| Runtime `VoiceUiState` *(evidence: `ingress/.../VoiceUiState.kt:3–12`)* | `AssistantUiState` *(evidence: `app/.../ui/AssistantUiState.kt`, v2 §6 verbatim)* | Displayed |
| --- | --- | --- |
| `DISCONNECTED` | `IDLE` | IDLE |
| `CONNECTING`, `RECONNECTING`, `THINKING` | `PROCESSING` | PROCESSING |
| `LISTENING`, `USER_SPEAKING` | `LISTENING` | LISTENING |
| `SPEAKING` | `RESPONDING` | **SPEAKING** |
| `ERROR` | `ERROR` | ERROR |
| *(not a session state — from tool results)* | `ACTION_SUCCESS`, `ACTION_FAILURE` | card |

**Naming conflict, resolved without renaming:** the UI design says *SPEAKING*; `AssistantUiState` says `RESPONDING` because v2 §6 specifies that identifier. The enum is left alone and the **display label** reads SPEAKING. Renaming a v2-specified enum to match a label would be the worse trade.

---

## 2. §46 architecture checkpoint — answered

| # | Item | Answer |
| --- | --- | --- |
| 1 | Package | `com.novadrive.app` *(evidence: `app/build.gradle.kts:11`)* |
| 2 | SDK levels | `compileSdk 34`, `minSdk 28`, `targetSdk 34` *(evidence: `app/build.gradle.kts:8,12,13`; `libs.versions.toml:7–9`)*. Test device SDK 36 / HyperOS |
| 3 | UI framework | **Programmatic Android Views. No XML layouts, no Compose.** `res/layout/` absent; no Compose in the catalog; `MainActivity : Activity()` calls `setContentView(ScrollView(this).apply { addView(column) })` *(evidence: `MainActivity.kt:32,109`)*. A `FrameLayout` host is therefore a few lines |
| 4 | Amap-related files | `NavigationAdapter.kt` (deep links), `AmapPoiClient.kt` + `AmapSettings.kt` (Web POI search — **keep**, future `DestinationResolver` backend), `AmapAutoPickService.kt` + `res/xml/amap_auto_pick.xml` (obsolete), manifest `<queries>` and the accessibility `<service>` *(evidence: grep; `AndroidManifest.xml:38–44`)* |
| 5 | Launches external Amap? | **Yes, two independent paths.** (a) `navigate_to` → deep link *(evidence: `AndroidToolDispatcher.kt:95–99`)* — **removed by D1**. (b) `openApp(maps)` fires `ACTION_VIEW geo:0,0?q=` *(evidence: `AndroidToolDispatcher.kt:103`)*, which resolves to the installed Amap. (b) is a distinct "open the map app" request, is **outside D1's acceptance check**, and is **left unchanged in Phase 1** — flagged as **OQ-3** |
| 6 | Voice modules preserved — untouched | `voice/BaiduFlexClient`, `BaiduFlexProvider`, `BaiduFlexProtocol` (+`FlexFunctionCallAssembler`), `BaiduRealtimeClient`, `BaiduProtocol`, `BaiduAccessTokenClient`, `BaiduDirectRealtimeProvider`, `PcmAudioCapture`, `PcmAudioPlayer`, `AudioFocusController`, app `VoiceSessionController`, `VoiceSessionService`, `PersonaProfiles`, `BaiduSettings*`, `ingress/**`, `wake/**`, `AndroidKeystore*CredentialStore`, legacy `NavigationState` object |
| 7 | Action modules preserved | `AndroidToolDispatcher` validation logic, `AndroidActionExecutor` interface, `BundledMusicPlayer`, `action/ActionExecutor` + `MockActionExecutor`, `voicepolicy/`, `nav/` domain layer. `SafeAndroidActionExecutor` changes **only** in its navigation wiring (D1/E9) |
| 8 | Test infrastructure | 196 distinct JVM tests, 0 failures, counted from JUnit XML after a forced re-run *(evidence: `ACCEPTANCE_TESTS.md`)*. `DependencyBoundaryTest` forbids `com.amap` imports in the seven core modules — so Amap types must live in `app`, which §4's layout guarantees. `SecretScanTest` matches `com.amap.api.v2.apikey` meta-data and `amap`-named Gradle assignments; E2 is compatible. **No instrumented/`androidTest` infrastructure exists** |
| 9 | Key / SHA1 | `AMAP_API_KEY` in `local.properties`, 32-char lowercase hex, gitignored, untracked, value absent from all source and Gradle files *(evidence: masked inspection)*. Debug SHA1 `32:2F:E8:E7:33:D5:03:AA:FA:6F:00:37:DF:66:54:52:CB:D4:DA:EB`. **(unverified)** that the key is the *Android platform* type bound to that SHA1 — indistinguishable from a Web key by format; proven only by the map rendering |
| 10 | Files | §4 below |

---

## 3. Engineering decisions

**E1 — Dependency.** Catalog: `amap-navi = "11.2.100"`, `amap-navi-3dmap = { module = "com.amap.api:navi-3dmap", version.ref = "amap-navi" }`. **(unverified)** that this version resolves from the configured repositories and that its `minSdk ≤ 28`. `settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS` with aliyun `google/central/public` + `google()` + `mavenCentral()` *(evidence: `settings.gradle.kts:15–24`)*, so any repository fix belongs there, not in `app`. **Resolution is the first implementation step and answers SPEC-005 Q2.** Ladder if unresolvable: nearest resolvable 11.x → hand-placed AAR in `app/libs/`, recorded as such.

**E2 — Key injection.** `buildFeatures { buildConfig = false }` *(evidence: `app/build.gradle.kts:46`)* and the SDK reads its key from manifest meta-data, so use a **manifest placeholder** and leave `buildConfig` off:

```kotlin
val amapApiKey: String = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.getProperty("AMAP_API_KEY")?.trim().orEmpty()
if (amapApiKey.isEmpty()) logger.warn("AMAP_API_KEY missing from local.properties — the navigation view will not authorise")
android { defaultConfig { manifestPlaceholders["AMAP_API_KEY"] = amapApiKey } }
```
```xml
<meta-data android:name="com.amap.api.v2.apikey" android:value="${AMAP_API_KEY}" />
```
A clone without the key still builds; `SecretScanTest` treats `${…}` as a placeholder and the Gradle right-hand side is not a string literal; the value is never logged.

**E3 — Privacy compliance.** The SDK refuses to initialise until the privacy calls are made. One idempotent class, invoked before any view construction, with a fixed acknowledgement **explicitly commented as a development stand-in**: `MapsInitializer.updatePrivacyShow(ctx, true, true)`, `updatePrivacyAgree(ctx, true)`, and the `NaviSetting` equivalents. A real consent screen is a later phase and a release prerequisite. **(unverified)** exact class/method names in 11.2.100.

**E4 — Screen structure.** `MainActivity` hosts `AssistantNavigationScreen : FrameLayout`:

```
FrameLayout (AssistantNavigationScreen)
├── [0] Amap view                 — base, match_parent
├── [1] AssistantOverlayView      — transparent, pass-through; avatar + state + bubble + action card
├── [2] BottomBarView             — bottom-anchored strip
└── [3] CameraPreviewView         — GONE until the camera button is pressed, then covers [0]-[2]
```
All Amap types confined to `nav/amap/`; `MainActivity` never imports `com.amap`.

**E5 — Lifecycle.** Forward `onCreate(savedInstanceState)`, `onResume`, `onPause`, `onDestroy`, `onSaveInstanceState` to the Amap view; the host class owns the calls. `AMapNavi.getInstance(applicationContext)` created after privacy, before the view; `destroy()` in `onDestroy`. **(unverified)** whether `AMapNaviView` renders a base map with **no active route** — if it is blank with a valid key, fall back to `com.amap.api.maps.MapView` from the same artifact for the Phase-1 proof, **record the fallback in the report**, and restore `AMapNaviView` at Phase 2 when a route exists.

**E6 — Permissions.** Add `ACCESS_FINE_LOCATION`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (SDK expectations) and **`CAMERA`** (E10). Do **not** add `WRITE_EXTERNAL_STORAGE` or `READ_PHONE_STATE` unless the first build proves the SDK demands them — and then record it. Rendering the map does not require granted location.

**E7 — Overlay (amended per D2).** Avatar (static), state indicator, speech bubble beside the avatar, action feedback card centred lower-middle and `GONE` when idle. Bound to the existing callbacks *(evidence: `MainActivity.kt:54–68` already routes `onUiState` / `onTranscript` / `onError`)* through a **pure mapper** `VoiceUiState → AssistantUiState`. Touch: only the bubble, card, bottom bar and camera button consume touches; the remaining overlay area passes through to the map (v2 §26). No animation.

**E8 — Left in place deliberately.** `NavigationAdapter` (unreferenced after E9 but retained per D1.5), `AmapAutoPickService`, `res/xml/amap_auto_pick.xml`, the `<queries>` block, `VoiceSessionService`, the legacy `NavigationState` object, `AmapPoiClient`. Retirement is Phase 4, after the embedded path passes L5.

**E9 — Navigation rewiring (implements D1).** In `SafeAndroidActionExecutor`: delete the `NavigationAdapter` field and its `poiResolver` construction *(evidence: `AndroidToolDispatcher.kt:81–94`)*; `navigate(destination)` delegates to a `NavigationController` supplied by constructor, defaulting to a Phase-1 binding that records the request, leaves `NavigationPhase` unchanged, and returns `Rejected("EMBEDDED_ROUTING_NOT_IMPLEMENTED")`. **After this change no code path in the Phase-1 user flow can reach a `com.autonavi.minimap` intent through `navigate_to`** — which is the check in §5 row 4b. `AmapPoiClient` and `AmapSettingsRepository` remain in the tree, unreferenced by this path, for Phase 2's resolver.

**E10 — Camera (new, per D2).** A `CameraPreviewView` using CameraX or `Camera2` bound to the **front** lens, added as the top child and `GONE` by default; the bottom-right button toggles it; closing restores the map. **The assistant session is untouched by either transition** — no call to `controller.stop()`, no `VoiceSessionService.stop()`. **(unverified)** whether an active `AudioRecord` capture and a camera preview coexist without contention on this device; Android permits it in principle, but it is measured at L5, not assumed. CameraX would add a dependency; `Camera2` adds none but more code. **Executor picks at build time and records which, with the APK delta.**

**E11 — One session entry point, three future callers (implements the OQ-1 decision).**

The requirement is that the 开发者设置 toggle and the future wake-word handler call **the same** API. Today that is impossible as written: the session is started by `MainActivity.toggleSession()`, which owns the `VoiceSessionController` instance *(evidence: constructed at `MainActivity.kt:50–81`; started at `MainActivity.kt:145`)*. A second Activity cannot reach that instance, and constructing its own would create a **second `AudioRecord` owner** — the exact class of defect this project has already paid for twice.

Introduce a single application-scoped seam:

```kotlin
object VoiceSessionGateway {
    fun attach(controller: VoiceSessionController, service: SessionServiceControl)
    fun detach(controller: VoiceSessionController)   // identity-compared; see the hazard below
    val isActive: Boolean
    fun start(): StartResult   // Started | AlreadyActive | MicPermissionMissing | NotAttached | ConfigInvalid
    fun stop()
}
```

- `MainActivity` **keeps owning and releasing** the controller exactly as today — lifecycle, `release()`, and the `VoiceSessionController` construction are unchanged byte-for-byte — and simply `attach`es on create and `detach`es on destroy.
- `DeveloperSettingsActivity` calls `VoiceSessionGateway.start()` / `.stop()`.
- The wake-word handler will call the **same two methods** when credentials arrive (ADR-006). Replacing the trigger then touches neither the controller nor the gateway.
- **Permission stays with the caller.** `start()` returns `MicPermissionMissing` rather than requesting `RECORD_AUDIO` itself, because only an Activity can request it and the wake-word path will already hold it. `DeveloperSettingsActivity` handles that result by requesting, mirroring the existing logic *(evidence: `MainActivity.kt:140–143`)*.

> ⚠️ **Ownership hazard — the same shape that has bitten this codebase twice** (duplicate `onFocusChanged` assignment; the throwaway `BaiduFlexClient` in Test Connection). `detach` must clear the slot **only if the controller passed is identical (`===`) to the one currently attached**, so a finishing Activity cannot unhook a live session owned by a newer one. Cover it with a test in the same shape as `releasingFirstOwnerDoesNotClearSecondOwner`.

**(unverified)** behaviour when `MainActivity` is destroyed while a session runs: the gateway then holds a detached controller and `start()` returns `NotAttached`. Acceptable for Phase 1 — the product is foreground-first by v2 §3.10 — but it is a real limitation and must be stated in the report, not discovered later.

**Temporary by construction:** the toggle is developer infrastructure. It is removed as the *normal* entry point when the wake word ships; the gateway is not.

---

## 4. Exact files

**Modify (6)**

| File | Change | Why |
| --- | --- | --- |
| `gradle/libs.versions.toml` | `amap-navi` version + `amap-navi-3dmap` library; CameraX entries **only if** E10 chooses CameraX | E1, E10 |
| `app/build.gradle.kts` | Amap dependency; `local.properties` → `manifestPlaceholders`; missing-key warning | E1, E2 |
| `app/src/main/AndroidManifest.xml` | `com.amap.api.v2.apikey` meta-data with placeholder; `ACCESS_FINE_LOCATION`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CAMERA` | E2, E6 |
| `app/src/main/kotlin/com/novadrive/app/MainActivity.kt` | Replace the widget column with `AssistantNavigationScreen`; forward lifecycle; **keep the `VoiceSessionController` wiring byte-for-byte**; route `renderState`/`appendTranscript`/`showError` into the overlay; move 麦克风测试 and 结构化演示 to `DeveloperSettingsActivity` | E4, E5, E7 |
| `app/src/main/kotlin/com/novadrive/app/AndroidToolDispatcher.kt` | `SafeAndroidActionExecutor` navigation wiring only (E9). Validation, other tools, and `allowConfirmation()` unchanged | D1 |
| `app/src/main/res/values/strings.xml` | Overlay, bottom-bar, camera and voice-toggle strings | E7, E10, E11 |
| `app/src/main/kotlin/com/novadrive/app/DeveloperSettingsActivity.kt` | Receive 麦克风测试 and 结构化演示; add the temporary **`Voice Session: Start / Stop`** toggle calling `VoiceSessionGateway`, handling `MicPermissionMissing` by requesting `RECORD_AUDIO` | E11 |

**Add (8)**

| File | Contents |
| --- | --- |
| `nav/amap/AmapPrivacyCompliance.kt` | E3 calls, idempotent, stand-in comment |
| `nav/amap/AmapNaviViewHost.kt` | Creates the Amap view + `AMapNavi`; owns lifecycle forwarding. **The only file permitted to import `com.amap`** |
| `nav/PhaseOneNavigationController.kt` | E9 binding: records the request, returns the not-implemented outcome, touches no Amap intent |
| `ui/AssistantNavigationScreen.kt` | `FrameLayout` composing the four layers of E4 |
| `ui/AssistantOverlayView.kt` | Avatar, state indicator, speech bubble, action feedback card; pass-through touch |
| `ui/BottomBarView.kt` | Media/climate strip + camera button; inert in Phase 1 except the camera button |
| `ui/CameraPreviewView.kt` | Front-lens preview, show/hide, session-preserving (E10) |
| `ui/AssistantUiStateMapper.kt` | Pure `VoiceUiState → AssistantUiState` per §1's table |
| `voice/VoiceSessionGateway.kt` | E11 — the single start/stop seam shared by the developer toggle and the future wake-word handler; identity-guarded `attach`/`detach` |

**Add (tests, 2)** — `ui/AssistantUiStateMapperTest.kt` (one assertion per `VoiceUiState` value, all 8) and `nav/PhaseOneNavigationControllerTest.kt` (asserts the result carries `EMBEDDED_ROUTING_NOT_IMPLEMENTED` and that no intent is constructed).

**Not touched:** checkpoint items 6 and 7, `ingress/**`, `behavior-test/**`, `wake/**`, the debug source set, every control document.

**Test expectation:** 196 → 196 + 8 + (controller cases). No existing test should change; if one does, the executor names it and why. `AndroidToolDispatcher` tests use a fake executor *(evidence)*, so E9 should not disturb them.

---

## 5. Phase-1 acceptance criteria

| # | Row | Level | Evidence required |
| --- | --- | --- | --- |
| 1 | Project builds | L3 | `gradlew :app:assembleDebug` exit 0; **APK size before → after** |
| 2 | Embedded Amap view renders inside our app | **L5** | `adb screencap` showing map tiles; `dumpsys activity activities` with `topResumedActivity = com.novadrive.app/.MainActivity` |
| 3 | Assistant overlay visible above the map | **L5** | Same screenshot: avatar, state indicator, speech bubble over tiles |
| 4a | External 高德 does not open during normal use | **L5** | `dumpsys activity activities` shows no `com.autonavi.minimap` task created during the run |
| **4b** | **`navigate_to` does not launch external Amap** *(new, per D1.6)* | **L5** | Issue `navigate_to(destination)` — via `adb shell am broadcast -n com.novadrive.app/.DebugToolReceiver --es tool navigate --es arg <dest>` *(evidence: `DebugToolReceiver.kt:14` supports `tool=navigate`)*. Then show **all three**: our app still `topResumedActivity`; **no** `com.autonavi.minimap` task created; the log line carrying `EMBEDDED_ROUTING_NOT_IMPLEMENTED` |
| 5 | Existing Baidu voice code compiles | L3 | Build; plus a Test Connection (L6) showing the Baidu path is unbroken |
| 6 | Unrelated tests still pass | L2 | `gradlew test --rerun-tasks`, counts from JUnit XML in the **current output directory** (per `ACCEPTANCE_TESTS.md`) |
| **7** | **Camera opens, closes, and does not kill the session** *(new, per D2)* | **L5** | Start a session; press 📷; front preview appears; close it; map returns; `dumpsys activity services com.novadrive.app` shows the session service still running and the log shows no `DISCONNECTED` |
| **8** | **The 开发者设置 toggle starts and stops a real session** *(new, per the OQ-1 decision)* | **L5** | From 开发者设置, Start → logcat shows `state=CONNECTING` then `LISTENING`, and the **overlay on the map screen reflects it** (proving one shared session, not a debug path); Stop → `state=DISCONNECTED`. Also assert **no mic control exists anywhere in the product UI** |

**Blank-map triage** (row 2 fails): logcat tags `AMapNavi`, `amapsdk`, `MapCore`. `INVALID_USER_KEY` / `USERKEY_PLAT_NOMATCH` → wrong key type or SHA1 binding; owner action, no code change. No error but blank → privacy calls missing or late, or take the E5 `MapView` fallback. Tiles but no position dot → expected in Phase 1; no location is requested.

---

## 6. Open questions — owner decisions, not executor choices

| # | Question | Why it matters |
| --- | --- | --- |
| ~~**OQ-1**~~ | **DECIDED 2026-09-16 — a temporary `Voice Session: Start / Stop` toggle inside 开发者设置 only.** No mic button and no voice control in the product UI. It must call **the same** start/stop path the iFlytek wake-word handler will later call — **not** a separate debug path — so that swapping the trigger needs no redesign of the session implementation. Treated as temporary developer infrastructure; Phase 1 is **not** blocked on the iFlytek credentials. Implemented per **E11** | Resolved |
| **OQ-2** | Bottom bar `◀ ❚❚ ▶` and `30°C` have **no backing capability**: `BundledMusicPlayer` exposes play/stop over one bundled track — no previous/next, no playlist — and `MockActionExecutor` returns messages, not readable climate state *(evidence: `BundledMusicPlayer`, `action/MockActionExecutor.kt`)*. Phase 1 renders them inert. Confirm that is intended, or say which should function | Prevents shipping controls that silently do nothing |
| **OQ-3** | `openApp(maps)` still resolves to the installed Amap (checkpoint item 5b). With an embedded map, should "打开地图" show **our** screen instead? Out of D1's scope; unchanged in Phase 1 | A second, quieter route to the external app |
| **OQ-4** | v2 §42 lists "advanced camera/video assistant panel" as an **explicit MVP non-goal**, while the UI design puts a camera button in the Phase-1 screen. Read here as: a plain front-preview is not the excluded "advanced panel", so it is in scope. Confirm | A direct tension between two owner documents |

---

## 7. Risks

| Risk | Likelihood | Handling |
| --- | --- | --- |
| Key is Web-service type, or bound to a different SHA1 | real; cannot be excluded from disk | §5 triage; owner re-registers; no code change |
| `navi-3dmap:11.2.100` not resolvable | moderate | E1 ladder, recorded |
| `AMapNaviView` blank without a route | possible | E5 `MapView` fallback, recorded honestly |
| **R-CAM: camera preview contends with the active `AudioRecord`** | **unknown** | Row 7 measures it. If they contend, report it — do **not** silently stop capture to make the camera work |
| ~~APK grows again (already ~26 MB)~~ | **MATERIALISED far beyond the prediction — see below** | Escalated to a blocker for anything past Phase 1 |

### 🔴 R-SIZE — the APK is 204 MB, up 8× from 26 MB (measured 2026-09-16)

`213,950,365` bytes. This was logged as an expected "growth" risk; it is not growth, it is a defect. Anatomy, read from the APK's zip entries *(evidence)*:

| Component | Uncompressed |
| --- | --- |
| `lib/arm64-v8a/libAMapSDK_NAVI_v11_2_100.so` | **67,729,320** |
| `lib/armeabi-v7a/libAMapSDK_NAVI_v11_2_100.so` | **46,284,360** |
| `libneonui_shared.so` (both ABIs) | ~13,700,000 |
| `libnui.so` (both ABIs) | ~12,460,000 |
| `assets/` — 1664 entries, incl. an 8 MB texture and `assets/tts/languagedata_embedded.bin` | **46,436,839** |
| `lib/` total, 2 ABIs only | **161,742,060** |

**ABI filtering worked** — only `arm64-v8a` and `armeabi-v7a` are present, so that is not the cause. Two things are:

1. **The resolved artifact is the combined bundle.** `com.amap.api:navi-3dmap-location-search:11.2.100_…` ships navigation **plus** 3D map, location, search **and an embedded TTS voice** (`libnui.so`, `languagedata_embedded.bin`). E1 asked for `navi-3dmap`, which is unpublished at 11.2.100, so the executor correctly took the documented fallback — but the fallback carries far more than Phase 1 needs. Note the embedded TTS is *navigation voice*, which SPEC-002 Phase 7 may actually want; do not discard it without checking that first.
2. **Native strip failed** *(executor-reported)*. A single 67 MB `.so` is consistent with unstripped debug symbols and is the most likely single lever.

**Phase 1 is not blocked by this** — a render proof may ship at any size, and the build installs. **Everything after Phase 1 is.** Levers, cheapest first: fix the strip; split the ABIs (the owner's open decision, now worth roughly 68 MB); look for a narrower artifact than the combined bundle; prune unused `assets/`.

**Also measured:** `WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` reached the **merged** manifest through the SDK, though our source manifest requests neither *(evidence: `merged_manifest/debug/.../AndroidManifest.xml`)*. E6 explicitly excluded them. They are removable with `tools:node="remove"`; for a voice assistant that asks for the microphone, silently also requesting storage is the kind of thing an app store reviewer and a privacy-conscious user both notice. **Owner decision, recorded not actioned.**
| Privacy API names differ in 11.2.100 | low | Confirmed at build; only `AmapPrivacyCompliance.kt` changes |

---

## 8. Executor instructions

1. **Cleared to start 2026-09-16.** The amendment is confirmed and OQ-1 is decided (E11); OQ-2 to OQ-4 take the defaults written above.
2. Resolve the dependency first (E1) and report the artifact actually used — that is SPEC-005 Q2.
3. Implement **only** §4. Any deviation is reported, not absorbed.
4. Never log the key. Never write it to a committed file.
5. All `com.amap` imports live in `nav/amap/` — `DependencyBoundaryTest` enforces the module boundary, not the package one, so this is on you.
6. Preserve the `VoiceSessionController` construction in `MainActivity` byte-for-byte; only its output routing changes.
7. Stop after Phase 1. Do not add destination search, route planning, or voice arbitration.

### Report template

```
Files changed: (exact list, matched against §4; deviations explained)
Build: assembleDebug exit code; APK bytes before → after
Dependency resolved: artifact:version actually used; source repository
Camera implementation: CameraX or Camera2; dependency delta
Tests: gradlew test --rerun-tasks; counts from JUnit XML; any test changed and why
Amap view rendered: YES / NO / FALLBACK-MapView, with screenshot path
External Amap opened: NO, with dumpsys evidence
navigate_to result: the log line, showing no external launch (row 4b)
Camera: opened / closed / session survived (row 7)
Missing requirements: permission / key / privacy items discovered at build or on device
NOT verified: (list)
```

Phase 2 (route proof) does not begin until every row in §5 is evidenced.
