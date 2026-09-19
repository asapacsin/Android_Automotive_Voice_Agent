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

### L5 evidence — map startup recentre, 2026-09-18 (ADB-driven, no human voice)

Run on `2391ff70` against the build of 2026-09-18. Triggered by ADB, so it proves the app's
behaviour on a real phone; it says nothing about speech.

| Claim | Evidence | Status |
| --- | --- | --- |
| Cold start recentres on the current position | 5/5 restarts `map_recenter ok=true` → `map_recenter_check offsetMeters=0 zoom=16.0` | **VERIFIED** |
| The camera actually moved (not just the call returning) | `offsetMeters` measured from `cameraPosition` 1.5 s after the move | **VERIFIED** |
| The SDK's own camera is corrected | first sample 386 m / zoom 18 → after retry 0 m / zoom 16, every run | **VERIFIED** |
| WGS-84 → GCJ-02 conversion is right | `map_coord_check convertedDeltaM=0..6 rawDeltaM=611..621` against the SDK's own fix | **VERIFIED** |
| Recentre happens once per start | one `why=recenter_fresh` per run; none on resume | **VERIFIED** |
| A manual pan ends it | `map_recenter_stopped reason=user_pan`; nothing recentred in the next 8 s | **VERIFIED** |
| 📍 recentres on demand | `source=manual why=driver_request` → `offsetMeters=0` | **VERIFIED** |
| No leaked location listener | live listeners for our uid 3 foreground → 0 backgrounded | **VERIFIED** |
| Location off / permission denied / network off | clean logs, `NO_LOCATION_SERVICE` to the driver, 0 crashes, 0 SecurityExceptions | **VERIFIED** |
| Navigation unaffected | routes=3, `nav_start accepted=true`, guidance spoken, **0** recentre attempts while navigating | **VERIFIED** |
| The map opens on a **new** place after the driver travels | — | **NOT VERIFIED — needs the owner to move the phone.** `cmd location` test providers do not reach the Amap SDK's location stack (injected Beijing fixes never reached the map), so this cannot be simulated |

### L5-harness evidence — product-owner pre-drive checklist, 2026-09-18

The owner's 82-row checklist (`android_doc/voice_agent_human_test_checklist.html`) run as far as a
machine can take it: `tools/speech-harness` for the voice rows, ADB for lifecycle and failure rows.
Synthetic speech, so acoustics are **not** covered.

| Checklist area | Rows covered | Result |
| --- | --- | --- |
| Basic session | cold launch, connection, first command, long idle, background/foreground, screen off/on | ✅ 6/6 |
| Speech recognition | normal Mandarin, short command, correction phrasing (the rows reachable with the fixed phrase set) | ✅ as far as the corpus reaches |
| Turn-taking / VAD | no-speech timeout (`SLEEP reason=inactivity_timeout` at 30 s), barge-in (`cancelled reason=turn_detected`) | ✅ 2 rows; accidental-noise produced one phantom turn (see below) |
| Silence / sleep | 闭嘴 → SILENT_WAIT, command while silent executes and is answered, 休眠 → SLEEP, ignored while asleep, tap wakes, distinction visible | ✅ 6/6 |
| Multi-turn context | destination correction, topic switch, cancel | ✅ 3 rows |
| Navigation | clear destination, choose by voice, route choice, cancel, repeated request, end navigation | ✅ 6/6 |
| Music | play, stop, interrupt during TTS | ✅ 3 rows |
| Failure recovery | network off, loss mid-turn, restore, permission denied, permission restored | ✅ 5/5 — **restore was a real FAIL, fixed as P19** |
| Latency | speech end → tool ≈ 0.2–0.5 s; tool → spoken reply ≈ 0.5–1.0 s | ✅ measured |
| Calling (5 rows) | — | **N/A — no calling tool exists in this product** |
| Audio robustness (7), TTS naturalness, emotion, eyes-free, one-shot success, 20-minute session | — | **HUMAN ONLY** — needs a real voice, ears and a cabin |

Findings that are not regressions but are worth the owner's eye:

