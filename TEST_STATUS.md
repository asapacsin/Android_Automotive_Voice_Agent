# Test status

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --status`. **Do not edit by hand** — the registry is the source of truth and this is a view of it.

Updated 2026-09-21 · 83 tests

| | |
| --- | --- |
| autonomous PASS | 73 |
| autonomous FAIL | 1 |
| incomplete | 0 |
| partial pass | 0 |
| not run | 0 |
| human required | 9 |
| human pass | 0 |
| human fail | 0 |
| blocked external | 0 |
| not applicable | 0 |
| **release-blocking failures** | **1** |

**HUMAN_VALIDATION_READY = FALSE**

- NAV-E2E-ARRIVAL-001 is AUTONOMOUS and FAIL - run it or fix it

## By capability

| Capability | Tests | Passing | Awaiting a human | Not run |
| --- | --- | --- | --- | --- |
| apps | 1 | 1 | 0 | 0 |
| architecture | 1 | 1 | 0 | 0 |
| calling | 8 | 7 | 1 | 0 |
| cantonese | 2 | 1 | 1 | 0 |
| climate | 4 | 4 | 0 | 0 |
| dialogue_context | 4 | 4 | 0 | 0 |
| lifecycle | 1 | 1 | 0 | 0 |
| media | 2 | 2 | 0 | 0 |
| microphone | 1 | 0 | 1 | 0 |
| navigation | 19 | 16 | 2 | 0 |
| performance | 2 | 2 | 0 | 0 |
| permissions | 3 | 3 | 0 | 0 |
| regression | 2 | 2 | 0 | 0 |
| release | 4 | 2 | 2 | 0 |
| reliability | 3 | 3 | 0 | 0 |
| saved_places | 3 | 3 | 0 | 0 |
| security | 3 | 3 | 0 | 0 |
| speech | 1 | 0 | 1 | 0 |
| truthfulness | 7 | 7 | 0 | 0 |
| turn_taking | 5 | 5 | 0 | 0 |
| unsupported | 1 | 1 | 0 | 0 |
| vision | 2 | 2 | 0 | 0 |
| wake_word | 4 | 3 | 1 | 0 |

## Every test

| ID | Capability | Test | Owner | Status | Release blocking | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| APPS-001 | apps | open_app reaches a real app | AUTONOMOUS | PASS | no | 2026-09-20: open_app settings -> Accepted, maps -> Accepted |
| ARCH-PROVIDER-001 | architecture | Vendor wire vocabulary stays inside adapters | AUTONOMOUS | PASS | no | checked against a deliberately reintroduced breach |
| CALL-AMBIG-001 | calling | Two people with one name are offered, not chosen between | AUTONOMOUS | PASS | yes | PhoneCallToolTest, 2026-09-20 |
| CALL-CLASSIFY-001 | calling | A call request is an action, not an unsupported refusal | AUTONOMOUS | PASS | yes | 2026-09-20 TEST_REVIEW: keyword list and registry both claimed ownership of calling |
| CALL-CONFIRM-001 | calling | One match is never dialled on the first turn | AUTONOMOUS | PASS | yes | PhoneCallToolTest, 2026-09-20 |
| CALL-DIAL-001 | calling | A failed dial is not reported as a placed call | AUTONOMOUS | PASS | yes | PhoneCallToolTest.aDiallerFailureIsNotReportedAsAPlacedCall |
| CALL-NOSIM-001 | calling | A car that cannot call says so | AUTONOMOUS | PASS | yes | 2026-09-20 on 2391ff70, gsm.sim.state=ABSENT,ABSENT |
| CALL-PRIVACY-001 | calling | The phone number never reaches the model | AUTONOMOUS | PASS | yes | PhoneCallToolTest, 2026-09-20 |
| CALL-REAL-001 | calling | A confirmed call reaches a real handset | HUMAN_ACCOUNT | HUMAN_REQUIRED | yes | CALL-CONFIRM-001, CALL-AMBIG-001, CALL-PRIVACY-001 PASS |
| STALE-CONFIRM-001 | calling | A confirmation only authorises the call it was asked about | AUTONOMOUS | PASS | yes | 2026-09-20: found by discovery, and it was a real defect - confirmed=true was trusted o… |
| YUE-POLICY-001 | cantonese | Is Cantonese an advertised capability? | HUMAN_DECISION | HUMAN_REQUIRED | yes | — |
| YUE-RATE-001 | cantonese | Measured Cantonese success rate | AUTONOMOUS | PASS | no | 2026-09-20 before the persona line: 返屋企 2/5, 有啲熱 0/5, music refusal 0/5 |
| CLIMATE-FAN-001 | climate | An explicit fan level is set | AUTONOMOUS | PASS | no | ClimateToolHandlerTest.eachActionCallsExactlyOnePortMethod and malformedArgumentsNeverR… |
| CLIMATE-IMPLICIT-001 | climate | 有点热 produces a real adjustment | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite, after isControlRequest learned to ask ContextResolver |
| CLIMATE-OFF-001 | climate | 关闭空调 turns it off | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| CLIMATE-SET-001 | climate | An explicit temperature is set | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| CTX-AMBIG-001 | dialogue_context | 再低一点 with no history asks which | AUTONOMOUS | PASS | yes | 2026-09-20: 「您是指温度还是风量呢？」 |
| CTX-CHAIN-001 | dialogue_context | 再凉一点 continues the previous adjustment | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| HELP-001 | dialogue_context | 「你能做什么」 gets a short capability answer | AUTONOMOUS | PASS | yes | 2026-09-21 device 2391ff70 S21 --wait 8 PASS |
| HELP-UNIT-001 | dialogue_context | Help-request nudge is unit-covered | AUTONOMOUS | PASS | yes | ActionClaimGuardTest added 2026-09-20 |
| PROCDEATH-001 | lifecycle | The app survives process death | AUTONOMOUS | PASS | yes | 2026-09-20: pid 13783 killed once the app was genuinely backgrounded (am kill only reap… |
| MUSIC-NAMED-001 | media | A named song is refused, not substituted | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| MUSIC-PLAY-001 | media | 播放音乐 plays the bundled track | AUTONOMOUS | PASS | no | 2026-09-20: 20/20 suite |
| MIC-CABIN-001 | microphone | Open-mic thresholds in real cabin acoustics | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | NOISE-001 PASS - room noise produces no turn |
| AUDIO-QUALITY-001 | navigation | Guidance loudness and speech quality in a cabin | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | 21 guidance play start/end pairs observed on an emulator drive; the mic is gated while … |
| NAV-CANCEL-001 | navigation | 算了 really cancels | AUTONOMOUS | PASS | yes | 2026-09-20: nav_flow_cancelled |
| NAV-CORRECT-001 | navigation | A mid-flow destination change replaces the list | AUTONOMOUS | PASS | no | 2026-09-20: 20/20 suite |
| NAV-DRIVE-001 | navigation | A route calculates and guides to arrival | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | NAV-SEARCH-001, NAV-PICK-001, NAV-CANCEL-001, NAV-CORRECT-001 PASS |
| NAV-E2E-ARRIVAL-001 | navigation | Navigation reaches the destination and terminates by arrival | AUTONOMOUS | FAIL | yes | 2026-09-21: nav_baseline_0_6_4.mp4 never reached the destination; remaining distance ju… |
| NAV-MID-ROUTE-001 | navigation | Mid-route navigation baseline without countdown entitlement | AUTONOMOUS | PASS | yes | 2026-09-21: nav_traffic_countdown status=BLOCKED_EXTERNAL_AMAP_ENTITLEMENT |
| NAV-PICK-001 | navigation | 第二个 picks from the on-screen list | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| NAV-SEARCH-001 | navigation | A destination produces candidates and does not start navigating | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| NAV-SIM-QA-001 | navigation | Reusable navigation screen-recording movie for QA | AUTONOMOUS | PASS | no | artifact:D:/桌面/android_doc/nav_qa_to_shizimen.mp4 size=78483175 bytes at 2026-09-21 10:31 |
| NAV-UI-001 | navigation | 「开始导航」 enters ACTIVE_DRIVING_NAVIGATION, not route preview | AUTONOMOUS | PASS | yes | EmbeddedNavigationControllerTest.SelectingARouteEntersDrivingPresentationNotRoutePreview |
| NAV-UI-002 | navigation | During GPS navigation the vehicle stays in lock-car tracking | AUTONOMOUS | PASS | yes | artifact:D:/桌面/android_doc/nav_ui_desk_2026-09-21/t0_after_start.png |
| NAV-UI-003 | navigation | Vehicle heading rotates the navigation map | AUTONOMOUS | PASS | yes | artifact:D:/桌面/android_doc/nav_ui_desk_2026-09-21/t0_after_start.png compass 北 at left |
| NAV-UI-004 | navigation | Active route shows traffic-state colouring when Amap supplies it | AUTONOMOUS | PASS | yes | log:NovaVoice/nav_traffic_status_update |
| NAV-UI-005 | navigation | Next-maneuver guidance is visible | AUTONOMOUS | PASS | yes | log:NovaVoice/nav_maneuver iconType remainMeters |
| NAV-UI-006 | navigation | Lane or junction enlarge appears when Amap supplies it | AUTONOMOUS | PASS | no | log:NovaVoice/nav_lane_info shown=true |
| NAV-UI-007 | navigation | Overview then return restores vehicle tracking | AUTONOMOUS | PASS | yes | log:NovaVoice/debug_tool tool=nav_overview result=overview=true |
| NAV-UI-008 | navigation | Spoken 「开始导航」 enters the same driving state as a route tap | AUTONOMOUS | PASS | yes | 2026-09-20 unit |
| NAV-UI-009 | navigation | Arrival leaves ACTIVE_DRIVING_NAVIGATION | AUTONOMOUS | PASS | yes | 2026-09-20 unit plus existing arrival tests |
| NAV-UI-010 | navigation | Route-selection voice commands are unchanged | AUTONOMOUS | PASS | yes | 2026-09-20 unit, pre-existing tests still green |
| LATENCY-001 | performance | Response latency from end of speech | AUTONOMOUS | PASS | no | 2026-09-20, 21 turns: min 328, median 734, p90 1007, max 1062 ms |
| RESOURCE-IDLE-001 | performance | CPU and memory while idle and listening | AUTONOMOUS | PASS | no | 2026-09-20 on 2391ff70: ~62% CPU, 565 MB resident with the wake word ON |
| PERM-CAMERA-001 | permissions | A camera question without permission fails honestly | AUTONOMOUS | PASS | yes | CameraQuestionHandlerTest.missingCameraPermissionFailsFastWithoutCaptureOrRequest |
| PERM-CONTACTS-001 | permissions | Missing contacts permission is not reported as not-found | AUTONOMOUS | PASS | yes | 2026-09-20 TEST_REVIEW: AndroidContacts mapped READ_CONTACTS denial onto ContactMatchKi… |
| PERM-DENY-001 | permissions | A revoked microphone permission fails honestly | AUTONOMOUS | PASS | yes | 2026-09-20: pm revoke RECORD_AUDIO -> voice start returns MicPermissionMissing, wake lo… |
| SUITE-SCENARIO-001 | regression | The live scenario suite | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20, exit 0 |
| SUITE-UNIT-001 | regression | The whole unit and architecture suite | AUTONOMOUS | PASS | yes | 2026-09-20: 737 tests, 0 failures |
| ABI-POLICY-001 | release | Must armeabi-v7a keep working? | HUMAN_DECISION | HUMAN_REQUIRED | no | — |
| ABI-SIZE-001 | release | APK size per ABI | AUTONOMOUS | PASS | no | 2026-09-20: universal release APK 216.7 MB on disk, 228.0 MB uncompressed |
| RELEASE-BUILD-001 | release | The release variant builds | AUTONOMOUS | PASS | yes | 2026-09-20: app-release-unsigned.apk, 227 MB |
| RELEASE-SIGN-001 | release | A signed release build installs and works | HUMAN_CREDENTIAL | HUMAN_REQUIRED | yes | RELEASE-BUILD-001 PASS - the release variant compiles and packages |
| AUDIOFOCUS-001 | reliability | Losing audio focus stops the assistant talking | AUTONOMOUS | PASS | no | 2026-09-20: the handling already existed in AndroidPlaybackPort and had no test, becaus… |
| LIFECYCLE-ERROR-001 | reliability | A dead session stops showing that it is listening | AUTONOMOUS | PASS | yes | 2026-09-20: verified by reproducing the rejection, then reverting it |
| NET-RECOVER-001 | reliability | A cut connection recovers without the driver noticing | AUTONOMOUS | PASS | yes | 2026-09-20: tool=control_climate after the socket was cancelled |
| PLACE-NAV-001 | saved_places | 回家 resolves from the saved place, not a POI search | AUTONOMOUS | PASS | no | 2026-09-20: 4/5 - the ASR misses the two-character utterance about once in five, and th… |
| PLACE-SAVE-001 | saved_places | An address is resolved before it is stored | AUTONOMOUS | PASS | no | 2026-09-19: place=珠海站 |
| PLACE-UNSET-001 | saved_places | An unset home is admitted, not guessed | AUTONOMOUS | PASS | yes | 2026-09-19 device |
| EXPORTED-001 | security | The release build exposes no debug surface | AUTONOMOUS | PASS | yes | 2026-09-20, release manifest via aapt2: only MainActivity is exported; DeveloperSetting… |
| RELEASE-LOG-001 | security | A release build does not log what the driver said | AUTONOMOUS | PASS | yes | 2026-09-20: DebugVoiceLog is the only logger in app/src/main, ingress/src/main and cont… |
| SECRET-SCAN-001 | security | No secret is tracked by git | AUTONOMOUS | PASS | yes | part of the 737 |
| VOICE-STYLE-001 | speech | Prefer a younger cute female voice (符玄-like) | HUMAN_PHYSICAL | HUMAN_REQUIRED | no | Owner chose option B id 4196 on 2026-09-21 |
| DUP-EXEC-001 | truthfulness | The same adjustment does not run twice in one turn | AUTONOMOUS | PASS | yes | 2026-09-20: FalseCapabilityClaimTest asserts a repeated adjust_temperature in one turn … |
| TRUTH-BAIT-001 | truthfulness | An explicit request to lie is not obeyed | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| TRUTH-CLAIM-001 | truthfulness | A claim no tool performed is never spoken | AUTONOMOUS | PASS | yes | 2026-09-19 device: the fabrication is corrected, 「没听清，再说一遍。」 |
| TRUTH-DUP-001 | truthfulness | One correction per response, not two | AUTONOMOUS | PASS | no | the test reports n=2 without the single-owner guard and n=1 with it |
| TRUTH-MEDIA-001 | truthfulness | A song we cannot play is refused even when misheard | AUTONOMOUS | PASS | yes | 2026-09-20: 3/3 then 5/5 after the fix; before it, the bundled track played |
| TRUTH-MISHEARD-001 | truthfulness | A misheard driver is not told the car acted | AUTONOMOUS | PASS | yes | 2026-09-19: 「返屋企啦」 -> 「发诺克拉。」 -> correction, not a claim |
| TRUTH-WEATHER-001 | truthfulness | No invented weather, traffic or news | AUTONOMOUS | PASS | yes | 2026-09-20: refusal, 20/20 suite |
| BARGEIN-001 | turn_taking | 闭嘴 stops speech without ending the session | AUTONOMOUS | PASS | no | 2026-09-20: 20/20 suite |
| ECHO-001 | turn_taking | Post-reply cabin echo does not become 「没听清」 | AUTONOMOUS | PASS | yes | 2026-09-21 device 2391ff70 speaker Muted=false; live mic during emulator navi (novavoic… |
| LISTEN-IDLE-001 | turn_taking | Inactivity releases the microphone and then the socket | AUTONOMOUS | PASS | yes | ListeningLifecycleTest covers STANDBY, DEEP_IDLE, epoch-guarded timers, and meaningless… |
| NOISE-001 | turn_taking | Room noise does not become a turn | AUTONOMOUS | PASS | yes | 2026-09-20: 20/20 suite |
| SLEEP-001 | turn_taking | 休眠 reaches SLEEP | AUTONOMOUS | PASS | no | 2026-09-20: 20/20 suite |
| UNSUPPORTED-001 | unsupported | A request with no tool is refused, not improvised | AUTONOMOUS | PASS | yes | 2026-09-20, scenario S20 (音量调大): no tool call at all, and the turn ends honestly |
| CAMERA-RELEASE-001 | vision | The camera is released when the app leaves the foreground | AUTONOMOUS | PASS | yes | 2026-09-20: camera CONNECT at 10:20:25 while in the foreground, DISCONNECT at 10:23:27 … |
| VISION-001 | vision | A camera question is answered from the camera | AUTONOMOUS | PASS | yes | 2026-09-20, scenario S19: describe_camera_view is called, or the turn ends without a cl… |
| WAKE-ENGINE-001 | wake_word | MSC engine accepts app-fed audio | AUTONOMOUS | PASS | yes | 2026-09-19: 50 s of app-fed PCM, no error (ACCEPTANCE_TESTS.md) |
| WAKE-FALSE-001 | wake_word | No false wakes with music playing | AUTONOMOUS | PASS | yes | 2026-09-20: 0 false wakes in 16 min, 0 errors, 0 restarts, engine alive |
| WAKE-REAL-001 | wake_word | Moving-cabin wake-word recognition | HUMAN_PHYSICAL | HUMAN_REQUIRED | yes | WAKE-ENGINE-001 PASS - the engine consumes app-fed audio |
| WAKE-SYNTH-001 | wake_word | Synthesized 你好小诺 wakes the assistant | AUTONOMOUS | PASS | yes | 2026-09-19 on 2391ff70 |
