# Implementation plan following Astra architecture selection

Date: 2026-09-24. Reviewed source baseline: `c4c6a5d`.
Status: **IMPLEMENTATION IN PROGRESS — P1–P5 autonomous slices landed; P6 JVM/harness verification passed 2026-09-24; device-only acoustics and demo rehearsals remain HUMAN_REQUIRED.**

This document translates the five decisions in [ASTRA_ARCHITECTURE_SELECTION.md](ASTRA_ARCHITECTURE_SELECTION.md) into implementation slices, without replacing their constraints. The implementation progress and remaining acceptance work are recorded below; compilation is not treated as shipped behavior or acoustic evidence.

## 1. Review findings and local baseline

Sources reviewed: the architecture selection, [canonical architecture](ARCHITECTURE.md), [listening lifecycle](LISTENING_LIFECYCLE.md), [product plan](../PRODUCT_FEATURE_BUILD_PLAN.md), local field-failure memory, local field-test rules, setup guides, Gradle/CMake configuration, and targeted capture/playback source. This is not an exhaustive source audit or a fresh acoustic experiment.

| Finding | Consequence for implementation |
| --- | --- |
| FC-010 remains INVESTIGATING. The loop was recorded on the older 17:07 APK; native backend loading was later recorded at 22:36. | Bind every new result to the tested APK. Do not equate library loading/session IDs with echo cancellation. |
| FC-006 and FC-008 are FIXED at a narrower evidence level; FC-009 has injected exact-name evidence. | Preserve those fixes and add live microphone coverage; do not report all three as absent or cabin-verified. |
| `PROVIDER_SETUP.md` and `BAIDU_E2E_SETUP.md` describe the deleted PC backend/Qwen path; `BAIDU_DIRECT_ANDROID.md` retains legacy providers and navigation paths. | Reconcile operational documentation against source and ADR-008 before using it as a runbook. Do not rebuild those old paths. |
| The old milestone says no active milestone and wake credentials blocked, while later product/field records describe demo work and wake evidence. | Refresh and reconcile canonical state; determine actual prerequisites without asking for credentials already available. |
| The architecture selection is an existing untracked file at review time. | Preserve it. A portable implementation handoff must deliberately include this design input; a plan-only commit does not automatically track it. |

Local setup checks to perform at implementation start:

- Confirm `local.properties` is ignored and required property names are present without printing values. `sdk.dir` must resolve an Android SDK containing platform 34: `settings.gradle.kts` otherwise excludes `:app`, so a JVM-only green run is insufficient.
- Confirm JDK/Gradle compatibility, Android SDK/build tools, NDK, CMake (configuration requires at least 3.22.1), and vendored native sources. Record actual tool versions; pin native provenance and licenses rather than guessing an upstream revision.
- Preserve `minSdk=28`, target/compile SDK 34 and the existing arm64-v8a/armeabi-v7a configuration unless a separately justified change is needed.
- Amap's Android key currently enters the manifest from the local property; runtime provider credentials belong to the existing Keystore settings owner. Preserve these existing paths and never copy values into plans, diagnostics, commits or test fixtures.
- Outputs default to `C:/Users/Administrator/tools/nova-drive-build`, with `NOVA_BUILD_DIR` override. Keep the ASCII build path required by this Windows environment.
- Discover the attached device afresh; `2391ff70` is historical evidence, not proof of current availability. Record APK hash, commit, installed version, device/OS, audio route, negotiated rate and test conditions. Do not clear application data as a setup shortcut.
- Use `.local-agent-memory/failure_cases.md` and artifacts under `D:/桌面/android_doc/` as local field evidence. Keep that memory gitignored; record only sanitized summaries in canonical tracked documents. Do not overwrite it from the older spreadsheet.

## 2. Execution ownership and order

Planner (Astra Low) defines integration contracts, owns review and verifies user-visible behavior. Executor (Luna Medium) implements bounded, settled edits and tests. Astra High reviews genuine unresolved cross-module decisions; the five existing selections do not need to be redesigned merely to start work. If the owner explicitly requests Astra High for implementation, use that requested routing for the implementation phase.

Dependency order:

1. **P0:** establish baseline and register acceptance cases.
2. **P1 + P2:** settle a shared audio lifetime/clock contract, implement safe device ownership, then framing/reference repair; verify them together.
3. **P3:** microphone handoff after safe capture teardown is established.
4. **P4:** interruption after cleaned audio and trustworthy playout state are established.
5. **P5:** candidate-dialog work can proceed independently after its contract is fixed; integrate the live flow after voice reliability is demonstrated.
6. **P6:** device regression and full demo rehearsal.

