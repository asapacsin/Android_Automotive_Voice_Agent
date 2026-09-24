# LOCAL_DEVICE_REQUIRED — consolidated device-test batch

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --local-device`. **Do not edit by hand.**

Every case below **requires a physical Android device** (and usually a real cabin / GPS / human voice). Cloud agents must not block on these: record them here and continue autonomous work.

> Autonomous work may still be open. Prefer finishing cloud-verifiable work first; this file is still the device queue.
>
> - WAKE-ENGINE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - WAKE-SYNTH-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-MISHEARD-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-MEDIA-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-WEATHER-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-BAIT-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - PLACE-SAVE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - PLACE-NAV-001 is AUTONOMOUS and NOT_RUN - run it or fix it

**11 LOCAL_DEVICE_REQUIRED item(s).**

Install tip (from a cloud-built APK, when one exists):

```bash
adb install -r "$NOVA_BUILD_DIR/app/outputs/apk/debug/app-debug.apk"
adb logcat -s NovaVoice:D
```

### LOCAL_DEVICE_REQUIRED — WAKE-REAL-001 — Moving-cabin wake-word recognition

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Cabin acoustics - distance, road noise, speaker position - cannot be reproduced by injected audio or by a phone on a desk

**Automation blocker:** `physical_world`

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

**Why this needs you.** Live GNSS origin: route calculation fails with code=3 起点不在支持范围内 from a desk. The Amap SDK serves its own fix and ignores mock providers, measured 2026-09-18 and again 2026-09-19

**Automation blocker:** `physical_world`

**Already established without you:**

- NAV-SEARCH-001, NAV-PICK-001, NAV-CANCEL-001, NAV-CORRECT-001 PASS
- the simulation benchmark drives arrival in a simulated world on every build
- NAV-E2E-ARRIVAL-001 PASS 2026-09-21: emulator drive to arrival via nav_proximity_arrival / nav_stopped reason=emulator_end; video D:/桌面/android_doc/nav_e2e_arrival_2026-09-21/nav_e2e_arrival.mp4
- Desk null-start code=3 mitigated for debug E2E by nav_desk_origin; live outdoor GNSS still required for real-GPS arrival

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

**Still unknown until you do:** route calculation and guidance with a real live GNSS fix (not desk_origin); the guidance mic gate during a real outdoor drive

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — AUDIO-QUALITY-001 — Guidance loudness and speech quality in a cabin

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Loudness relative to road noise, and whether two voices collide, are judgements only ears make

**Automation blocker:** `subjective_perception`

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

**Automation blocker:** `physical_world`

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

### LOCAL_DEVICE_REQUIRED — VOICE-STYLE-001 — Prefer a younger cute female voice (符玄-like)

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Ear check that Flex voice 4196 (now product default) sounds youthful/cute enough

**Automation blocker:** `subjective_perception`

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

1. listen to a short Flex turn on the installed build
2. accept the timbre or name another catalog id

**It passes if:**

- owner accepts timbre (or names another catalog id)

**Tell me back:** pass/fail on timbre; alternate id if reject

**Still unknown until you do:** whether 4196 sounds youthful/cute enough in a real cabin vs needing another catalog id

### LOCAL_DEVICE_REQUIRED — ASTRA-ECHO-PLAYBACK-001 — Playback-only cabin echo does not self-trigger replies

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Playback-only echo and cabin acoustics cannot be certified from JVM tests or a desk

**Automation blocker:** `physical_world`

**Already established without you:**

- qualified barge-in uses uplinkGateOpen instead of RMS ratio veto
- AEC-FRAME-CONTINUITY-001 and PLAYBACK-FLUSH-001 JVM seams passed 2026-09-24

**You will need:** installed debug APK from this commit; vehicle or bench with cabin speakers

**What to do:**

1. play a Flex reply with no driver speech for the full reply and tail
2. observe whether a new assistant turn starts without a wake word or command

**It passes if:**

- zero self-triggered audible reply chains in the playback-only trial
- driver speech still interrupts when spoken after playback starts

**Tell me back:** any self-triggered reply after playback-only silence; whether genuine speech still interrupts

**Still unknown until you do:** cabin echo path, route and volume combinations

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — ASTRA-DOUBLE-TALK-001 — Real double-talk and route-change acoustics

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Near-end-only and real double-talk need the vehicle microphone and speakers

**Automation blocker:** `physical_world`

**Already established without you:**

- SpeechUplinkGate + qualifyPlayoutBargeIn uplinkGateOpen wiring

**You will need:** installed debug APK; moving or idling cabin

**What to do:**

1. during assistant playback, speak a short command at normal volume
2. repeat after a Bluetooth or volume route change if available

**It passes if:**

- genuine speech interrupts playback and reaches a successful next command
- echo-only energy does not open an audible turn

**Tell me back:** did genuine speech interrupt; did echo-only energy stay silent; audio route used

**Still unknown until you do:** route-change tail and hardware-buffer delay

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — ASTRA-WAKE-CYCLES-001 — Ten ACTIVE to SLEEP to wake microphone handoffs

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Real wake-word recognition and microphone ownership need the device microphone

**Automation blocker:** `physical_world`

**Already established without you:**

- WakeWordController uses listeningState.uploads instead of session isActive
- WakeWordController.reconcile on lifecycle transitions

**You will need:** wake enabled; installed debug APK

**What to do:**

1. repeat ten cycles: ACTIVE conversation, sleep command, wake phrase
2. include at least one DEEP_IDLE recovery cycle

**It passes if:**

- wake succeeds after each SLEEP without manual app restart
- no simultaneous recorders or missing wake capture in SLEEP

**Tell me back:** successful wake count of ten; any cycle that needed manual restart; DEEP_IDLE recovery result

**Still unknown until you do:** cabin noise and MSC false accepts

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — ASTRA-DEMO-REHEARSAL-001 — No-touch demo rehearsal — wake search choice route

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Demo rehearsals require live Flex, map, and cabin audio

**Automation blocker:** `physical_world`

**Already established without you:**

- NavigationLocalPickGuard turn/list authority + NavigationPickSession executor results

**You will need:** installed debug APK; network; map entitlement

**What to do:**

1. wake, search a destination, pick from the list by voice, start a route
2. do not touch the screen

**It passes if:**

- correct destination and route selected once
- no duplicate navigate_to after local pick

**Tell me back:** destination and route reached; whether any touch was required; duplicate tool calls observed

**Still unknown until you do:** live map entitlement and cabin microphone quality

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — ASTRA-DEMO-REHEARSAL-002 — No-touch demo rehearsal — climate confirmation and stop-output

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Audible confirmation and stop-output timing are ear checks

**Automation blocker:** `physical_world`

**Already established without you:**

- ListeningLifecycle SILENT_WAIT path unchanged

**You will need:** installed debug APK

**What to do:**

1. request a climate change, confirm when prompted, then say stop-output
2. issue a follow-up command without wake

**It passes if:**

- climate action confirmed once
- stop-output reaches SILENT_WAIT and the follow-up command succeeds

**Tell me back:** climate confirmation heard once; listening state after stop-output; follow-up command result

**Still unknown until you do:** audible stop-output latency in cabin

*Release-blocking.*

### LOCAL_DEVICE_REQUIRED — ASTRA-DEMO-REHEARSAL-003 — No-touch demo rehearsal — replacement search after ambiguous pick

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Phonetic and ambiguous navigation picks need live speech in cabin

**Automation blocker:** `physical_world`

**Already established without you:**

- NavigationLocalPickGuard AMBIGUOUS/NO_MATCH/SELECTED outcomes
- NavigationPhoneticConfirmation API-29+ proposal path

**You will need:** installed debug APK; ambiguous POI list on screen

**What to do:**

1. open a multi-candidate list, speak an ambiguous name, then replace with a new destination

**It passes if:**

- ambiguous utterance keeps the list and asks for ordinal or confirmation
- explicit new destination replaces the pending choice

**Tell me back:** whether the list stayed open after ambiguity; replacement destination result

**Still unknown until you do:** phonetic confusion cases beyond exact-name matching

*Release-blocking.*

---

Return every result together. Non-device human items remain in [HUMAN_VALIDATION.md](HUMAN_VALIDATION.md).
