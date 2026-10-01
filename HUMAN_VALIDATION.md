# Human validation packet

Generated from [TEST_MATRIX.yaml](TEST_MATRIX.yaml) by `python scripts/test_matrix.py --packet`. **Do not edit by hand.**

> **Not ready yet.** This is a preview of the queue; autonomous work remains:
>
> - WAKE-ENGINE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - WAKE-SYNTH-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-MISHEARD-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-MEDIA-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-WEATHER-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - TRUTH-BAIT-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - PLACE-SAVE-001 is AUTONOMOUS and NOT_RUN - run it or fix it
> - PLACE-NAV-001 is AUTONOMOUS and NOT_RUN - run it or fix it

21 item(s) queued.

Physical-device cases are also collected in [LOCAL_DEVICE_REQUIRED.md](LOCAL_DEVICE_REQUIRED.md).

## A. Physical tests (LOCAL_DEVICE_REQUIRED)

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

### LOCAL_DEVICE_REQUIRED — NAV-PHONETIC-DEVICE-001 — Misheard destination name is confirmed by voice on the device

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Needs live Baidu ASR on real speech and the device's ICU data; injected text cannot reproduce the mishearing

**Automation blocker:** `physical_world`

**Already established without you:**

- NAV-CHOICE-STALE-001 and NAV-PHONETIC-CONFIRM-001 passed 2026-09-24

**You will need:** installed debug APK from this commit; Amap key authorised for its signature; a search that lists several similar names

**What to do:**

1. search a destination that returns several candidates
2. say a listed name slightly wrong (a near-homophone)
3. answer 对 to the question
4. repeat once answering 不是, and once letting the list sit over two minutes before saying 第二个

**It passes if:**

- one question naming a row, no navigation before the answer
- 对 selects that row exactly once; 不是 selects nothing
- an ordinal after two minutes is answered by re-reading the options first

**Tell me back:** question wording heard; selection count after 对; behaviour after 不是 and after two minutes

**Still unknown until you do:** how often real mishearings clear the 0.55 similarity threshold

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
- BARGE-IN-EVIDENCE-001 (time-scoped evidence, echo-candidate hold) passed 2026-09-24

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
- BARGE-IN-EVIDENCE-001 (time-scoped evidence, echo-candidate hold) passed 2026-09-24

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
- WAKE-ARMING-001 (stale-event guard, bounded capture/engine retry) passed 2026-09-24

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
- P32: suppressed navigate_to reports destination_selected + route list; ActionClaimGuard corrects a navigation-started claim without navigation_started evidence (ActionClaimGuardTest, AndroidToolDispatcherTest)

**You will need:** installed debug APK; network; map entitlement

**What to do:**

1. wake, search a destination, pick from the list by voice, start a route
2. do not touch the screen

**It passes if:**

- correct destination and route selected once
- no duplicate navigate_to after local pick
- no 导航已开始 before nav_navigation_started (P32)

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

### LOCAL_DEVICE_REQUIRED — GEMINI-DEVICE-LATENCY-001 — Gemini (gemini-3.8-live) end of speech to first audio heard, on the phone

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Latency heard in the cabin depends on the phone's network to Google, playback and road noise; the cloud container measured only the wire and the gate

**Automation blocker:** `physical_world`

**Already established without you:**

- docs/reports/2026-09-30-gemini-native-smoke.md: live API through GeminiLiveClient, L1 9/10 (median 1.6 s), L2 ~0 ms gate release, L3 0 claims before result

**You will need:** developer settings: Gemini enabled, model gemini-3.8-live selected (a saved extended-thinking choice is kept); GEMINI-DEVICE-REACH-001 passed

**What to do:**

1. say 10 commands (空调, 导航, 音乐) and 5 chat questions
2. note seconds from end of speech to the action and to the first word heard
3. note any 'done' heard before the action happened

**It passes if:**

- actions within ~2.5 s in at least 9 of 10
- chat replies start within ~2 s
- no claim heard before its action

**Tell me back:** commands acted within ~2.5 s, out of 10; seconds to first word for the 5 chat questions; any claim heard before its action

**Still unknown until you do:** seconds heard in the cabin on the phone's network; whether gemini-3.8-live self-interrupts through the speaker (G-M2)

### LOCAL_DEVICE_REQUIRED — STYLE-EAR-001 — 嗲 / 傲娇 / 温柔 / 元气 each sound right and appropriate, with the same voice

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Whether the tone is sweet but not inappropriate, and whether the voice is unchanged, is a listening judgement