Avoid concurrent edits to capture/player/JNI across P1 and P2. P3 and P4 also share session/gateway files and must be integrated sequentially. A future bounded navigation worker may work independently with explicit file ownership.

## 3. Implementation slices and acceptance

### P0 — Baseline, reproducibility and contracts

Deliverables:

- Refresh project state; inspect discovery/test-matrix gate results. Record existing failures separately from regressions introduced by this work.
- Reconcile the stale setup references above and register this work using the existing SPEC, design-basis and TEST_MATRIX mechanisms. Reuse relevant cases rather than create duplicate registries.
- Establish baseline unit/native tests and a debug APK. Document which field scenarios remain unverified.
- Define contracts before delegation: session/reply identity, output completion/flush acknowledgment, capture/render timestamps and fallback, AEC pending versus failure, microphone handoff acknowledgment, candidate-list identity and selection result.

Exit: each slice has an owner, a failing regression or observable scenario, and a defined completion oracle. No acoustic PASS is inferred from baseline compilation.

### P1 — Ordered audio device lifecycle (architecture decision 2)

Primary owners: `PcmAudioPlayer`, `PcmAudioCapture`, `AndroidPlaybackPort`, `LowLatencyPlaybackBuffer` and the existing audio port contracts.

- Serialize output writes, flush and close through one output owner. Carry reply/session identity to the final write boundary; invalidate queued and in-flight old output before flush acknowledgment.
- P1/P2 share a prerequisite contract: controller-owned output epoch plus session generation; every queue/remainder/write retains identity; flush invalidates identity before acknowledgment; render reference accounts only accepted bytes once; native/capture release follows worker quiescence. Resolve audio-focus STOP without independently advancing an epoch the ingress controller does not know about.
- Retain the unwritten suffix of partial writes. Define zero/error-write behavior and bounded retry/failure handling. Finalize a short last fragment only on explicit response completion; do not silently lose the final syllable.
- Distinguish producer completion, application-queue drain and hardware drain. Emit one authoritative playout-active transition and keep tail interruption possible after generation ends.
- Bound buffering in elapsed audio time; specify a cancellation/error outcome on overflow rather than dropping arbitrary PCM. Choose the budget from baseline measurements and record it in the SPEC.
- Quiesce/join workers before releasing track, recorder or AEC. A join timeout is an unresolved live-resource condition, not permission to free a handle still in use.
- Adapt buffer sizing using actual platform-accepted sizes and underruns. Preserve audio usage/focus, navigation confirmations and FC-008 unducking.

Automated acceptance: deterministic write/flush race, short/zero/error writes, final fragment, queue overflow, old epoch, hardware-tail state, blocked teardown and repeated restart tests. No old-epoch write may occur after acknowledged flush.

Device acceptance: no stale output after stop, audible final syllable, truthful playback state through tail, repeated stop/restart without resource failures; report measured latency/underruns without inventing a performance target after seeing results.

### P2 — AEC framing and clock boundary (architecture decision 1)

Primary owners: `PcmAudioCapture`, `PcmAudioPlayer`, `WebRtcAcousticEcho`/`VoiceAec`, `nova_aec.cpp`, JNI and native dependency configuration.

- Process 10-ms capture frames (160 samples / 320 bytes at 16-kHz mono PCM16). Keep Flex packet aggregation downstream and account by samples/time, not callback count.
- Audit every frame-duration consumer before changing constants: gate onset/pre-roll/hangover and diagnostics, packet aggregation, and wake/debug injection pacing. Astra High's read-only review confirmed that wake injection inherits capture frame bytes while retaining a 100-ms pacing constant, and some gate metrics use the default 100-ms duration. A constant-only change would corrupt timing and slow wake injection tenfold; add elapsed-sample regression cases.
- Preserve elapsed-time behavior for callback-rate algorithms too: `MicInputGain` recovers by 1.15 per frame, `SpeechUplinkGate` adapts its noise floor by 0.05 per frame, and Flex's 100-message holdback assumes 100-ms packets. At 10 ms these rates change by 10× and the holdback shrinks from about 10 seconds to 1 second unless normalized by samples/time. Keep 100-ms capture until gate, gain, wake/debug injection and downstream packet aggregation migrate as one measured unit.
- Preserve one stateful render resampler/framer per stream rate, including direct 16-kHz input. Keep partial fragments across calls and reset only at an explicit stream discontinuity.
- Return distinct processed, pending and failed results. Pending input must never be forwarded raw and later sent again processed. Define explicit backend failure handling through the existing single-backend policy.
- Correlate render reference with accepted playback and short-write progress. Reset/realign consistently after flush, route or format changes; avoid duplicating the reference on retries.
- Use capture/render clock observations for delay; measure and document fallback when timestamps are unavailable. Do not mistake capture capacity or the full application queue for acoustic delay.
- Keep AEC before microphone gain and gating. Preserve internal FloatS16 scaling; do not repeat a normalized-float conversion intended for a different API. Do not stack platform and native AEC.
- Record extracted AEC3 revision, source provenance, dependency notices and reproducible native build inputs.

