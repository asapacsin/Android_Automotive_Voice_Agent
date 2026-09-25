# Current Milestone

**M4 — More voice coverage without depending on the model** — opened 2026-09-24 by the product owner

Date opened: 2026-09-24 · Source: [B-024, B-025, B-026](BACKLOG.md) · Specs:
[SPEC-010](SPECS/SPEC-010-screen-affordances.md), [SPEC-011](SPECS/SPEC-011-amap-live-info.md),
[SPEC-012](SPECS/SPEC-012-speech-arbiter.md)

Chosen from six directions reviewed on 2026-09-24 because each can be built and verified on the
phone with no server of our own, and none rests on unmeasured provider behaviour. The talker/planner
split, offline mode and real vehicle control were set aside; the reasons are under
[B-026](BACKLOG.md#b-026--one-owner-for-who-may-speak).

**Order and why.** SPEC-010 first: it adds commands with no model decision at all, and its per-turn
capability claim is what SPEC-011's fallback needs. SPEC-011 second: most visible value, and its
baseline measurement must be taken before its tool declaration changes the prompt. SPEC-012 last:
a pure consolidation of device-verified behaviour, so it goes where a regression is cheapest to see.
Each SPEC's steps are separate verified commits; a device row never blocks the next autonomous step.

| # | Required | Level | State |
| --- | --- | --- | --- |
| 1 | `ScreenControls` owns recentre and camera, so tap and voice share a route | L2 | **built** (L2) — SPEC-010 step 1, `ScreenRouteParityTest` |
| 2 | Affordance registry and whole-utterance matcher | L2 | **built** (L2) — SPEC-010 step 2, `AffordanceMatcherTest` |
| 3 | One execution per turn across the local and model paths | L2 | **not built** — SPEC-010 step 3 |
| 4 | Screen and picker publish affordances; the old local-pick path is deleted | L2–L4 | **not built** — SPEC-010 step 4 |
| 5 | Spoken control names act on the phone without a model tool call | **L5** | **not earned** — SPEC-010 A7, needs the phone |
| 6 | SPEC-008 baseline selection rate recorded before any new declaration | **L5** | **not earned** — SPEC-011 step 0, needs the phone |
| 7 | `query_live_info` weather: parser, tool, truth guard, capability split | L2–L4 | **not built** — SPEC-011 steps 1–3 |
| 8 | Weather selected from speech ≥ 9/10 with no SPEC-008 regression; live Amap data | **L5–L6** | **not earned** — SPEC-011 step 4 |
| 9 | `route_traffic`, `along_route`, `place_details` | L2–L6 | **not built** — SPEC-011 step 5 |
| 10 | Speech rules characterised against today's classes | L2 | **not built** — SPEC-012 step 1 |
| 11 | `SpeechArbiter` owns every speak/uplink decision; `VoicePolicy` deleted | L2–L4 | **not built** — SPEC-012 steps 2–3 |
| 12 | P1 and P3 re-pass on a simulated drive through the arbiter | **L5** | **not earned** — SPEC-012 A6 |
| 13 | Workload hold before manoeuvres | L2, **L5** | **not built** — SPEC-012 step 4 |

**Completion rule.** Every row earned at its level, the SPEC acceptance tables have no `not built`
row, and the device rows are either earned or queued in [HUMAN_VALIDATION.md](HUMAN_VALIDATION.md)
with their owner. Cloud sessions can do rows 1–4, 7, 9 (code), 10, 11 and 13 (code); the device
rows need the phone and the owner's PC.

# M3 — Contextual voice commands (CLOSED 2026-09-19 — record kept, do not reopen)

**M3 — Contextual voice commands: the driver stops speaking like an API** — ✅ **CLOSED 2026-09-19**, all six rows earned

Date opened: 2026-09-19 · Source: [B-008](BACKLOG.md) · Spec: [SPEC-006](SPECS/SPEC-006-complex-voice-commands.md)

| # | Required | Level | State |
| --- | --- | --- | --- |
| 1 | Cross-turn context has one owner, built only from proven execution | L2 | **done** — `DriverContext`, 23 tests |
| 2 | Implicit goals, relative continuation and reversal resolve deterministically | L2 | **done** — `ContextResolver` |
| 3 | An ambiguous referent is asked about, never guessed | L2 | **done** — CVC-11/12/13 |
| 4 | A capability we do not have is never claimed to have run | L2 | **done** — `FalseCapabilityClaimTest`, 10 tests |
| 5 | Multi-intent per-call safety (validation, duplicate, cancellation, ambiguity) | L2 | **done** — decomposition itself is the model's, see SPEC-006 §On multi-intent |
| 6 | The **live model** acts on the injected context | **L5** | **earned 2026-09-19** — 「有点热」 → 26→24 °C, 「再凉一点」 → 24→23 °C, 「还是有点热」 → drop-unproven → nudge → 26→24 °C, named song refused and nothing played ([ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)) |

Row 6 was the row that mattered and the one simulation could not earn. It took three live failures to get there — the implicit table was missing from the tool declaration, an implicit
request was classified as conversation so its claims were never held, and a lost action was not
nudged because nothing had been *claimed*. All three are recorded in ACCEPTANCE_TESTS.md.

**M3 is complete except for a human voice in a real cabin**, which is a standing human gap.

## Was blocked on the phone's network — LIFTED 2026-09-19

`2391ff70` is attached over ADB and the app installs and runs, but Wi-Fi is **enabled and
disconnected**: supplicant `DISCONNECTED`, no IP, `ip route` empty, `ping 8.8.8.8` → "Network is
unreachable". Every session attempt ends `BAIDU_DNS_FAILED: UnknownHostException`. It last
associated with `CU_62fC_5G` on 09-15.

The owner reconnected it on 2026-09-19: `wlan0` now has `192.168.0.135` and the provider answers
ping in ~50 ms. Row 6 and D-2 are back on the autonomous frontier.

What follows is the record of the block, kept because it is why the guards below were proven
without a model first. Everything that does **not** need the model was
verified instead, through the real dispatcher — see [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md).


**Also blocked by this, and only by this:**

- [TECH_DEBT.md](docs/TECH_DEBT.md) **D-2** — trimming the persona prompt to tone. The rules being
  removed are ones the model currently reads; removing them blind and unverified could degrade live
  behaviour in exactly the way no test here would catch.

**Not blocked by it, corrected 2026-09-19:** **D-4** (merging the two navigation state machines)
was listed here as network-blocked. That was wrong. The simulation harness has a
`SimulatedNavigationWorld` with fixed places and routes, so the merge can be built and verified at
`SIM_LOGIC` without touching the network; only a device confirmation would need it. D-4 is on the
autonomous frontier.

---

# M2 — Embedded map proof (CLOSED 2026-09-19 — record kept, do not reopen)

**M2 — Embedded map proof: `AMapNaviView` inside our Activity with an assistant placeholder above it** — ✅ **CLOSED 2026-09-19**

Date opened: 2026-09-16 · Source: [DEMAND v2 §44 Phase 1 + §47](SPECS/DEMAND-2026-09-16-embedded-amap-v2.md) · Decision: [ADR-007](DECISIONS/ADR-007-embedded-amap-navigation-sdk.md) · Spec: [SPEC-005](SPECS/SPEC-005-embedded-amap-mvp.md)

> **Closed on the evidence already recorded in [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md)** against
> `2391ff70`: destination candidates resolve and render as real POIs with name and distance, routes
> draw, the *chosen* route id is the one driven (`nav_active_route meters=21410`), `nav_stopped
> reached=true` fires exactly once, and arrival auto-stops. A map that renders POIs and routes
> inside our Activity is the map rendering inside our Activity, so the credential question below is
> settled by the navigation working at all.
>
> What is **not** earned by this, and is not an M2 gap: **L5 with a human voice**. Every device
> result so far came from ADB-triggered calls or synthetic speech. That is a standing human gap.

> M1 (close the on-device voice loop) closed 2026-09-16 — its record is preserved in git history and summarised in `OPEN_PROBLEMS.md`. Do not reopen it.

## Blocker — ✅ CREDENTIAL SUPPLIED 2026-09-16 (type not yet proven)

The product owner added the key to `local.properties` as **`AMAP_API_KEY`**. Inspected without printing the value: **32 characters, lowercase hex**, `local.properties` is ignored by `.gitignore` and untracked by git, and the value appears in **no** source or Gradle file. Storage is correct.

> ⚠️ **What this does NOT establish.** An Amap *Android platform* key and a *Web service* key are **indistinguishable by format** — both are 32-char lowercase hex — and this project already holds a Web-service key (`amap_web_key`, in Keystore, used by `AmapPoiClient`). Nothing inspectable on disk proves which type was registered, nor that it was bound to `com.novadrive.app` with SHA1 `32:2F:E8:E7:33:D5:03:AA:FA:6F:00:37:DF:66:54:52:CB:D4:DA:EB`. A wrong type **or** a wrong SHA1 produces the *same* symptom: a blank navigation view with `INVALID_USER_KEY` in logcat (tags `AMapNavi` / `amapsdk`). This is settled on device at completion-rule row 2, not before.

**Injection route decided:** `app/build.gradle.kts` has `buildConfig = false`, and the SDK reads its key from manifest meta-data, so use a **`manifestPlaceholders`** entry fed from `local.properties` — no need to enable `buildConfig`. `abiFilters` are already `arm64-v8a` + `armeabi-v7a`, matching the SDK's native libraries. If the property is absent the build must fall back to an empty placeholder and still compile, so a fresh clone without the key is not broken.

Original requirement, kept for the record — the key must be registered at console.amap.com for:

| Field | Value |
| --- | --- |
| 服务平台 | **Android平台** (not Web服务 — the existing key is the wrong type) |
| PackageName | `com.novadrive.app` |
| 发布版安全码 SHA1 (debug, for now) | `32:2F:E8:E7:33:D5:03:AA:FA:6F:00:37:DF:66:54:52:CB:D4:DA:EB` |

The SHA1 is the debug keystore's fingerprint (`~/.android/debug.keystore`, valid to 2056), computed 2026-09-16. It is not a secret; the **key the console returns is**, and it goes into `local.properties` (git-ignored) → `BuildConfig` → manifest placeholder. **It must never appear in a committed file, a log, or a chat message.** A release SHA1 does not exist yet; that is a later step.

## Goal

Prove the new product model on the phone: our Activity stays foreground, Amap's navigation view renders inside it, and a static assistant element sits above it — with the external Amap app never launched.

## Step 0 — §46 architecture checkpoint — ✅ ANSWERED; design awaiting owner review

The full checkpoint and the Phase 1 design are in **[SPEC-005-P1-design.md](SPECS/SPEC-005-P1-design.md)** — **amended 2026-09-16 by product-owner decision; that amended file is authoritative for Phase 1.**

**D1 and D2 are now decided, not open.** D1: the external-Amap deep-link path is *removed* from the Phase-1 flow and `navigate_to` keeps its abstraction pointed at the embedded implementation — it is **not** globally rejected. D2: the layout is the [reference UI design](SPECS/DEMAND-2026-09-16-ui-design.md) — avatar, state indicator, speech bubble, action-feedback card, media/climate bottom bar, and a bottom-right **front-facing camera** button (not settings). An earlier draft of mine proposing `NAVIGATION_NOT_READY` and a mic + settings bar is recorded as **superseded** at the top of that file.

**OQ-1 is decided (2026-09-16):** a temporary `Voice Session: Start / Stop` toggle lives in **开发者设置 only**. No mic button, and no voice control, in the product UI. It must call the **same** start/stop path the iFlytek wake-word handler will later call — a separate debug voice path would be a failure — implemented as the `VoiceSessionGateway` seam in design **E11**, with an identity-guarded `attach`/`detach` because this codebase has shipped that ownership bug twice. Phase 1 is explicitly **not** blocked on the iFlytek credentials. OQ-2 to OQ-4 take their written defaults.

Still open: the SDK artifact, resolved at build time (SPEC-005 Q2).

**Status: implementation briefed 2026-09-16.** A worker can earn only rows 1, 5 and 6 (build, compile, tests). Rows 2, 3, 4a, 4b, 7 and 8 are device evidence on `2391ff70` and are gathered separately — a green build is not this milestone.

## Required implementation changes (Phase 1 only)

1. Gradle: Amap Navigation SDK dependency (≥ 11.2.100; exact artifact confirmed at checkpoint). Report APK size delta.
2. Key injection: `local.properties` → `BuildConfig`/manifest placeholder. Debug/release separation ready.
3. Manifest: `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` (coarse already exists), the SDK's required meta-data. No `SYSTEM_ALERT_WINDOW`.
4. Amap privacy-compliance initialisation before first SDK call (a real consent screen is a later phase; Phase 1 may use a fixed debug acknowledgement, **clearly marked as such**).
5. A `FrameLayout` host in `MainActivity` (the app is programmatic Views — no XML, no Compose): `AMapNaviView` as the base child, a placeholder overlay (avatar/status text) as the top child. The existing settings column moves behind a button or a second screen; it is **not** deleted.
6. Lifecycle forwarding for `AMapNaviView` (`onCreate/onResume/onPause/onDestroy`, `onSaveInstanceState` if required).

## Explicitly not in this milestone

Route planning, any voice command wired to navigation, the `AmapNavigationController` implementation, overlay behaviour wired to real state, removing `NavigationAdapter`/`AmapAutoPickService`, tablet layout.

### Built ahead of phase order, deliberately — ✅ LANDED 2026-09-16

While Phase 1 is blocked on the Amap key, the **SDK-independent domain layer** from v2 Phases 3/5/6 was built and verified: `NavigationPhase` + `NavigationStateStore`, the `NavigationController` and `DestinationResolver` **interfaces** with fakes, `ActionExecutor` + `MockActionExecutor`, `VoicePolicy` + `ResponseCategory` + `VoiceDecision`, `AssistantUiState`.

**Evidence (L2/L3 only):** 15 files added under `app/.../nav/`, `.../action/`, `.../voicepolicy/`, `.../ui/` and their tests; **116 tests pass, 0 fail** (up from 90); `assembleDebug` exit 0. Scope independently verified by file timestamps — **no pre-existing file was modified**. `VoicePolicy.decide` was read and all 12 cells of v2 §18 confirmed by hand, including `CRITICAL_ALERT` staying `SPEAK_NORMAL` while navigating; the signature takes no `String`, so v2 §17's "never inspect response text" is enforced structurally.

**What this is NOT.** None of it is wired to anything. No runtime behaviour changed. It satisfies **no row** of the completion rule below, and the MVP acceptance in SPEC-005 remains entirely unearned. It is scaffolding placed so that when the Amap key arrives, Phases 3–6 are assembly rather than design.

**Why this does not violate §44's ordering.** The phase order exists so route planning is not built on an unproven map. None of the above touches the Amap SDK, so nothing about it can be invalidated by the map failing to render. v2 §9 states the abstraction exists precisely so Amap can be mocked, and §28 Level A requires fake Amap interfaces. The interface shapes are dictated verbatim by §9, §10, §21 — they are not guesses ahead of the SDK.

**Constraint on that work:** it is additive only and is wired into nothing. The live audio path, `AndroidToolDispatcher`, and the existing `NavigationState` object (which still drives the shipped speech-mute and VAD mitigation) are untouched. The two navigation-state types merge at Phase 4. This milestone's completion rule below is unchanged and is **not** satisfied by any of it.

## Completion rule (v2 §47) — every row, with level

| # | Required | Level | Evidence |
| --- | --- | --- | --- |
| 1 | Project builds | L3 | `gradlew :app:assembleDebug` exit 0 |
| 2 | `AMapNaviView` renders inside our Activity | **L5** | screenshot on `2391ff70` showing the map in our task |
| 3 | Placeholder assistant element visible above the map | **L5** | same screenshot |
| 4 | No external Amap app opens | **L5** | `dumpsys activity activities` shows no `com.autonavi.minimap` task started during the run |
| 5 | Existing Baidu/voice code still compiles | L3 | build |
| 6 | Existing tests still pass, except those tied to the removed handoff | L2 | `gradlew test` green; **the list of any skipped/removed tests and why** in the report |

A green build satisfies rows 1 and 5 only. Rows 2–4 are device evidence or nothing. Only after all six pass does Phase 2 (route proof) open.

> **Superseded by the amended design — use [SPEC-005-P1-design.md §5](SPECS/SPEC-005-P1-design.md) as the authoritative acceptance list.** The six rows above are v2 §47 verbatim and remain necessary, but the 2026-09-16 amendment adds two: **4b** — issue `navigate_to` and show our app still foreground, no `com.autonavi.minimap` task created, and the honest not-implemented result in the log; and **7** — the camera opens, closes, and does not terminate the assistant session. Eight rows in total.

## Known risks

- The SDK refuses to initialise without the privacy calls — a blank view with no error is the typical symptom.
- Wrong key type or SHA1 mismatch also yields a blank map (`INVALID_USER_KEY` in logcat, tag `AMapNavi`/`amapsdk`).
- APK is already 26 MB; measure the delta.
- The mic FGS and `NavigationState` are untouched in Phase 1; they change in Phase 4 (see SPEC-005 C4).
