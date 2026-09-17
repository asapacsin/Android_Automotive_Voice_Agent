# Acceptance tests

> A builder may **not** declare a milestone complete because the code compiles.
> This file defines what each kind of evidence is allowed to claim.

## Verification levels

| Level | Name | What it actually proves |
| --- | --- | --- |
| **L1** | Static checks | The code reads correctly; no stale provider references; no credentials in source. Proves nothing about behaviour. |
| **L2** | Unit tests | Pure logic: settings semantics, URI construction, argument validation, parsing. JVM only. |
| **L3** | Integration / protocol tests | Message shapes over MockWebServer, state-machine transitions, module boundaries. **Proves protocol shape, not connectivity.** |
| **L4** | Build / package verification | `assembleDebug` succeeds; APK contains what it should and no secrets. **Proves compilation, not behaviour.** |
| **L5** | Device / E2E verification | Installed on a physical phone and exercised. Audio, lifecycle, foreground/background, and tool execution can only be verified here. |
| **L6** | Real external-service verification | A live call to Baidu or Amap with real credentials returned a real response. |

### Level traps — stated explicitly because each has already caused a false "done" in this project

- A successful **APK build (L4) is not** working phone behaviour.
- A **MockWebServer test (L3) is not** Baidu connectivity. It proves we send the bytes we think we send.
- A **passing navigation intent test (L2/L3) is not** the assistant continuing to work while Amap is foreground.
- An **ADB-triggered tool call (L5-partial) is not** the model choosing to call that tool from speech.
- **Compilation is not** test coverage. Android-framework types cannot be unit-tested here; say so rather than implying coverage.
- **A green `gradlew test` may have executed nothing.** Gradle marks test tasks `UP-TO-DATE` when nothing they depend on changed, and still prints `BUILD SUCCESSFUL` with exit code 0. On 2026-09-16 a full-suite run returned `BUILD SUCCESSFUL in 14s` against a prior `1m 56s`; the difference was entirely cache. It proved only that nothing had changed since someone else's run — **not** that the tests pass.
  - **Tell them apart by wall-clock time and by the absence of `tests completed` lines.** A run far faster than the last real one is a cache hit.
  - To produce evidence of your own, force execution with `--rerun-tasks` (or `cleanTest test`), and take the counts from the JUnit XML under the build output directory rather than from a summary line.
  - This matters most when verifying work you did not do yourself: re-running an unchanged tree confirms the tree is unchanged, which is not verification.
- **Counting JUnit XML can report a failure that did not happen.** Build output is redirected outside the workspace (D11, ASCII path), so **`<module>/build/test-results/` inside the repo is orphaned residue from the old layout and is never refreshed or cleaned.** On 2026-09-16 a recursive `TEST-*.xml` sweep reported `failed: 1` against a `BUILD SUCCESSFUL` run; the culprit was `contracts/build/test-results/test/TEST-Gradle#20Test#20Executor#201.xml` dated **2026-09-14**, containing a two-day-old `TestSuiteExecutionException` — most likely the non-ASCII path crash the redirect exists to prevent.
  - **Scan only the current build output directory.** Never sweep the repo tree for `TEST-*.xml`.
  - **Check report mtimes against the run you just performed.** A report older than your run is not evidence about your run.
  - A genuine test failure fails the Gradle build. `BUILD SUCCESSFUL` alongside a parsed failure means your parser is wrong, not that Gradle is lying.
- **`--rerun-tasks` double-counts Android app tests.** It runs `testDebugUnitTest` *and* `testReleaseUnitTest`, which are the same tests over two variants. Summing both overstates coverage; report the debug variant plus the JVM modules.

## Status vocabulary

Use exactly these words in reports:

| Status | Meaning |
| --- | --- |
| **VERIFIED** | Every level required for this item was performed and passed. |
| **PASSED AUTOMATED TESTS ONLY** | L1–L4 pass; no device evidence exists. |
| **NOT YET VERIFIED ON DEVICE** | Implemented, automated levels pass, L5 outstanding. |
| **BLOCKED** | Cannot be verified: no device, no credentials, service unavailable. Say which. |
| **FAILED** | Performed and did not pass. Include the observed behaviour. |