- **Honesty is inconsistent on out-of-scope questions.** 「今天天气怎么样」 was answered correctly once
  (「抱歉，我无法获取实时天气信息。」) and, in a later session, with an invented forecast for **Beijing**
  (「今天北京天气晴转多云，气温20到28度」). There is no weather tool; the second answer is a fabrication.
- **`ActionClaimGuard` works, and you hear both sentences.** 「音量调大」 → 「音量调大设置中，正在调整。」
  then the correction 「这个操作没有执行，暂时不支持。」 Same pattern on a climate turn. The false claim is
  spoken before it is corrected.
- **Open-mic picks up stray sound.** One phantom turn (「怎么回事。」 → 「没听清，再说一遍。」) appeared
  between commands. No nonsense command executed. Matches the residual risk recorded under P9.

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

---

## Execution guards on device — 2026-09-19, `2391ff70`

Build of 2026-09-19. Driven through the **real `AndroidToolDispatcher`** with `debug_tool
tool=dispatch`, which crosses the same bridge the model's output crosses. The driver's words were
set with `debug_tool tool=turn`, because the guards read the transcript and the turn epoch.

**No live model was involved: the phone had no network** (Wi-Fi enabled, supplicant DISCONNECTED,
no route; `BAIDU_DNS_FAILED` on every session attempt). So this proves the guards, not the
assistant's wording, and not that the model acts on the injected context.

| What must hold | Evidence | Result |
| --- | --- | --- |
| A named song never starts the bundled track | 「放一下周杰伦那首讲晴天的歌。」 → `ok:false MEDIA_LIBRARY_UNSUPPORTED` with the honest `next`; nothing played | **VERIFIED** |
| A generic request is not over-blocked | 「播放音乐。」 → `ok:true status:music_playing`, then `music_stopped` | **VERIFIED** |
| An ambiguous relative change is refused, not guessed | temperature set to 24 **and** fan set to 3, then 「再低一点。」 → `ok:false AMBIGUOUS_REFERENT` | **VERIFIED** |
| …and nothing moves while the question is open | `get_state` immediately after: `temperature_c:24` — unchanged | **VERIFIED** |
| The driver's one-word answer resolves | 「温度。」 → `ok:true`, `temperature_c` 24 → **23** | **VERIFIED** |
| An identical call twice in one turn applies once | 「再凉一点。」 → 23 → **22**; second identical call `ok:false DUPLICATE_IN_TURN`; `get_state` → **22**, not 21 | **VERIFIED** |

The last row is the one worth keeping: the refusal is not the evidence, the **unchanged state** is.
A guard that returned an error while the temperature still moved would have passed a test that only
read the tool result.

### Not verified here

- `ctx_hint` / the referent line reaching the model: the hint is composed when a conversation is
  configured, which needs the network. Unproven on device.
- Every human-voice row: still L5 outstanding, as recorded above.

---

## SPEC-006 against the live model — 2026-09-19, `2391ff70`

The phone's network was restored, so the row simulation cannot earn was finally attempted: does the
**live** Baidu model act on what the app resolved? Synthetic Mandarin through the real audio path
(`tools/speech-harness`), real sessions, real quota.

| Case | Utterance | What happened | Result |
| --- | --- | --- | --- |
| CVC-04 | 「有点热。」 | `function_call` → `control_climate{adjust_temperature,-2}` → 26 → **24 °C** → 「已经调低了温度。」 | **VERIFIED** |
| CVC-09 | 「再凉一点。」 | `function_call` → 24 → **23 °C** → 「已经调到23度了。」 | **VERIFIED** |
| CVC-27 | 「还是有点热。」 | held → `TURN_DROP unproven_action_claim` → nudge → `function_call` → 26 → **24 °C** → 「温度已调至24度。」 | **VERIFIED** |
| P22 | 「放一下周杰伦那首讲晴天的歌。」 | held `NO_TOOL_ACTION` → `control_music` refused `MEDIA_LIBRARY_UNSUPPORTED` → 「放不了，车上只有一首内置曲目。」, nothing played | **VERIFIED** |

The step sizes are the spec's own: implicit discomfort moved 2 °C, an explicit relative request
moved 1 °C. Nothing was spoken before the result that made it true.

### What it took, and why that is the interesting part

The first live run failed in three different ways, none of which any test here could have caught:

