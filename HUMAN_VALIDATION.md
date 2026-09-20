# Human validation packet

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --packet`. **Do not edit by hand.**

Everything an agent could do has been done. What follows is the whole of what needs a person — **in one batch, to be handled in one sitting**, rather than one interruption per test.

8 item(s) queued.

## A. Physical tests

### WAKE-REAL-001 — Moving-cabin wake-word recognition

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

### NAV-DRIVE-001 — A route calculates and guides to arrival

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

### AUDIO-QUALITY-001 — Guidance loudness and speech quality in a cabin

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

### MIC-CABIN-001 — Open-mic thresholds in real cabin acoustics

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

## B. Account and real-service tests

### CALL-REAL-001 — A confirmed call reaches a real handset

**Why this needs you.** Needs a SIM with service and a number whose owner agrees this app may ring it. An agent must not dial a real person

**Already established without you:**

- CALL-CONFIRM-001, CALL-AMBIG-001, CALL-PRIVACY-001 PASS
- CALL-NOSIM-001 PASS - the no-telephony refusal is honest
- no call has been placed by this project

**You will need:** an Android device with an active SIM; a contact saved on it; consent from the number's owner

**What to do:**

1. install the build on a phone with a SIM
2. grant contacts and phone permissions
3. say 打电话给<name>
4. confirm when the assistant asks
5. observe whether the call is placed to the right person
6. repeat, and this time say no at the confirmation

**It passes if:**

- on confirmation, the correct number is dialled exactly once
- on refusal, nothing is dialled

**Tell me back:** did it ring the right person; did refusing dial anything; any system prompt that appeared

**Still unknown until you do:** ACTION_CALL on a real SIM, including any system confirmation MIUI adds; whether the dialled number matches the resolved contact

*Release-blocking.*

## C. Required credentials

### RELEASE-SIGN-001 — A signed release build installs and works

**Why this needs you.** Signing needs a keystore and its passwords. Those are the owner's credentials and must never enter this repository (I-7). An agent must not create a production signing key

**Already established without you:**

- RELEASE-BUILD-001 PASS - the release variant compiles and packages
- EXPORTED-001 and RELEASE-LOG-001 cover the release-only risks that can be checked statically

**You will need:** a keystore; its passwords, supplied outside the repository

**What to do:**

1. configure signing outside version control (Gradle properties in a local file, or an environment variable)
2. build the signed release
3. install it on the device
4. run the scenario suite against it

**It passes if:**

- the signed APK installs
- the scenario suite passes against the release variant
- no debug receiver responds

**Tell me back:** did it install; scenario suite result against the release build

**Still unknown until you do:** release-only runtime behaviour, especially anything reflection-based in the three vendor SDKs; whether enabling R8 is safe - it can only be judged against an installed signed build

*Release-blocking.*

## D. Product decisions

### YUE-POLICY-001 — Is Cantonese an advertised capability?

**Why this needs you.** Three options that are not comparable on technical grounds. The cost and the promise to users are the owner's to weigh

**Measured, so this is a choice and not a question:**

- the transcriber rejects any language but zh: `Invalid value: 'yue'. Value must be null or 'zh'` - the vendor's own words
- after a persona sentence: 返屋企 4/5, 有啲熱 1/5, style-of-song refusal 5/5
- Mandarin unaffected: 20/20
- the implicit-comfort phrasing has no keyword for a Mandarin transcriber to anchor on; no further wording will move it
- option C means a new provider integration; the provider-neutral contract (SPEC-007) exists, so it is an adapter, not a rewrite

**Options:**

- A. Mandarin-only. Record it in ADR-002 and the capability registry, and stop measuring Cantonese.
- B. Keep the current partial support and publish the measured rates as the expected behaviour.
- C. Reopen ADR-008 and evaluate a provider whose transcriber accepts Cantonese.

**What to do:**

1. choose one of the options below and say which

**It passes if:**

- ADR-002 and config/capabilities.yaml record the decision, so nothing downstream claims otherwise

**Tell me back:** which option, and whether the registry should say Mandarin-only

*Release-blocking.*

### ABI-POLICY-001 — Must armeabi-v7a keep working?

**Why this needs you.** Dropping an ABI is a compatibility promise, not an engineering trade-off. Which head units must install this is the owner's call

**Measured, so this is a choice and not a question:**

- see ABI-SIZE-001 - filled in once it runs

**Options:**

- A. arm64-only. Smallest artifact; no 32-bit device can install it.
- B. ABI splits or an App Bundle. Nobody loses support and each artifact is smaller; needs a channel that supports split delivery.
- C. Keep the universal APK. Simplest to distribute, largest download.

**You will need:** ABI-SIZE-001 measured

**What to do:**

1. choose one of the options below

**It passes if:**

- the choice is recorded in build.gradle.kts and B-019

**Tell me back:** which option, and the minimum head-unit generation that must be supported

---

When you have results for any of these, give me all of them at once. They will be applied together, every resulting failure triaged together, and every fix that follows made in one autonomous cycle before anything is asked of you again ([harness/PHASES.md](harness/PHASES.md)).