Never write "fully verified" unless every required level was actually performed.

## Required level by feature

| Feature | Required | Notes |
| --- | --- | --- |
| Settings persistence, keep/replace/clear semantics | L2 | |
| Baidu protocol message shapes (`session.update`, tools, audio append) | L3 | |
| Tool argument validation and rejection | L2 | Whitelist, bounds, enums |
| Navigation URI construction | L2 | Encoding, injection safety |
| Amap POI lookup request shape | L3 | MockWebServer |
| Module dependency boundaries | L3 | `behavior-test` |
| No credentials in source or APK | L1 + L4 | Source scan and APK scan |
| Build integrity | L4 | |
| Baidu authentication and session establishment | **L6** | Test Connection is sufficient; it does not use the mic |
| Reply audio audible, correct pitch, correct stream | **L5** | Requires a human ear |
| Microphone gating / no self-transcription | **L5** | |
| Conversation survives without session-fatal errors | **L5** | Human voice |
| `navigate_to` → turn-by-turn, zero taps | **L5 + L6** | Needs a live Amap key |
| `control_music` play / stop by voice | **L5** | Model must choose the tool |
| `open_app` execution | **L5** | |
| Session survival with another app foreground | **L5** | |
| Model refuses rather than fabricating success | **L5** | |
| Persona language (Mandarin, no dialect) | **L5** | Reset persona on device first |

## Commands

```powershell
# L2 + L3 + L4 (app only)
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# L2 + L3 + L4 (all modules)
.\gradlew.bat test :app:assembleDebug

# Independent verification of someone else's run — forces execution, defeats UP-TO-DATE
.\gradlew.bat test --rerun-tasks
```

> Both commands above can return `BUILD SUCCESSFUL` **without running a single test** if nothing changed. Use `--rerun-tasks` whenever the green result is the evidence you intend to rely on, and read the counts out of the JUnit XML (`TEST-*.xml` under the build output directory) rather than trusting the console summary.

Build output is written outside the workspace to keep the worker classpath ASCII (see `docs/DECISIONS.md` D11).

For L5, install and observe:

```powershell
adb install -r <apk>
adb logcat -s NovaVoice:D        # debug-build event log, gated on FLAG_DEBUGGABLE
adb shell dumpsys audio          # routing, stream, silenced state
adb shell dumpsys activity services com.novadrive.app   # foreground service state
```

## Current build status

Build `0.6.0-baidu-flex` (versionCode 7) — counts refreshed 2026-09-17 from a forced `--rerun-tasks` run, read out of the JUnit XML:

- **L1–L4: PASSED** — **334 distinct tests, 0 failures, 0 skipped** (`:app:testDebugUnitTest` 232; JVM modules 102). `testReleaseUnitTest` re-runs the same app tests and is deliberately **not** added in. Clean `assembleDebug`.
  - Growth 240 → 334: vehicle control, camera vision, conversation reset, mic gain, tool coverage and feature-presence regression tests (2026-09-16/17).
  - Growth 196 → 240: the destination/route selection flow (`EmbeddedNavigationControllerTest` 14, `NavigationCandidatesTest` 7) plus the navigation-ended resync tests added 2026-09-16.
  - Growth since the 135-test baseline: app 66 → 116 (navigation-exit tool, VAD mitigation, and the SDK-independent domain layer), JVM modules 69 → 80 (11 new secret-scan tests).
  - The APK is now **~26 MB** (was ~13 MB) after the iFlytek AIKit native libraries; an ABI-filter decision is open with the product owner.
- **L1: source secret scan** now also covers Gradle files and the Amap/iFlytek credential shapes, with each matcher proven against synthetic fixtures. Two residual bypasses are recorded in `SPECS/SPEC-005-embedded-amap-mvp.md` C11 — the scan is improved, not complete.
- **L6: VERIFIED** for Baidu authentication and session establishment (Test Connection returned `session.updated` with the live payload).
- **L6: VERIFIED** for Amap POI lookup and turn-by-turn handoff via the real tool path.
- **L5: NOT YET VERIFIED** for every human-voice row in `CURRENT_MILESTONE.md`. All device results so far came from ADB-triggered tool calls or from the microphone picking up ambient noise and the assistant's own output — never from human speech.