1. 「有点热。」 was answered 「需要我帮你调低空调温度吗？」 with **no tool call**. The implicit-intent table
   existed in `ContextResolver` and in the hint, but not in the one place the model reads when
   deciding whether to call a tool — the `control_climate` declaration.
2. With the table added, 「有点热。」 produced 「空调还没开呢，我给你打开。」 and **never called `power_on`** —
   a claim of an action that did not happen. It was released unheld because `DriverTurn.classify`
   saw no control word in 「有点热」 and called the turn conversation, so I-1 did not apply to a
   request that plainly asks for an action.
3. 「还是有点热。」 was answered 「再调高两度。」 — an intention, no call, and no claim either, so the
   existing nudge (which only fires on a *claim*) did nothing and the request evaporated.

Each was fixed at its owner: the tool declaration, `DriverTurn.classify` (reading the implicit table
rather than a second copy of it), and the nudge condition. A request the app has already resolved to
one concrete action is now also nudged when the model answers it with a **question** — that is not
the clarification the ambiguity policy protects, because an ambiguous referent returns `Clarify` and
never reaches this path.

### Still not earned

A **human voice** in a real cabin. Every line above came from synthetic speech injected into the
audio path, which proves the software and says nothing about acoustics.

### Navigation by voice, and an honest ending — 2026-09-19, `2391ff70`

| Step | Evidence |
| --- | --- |
| 「导航去珠海站。」 | `navigate_to` → `nav_resolve_candidates count=5` → 「找到5个地点，请选择第几个。」 — the list is shown, not read aloud |
| 「第二个。」 | `choose_navigation_option{index}` → `nav_route_candidates count=3` |
| 「结束导航。」 | `exit_navigation_mode` → `nav_flow_cancelled` → 「已取消导航选择。」 |

The last line is the point: the persona used to instruct the model to add that the driver must exit
高德地图 themselves, which stopped being true when ADR-007 embedded the SDK. It no longer says it.

---

## B-003 wake word — it was the microphone, and the wake word now fires — 2026-09-19, `2391ff70`

**The wake word works.** 「你好小诺」 is detected and opens an assistant session. What follows is
what it took, because three earlier diagnoses were wrong and the reason they were wrong is useful.

### The failure, and what it actually was

| Step | Evidence | Result |
| --- | --- | --- |
| Credential stored and readable | `debug_tool tool=wake arg=status → credentials_complete=true` | **VERIFIED** |
| MSC engine loads, IVW session opens | `ivw sessionBegin ErrCode:0 time:96` | **VERIFIED** |
| Wake model loads | `setStatus success=recording`, no `model is null` | **VERIFIED** |
| Engine receives audio | `cur writen size: 1600000` — 50 s of app-fed PCM, no error | **VERIFIED** |
| Wake word fires | `listening DEEP_IDLE->ACTIVE reason=wake_word`, Baidu `session.created` | **VERIFIED** |

The error was `wake_session_error code=200061`, which MSC renders as 网络连接发生异常. The line that
actually mattered was one the earlier pass never read, in the engine's own log:

```
E MscSpeechLog: cannot get record permission, get invalid audio data.
        at com.iflytek.cloud.record.PcmRecorder.run(SourceFile:60)
```

`RECORD_AUDIO` was granted (`granted=true` in `dumpsys package`). The engine had opened **its own**
`AudioRecord`, produced volume callbacks for about 0.9 s, and then read invalid audio and ended the
session. MSC reports that as a network code.

### Why three diagnoses missed it

[ADR-006](DECISIONS/ADR-006-wake-word-aikit-shared-capture.md) chose app-fed capture, and
[FINDINGS-2026-09-16](SPECS/FINDINGS-2026-09-16-iflytek-msc-sdk.md) said to prove that premise with
one small experiment **before** writing the integration. The integration was written first. It
declared `writeFrame`, and:

- `IflytekWakeWordDetector` never set `AUDIO_SOURCE`, so the engine kept its own recorder;
- **nothing in `app/src/main` ever called `writeFrame`** — only tests did.

So the app was configured for neither design: the engine recorded for itself while the app believed
it was feeding it. Every hypothesis that followed the misleading error code — stale `SpeechUtility`,
cleartext blocking, DNS, a mismatched `.jet` — was chasing a network symptom.