Automated acceptance: irregular input partitioning preserves samples exactly once; 16/24-kHz render continuity; pending versus failure; rate/discontinuity changes; timestamp fallback; reference/write accounting; JNI lifetime and both configured ABIs.

Device acceptance: playback-only, near-end-only, real double-talk, tail, route and volume changes. Zero self-triggered audible reply chains in the defined playback-only trial; verify the driver is still heard. RMS attenuation alone is insufficient.

### P3 — Sleep/wake microphone handoff (architecture decision 3)

Primary owners: `ListeningLifecycle`, `VoiceSessionGateway`, `WakeWordController` and capture ownership adapters.

- Derive desired capture from lifecycle, permission, eligibility and actual health rather than socket/session existence. ACTIVE/SILENT_WAIT use conversational capture; SLEEP/DEEP_IDLE may use enabled local wake-only capture.
- Stop and quiesce the previous recorder before starting the next. Use the same reconciliation function for state/error/permission events and periodic recovery.
- Clear failed actual ownership, use bounded retry, and epoch-guard late wake events. Disarm wake before gateway resume/start; preserve healthy-session resume and failed-session rebuild.
- Clarify documentation: SLEEP forbids conversational cloud audio, while enabled wake detection may capture locally. Preserve user-disabled wake and denied permission.

Automated acceptance: lifecycle × permission × wake-enabled × capture-health matrix; no simultaneous recorders; stale callback; failed start/retry; reconnect while asleep; no cloud microphone frames in SLEEP.

Device acceptance: proposed minimum ten ACTIVE → SLEEP → wake cycles, plus DEEP_IDLE and error recovery, using the real microphone. Record success count and failures; synthesized wake clips do not establish cabin recognition.

### P4 — Qualified interruption and stop-output (architecture decision 4)

Primary owners: `SpeechUplinkGate`, `BaiduFlexClient`, ingress `VoiceSessionController`, `DriverTurn`/`PhantomTurnGate` and existing listening controls.

- Replace instantaneous attenuation-ratio veto with time-scoped post-AEC speech evidence, using a bounded WebRTC VAD adapter inside the existing gate. Preserve pre-roll/hangover and duration-based accounting.
- Treat cloud speech-start as a candidate and associate acoustic evidence, acceptance and replies with the same turn. An unconfirmed turn cannot become audible through the no-action-claim release path.
- Accepted barge-in invalidates local output immediately, including hardware tail. Cancel a remote response only when live/supported; network cancellation must not delay local silence.
- Preserve stop-output → SILENT_WAIT and continued listening, versus stop-listening → SLEEP. Stop-tool acknowledgment and late audio must not restart unwanted output.
- Confirm actual Flex event correlation capabilities before implementation. Quarantine ambiguous old output at the provider seam when reliable identity is unavailable; do not invent protocol IDs or truncation operations.

Automated acceptance: delayed/reordered speech and response events; echo-only candidate rejected throughout its reply lifecycle; quiet double-talk; stop before first PCM; stop during tail; late old PCM; cancellation failure; successful next command; proof-before-action claims unchanged.

Device acceptance: playback-only causes no self-loop; genuine speech interrupts; stop silences output and the next command succeeds. Record local invalidation and audible-stop latency separately; choose a justified UX threshold before declaring latency acceptance.

### P5 — Candidate dialog and confirmed phonetic recovery (architecture decision 5)

Primary owners: `EmbeddedNavigationController`/`NavigationStateStore`, `NavigationChoiceResolver`, `NavigationPickerIntercept`, `NavigationLocalPickGuard` and `AndroidToolDispatcher`.

