# LOCAL_DEVICE_REQUIRED — consolidated device-test batch

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --local-device`. **Do not edit by hand.**

Every case below **requires a physical Android device** (and usually a real cabin / GPS / human voice). Cloud agents must not block on these: record them here and continue autonomous work.

Autonomous cloud work for the current frontier is settled. Run this batch locally in one sitting.

**10 LOCAL_DEVICE_REQUIRED item(s).**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

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

**Exact procedure:**

1. drive with the assistant listening
2. hold a normal conversation with a passenger without addressing the assistant
3. then address the assistant normally

**It passes if:**

- passenger conversation does not produce assistant turns
- a normal command is still heard first time

**Tell me back:** spurious turns during conversation; whether commands were heard first time

**Still unknown until you do:** thresholds against road noise and cross-talk at speed

---

Return every result together. Non-device human items remain in [HUMAN_VALIDATION.md](HUMAN_VALIDATION.md).