The `.jet` hypothesis is also disproven directly: the staged resource is named for, and bound to,
the same APPID the console shows for this app.

### The fix

`AUDIO_SOURCE = "-1"`, and `WakeWordController` owns a `PcmAudioCapture` while the assistant is
idle, feeding frames to the engine and standing down while a session owns the microphone. That is
[ADR-006](DECISIONS/ADR-006-wake-word-aikit-shared-capture.md) Option A as written — now measured,
not assumed.

### What is proven, and what is not

Detection was driven by a synthesized 「你好小诺」 (edge-tts, `zh-CN-YunxiNeural`, 16 kHz mono)
pushed to the device and fed through the same `writeFrame` path the microphone uses
(`debug_tool tool=wake arg=inject`). That proves the model, the resource, the credential, the
session and the handover to the assistant.

It does **not** prove the acoustic path: a person's voice, at distance, over road noise, with the
threshold at 1450. The live microphone is proven to reach the engine cleanly — 50 s of it, with no
error — but nobody has yet said the phrase out loud to this build. That is the remaining check, and
it needs a person.

---

## Saved places — 「回家」 and 「去公司」 — 2026-09-19, `2391ff70`

Before this, 「回家」 was sent to Amap's POI search verbatim and returned `count=0`. The driver
could not go home.

| Step | Evidence | Result |
| --- | --- | --- |
| 「回家」 with nothing saved is refused, not guessed | `{"ok":false,"error":"HOME_NOT_SET","next":"…请他直接说出地址，不要猜一个地方。"}` | **PROVEN** |
| Saving resolves the address before storing it | `save_place{home,珠海站}` → `{"ok":true,"place":"珠海站"}`, `saved_place_set slot=HOME` | **PROVEN** |
| 「回家」 then answers from the saved place, with no search | `nav_saved_place slot=HOME set=true`, `nav_resolve_candidates count=1` | **PROVEN** |
| The work slot is separate and behaves the same | `save_place{work,横琴口岸}` → `place=珠海横琴口岸`; 「去公司」 → `slot=WORK set=true`, `count=1` | **PROVEN** |
| A saved place drives to arrival | — | **UNPROVEN** |

**Why the last row is unproven, and why it is not this change's fault.** Route calculation fails
with `nav_calc_failure_v2 code=3 详情=起点不在支持范围内` — the *starting point*, not the destination.
The control proves it: 「横琴口岸」 resolved 5 candidates normally and failed calculation with the
same code 3 from the same desk. A mock GPS provider did not lift it, consistent with the
2026-09-18 finding that the Amap SDK serves its own fix and ignores test providers. This needs the
phone outdoors with a real fix — a physical condition, not a code one.

**Not claimed:** that a *spoken* 「回家」 reaches this path. These went through `debug_tool dispatch`,
which is the real dispatcher with the real store and the real Amap search, but with the model
bypassed. What the model does with 「返屋企啦」 is a separate question, tracked as B-011.

---

## The Cantonese scenario set against the live model — 2026-09-19, `2391ff70`

Synthesized `zh-HK` speech injected into a live Baidu Flex session. The point was to find out
whether the product behaves as intended for the person who owns it. It did not, and the way it
failed was more interesting than the failure.

| Said | Heard | Model | Verdict |
| --- | --- | --- | --- |
| 「返屋企啦」 | 「发诺克拉。」 | 「导航到家。正在搜索您的家地址」, `outputs=[message]` | **false claim, uncorrected** → fixed, see P23 |
| 「有啲熱，幫我舒服啲」 | 「有的人帮我舒服的。」 | 「我帮你调低温度。」, `outputs=[message]` | **empty promise** → fixed, see P23 |
| 「播啲精神啲嘅歌」 | 「波低精神的k歌。」 | `control_music{play}` → 「音乐已经播放了。」 | **wrong capability ran** → B-015, not fixed |

### After the fix

| Utterance | Evidence | Result |
| --- | --- | --- |
| 「返屋企啦」 | fabrication → `flex_user_text chars=98` → 「没听清，再说一遍。」 | **PROVEN** |
| 「有啲熱…」 | 「我帮你调低温度。」 → correction → 「没听清，再说一遍。」 | **PROVEN** |