- Extend the existing owner with pending-choice context: candidate identity/order, query intent, navigation generation and selection turn. Keep it across turns, not as permanent destination history or UI-owned duplicate state.
- Return explicit selected/refine/new-search/ambiguous/no-match outcomes. Retain the list during clarification; allow explicit replacement destination requests to escape it.
- Use canonical touch/voice selection methods. Replace the global one-shot flag with turn/list-scoped idempotency carrying the actual executor result, including failures.
- Invalidate old authority on replacement, cancel, expiry and teardown. After sleep, re-present/reconfirm options before accepting an old ordinal.
- Keep exact-name/character-fold matching first. Guard platform ICU transliteration on API 29+; API 28/missing data uses retained-list ordinal clarification. Phonetic ranking only proposes confirmation candidates; ties/polyphonic/weak matches never auto-navigate.

Automated acceptance: displayed ordering equals spoken ordinal resolution; exact-name regression; refinement/no-match preserves list; explicit new search replaces it; duplicate/late tool cannot pick twice or claim false success; canceled/expired/sleep-stale selection rejected; API-28 fallback and phonetic ambiguity.

Device acceptance: live ordinal, name, refinement, ambiguous pronunciation and explicit replacement flows reach the correct executor result exactly once. Existing injected exact-name evidence remains labeled as such.

### P6 — Integrated verification and handoff

For each behavior-changing unit, run targeted regressions, then the repository completion command:

```powershell
.\gradlew.bat test --rerun-tasks :app:assembleDebug
python scripts/collect_state.py
python scripts/harness_check.py
python scripts/design_basis.py --validate
python scripts/test_matrix.py --work
python scripts/discover_work.py
python scripts/test_matrix.py --gate
```

Read test totals from JUnit XML. Confirm `:app` participated, expected native ABIs are packaged and the APK tested on-device is the built artifact. Native runtime tests require an appropriate native/device harness; JVM source guards cannot prove native behavior.

Run audio/lifecycle/navigation device scenarios against that artifact, then three consecutive no-touch demo rehearsals covering wake, search, choice, route/start, audible simulated-climate confirmation, stop-output and a subsequent command. Preserve existing guidance and capability-truthfulness behavior. Keep actual road/GNSS arrival and cabin acoustics distinct from emulator/injected evidence.

Store sanitized outcomes and artifact bindings in `ACCEPTANCE_TESTS.md` and `TEST_MATRIX.yaml`; reconcile relevant issues, capabilities, design basis and product plan. Preserve the difference between implemented, automated regression protected and device/field verified. Record truly human-only cases with prerequisites, procedure, expected outcome and remaining uncertainty; batch the human packet only when the registry gate permits it. Continue independent authorized implementation work while those cases await validation.

Commit each reviewed and verified implementation slice locally; never push without an explicit request. A worker's green build is input to Planner review, not self-certification.

## 4. Deferred scope and decision checkpoints

No provider switch, separate ASR/TTS, PeerConnection/LiveKit runtime, second navigation controller, platform-plus-native AEC, blanket reply-time mic mute, hidden ICU API, raised minimum SDK or bundled pronunciation corpus. FC-007 guidance wording needs its own measured SDK-boundary investigation; it does not justify changing Flex. Rerouting, GPS-loss enhancements, traffic-light entitlement and UI polish remain outside this repair plan.

Before dispatching implementation, Planner must settle the measurable buffer limit, clock fallback, turn-correlation/quarantine contract, retry budget, choice expiry and phonetic confirmation criteria. Use existing evidence and bounded experiments; escalate to Astra High only when a consequential choice remains unresolved. No latency, acoustic success or phonetic accuracy number in this plan is a claim of achieved performance.

Initial next implementation actions were P0 baseline/reconciliation, a shared P1/P2 interface contract, and a deterministic stale-write regression. Progress and the remaining verification work are tracked below. No device trials have been run in this implementation turn.

Planning review: Astra High independently reviewed sequencing, source contracts and acceptance boundaries. Its recommendations are incorporated, including the shared output epoch/flush contract, focus-stop interaction, explicit completion path, JNI quiescence, and full elapsed-sample consumer audit. This review approves no runtime behavior or acoustic result.

## Implementation progress (2026-09-24)

