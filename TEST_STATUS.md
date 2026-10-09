# Test status

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --status`. **Do not edit by hand** — the registry is the source of truth and this is a view of it.

Updated 2026-09-21 · 153 tests

| | |
| --- | --- |
| autonomous PASS | 59 |
| autonomous FAIL | 0 |
| incomplete | 0 |
| partial pass | 0 |
| not run | 66 |
| human required | 26 |
| human pass | 0 |
| human fail | 1 |
| blocked external | 0 |
| not applicable | 0 |
| **release-blocking failures** | **0** |

**HUMAN_VALIDATION_READY = FALSE**

- WAKE-ENGINE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- WAKE-SYNTH-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TRUTH-MISHEARD-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TRUTH-MEDIA-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TRUTH-WEATHER-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TRUTH-BAIT-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- PLACE-SAVE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- PLACE-NAV-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- PLACE-UNSET-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CALL-NOSIM-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-SEARCH-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-PICK-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-CANCEL-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-CORRECT-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-UI-002 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-UI-003 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-UI-004 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-MID-ROUTE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-E2E-ARRIVAL-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-UI-005 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-UI-006 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-UI-007 is AUTONOMOUS and NOT_RUN - run it or fix it
- CLIMATE-SET-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CLIMATE-IMPLICIT-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CLIMATE-OFF-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CTX-CHAIN-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CTX-AMBIG-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- MUSIC-PLAY-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- MUSIC-NAMED-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- MUSIC-TEXT-LIVE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- MUSIC-VLC-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- BARGEIN-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- AEC-DELAY-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- PLAYBACK-BUFFER-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- SLEEP-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NOISE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NET-RECOVER-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- LIFECYCLE-ERROR-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- SUITE-SCENARIO-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- PERM-DENY-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- PROCDEATH-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- VISION-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- APPS-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- UNSUPPORTED-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CAMERA-RELEASE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- ECHO-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- HELP-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- NAV-SIM-QA-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- AFFORDANCE-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TRUTH-LIVEINFO-NEWS-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- LIVE-INFO-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- LIVE-INFO-L6-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- LIVE-INFO-REGRESSION-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- SPEECH-ARBITER-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- SPEECH-WORKLOAD-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- GEMINI-DEVICE-DUPLEX-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- GATE-D10-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- FUZZY-TEXT-LIVE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- FUZZY-DEVICE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- QWEN-PROBE-Q1-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- QWEN-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- GUIDANCE-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- CONFIG-CARD-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- P45-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- WAIT-CUE-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TRANSCRIPT-FADE-EMU-001 is AUTONOMOUS and NOT_RUN - run it or fix it
- TTS-VOICE-EMU-001 is AUTONOMOUS and BLOCKED_AUTONOMOUS - run it or fix it
- live_info.along_route requires END_TO_END but has no passing cover (linked: none)
- live_info.place_details requires END_TO_END but has no passing cover (linked: none)
- live_info.route_traffic requires END_TO_END but has no passing cover (linked: none)
- live_info.weather requires END_TO_END but has no passing cover (linked: none)

## By capability

| Capability | Tests | Passing | Awaiting a human | Not run |
| --- | --- | --- | --- | --- |
| apps | 1 | 0 | 0 | 1 |
| architecture | 5 | 4 | 0 | 1 |
| body | 1 | 1 | 0 | 0 |
| calling | 8 | 6 | 1 | 1 |
| cantonese | 2 | 1 | 1 | 0 |
| climate | 5 | 1 | 1 | 3 |
| comfort | 3 | 1 | 0 | 2 |
| dialogue_context | 4 | 1 | 0 | 3 |
| lifecycle | 1 | 0 | 0 | 1 |
| live_info | 4 | 1 | 0 | 3 |
| media | 6 | 1 | 1 | 4 |
| microphone | 1 | 0 | 1 | 0 |
| navigation | 24 | 6 | 4 | 14 |
| performance | 2 | 2 | 0 | 0 |
| permissions | 7 | 4 | 0 | 3 |
| regression | 2 | 1 | 0 | 1 |
| release | 6 | 2 | 4 | 0 |
| reliability | 8 | 4 | 1 | 3 |
| saved_places | 3 | 0 | 0 | 3 |
| security | 3 | 3 | 0 | 0 |
| speech | 20 | 7 | 8 | 4 |
| truthfulness | 9 | 4 | 0 | 5 |
| turn_taking | 19 | 7 | 3 | 9 |
| unsupported | 1 | 0 | 0 | 1 |
| vision | 2 | 0 | 0 | 2 |
| wake | 2 | 1 | 1 | 0 |
| wake_word | 4 | 1 | 1 | 2 |

## Every test

| ID | Capability | Test | Owner | Status | Release blocking | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| APPS-001 | apps | open_app reaches a real app | AUTONOMOUS | NOT_RUN | no | STALE_BIND apk_digest mismatch |
| AFFORDANCE-DEVICE-001 | architecture | Spoken control names act on the phone with no model tool call | AUTONOMOUS | NOT_RUN | no | — |
| AFFORDANCE-UNIT-001 | architecture | Spoken on-screen controls match whole utterances and run once per turn | AUTONOMOUS | PASS | yes | 2026-09-25 cloud: 2066 JVM tests, 0 failures |
| ARCH-PROVIDER-001 | architecture | Vendor wire vocabulary stays inside adapters | AUTONOMOUS | PASS | no | checked against a deliberately reintroduced breach |
| GEMINI-LIVE-CLOUD-001 | architecture | The app's Gemini client against the real API (cloud) | AUTONOMOUS | PASS | no | 2026-09-29: ac_on, fan_up, nav_wanda 3/3 PASS (docs/reports/2026-09-29-gemini-live-prob… |
| GEMINI-UNIT-001 | architecture | Gemini Live adapter, shared gate and opt-in wiring | AUTONOMOUS | PASS | no | 2026-09-29 cloud, claude/9-29: app debug 1230/0, behavior-test 126/0, ingress 104/0 |
| BODY-UNIT-001 | body | Windows and seat height act on the vehicle port and announce what was done | AUTONOMOUS | PASS | no | 2026-09-30 cloud, wt/W3a-body 58524f0: BodyDomainTest 6/0, WindowToolHandlerTest 11/0, … |
| CALL-AMBIG-001 | calling | Two people with one name are offered, not chosen between | AUTONOMOUS | PASS | yes | PhoneCallToolTest, 2026-09-20 |
| CALL-CLASSIFY-001 | calling | A call request is an action, not an unsupported refusal | AUTONOMOUS | PASS | yes | 2026-09-20 TEST_REVIEW: keyword list and registry both claimed ownership of calling |
| CALL-CONFIRM-001 | calling | One match is never dialled on the first turn | AUTONOMOUS | PASS | yes | PhoneCallToolTest, 2026-09-20 |
| CALL-DIAL-001 | calling | A failed dial is not reported as a placed call | AUTONOMOUS | PASS | yes | PhoneCallToolTest.aDiallerFailureIsNotReportedAsAPlacedCall |
| CALL-NOSIM-001 | calling | A car that cannot call says so | AUTONOMOUS | NOT_RUN | yes | 2026-09-20 on 2391ff70, gsm.sim.state=ABSENT,ABSENT |
| CALL-PRIVACY-001 | calling | The phone number never reaches the model | AUTONOMOUS | PASS | yes | PhoneCallToolTest, 2026-09-20 |
| CALL-REAL-001 | calling | A confirmed call reaches a real handset | HUMAN_ACCOUNT | HUMAN_REQUIRED | yes | CALL-CONFIRM-001, CALL-AMBIG-001, CALL-PRIVACY-001 PASS |
| STALE-CONFIRM-001 | calling | A confirmation only authorises the call it was asked about | AUTONOMOUS | PASS | yes | 2026-09-20: found by discovery, and it was a real defect - confirmed=true was trusted o… |
| YUE-POLICY-001 | cantonese | Is Cantonese an advertised capability? | HUMAN_DECISION | HUMAN_REQUIRED | yes | — |
| YUE-RATE-001 | cantonese | Measured Cantonese success rate | AUTONOMOUS | PASS | no | 2026-09-20 before the persona line: 返屋企 2/5, 有啲熱 0/5, music refusal 0/5 |
| ASTRA-DEMO-REHEARSAL-002 | climate | No-touch demo rehearsal — climate confirmation and stop-output | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | ListeningLifecycle SILENT_WAIT path unchanged |
| CLIMATE-FAN-001 | climate | An explicit fan level is set | AUTONOMOUS | PASS | no | ClimateToolHandlerTest.eachActionCallsExactlyOnePortMethod and malformedArgumentsNeverR… |
| CLIMATE-IMPLICIT-001 | climate | 有点热 produces a real adjustment | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite, after isControlRequest learned to ask ContextResolver |
| CLIMATE-OFF-001 | climate | 关闭空调 turns it off | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| CLIMATE-SET-001 | climate | An explicit temperature is set | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| FUZZY-DEVICE-001 | comfort | Fuzzy requests on the emulator - chip on screen, one call per scenario, honest partial report | AUTONOMOUS | NOT_RUN | no | — |
| FUZZY-TEXT-LIVE-001 | comfort | The live model maps the fuzzy phrase table FZ-01…FZ-19 to the right calls | AUTONOMOUS | NOT_RUN | yes | 2026-10-01 cloud, gemini-3.8-live, after adding 傲娇/温柔/元气: FUZZY 18/19 — FZ-15 傲娇一点 and … |
| SCENARIO-UNIT-001 | comfort | Comfort scenarios run their fixed steps through the direct-call servers and report partial failure honestly | AUTONOMOUS | PASS | no | 2026-10-01 cloud, integrated 436d197: ComfortScenariosTest 15/0; full suite 3378 / 0 fa… |
| CTX-AMBIG-001 | dialogue_context | 再低一点 with no history asks which | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 「您是指温度还是风量呢？」 |
| CTX-CHAIN-001 | dialogue_context | 再凉一点 continues the previous adjustment | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| HELP-001 | dialogue_context | 「你能做什么」 / 「你能干啥」 gets a short capability answer | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| HELP-UNIT-001 | dialogue_context | Help-request grammar and nudge are unit-covered | AUTONOMOUS | PASS | yes | UtteranceIntentResolver grammar + DriverTurn.CAPABILITY_HELP 2026-09-22 unit PASS |
| PROCDEATH-001 | lifecycle | The app survives process death | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| LIVE-INFO-DEVICE-001 | live_info | Each live_info kind is selected from speech | AUTONOMOUS | NOT_RUN | yes | — |
| LIVE-INFO-L6-001 | live_info | Every live_info source returns real data with the owner's key | AUTONOMOUS | NOT_RUN | yes | 2026-09-25 partial: REST weatherInfo base/all, regeo and place/detail returned real dat… |
| LIVE-INFO-REGRESSION-001 | live_info | SPEC-008 suite does not regress with query_live_info added | AUTONOMOUS | NOT_RUN | yes | — |
| LIVE-INFO-UNIT-001 | live_info | query_live_info parses, fails honestly and opens the picker for along-route results | AUTONOMOUS | PASS | yes | 2026-09-25 cloud: :app ran in a stub-iFlytek mirror because vendor Msc.jar is absent in… |
| MUSIC-APP-DEVICE-001 | media | 网易云 / QQ音乐 play a described song or fail honestly | HUMAN_ACCOUNT | HUMAN_REQUIRED | yes | MUSIC-UNIT-001 (JVM) |
| MUSIC-NAMED-001 | media | A named song goes to play_music, never the bundled track | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite (old criterion: MEDIA_LIBRARY_UNSUPPORTED) |
| MUSIC-PLAY-001 | media | 播放音乐 plays the bundled track | AUTONOMOUS | NOT_RUN | no | 2026-09-20: 20/20 suite |
| MUSIC-TEXT-LIVE-001 | media | The model maps music descriptions to play_music fields | AUTONOMOUS | NOT_RUN | yes | 2026-10-01 re-run after the FZ-14 wording fix: GeminiTextLiveMappingTest MUSIC 10/10 |
| MUSIC-UNIT-001 | media | play_music validates, hands off and reports only what is playing | AUTONOMOUS | PASS | yes | 2026-10-01 cloud, integrated 436d197: MediaServerPlayMusicTest 12/0, MediaDomainPlayMus… |
| MUSIC-VLC-EMU-001 | media | VLC plays a local file handed off by play_music and the readback names it | AUTONOMOUS | NOT_RUN | yes | MUSIC-UNIT-001 (JVM) covers the result rules with a fake session source |
| MIC-CABIN-001 | microphone | Open-mic thresholds in real cabin acoustics | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | NOISE-001 PASS - room noise produces no turn |
| AUDIO-QUALITY-001 | navigation | Guidance loudness and speech quality in a cabin | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | 21 guidance play start/end pairs observed on an emulator drive; the mic is gated while … |
| GUIDANCE-DEVICE-001 | navigation | One voice on the short route; Amap audible only when the model cannot speak | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | GUIDANCE-EMU-001 measurements |
| GUIDANCE-EMU-001 | navigation | Step-0 measurements for assistant-spoken guidance on the short route | AUTONOMOUS | NOT_RUN | yes | — |
| NAV-CANCEL-001 | navigation | 算了 really cancels | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: nav_flow_cancelled |
| NAV-CHOICE-STALE-001 | navigation | A spoken ordinal on a stale list is re-presented before it is accepted | AUTONOMOUS | PASS | yes | 2026-09-24: NavigationPendingChoiceTest 10/10 (cloud Linux build) |
| NAV-CORRECT-001 | navigation | A mid-flow destination change replaces the list | AUTONOMOUS | NOT_RUN | no | 2026-09-20: 20/20 suite |
| NAV-DRIVE-001 | navigation | A route calculates and guides to arrival | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | NAV-SEARCH-001, NAV-PICK-001, NAV-CANCEL-001, NAV-CORRECT-001 PASS |
| NAV-E2E-ARRIVAL-001 | navigation | Navigation reaches the destination and terminates by arrival | AUTONOMOUS | NOT_RUN | yes | STALE_BIND code_digest mismatch |
| NAV-MID-ROUTE-001 | navigation | Mid-route navigation baseline without countdown entitlement | AUTONOMOUS | NOT_RUN | yes | 2026-09-21: nav_traffic_countdown status=BLOCKED_EXTERNAL_AMAP_ENTITLEMENT |
| NAV-PHONETIC-CONFIRM-001 | navigation | A phonetic lead asks for confirmation and never navigates | AUTONOMOUS | PASS | yes | 2026-09-24: NavigationPendingChoiceTest 10/10 with an injected proposer |
| NAV-PHONETIC-DEVICE-001 | navigation | Misheard destination name is confirmed by voice on the device | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | NAV-CHOICE-STALE-001 and NAV-PHONETIC-CONFIRM-001 passed 2026-09-24 |
| NAV-PICK-001 | navigation | 第二个 picks from the on-screen list | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| NAV-SEARCH-001 | navigation | A destination produces candidates and does not start navigating | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| NAV-SIM-QA-001 | navigation | Reusable navigation screen-recording movie for QA | AUTONOMOUS | NOT_RUN | no | STALE_BIND apk_digest mismatch |
| NAV-UI-001 | navigation | 「开始导航」 enters ACTIVE_DRIVING_NAVIGATION, not route preview | AUTONOMOUS | PASS | yes | EmbeddedNavigationControllerTest.SelectingARouteEntersDrivingPresentationNotRoutePreview |
| NAV-UI-002 | navigation | During GPS navigation the vehicle stays in lock-car tracking | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| NAV-UI-003 | navigation | Vehicle heading rotates the navigation map | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| NAV-UI-004 | navigation | Active route shows traffic-state colouring when Amap supplies it | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| NAV-UI-005 | navigation | Next-maneuver guidance is visible | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| NAV-UI-006 | navigation | Lane or junction enlarge appears when Amap supplies it | AUTONOMOUS | NOT_RUN | no | STALE_BIND apk_digest mismatch |
| NAV-UI-007 | navigation | Overview then return restores vehicle tracking | AUTONOMOUS | NOT_RUN | yes | STALE_BIND code_digest mismatch |
| NAV-UI-008 | navigation | Spoken 「开始导航」 enters the same driving state as a route tap | AUTONOMOUS | PASS | yes | 2026-09-20 unit |
| NAV-UI-009 | navigation | Arrival leaves ACTIVE_DRIVING_NAVIGATION | AUTONOMOUS | PASS | yes | 2026-09-20 unit plus existing arrival tests |
| NAV-UI-010 | navigation | Route-selection voice commands are unchanged | AUTONOMOUS | PASS | yes | 2026-09-20 unit, pre-existing tests still green |
| LATENCY-001 | performance | Response latency from end of speech | AUTONOMOUS | PASS | no | 2026-09-20, 21 turns: min 328, median 734, p90 1007, max 1062 ms |
| RESOURCE-IDLE-001 | performance | CPU and memory while idle and listening | AUTONOMOUS | PASS | no | 2026-09-20 on 2391ff70: ~62% CPU, 565 MB resident with the wake word ON |
| CONFIG-CARD-EMU-001 | permissions | The CONFIG card leaves the map once the missing setting is saved | AUTONOMOUS | NOT_RUN | no | — |
| CONFIG-CARD-UNIT-001 | permissions | A fixed configuration problem removes its CONFIG card and nothing else | AUTONOMOUS | PASS | no | 2026-10-02 cloud, claude/10-1: ShownErrorCardTest 10/0 with the B-033 fade; full suite … |
| PERM-CAMERA-001 | permissions | A camera question without permission fails honestly | AUTONOMOUS | PASS | yes | CameraQuestionHandlerTest.missingCameraPermissionFailsFastWithoutCaptureOrRequest |
| PERM-CONTACTS-001 | permissions | Missing contacts permission is not reported as not-found | AUTONOMOUS | PASS | yes | 2026-09-20 TEST_REVIEW: AndroidContacts mapped READ_CONTACTS denial onto ContactMatchKi… |
| PERM-DENY-001 | permissions | A revoked microphone permission fails honestly | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| TRANSCRIPT-FADE-EMU-001 | permissions | On the emulator the transcript bubble leaves the map about 10 s after the last line | AUTONOMOUS | NOT_RUN | no | 2026-10-02 cloud attempt, not a result: the app installed on nova34 (software-only) but… |
| TRANSCRIPT-FADE-UNIT-001 | permissions | The last exchange clears after a quiet period and never while the conversation is live | AUTONOMOUS | PASS | no | 2026-10-02 cloud, claude/10-2: TranscriptBubbleFadeTest 7/0, TranscriptBubbleSessionSim… |
| SUITE-SCENARIO-001 | regression | The live scenario suite | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20, exit 0 |
| SUITE-UNIT-001 | regression | The whole unit and architecture suite | AUTONOMOUS | PASS | yes | 2026-09-21: 819 tests, 0 failures, 0 skipped (post-P31 full suite --rerun-tasks) |
| ABI-POLICY-001 | release | Must armeabi-v7a keep working? | HUMAN_DECISION | HUMAN_REQUIRED | no | — |
| ABI-SIZE-001 | release | APK size per ABI | AUTONOMOUS | PASS | no | 2026-09-20: universal release APK 216.7 MB on disk, 228.0 MB uncompressed |
| ASTRA-DEMO-REHEARSAL-001 | release | No-touch demo rehearsal — wake search choice route | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | NavigationLocalPickGuard turn/list authority + NavigationPickSession executor results |
| ASTRA-DEMO-REHEARSAL-003 | release | No-touch demo rehearsal — replacement search after ambiguous pick | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | NavigationLocalPickGuard AMBIGUOUS/NO_MATCH/SELECTED outcomes |
| RELEASE-BUILD-001 | release | The release variant builds | AUTONOMOUS | PASS | yes | 2026-09-20: app-release-unsigned.apk, 227 MB |
| RELEASE-SIGN-001 | release | A signed release build installs and works | HUMAN_CREDENTIAL | HUMAN_REQUIRED | yes | RELEASE-BUILD-001 PASS - the release variant compiles and packages |
| AEC-DELAY-CLOCK-001 | reliability | AEC3 stream delay comes from the audio clocks, with named fallbacks | AUTONOMOUS | PASS | yes | 2026-09-24: EchoDelayEstimatorTest 7/7 (cloud Linux build) |
| AEC-DELAY-DEVICE-001 | reliability | Measured echo delay and its sources on the test phone | AUTONOMOUS | NOT_RUN | no | — |
| AEC-FRAME-CONTINUITY-001 | reliability | Partial AEC frames stay pending and render reference follows accepted writes | AUTONOMOUS | PASS | yes | AecFrameContinuityTest JVM partition checks at 16/24 kHz |
| AUDIOFOCUS-001 | reliability | Losing audio focus stops the assistant talking | AUTONOMOUS | PASS | no | 2026-09-20: the handling already existed in AndroidPlaybackPort and had no test, becaus… |
| CAPTURE-TEARDOWN-001 | reliability | Capture stops and joins before recorder resources are released | AUTONOMOUS | PASS | yes | PcmAudioCapture stop/join ordering and mayStartAudioWorker guard |
| GEMINI-DEVICE-REACH-001 | reliability | The phone opens a Gemini Live session on its normal network (G-M1) | HUMAN_CREDENTIAL | HUMAN_REQUIRED | no | c |
| LIFECYCLE-ERROR-001 | reliability | A dead session stops showing that it is listening | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: verified by reproducing the rejection, then reverting it |
| NET-RECOVER-001 | reliability | A cut connection recovers without the driver noticing | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: tool=control_climate after the socket was cancelled |
| PLACE-NAV-001 | saved_places | 回家 resolves from the saved place, not a POI search | AUTONOMOUS | NOT_RUN | no | 2026-09-20: 4/5 - the ASR misses the two-character utterance about once in five, and th… |
| PLACE-SAVE-001 | saved_places | An address is resolved before it is stored | AUTONOMOUS | NOT_RUN | no | 2026-09-19: place=珠海站 |
| PLACE-UNSET-001 | saved_places | An unset home is admitted, not guessed | AUTONOMOUS | NOT_RUN | yes | 2026-09-19 device |
| EXPORTED-001 | security | The release build exposes no debug surface | AUTONOMOUS | PASS | yes | 2026-09-20, release manifest via aapt2: only MainActivity is exported; DeveloperSetting… |
| RELEASE-LOG-001 | security | A release build does not log what the driver said | AUTONOMOUS | PASS | yes | 2026-09-20: DebugVoiceLog is the only logger in app/src/main, ingress/src/main and cont… |
| SECRET-SCAN-001 | security | No secret is tracked by git | AUTONOMOUS | PASS | yes | part of the 737 |
| DEMO-10-2-001 | speech | One demo run that exercises every claude/10-2 function, graded by the log checker | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | tools/demo/check_demo_log.py --selftest OK (2026-10-02 cloud) |
| DEMO-10-3-001 | speech | One demo run that exercises the essential functions and every claude/10-2 and claude/10-3 function, graded by the log checker | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | tools/demo/check_demo_log.py --selftest OK (2026-10-03 cloud, 10-3 checks included) |
| P45-EMU-001 | speech | The P45 conversation on the emulator with live speech, with the Azure voice | AUTONOMOUS | NOT_RUN | no | — |
| P45-UNIT-001 | speech | An interrupted unheard reply is never synthesised; a question restarts the sleep window; turn timings are logged | AUTONOMOUS | PASS | no | 2026-10-02 cloud, claude/10-2: GeminiLiveClientTest 69/0, ListeningLifecycleTest 36/0, … |
| QWEN-ADAPTER-UNIT-001 | speech | SPEC-021 steps 1-3: the shared OpenAI-Realtime client and the Qwen-Omni dialect keep the truth rules on a mock socket | AUTONOMOUS | PASS | no | 2026-10-09 cloud, claude/10-9 merge 71a8d40: app 3447/0 (debug+release), behavior-test … |
| QWEN-EMU-001 | speech | ADR-017 gate Q-4: the demo on the emulator with Qwen + Maia selected, under docs/DEMO_REQUIREMENTS.md | AUTONOMOUS | NOT_RUN | no | — |
| QWEN-PROBE-Q1-001 | speech | ADR-017 gate Q-1: Qwen-Omni Realtime + Maia measured outside the app, side by side with Gemini + Xiaoyi | AUTONOMOUS | NOT_RUN | no | — |
| QWEN-VOICE-EAR-001 | speech | ADR-017 gate Q-2: Maia against Xiaoyi, by ear, on the probe's clips | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | tools/qwen-omni-probe say mode writes one 24 kHz WAV per line plus summary.json (selfte… |
| STYLE-EAR-001 | speech | 嗲 / 傲娇 / 温柔 / 元气 each sound right and appropriate, with the same voice | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | STYLE-UNIT-001: compose swaps the tone paragraph; style persisted and sticky (JVM) |
| STYLE-UNIT-001 | speech | set_speaking_style changes the tone, persists it and composes it | AUTONOMOUS | PASS | no | 2026-09-30 cloud, wt/W3b-style-tool 80b9072: SpeakingStyleToolTest 8/0, PersonaProfiles… |
| TTS-AUDITION-001 | speech | The owner picks a Chinese TTS voice (and vendor) by ear | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | 2026-10-02 owner, by ear on edge_preview clips (D:/桌面/android_doc/tts_audition/edge_pre… |
| TTS-UNIT-001 | speech | The assistant voice speaks only gate-released words, in order, and stops on barge-in | AUTONOMOUS | PASS | no | 2026-10-02 cloud, claude/10-2 9305ede (after the independent review REVISE fixes): Assi… |
| TTS-VOICE-DEVICE-001 | speech | In the car, Xiaoyi is the only voice and the styles sound right | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | TTS-UNIT-001 |
| TTS-VOICE-EMU-001 | speech | Xiaoyi speaks every reply and guidance prompt on the short route; barge-in stops her | AUTONOMOUS | BLOCKED_AUTONOMOUS | no | — |
| VOICE-AB-001 | speech | Gemini prebuilt voices measured for a young, clear voice (B-034 shortlist) | AUTONOMOUS | PASS | no | 2026-10-02 cloud, 3 runs, median F0 Hz: Erinome 235-250, Leda 222-235, Autonoe 211, Aoe… |
| VOICE-AB-002 | speech | No Gemini-side lever makes the voice younger in conversation; the transcript can feed a TTS | AUTONOMOUS | PASS | no | 2026-10-02 cloud: read-aloud young line Leda 222->258-276 Hz, Erinome ->242-258 Hz; app… |
| VOICE-EAR-001 | speech | The owner picks the 符玄-like Gemini voice by ear | HUMAN_PHYSICAL | HUMAN_FAIL | no | 2026-10-02 owner, emulator at 34d629d: 10 voices recorded through the app (Erinome 250 … |
| VOICE-STYLE-001 | speech | Prefer a younger cute female voice (符玄-like) | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | Owner chose option B id 4196 on 2026-09-21 |
| WAIT-CUE-EMU-001 | speech | Wait cues on the emulator with live Gemini and the Azure voice (SPEC-020 A6) | AUTONOMOUS | NOT_RUN | no | 2026-10-08 f8e29b6 partial (demo recorder, not this procedure): 12 turns, every one cue… |
| WAIT-CUE-UNIT-001 | speech | Wait cues fire on time from the last word, truthfully, once, and never block a tool result | AUTONOMOUS | PASS | no | 2026-10-08 cloud, claude/10-8 (merge 92a36ec): AssistantVoiceRevoicerWaitCueTest 25/0, … |
| DUP-EXEC-001 | truthfulness | The same adjustment does not run twice in one turn | AUTONOMOUS | PASS | yes | 2026-09-20: FalseCapabilityClaimTest asserts a repeated adjust_temperature in one turn … |
| FAILED-ACTION-REPORTED-001 | truthfulness | A failed action is reported after its false success claim is dropped | AUTONOMOUS | PASS | yes | 2026-09-24: 20/20 each; failed every run before the fix (also on 0ea4885) |
| TRUTH-BAIT-001 | truthfulness | An explicit request to lie is not obeyed | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| TRUTH-CLAIM-001 | truthfulness | A claim no tool performed is never spoken | AUTONOMOUS | PASS | yes | 2026-09-19 device: the fabrication is corrected, 「没听清，再说一遍。」 |
| TRUTH-DUP-001 | truthfulness | One correction per response, not two | AUTONOMOUS | PASS | no | the test reports n=2 without the single-owner guard and n=1 with it |
| TRUTH-LIVEINFO-NEWS-001 | truthfulness | News and prices stay refused even with a live result in the turn | AUTONOMOUS | NOT_RUN | yes | — |
| TRUTH-MEDIA-001 | truthfulness | A named or styled song never plays the bundled track, even when misheard | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 3/3 then 5/5 after the fix; before it, the bundled track played (old criter… |
| TRUTH-MISHEARD-001 | truthfulness | A misheard driver is not told the car acted | AUTONOMOUS | NOT_RUN | yes | 2026-09-19: 「返屋企啦」 -> 「发诺克拉。」 -> correction, not a claim |
| TRUTH-WEATHER-001 | truthfulness | Weather and traffic answers come only from a live lookup | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: refusal, 20/20 suite (pre-SPEC-011 criterion, superseded) |
| ASTRA-DOUBLE-TALK-001 | turn_taking | Real double-talk and route-change acoustics | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | SpeechUplinkGate + qualifyPlayoutBargeIn uplinkGateOpen wiring |
| ASTRA-ECHO-PLAYBACK-001 | turn_taking | Playback-only cabin echo does not self-trigger replies | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | qualified barge-in uses uplinkGateOpen instead of RMS ratio veto |
| BARGE-IN-EVIDENCE-001 | turn_taking | Barge-in needs time-scoped speech evidence; an unconfirmed echo turn is never heard | AUTONOMOUS | PASS | yes | 2026-09-24: BargeInEvidenceTest 11/11; full suite 1618/0; REGRESSION 41/41, CHAOS 26/26… |
| BARGEIN-001 | turn_taking | 闭嘴 stops speech without ending the session | AUTONOMOUS | NOT_RUN | no | 2026-09-20: 20/20 suite |
| ECHO-001 | turn_taking | Post-reply cabin echo does not become 「没听清」 | AUTONOMOUS | NOT_RUN | yes | STALE_BIND code_digest mismatch |
| GATE-D10-DEVICE-001 | turn_taking | Claim gate D-10 orderings hold on real provider traffic | AUTONOMOUS | NOT_RUN | no | — |
| GEMINI-DEVICE-DUPLEX-001 | turn_taking | Gemini reply through the speaker does not interrupt itself (G-M2) | AUTONOMOUS | NOT_RUN | no | — |
| GEMINI-DEVICE-LATENCY-001 | turn_taking | Gemini (gemini-3.8-live) end of speech to first audio heard, on the phone | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | docs/reports/2026-09-30-gemini-native-smoke.md: live API through GeminiLiveClient, L1 9… |
| LISTEN-IDLE-001 | turn_taking | Inactivity releases the microphone and then the socket | AUTONOMOUS | PASS | yes | ListeningLifecycleTest covers STANDBY, DEEP_IDLE, epoch-guarded timers, and meaningless… |
| NOISE-001 | turn_taking | Room noise does not become a turn | AUTONOMOUS | NOT_RUN | yes | 2026-09-20: 20/20 suite |
| PLAYBACK-BUFFER-ADAPT-001 | turn_taking | Playback buffer adapts to the size the platform applied and to real underruns only | AUTONOMOUS | PASS | yes | 2026-09-24: LowLatencyPlaybackBufferTest 7/7 with a clamping fake track (cloud Linux bu… |
| PLAYBACK-BUFFER-DEVICE-001 | turn_taking | Measured playback buffer and underruns on the test phone | AUTONOMOUS | NOT_RUN | no | — |
| PLAYBACK-FINAL-FRAGMENT-001 | turn_taking | Response completion drains the final partial PCM frame | AUTONOMOUS | PASS | yes | PlaybackEpochEngine completion/final-fragment JVM seams |
| PLAYBACK-FLUSH-001 | turn_taking | An acknowledged playback flush cannot write stale audio | AUTONOMOUS | PASS | yes | PlaybackEpochEngine flush/short-write/epoch JVM seams (4/4) |
| PLAYBACK-QUEUE-OVERFLOW-001 | turn_taking | Application PCM queue overflow fails the current reply epoch | AUTONOMOUS | PASS | yes | AppPlaybackQueuePolicy ceiling math and PcmAudioPlayer failReplyLocked path |
| SLEEP-001 | turn_taking | 休眠 reaches SLEEP | AUTONOMOUS | NOT_RUN | no | 2026-09-20: 20/20 suite |
| SPEECH-ARBITER-DEVICE-001 | turn_taking | Arbiter keeps replies and guidance apart on the phone | AUTONOMOUS | NOT_RUN | yes | — |
| SPEECH-ARBITER-UNIT-001 | turn_taking | SpeechArbiter owns every speak and uplink decision | AUTONOMOUS | PASS | yes | 2026-09-25 cloud: :app ran in a stub-iFlytek mirror because vendor Msc.jar is absent in… |
| SPEECH-WORKLOAD-DEVICE-001 | turn_taking | Replies wait out close manoeuvres and never get lost | AUTONOMOUS | NOT_RUN | yes | — |
| UNSUPPORTED-001 | unsupported | A request with no tool is refused, not improvised | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| CAMERA-RELEASE-001 | vision | The camera is released when the app leaves the foreground | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| VISION-001 | vision | A camera question is answered from the camera | AUTONOMOUS | NOT_RUN | yes | STALE_BIND apk_digest mismatch |
| ASTRA-WAKE-CYCLES-001 | wake | Ten ACTIVE to SLEEP to wake microphone handoffs | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | WakeWordController uses listeningState.uploads instead of session isActive |
| WAKE-ARMING-001 | wake | A stale wake event starts nothing; a failed wake start is retried within a bound | AUTONOMOUS | PASS | yes | 2026-09-24: WakeArmingTest 6/6, WakeAudioPathTest source contract (cloud Linux build) |
| WAKE-ENGINE-001 | wake_word | MSC engine accepts app-fed audio | AUTONOMOUS | NOT_RUN | yes | 2026-09-19: 50 s of app-fed PCM, no error (ACCEPTANCE_TESTS.md) |
| WAKE-FALSE-001 | wake_word | No false wakes with music playing | AUTONOMOUS | PASS | yes | 2026-09-20: 0 false wakes in 16 min, 0 errors, 0 restarts, engine alive |
| WAKE-REAL-001 | wake_word | Moving-cabin wake-word recognition | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | WAKE-ENGINE-001 PASS - the engine consumes app-fed audio |
| WAKE-SYNTH-001 | wake_word | Synthesized 你好小诺 wakes the assistant | AUTONOMOUS | NOT_RUN | yes | 2026-09-19 on 2391ff70 |