### What this run established beyond the fix

The transcription does not fail cleanly. It produces **plausible Mandarin** that the model then
acts on confidently. The music case is the sharp one: the driver asked for a *style* of song, which
this product cannot serve and refuses on purpose ([P22](OPEN_PROBLEMS.md)) — but the refusal is
keyed on the driver's words, and those never arrived. So the bundled track played and the driver
was told they got what they asked for.

Every guard in this product that reads the driver's utterance has the same exposure. P23 closes the
case where nothing runs. Nothing yet closes the case where the **wrong thing** runs on a sentence
the driver never said.

---

## A false claim is no longer spoken — 2026-09-20, `2391ff70`

[P23](OPEN_PROBLEMS.md) made the fabrication honest by correcting it afterwards. The driver still
heard it. This closes that.

### The cost, measured before the design was chosen

`reply_timing` on device, across climate, chat and navigation turns:

| firstAudioMs | doneMs | holdCostMs |
| --- | --- | --- |
| -1 | 493 | — (tool-only response, no audio at all) |
| 727 | 965 | 238 |
| 285 | 378 | 93 |
| 682 | 988 | 306 |
| 609 | 764 | 155 |
| 242 | 757 | 515 |

`holdCostMs` is what the driver waits if reply audio is held until the response says whether
anything ran: **93–515 ms**, and **nothing** when the turn called a tool — because the spoken result
is a separate response that only starts after the tool has already returned. The whole cost falls
on replies that called nothing, which are exactly the ones that can be false.

### After

| Case | Evidence | Result |
| --- | --- | --- |
| Misheard turn, reply claims navigation, nothing ran | held, then `TURN_DROP` — never audible | **PROVEN** |
| Misheard turn, reply claims climate, tool *did* run | `TURN_HOLD reason=UNCLASSIFIED_CLAIM` → released | **PROVEN** |
| Ordinary chat | released at response end | **PROVEN** |
| Genuine prompt with a picker open | released | **PROVEN** (unit) |
| Repair prompt on doubtful audio, picker open | still immediate, deliberately exempt | **PROVEN** (unit) |

### One correction, not two ([P24](OPEN_PROBLEMS.md))

| | |
| --- | --- |
| Before | 「算了」 → `flex_user_text chars=127` ×2, `exit_navigation_mode` ×2 |
| After | one of each |

Cantonese tool calling is **intermittent, not absent** — worth recording because it changes what
B-015 is about. Across runs of the same clip, 「返屋企啦」 sometimes called `navigate_to` (and reached
the saved home, `count=1`) and sometimes answered with words alone. 「有啲熱，幫我舒服啲」 likewise
called `control_climate` and really set 22 °C on one run.

---

## The scenario suite against the live model — 2026-09-20, `2391ff70`

[SPEC-008](SPECS/SPEC-008-live-scenario-suite.md). `python tools/speech-harness/run_scenarios.py`.

| Run | Result |
| --- | --- |
| First | **9/12** |
| After the one real fix | **12/12**, exit 0 |

| # | Driver says | Asserted |
| --- | --- | --- |
| S1 | 调到二十四度 | `control_climate`, 24 |
| S2 | 有点热 | a real `control_climate` call |
| S3 | 回家 (home saved) | `nav_saved_place set=true`, `count=1`, no `HOME_NOT_SET` |
| S4 | 播放音乐 | `control_music` |
| S4b | 放一首周杰伦的歌 | `MEDIA_LIBRARY_UNSUPPORTED` |
| S5 | 导航去珠海站 | candidates, and **not** `nav_navigation_started` |
| S5b | 第二个 | `choose_navigation_option` |
| S6 | 算了 | `exit_navigation_mode` |
| S7 | 今天天气怎么样 | no 晴/多云/下雨/气温/摄氏 anywhere |
| S8 | 关闭空调 | `control_climate` |
| S9 | 闭嘴 | speech stops, session survives |
| S11 | 返屋企啦 (Cantonese) | the turn machinery reached a decision, never a released claim |

**The negative control matters more than the passes.** Pointing S1's expectation at a tool that
does not exist turns the run red and returns a non-zero exit code, so a green run means the
assertions ran.