**Automation blocker:** `subjective_perception`

**Already established without you:**

- STYLE-UNIT-001: compose swaps the tone paragraph; style persisted and sticky (JVM)

**You will need:** phone or emulator with this build; Gemini key

**What to do:**

1. say 说话能不能嗲一点, chat for three turns
2. restart the app, chat again
3. say 傲娇一点, then 把车窗打开一半: the window opens and 小诺 does not pretend to refuse
4. say 温柔一点 and 元气一点, one turn each
5. say 正常一点

**It passes if:**

- tone audibly sweeter, never sexualised
- same voice throughout
- still sweet after restart
- normal after 正常一点

**Tell me back:** yes/no per criterion, one sentence of impression

**Still unknown until you do:** how the model renders the sweet tone in audio; whether it ever drifts into inappropriate wording

### LOCAL_DEVICE_REQUIRED — GUIDANCE-DEVICE-001 — One voice on the short route; Amap audible only when the model cannot speak

**Tag:** `LOCAL_DEVICE_REQUIRED`

**Why this needs you.** Needs a person driving or riding the route and listening to which voice speaks each prompt

**Automation blocker:** `physical_world`

**Already established without you:**

- GUIDANCE-EMU-001 measurements
- SPEC-018 step 1 JVM suites (GuidanceRelayTest, SpeechArbiterGuidanceTest, GeminiLiveClientTest)

**You will need:** test phone with this build, toggle on; GUIDANCE-EMU-001 passed; route 横琴创业谷 → 励骏庞都

**What to do:**

1. drive the route
2. note any prompt spoken twice, missed, or in Amap's voice
3. cut mobile data for one prompt

**It passes if:**

- every prompt heard exactly once
- Amap's voice only when data was cut or the model failed
- no prompt reworded in direction or distance

**Tell me back:** per prompt: voice heard, correct yes/no

**Still unknown until you do:** real-network latency vs the deadline; Amap SDK behaviour with the inner voice off on a real device

*Release-blocking.*

## B. Account and real-service tests

### CALL-REAL-001 — A confirmed call reaches a real handset

**Why this needs you.** Needs a SIM with service and a number whose owner agrees this app may ring it. An agent must not dial a real person

**Automation blocker:** `hardware_interface`

**Already established without you:**

- CALL-CONFIRM-001, CALL-AMBIG-001, CALL-PRIVACY-001 PASS
- CALL-NOSIM-001 PASS - the no-telephony refusal is honest
- CALL-CLASSIFY-001 PASS - 打电话 is classified as an action we have, not as unsupported
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

### MUSIC-APP-DEVICE-001 — 网易云 / QQ音乐 play a described song or fail honestly

**Why this needs you.** Needs the owner's logged-in 网易云音乐 or QQ音乐 account (credential) and their consent for notification access on their phone

**Automation blocker:** `credential_permission`

**Already established without you:**

- MUSIC-UNIT-001 (JVM)
- MUSIC-TEXT-LIVE-001 for the model's field mapping

**You will need:** test phone with this build; 网易云音乐 or QQ音乐 installed and logged in; notification access (通知使用权) granted to 小诺; VPN split tunnel: the music app excluded so its catalogue region is China

**What to do:**

1. say 「放梶浦由记的、空之境界里很燃的那首 OP」
2. say 「不是这首」 once
3. ask for one VIP-only song
4. note what plays and what 小诺 says each time

**It passes if:**

- the described song or an honest mismatch question plays and is named only from now_playing
- 「不是这首」 excludes the previous title
- a VIP-only song gives NOT_PLAYING and 小诺 says it did not play
- the map returns to the foreground

**Tell me back:** per request: what played, what was said, status/error code; whether the split tunnel was needed

**Still unknown until you do:** how each app interprets MEDIA_PLAY_FROM_SEARCH extras; whether VIP-only tracks start a preview that looks like playing

*Release-blocking.*

## C. Required credentials

### RELEASE-SIGN-001 — A signed release build installs and works

**Why this needs you.** Signing needs a keystore and its passwords. Those are the owner's credentials and must never enter this repository (I-7). An agent must not create a production signing key

**Automation blocker:** `credential_permission`

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

### GEMINI-DEVICE-REACH-001 — The phone opens a Gemini Live session on its normal network (G-M1)

**Why this needs you.** Needs the owner's Gemini key entered on the phone and the owner's own acceptance of the cross-border notice

**Automation blocker:** `credential_permission`

**Already established without you:**