- **P0 partly complete:** the two setup guides and the direct-runtime guide now describe the Android-to-Baidu path. The generated work review surfaced 44 stale test evidence bindings; those records were requeued in `TEST_MATRIX.yaml` rather than silently re-attached to the new commit. Design basis `DB-ASTRA-AUDIO-001` records the selected adaptation.
- **P1 first slice implemented, regression pending:** `PlaybackPort` carries the controller's epoch through start and flush; the player serializes output and invalidation, retains positive short-write suffixes, uses nonblocking writes, tracks accepted bytes for the render reference and waits for hardware drain before reporting idle. Deterministic regression execution and device evidence remain outstanding.
- **P1 completion path added, regression pending:** provider `AudioDone`/successful `ResponseDone` now closes the reply epoch; the playback owner pads only its final sub-10-ms fragment, accounts the submitted silence in the render reference, and rejects later PCM from that completed epoch. A new response opens a monotonically newer reply epoch without flushing already accepted hardware tail. `PLAYBACK-FINAL-FRAGMENT-001` records the needed deterministic acceptance. Flush/write race and hardware-tail measurement remain open.
- **P1 queue ceiling and playback teardown added, partial regression earned:** `AppPlaybackQueuePolicy` bounds application-owned pending PCM to 500 ms; overflow calls `failReplyLocked`, marks `completedThroughEpoch`, flushes the track, and reports `AUDIO_PLAYBACK_FAILED` without advancing `acceptEpoch`. Playback `stop()` joins `nova-pcm-play` before `AudioTrack.release()`, mirroring capture teardown. `PLAYBACK-QUEUE-OVERFLOW-001` passed via `AppPlaybackQueuePolicyTest` (4/4) and queue/teardown source-contract rows in `WebRtcAecIntegrationTest` (2026-09-24).
- **Capture teardown guard added, regression pending:** capture start/stop are serialized; stop requests recorder shutdown before joining the read worker, and recorder/effect release waits until that worker exits. A timed-out worker retains resource ownership and prevents an overlapping restart while asynchronous cleanup waits. `CAPTURE-TEARDOWN-001` tracks blocked-read/restart behavior; its source-contract check is added but not executed.
- **P2 first defects repaired, regression pending:** native 16 kHz render framing now preserves partial blocks; capture distinguishes processed/pending/failure so pending capture samples do not fall through as raw audio; Java-side AEC calls and release are serialized and the native handle is invalidated once. This does not settle stream-clock measurements, all backend error paths, cadence migration, or cabin acoustics.
- **P1 JVM seams complete (2026-09-24):** `PlaybackEpochEngine` extracts epoch/flush/completion/short-write logic; `PLAYBACK-FLUSH-001`, `PLAYBACK-FINAL-FRAGMENT-001`, and `CAPTURE-TEARDOWN-001` passed via `PlaybackEpochEngineTest` and updated `WebRtcAecIntegrationTest` source-contract rows. Hardware-tail measurement remains a device row.
- **P2 10 ms capture migration (2026-09-24):** `PcmAudioCapture` uses 320-byte / 10 ms frames; `AudioFrameTiming` scales `MicInputGain`, `SpeechUplinkGate` noise adapt, wake harness pacing, and Flex `MAX_HELD_OUTBOUND` (~10 s). `AEC-FRAME-CONTINUITY-001` passed via `AecFrameContinuityTest` + native leftover source-contract. JNI runtime on device remains a device row.
- **P3 sleep/wake handoff (2026-09-24):** `WakeWordController` stands down on `listeningState.uploads` (ACTIVE/SILENT_WAIT) rather than `VoiceSessionGateway.isActive`; `reconcile()` runs on lifecycle transitions. Ten real wake cycles remain `ASTRA-WAKE-CYCLES-001` HUMAN_REQUIRED.
- **P4 qualified barge-in (2026-09-24):** `qualifyPlayoutBargeIn` uses `microphone.uplinkGateOpen` (post-AEC gate evidence) instead of `AecMetrics.shouldFlushBargeIn` RMS ratio. Playback-only/double-talk device rows added.
- **P5 pending navigation choice (2026-09-24):** `NavigationLocalPickGuard` stores turn/list-scoped outcomes; `NavigationPickSession` records executor results; `NavigationPhoneticConfirmation` proposes ICU confirmation on API 29+. Live ordinal/phonetic flows remain device rehearsals.
- **P5 pending-choice authority completed (2026-09-24, cloud session):** `EmbeddedNavigationController` now owns list staleness and the phonetic question. An ordinal or preference on a list older than `CHOICE_EXPIRY_MS` (120 s, a starting value) or one that survived SLEEP/DEEP_IDLE is refused once with `OPTIONS_STALE`, the tool result carries `options_on_screen`, and the list counts as re-presented; exact names are never refused. `NavigationPhoneticConfirmation` is wired: a single phonetic lead returns `ConfirmNeeded` → `CONFIRM_CANDIDATE` with `candidate_position`/`candidate_name`, selects nothing, and a whole short affirmative (`isAffirmative`) answers it through the local pick path; sleep, expiry, any other answer and a new list void it. `NAV-CHOICE-STALE-001` and `NAV-PHONETIC-CONFIRM-001` passed (10/10); the live flow is `NAV-PHONETIC-DEVICE-001` HUMAN_REQUIRED.
- **P3 retry and stale-event guard (2026-09-24, cloud session):** `WakeArming` owns the armed state and two `BoundedRetry` budgets (1.5 s doubling to 30 s, 6 attempts, reset by lifecycle/settings reconcile or the first healthy frame). A detection counts only while armed and disarms on acceptance, so a late or doubled engine callback, or one while conversational capture owns the microphone, starts nothing. A capture error now frees the recorder's slot (off its worker thread) so the reconcile tick restarts it; an engine in ERROR is no longer re-initialised every tick forever. `WAKE-ARMING-001` passed; the ten real cycles remain `ASTRA-WAKE-CYCLES-001`.
- **P4 time-scoped evidence and echo-candidate turns (2026-09-24, cloud session):** `SpeechUplinkGate.hasRecentSpeech()` asks for at least the onset (200 ms) of voiced post-AEC audio within the last `EVIDENCE_WINDOW_MS` (600 ms); `isOpen` included the 1.2 s hangover, so a short echo burst qualified any `speech_started` after it. `VoiceSessionController.bargeInQualified()` gives the same answer to the playback owner (flush or suppress) and to `BaiduFlexClient`, which marks the new `DriverTurn` an echo candidate when speech starts over playback without it. `HoldReason.ECHO_CANDIDATE` holds the reply until the driver's own words (a transcript that is not the assistant's last reply heard back, `isEchoOf`), a tool call or execution proof confirm the turn, and drops it at response end otherwise — the no-claim release can no longer voice a reply to the cabin's echo. WebRTC's VAD is not in the vendored extraction, so the bounded adapter is the gate's own energy evidence rather than a new native dependency. Provider-side turn IDs and remote truncation were not invented (Flex offers neither). `BARGE-IN-EVIDENCE-001` passed; cabin behaviour remains `ASTRA-ECHO-PLAYBACK-001` / `ASTRA-DOUBLE-TALK-001`.
- **P2 clock-based stream delay and native provenance (2026-09-24, cloud session):** `EchoDelayEstimator` computes AEC3's delay as render latency (frames written minus frames presented, from `AudioTrack.getTimestamp` extrapolated to now with the stamp's own `nanoTime`) plus capture latency (age of the latest frame read, from `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)`), plus the resampler. Fallbacks are explicit and logged (`echo_delay … render=… capture=…` every 5 s): head position without extrapolation, then half the record buffer; with no output clock the previous delay stands. Before, the capture half was always the half-buffer guess and the render stamp was never aged. `AEC-DELAY-CLOCK-001` passed; the per-device measurement is `AEC-DELAY-DEVICE-001`. The WebRTC AEC3 sources are now pinned to `2cec2f52` in both fetch scripts with provenance and licences in `docs/THIRD_PARTY_NATIVE.md` (`NativeProvenanceTest`).
- **P1 buffer adaptation (2026-09-24, cloud session):** `LowLatencyPlaybackBuffer` now records the size `setBufferSizeInFrames` actually applied (it clamps to capacity and granularity; the requested size used to be recorded), stops growing when the platform refuses, and counts only underruns while the app had audio to write: the player calls `onStarved` whenever its queue is empty, so the track running dry between replies (or through network jitter) no longer grows the buffer by up to 50 ms. The policy sits behind a two-method `Port` so it is JVM-tested (`PLAYBACK-BUFFER-ADAPT-001`); measured sizes, underruns and a latency budget are `PLAYBACK-BUFFER-DEVICE-001`.
- **P6 verification (2026-09-24):** `.\gradlew.bat test --rerun-tasks :app:assembleDebug` passed. Device-only cases registered as `HUMAN_REQUIRED` (`ASTRA-ECHO-PLAYBACK-001`, `ASTRA-DOUBLE-TALK-001`, `ASTRA-WAKE-CYCLES-001`, three demo rehearsals). No device verification was performed in this turn.