### What the first run found, which is the point of building it

`ActionClaimGuard.isControlRequest` was a word list, and 「有点热」 contains none of its words — no
空调, no 温度, no 调. The turn fell to the unclassified fallback and the driver was told 「刚才没听清
楚」 about a sentence `ContextResolver` resolves to a concrete adjustment. Two other failures were
my assertions demanding a *mechanism* (that a correction be sent) rather than a result; both were
rewritten to assert what the spec says to assert.

### Cost

One Baidu turn per scenario. Before a release and after a change to the turn machinery — not per
commit. The simulation benchmark remains the one that runs on every build.

---

## Calling — 2026-09-20, `2391ff70`

The last capability in the product objective that did not exist. **No call has been placed by this
project.** The test phone reports `gsm.sim.state=ABSENT,ABSENT`, and the point of the first
verification was that the app says so rather than pretending.

| Step | Evidence | Result |
| --- | --- | --- |
| A car with no SIM refuses, and says why | `{"ok":false,"error":"NO_TELEPHONY","next":"…不要谎称已经拨号。"}` | **PROVEN** |
| Nothing is launched on refusal | no dialler, no intent | **PROVEN** |
| One match is not dialled on the first turn | `status=confirm_required`, `dialled=0` | **PROVEN** (unit) |
| The number never reaches the model | asserted absent from the tool output | **PROVEN** (unit) |
| Two matches are offered, not chosen between | `status=ambiguous`, `dialled=0` even with `confirmed=true` | **PROVEN** (unit) |
| An unknown name is not substituted | `CONTACT_NOT_FOUND`, `dialled=0` | **PROVEN** (unit) |
| A confirmed call connects | — | **BLOCKED** — needs a SIM |

### Why it confirms before dialling

Every other tool here can be undone by saying the opposite. A wrong temperature is wrong for ten
seconds; a wrong call has already rung someone's phone. And the risk is measured rather than
imagined — [P23](OPEN_PROBLEMS.md) recorded the model acting confidently on a sentence the driver
never said. So resolution and dialling are separate turns, and the second needs the driver's word.

---

## Wake-word false accepts, and recovery from a lost signal — 2026-09-20, `2391ff70`

### False accepts (B-012, the half that needs no person)

`python tools/speech-harness/measure_false_wake.py --minutes 16`

| | |
| --- | --- |
| duration | 16 min, the car's own music playing throughout |
| **false wakes** | **0** |
| engine errors | 0 |
| engine restarts | 0 |
| engine alive at the end | **true** |

That last row is the one that makes the others mean anything, and the first attempt failed on it.
The script reported `engine started False` and the run was discarded — an engine that quietly
stopped listening would report zero false accepts too. The cause was the script clearing logcat
*after* launching the app, so the proof of a healthy engine had been thrown away rather than never
produced. Fixed, re-run, and the count above is from a run where the engine is known to have been
listening the whole time.

**Still needs a person:** detection of a human voice at a stated distance, with the car moving.
Synthesized speech through the injection path proves the model matches the phrase and says nothing
about a real cabin.

### Recovery from a lost signal (S18)

The realtime socket is cut exactly as a lost signal would cut it (`net:drop` →
`NetworkFaults.dropConnectionNow`), and then the driver speaks.

| Step | Evidence | Result |
| --- | --- | --- |
| The socket is cut mid-session | `dropped=true` | **PROVEN** |
| The next utterance still executes | `tool=control_climate → ✓ 空调开 · 24°C · 风2` | **PROVEN** |

A product that needs the driver to notice a dropped connection and retry is not one you can use
while driving.

---

## Latency, measured rather than assumed — 2026-09-20, `2391ff70`

From the driver finishing their sentence (`input_audio_buffer.speech_stopped`) to the first thing
that actually happens — a tool call, or reply audio starting. Twenty-one turns across the scenario
suite:

| | |
| --- | --- |
| min | 328 ms |
| **median** | **734 ms** |
| p90 | 1007 ms |
| max | 1062 ms |
| of which tool calls | median 849 ms |

Under a second at p90, end to end, including the model's thinking. This was on the gap-analysis
list as a suspected problem and is not one; recorded so nobody has to suspect it again.