- c
- l
- o
- u
- d
-  
- c
- o
- n
- t
- a
- i
- n
- e
- r
- ,
-  
- o
- u
- t
- s
- i
- d
- e
-  
- C
- h
- i
- n
- a
- :
-  
- s
- e
- t
- u
- p
-  
- a
- c
- c
- e
- p
- t
- e
- d
-  
- w
- i
- t
- h
-  
- t
- h
- e
-  
- a
- p
- p
- '
- s
-  
- f
- u
- l
- l
-  
- c
- o
- n
- f
- i
- g
- u
- r
- a
- t
- i
- o
- n
-  
- i
- n
-  
- 0
- .
- 8
- -
- 1
- .
- 1
-  
- s
- ;
-  
- s
- p
- o
- k
- e
- n
-  
- 空
- 调
- 打
- 开
-  
- a
- n
- d
-  
- 导
- 航
- 到
- 万
- 达
-  
- r
- e
- a
- c
- h
- e
- d
-  
- c
- o
- n
- t
- r
- o
- l
- _
- c
- l
- i
- m
- a
- t
- e
-  
- a
- n
- d
-  
- n
- a
- v
- i
- g
- a
- t
- e
- _
- t
- o
-  
- t
- h
- r
- o
- u
- g
- h
-  
- t
- h
- e
-  
- a
- p
- p
- '
- s
-  
- o
- w
- n
-  
- c
- l
- i
- e
- n
- t
-  
- (
- G
- E
- M
- I
- N
- I
- -
- L
- I
- V
- E
- -
- C
- L
- O
- U
- D
- -
- 0
- 0
- 1
- ,
-  
- p
- r
- o
- b
- e
-  
- r
- e
- p
- o
- r
- t
-  
- s
- e
- c
- o
- n
- d
-  
- r
- o
- u
- n
- d
- )

**You will need:** debug APK from claude/9-29; Gemini key

**What to do:**

1. Developer settings > Gemini Live: paste key, tick consent, tick use Gemini, save
2. start a session; say 打开空调 and 导航到万达
3. read logcat NovaVoice for gemini_setup_complete, gemini_tool_call, session_provider choice=gemini_live

**It passes if:**

- gemini_setup_complete within 3 s
- both commands reach a tool call

**Tell me back:** t; h; e;  ; t; h; r; e; e;  ; l; o; g; c; a; t;  ; l; i; n; e; s;  ; (; o; r;  ; t; h; e; i; r;  ; a; b; s; e; n; c; e; ); ,;  ; t; h; e;  ; t; i; m; e;  ; f; r; o; m;  ; s; e; s; s; i; o; n;  ; s; t; a; r; t;  ; t; o;  ; g; e; m; i; n; i; _; s; e; t; u; p; _; c; o; m; p; l; e; t; e; ,;  ; a; n; d;  ; w; h; i; c; h;  ; n; e; t; w; o; r; k;  ; t; h; e;  ; p; h; o; n; e;  ; w; a; s;  ; o; n;  ; (; V; P; N;  ; o; n; /; o; f; f; )

**Still unknown until you do:** w; h; e; t; h; e; r;  ; t; h; e;  ; p; h; o; n; e; '; s;  ; n; e; t; w; o; r; k;  ; (; w; i; t; h;  ; t; h; e;  ; o; w; n; e; r; '; s;  ; V; P; N; );  ; r; e; a; c; h; e; s;  ; g; e; n; e; r; a; t; i; v; e; l; a; n; g; u; a; g; e; .; g; o; o; g; l; e; a; p; i; s; .; c; o; m;  ; a; t;  ; a; l; l; ,;  ; a; n; d;  ; t; h; e;  ; s; e; t; u; p;  ; a; n; d;  ; f; i; r; s; t; -; a; u; d; i; o;  ; l; a; t; e; n; c; y;  ; f; r; o; m;  ; t; h; e; r; e

## D. Product decisions

### YUE-POLICY-001 — Is Cantonese an advertised capability?

**Why this needs you.** Three options that are not comparable on technical grounds. The cost and the promise to users are the owner's to weigh

**Automation blocker:** `subjective_perception`

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

**Automation blocker:** `subjective_perception`

**Measured, so this is a choice and not a question:**

- ABI-SIZE-001: universal release APK 216.7 MB on disk, 228.0 MB uncompressed
- lib/arm64-v8a 99.7 MB; lib/armeabi-v7a 68.4 MB
- arm64-only would drop 68.4 MB of native libs (~30% of the APK); v7a-only would drop 99.7 MB
- the test device reports ro.product.cpu.abilist=arm64-v8a only

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