### L5 evidence — navigation lifecycle, 2026-09-16 (ADB-triggered, not human voice)

Run on the physical device against build 18:51:57. The trigger was an ADB broadcast, so this is
**L5-partial**: it proves the app's behaviour, not that the model chooses the tool from speech.

| Claim | Evidence | Status |
| --- | --- | --- |
| Destination candidates resolve and render | `nav_resolve_candidates count=4`, four real POIs drawn with name + distance | **VERIFIED** |
| No route calculation before a destination is chosen | log stops at `nav_resolve_candidates` until the tap | **VERIFIED** |
| The *chosen* candidate is used, not a cached first result | tapped the 24.4 km row, not the 12.9 km one; routes returned 42–46 km, which the 12.9 km POI cannot produce | **VERIFIED** |
| Route alternatives show distance + ETA | `46.0 km · 44 分钟 · 推荐` / `42.6 km · 47 分钟 · 常规` / `42.0 km · 59 分钟 · 免费` | **VERIFIED** |
| `startNavi` is not called before a route is chosen | no `nav_start` line until the route tap | **VERIFIED** |
| The *chosen route id* is the one actually driven | tapped route 14 (21410 m) out of 12 (19507 m) / 13 (19987 m); `nav_active_route meters=21410` | **VERIFIED** |
| A different candidate routes to *that* candidate | second run tapped the 12.9 km POI and got 19.5–21.4 km routes, vs 42–46 km for the 24.4 km POI | **VERIFIED** |
| `nav_stop` stops exactly once | `nav_stopped reached=true` ×1, then `nav_stop_skipped already_inactive=true` | **VERIFIED** |
| Arrival auto-stops without a manual stop | emulator run 18:52:32 → `nav_emulator_end` + `nav_stopped reached=true reason=emulator_end` ×1 at 18:55:06 | **VERIFIED** (emulated vehicle) |
| State machine resyncs when the host stops | `nav_flow_ended reason=manual phase=STOPPED` | **VERIFIED** |

> **Trap recorded — this one produced a false "FAIL" before it was caught.**
> `AMapNavi.getNaviPath()` does **not** reflect a successful `selectRouteId(id)` until
> `startNavi()` has run. Sampled before the start it returns the *previous* route, so a log
> reading `nav_route_selected routeId=14` beside `meters=19507` (route 12's length) looks
> exactly like the selection being ignored. It is not.
>
> The pre-start sample is now named **`nav_route_info_prestart`** so it cannot be mistaken for
> the active route. The authoritative line is **`nav_active_route`**, logged after `startNavi`.
> Measured 2026-09-16: `selectRouteId` accepted=true, pre-start 19507 m, post-start 21410 m.

### L5-harness evidence — speech pipeline without a human, 2026-09-17

`tools/speech-harness` injects synthetic Mandarin into the live Baidu Flex session (debug builds).
It proves the software path end to end on the phone; it does **not** prove acoustics.

| Flow (by injected speech) | Result |
| --- | --- |
| 空调打开 / 调到24度 / 温度调高一点 / 风量调大 / 关闭空调 — simulated climate state read back | ✅ one long session, all correct |
| 播放音乐 / 关闭音乐 / 音量调大 (unsupported → honest refusal) | ✅ |
| 导航去珠海站 → candidates; 不对，换成拱北口岸 → list replaced; tap → routes; tap → navigating | ✅ |
| 结束导航 → guidance stopped once, state resynced | ✅ |
| 算了 with destination list / route list open → picker cancelled | ✅ |
| 看看前面有什么 → camera frame → Qianfan vision → spoken answer | ✅ |
| Text turn then speech still heard | ✅ |
| Quiet speech (peak 1905 / 2690) | ✅ with `MicInputGain`; ❌ before |

Device checks without speech: camera permission permanently refused → App Settings opens; return →
camera opens; camera released on HOME and on ✕; wake engine initialises after install.

Automated: **334 declared = 334 executed, 0 failed, 0 skipped** (app 232 debug variant, behavior-test 37,
ingress 46, simulator 15, contracts 2, safety 2).
