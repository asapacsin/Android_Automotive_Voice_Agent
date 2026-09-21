# LOCAL_DEVICE_REQUIRED — consolidated device-test batch

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --local-device`. **Do not edit by hand.**

Every case below **requires a physical Android device** (and usually a real cabin / GPS / human voice). Cloud agents must not block on these: record them here and continue autonomous work.

> Autonomous work may still be open. Prefer finishing cloud-verifiable work first; this file is still the device queue.
>
> - NAV-E2E-ARRIVAL-001 is AUTONOMOUS and FAIL - run it or fix it

**14 LOCAL_DEVICE_REQUIRED item(s).**

Install tip (from a cloud-built APK, when one exists):

```bash
adb install -r "$NOVA_BUILD_DIR/app/outputs/apk/debug/app-debug.apk"
adb logcat -s NovaVoice:D
```

### LOCAL_DEVICE_REQUIRED — WAKE-REAL-001 — Moving-cabin wake-word recognition

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Cabin acoustics - distance, road noise, speaker position - cannot be reproduced by injected audio or by a phone on a desk

**Already established without you:**

- WAKE-ENGINE-001 PASS - the engine consumes app-fed audio
- WAKE-SYNTH-001 PASS - the model matches the phrase
- WAKE-FALSE-001 PASS - 0 false wakes in 16 min of music

**You will need:** a vehicle; the app installed on the head unit or phone; normal driving conditions

**What to do:**

1. sit in the normal driver position
2. drive under ordinary cabin noise
3. say 你好小诺 ten times, spaced out
4. note how many times the assistant wakes
5. note any wake that happened when nobody said it

**It passes if:**

- successful wakes >= 9/10
- false wakes during the drive = 0

**Tell me back:** successes out of 10; false wake count; rough speed and noise conditions

**Still unknown until you do:** detection rate for a human voice at distance over road noise; threshold 1450 of 0-3000 is the vendor default and has never been tuned against real speech

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-DRIVE-001 — A route calculates and guides to arrival

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Route calculation fails with code=3 起点不在支持范围内 from a desk. The Amap SDK serves its own fix and ignores mock providers, measured 2026-09-18 and again 2026-09-19

**Already established without you:**

- NAV-SEARCH-001, NAV-PICK-001, NAV-CANCEL-001, NAV-CORRECT-001 PASS
- the simulation benchmark drives arrival in a simulated world on every build
- the desk failure is code=3 for every destination, control included - not destination-specific

**You will need:** the device outdoors with a real GPS fix; a destination a few minutes away

**What to do:**

1. take the device outside until it has a GPS fix
2. say a nearby destination, pick a candidate, pick a route
3. travel the route to arrival

**It passes if:**

- nav_navigation_started
- guidance is spoken during the route
- arrival returns the app to a clean state

**Tell me back:** did it start; was guidance audible; did arrival leave a clean state

**Still unknown until you do:** route calculation and guidance with a real fix; the guidance mic gate during a real drive

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-UI-002 — During GPS navigation the vehicle stays in lock-car tracking

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Lock-car is a visual judgement on a moving vehicle; the desk GPS origin is rejected (code=3)

**Already established without you:**

- AmapDrivingPresentation.lockCar calls recoverLockMode, CAR_UP_MODE, SHOW_MODE_LOCK_CAR
- 📍 during NAVIGATING calls resumeTracking, not newLatLngZoom

**You will need:** device with a real GPS fix or Amap emulator navi after a successful route

**What to do:**

1. start navigation
2. watch whether the vehicle stays in the lower-middle and the map follows

**It passes if:**

- vehicle stays near lower-middle
- map tracks the vehicle
- not a static overview

**Tell me back:** did the car stay locked; did the map follow

**Still unknown until you do:** whether the SDK honours those options on this device/key

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-UI-003 — Vehicle heading rotates the navigation map

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Heading-up is visible only while the vehicle turns

**Already established without you:**

- setNaviMode(CAR_UP_MODE) is applied on startNavi

**You will need:** NAV-UI-002 setup

**What to do:**

1. change heading during active navigation
2. confirm the map rotates

**It passes if:**

- map bearing follows direction of travel, not frozen north-up

**Tell me back:** did the map rotate with heading

**Still unknown until you do:** on-device camera bearing

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-UI-004 — Active route shows traffic-state colouring when Amap supplies it

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Traffic tiles and colouring need live Amap data and eyes

**Already established without you:**

- setTrafficLine(true), setTrafficStatusUpdateEnabled(true), setTrafficInfoUpdateEnabled(true)

**You will need:** network; a route with mixed traffic if the city has any

**What to do:**

1. start navigation
2. look at the route colour
3. wait for an update

**It passes if:**

- route is not a single static colour when traffic data exists
- nav_traffic_status_update may appear

**Tell me back:** was the route traffic-coloured; did it change during the drive

**Still unknown until you do:** whether this key/region returns traffic on the navi line

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-UI-005 — Next-maneuver guidance is visible

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Maneuver chrome is on-screen only

**Already established without you:**

- setLayoutVisible(driving), setNaviStatusBarEnabled(driving), nav_maneuver log on icon change

**You will need:** NAV-UI-002 setup

**What to do:**

1. start navigation
2. look for next-turn / remaining distance-time

**It passes if:**

- next maneuver visible
- remaining distance or time visible

**Tell me back:** was the next turn visible

**Still unknown until you do:** whether native layout is covered by our overlay on this screen size

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-UI-006 — Lane or junction enlarge appears when Amap supplies it

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Lane/junction views only appear at some intersections

**Already established without you:**

- setLaneInfoShow, setModeCrossDisplayShow, setRealCrossDisplayShow enabled while driving
- NavigationTraceListener logs nav_lane_info / nav_junction

**You will need:** a route that passes a multi-lane junction

**What to do:**

1. drive or emulate through a junction
2. note lane/junction chrome

**It passes if:**

- lane or junction view appears when the SDK has data

**Tell me back:** did a lane or junction view appear; or was there no such intersection

**Still unknown until you do:** this route may not include a junction Amap enlarges

### LOCAL_DEVICE_REQUIRED — NAV-UI-007 — Overview then return restores vehicle tracking

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Overview/lock-car toggle is a visual interaction

**Already established without you:**

- EmbeddedNavigationControllerTest.overviewAndResumeTrackingOnlyWorkWhileNavigating
- 📍 while navigating calls recoverLockMode

**You will need:** NAV-UI-002 setup

**What to do:**

1. enter overview (native 全览 or showOverview)
2. return to navigation (native lock or 📍 / resumeTracking)

**It passes if:**

- overview shows the whole remaining route
- return restores lock-car

**Tell me back:** did overview work; did lock-car return

**Still unknown until you do:** native 全览 button hit-testing under our overlay

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — AUDIO-QUALITY-001 — Guidance loudness and speech quality in a cabin

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Loudness relative to road noise, and whether two voices collide, are judgements only ears make

**Already established without you:**

- 21 guidance play start/end pairs observed on an emulator drive; the mic is gated while guidance speaks
- the guidance mic gate is logged and covered by unit tests

**You will need:** a drive with guidance active

**What to do:**

1. drive a route with guidance on
2. speak to the assistant while guidance is talking
3. note whether guidance is audible, and whether the two voices collide

**It passes if:**

- guidance is intelligible at normal cabin noise
- the assistant's reply is not lost under guidance

**Tell me back:** was guidance audible; did the two voices collide; rough noise conditions

**Still unknown until you do:** absolute loudness and intelligibility in a real cabin

### LOCAL_DEVICE_REQUIRED — MIC-CABIN-001 — Open-mic thresholds in real cabin acoustics

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Road noise, passengers and speed cannot be reproduced from a desk

**Already established without you:**

- NOISE-001 PASS - room noise produces no turn
- the uplink gate and phantom-turn gate are covered by unit tests and logged per segment
- server VAD threshold raised 0.5 to 0.62 after measuring our own playback tripping it

**You will need:** a drive; a passenger talking at some point

**What to do:**

1. drive with the assistant listening
2. hold a normal conversation with a passenger without addressing the assistant
3. then address the assistant normally

**It passes if:**

- passenger conversation does not produce assistant turns
- a normal command is still heard first time

**Tell me back:** spurious turns during conversation; whether commands were heard first time

**Still unknown until you do:** thresholds against road noise and cross-talk at speed

### LOCAL_DEVICE_REQUIRED — ECHO-001 — Post-reply cabin echo does not become 「没听清」

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Cabin echo of the assistant's own voice cannot be reproduced without a real speaker in a room/car

**Already established without you:**

- PLAYBACK_UNGATE_DELAY_MS=1000 and holdPostSpeechEcho(600) in VoiceSessionController / AndroidMicrophonePort
- PhantomTurnGate already drops contentless repairs when there is no user transcript

**You will need:** updated debug APK with 1000 ms ungate + 600 ms echo hold; device with live Baidu session

**What to do:**

1. start a voice session
2. say a destination, pick a route, start navigation so 小诺 speaks a short confirmation
3. do not speak for five seconds after her reply ends
4. watch the subtitle and logcat for a second turn

**It passes if:**

- no second assistant turn that says 没听清 / 再说一遍
- log may show mic_echo_hold_ms=600 after her reply

**Tell me back:** did a phantom 没听清 appear; rough room / speaker volume

**Still unknown until you do:** whether 1.6 s total blackout is enough in this cabin at this volume

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — HELP-001 — 「你能做什么」 gets a short capability answer

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Live model wording and Mandarin ASR of the help phrase need a human voice

**Already established without you:**

- ActionClaimGuardTest.aHelpQuestionThatIsMisheardGetsACapabilityNudge
- HELP_CAPABILITY_NUDGE forces a capability summary when the first reply refuses

**You will need:** updated debug APK; live Baidu session

**What to do:**

1. start a session
2. say 「你能做什么」 or 「有什么功能」
3. listen for a short list of real capabilities

**It passes if:**

- reply names at least navigation and one other real capability
- reply is not 没听清 / 不理解

**Tell me back:** exact reply heard; whether a second corrected turn was needed

**Still unknown until you do:** whether the live model answers correctly on the first turn without needing the nudge

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — NAV-SIM-QA-001 — Owner wants a reusable navigation screen-recording movie for QA

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Needs a connected phone for embedded nav + screenrecord; large mp4 stays out of git

**Already established without you:**

- 2026-09-21: nav_qa_to_shizimen.mp4 (~45s, 1220x2712) pulled to D:\桌面\android_doc\
- PNGs: destination list for 十字门 + route picker (推荐 4.8km/10min)
- navigate DEBUG_TOOL Accepted; nav_resolve_candidates count=5
- SimulatedNavigationWorld + evaluation Level A remain code gates
- screenrecord needs display ON (INVALID_LAYER_STACK when OFF)

**You will need:** test phone on ADB; embedded Amap can open a route; D:\桌面\android_doc\ writable

**What to do:**

1. start Nova Drive embedded navigation (preferred destination context: 十字門)
2. adb shell screenrecord /sdcard/nav_qa_to_shizimen.mp4 (or device recorder)
3. adb pull to D:\桌面\android_doc\nav_qa_to_shizimen.mp4
4. owner watches the movie in that folder

**It passes if:**

- mp4 exists under D:\桌面\android_doc\ and shows navigation UI

**Tell me back:** owner watched? pass/fail / re-record needed

**Still unknown until you do:** whether this clip is enough for repeat QA cadence vs needing active guidance / arrival footage

### LOCAL_DEVICE_REQUIRED — VOICE-STYLE-001 — Prefer a younger cute female voice (符玄-like)

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Ear check that Flex voice 4196 (now product default) sounds youthful/cute enough

**Already established without you:**

- Owner chose option B id 4196 on 2026-09-21
- BaiduFlexVoices.PREFERRED_YOUTHFUL_FEMALE=4196 is product DEFAULT_VOICE
- Developer settings spinner + free-text voice id
- BaiduFlexClient records confirmedVoice from session.updated; falls back to default if rejected
- Unit: BaiduFlexVoicesTest, BaiduFlexClientTest sessionUpdatedRecordsConfirmedVoiceMatchingRequest

**Measured, so this is a choice and not a question:**

- the app falls back to voice id default when a non-default id is refused
- AUTO sample rate maps Flex to 24 kHz; pitch perception is ear-only

**Options:**

- A. Keep Flex default (superseded).
- B. 4196 度清影-甜美女声 — CHOSEN 2026-09-21.
- C. Stay on default for release (superseded).

**You will need:** APK with DEFAULT_VOICE=4196; live Flex session

**What to do:**

1. Save/start voice session (default should be 4196)
2. Test Connection should show voice requested=4196 and voice confirmed=4196
3. Speak a short turn and listen

**It passes if:**

- session.updated confirms 4196
- owner accepts timbre (or names another catalog id)

**Tell me back:** pass/fail on timbre; alternate id if reject

**Still unknown until you do:** whether 4196 sounds youthful/cute enough in a real cabin vs needing another catalog id

---

Return every result together. Non-device human items remain in [HUMAN_VALIDATION.md](HUMAN_VALIDATION.md).
