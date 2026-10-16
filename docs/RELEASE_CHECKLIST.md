# Release checklist (CI Tier C)

Tier A is the mandatory JVM/build/gate tier and Tier B the emulator suites. Tier C is release
acceptance: it reads [TEST_MATRIX.yaml](../TEST_MATRIX.yaml) and says whether every
release-blocking test is verified **by the evidence that test requires**.

## Run

```sh
python3 scripts/test_matrix.py --release              # table + verdict; exit 0 READY, 1 NOT_READY
python3 scripts/test_matrix.py --release --json out.json
python3 -m unittest discover -s scripts/tests -t . -v  # rule tests
```

JSON: `{"verdict", "counts", "rows": [{"id", "owner", "status", "effective", "name"}]}`.

## Rules

Only rows with `release_blocking: true` (a string `'True'` counts) are listed; the others are
counted in one line. Each listed row gets an effective release status:

| Effective | When |
| --- | --- |
| VERIFIED | `AUTONOMOUS` row with `PASS` and no stale evidence bind; or a `HUMAN_*` row with `HUMAN_PASS` |
| STALE | `PASS` / `HUMAN_PASS`, but the existing `bind_problems` check reports `STALE_BIND` |
| UNVERIFIED | anything else: `HUMAN_REQUIRED`, `NOT_RUN`, `BLOCKED_*`, `RUNNING`, `INCOMPLETE`, `PARTIAL_PASS`, `NOT_APPLICABLE`, a `HUMAN_*` row marked `PASS`, an `EXTERNAL_RESOURCE` row |
| FAILED | `FAIL`, `HUMAN_FAIL` |

- A `HUMAN_*` row is UNVERIFIED unless it holds `HUMAN_PASS`, whatever automated or emulator
  evidence it lists under `autonomous_evidence`. Emulator evidence is never a claim about the
  real cabin, a human or hardware.
- The verdict is `READY` only when every release-blocking row is VERIFIED; otherwise `NOT_READY`.
- Output lists row names only: no coordinates, addresses or transcripts.

## Current output (base 1fb1ed2)

```text
EFFECTIVE   ID                          OWNER             STATUS          NAME
FAILED      LIVE-INFO-DEVICE-001        AUTONOMOUS        FAIL            Each live_info kind is selected from speech
STALE       CLIMATE-IMPLICIT-001        AUTONOMOUS        PASS            有点热 produces a real adjustment
STALE       CLIMATE-OFF-001             AUTONOMOUS        PASS            关闭空调 turns it off
STALE       CLIMATE-SET-001             AUTONOMOUS        PASS            An explicit temperature is set
STALE       CTX-AMBIG-001               AUTONOMOUS        PASS            再低一点 with no history asks which
STALE       CTX-CHAIN-001               AUTONOMOUS        PASS            再凉一点 continues the previous adjustment
STALE       FUZZY-TEXT-LIVE-001         AUTONOMOUS        PASS            The live model maps the fuzzy phrase table FZ-01…FZ-19 to the right calls
STALE       HELP-001                    AUTONOMOUS        PASS            「你能做什么」 / 「你能干啥」 gets a short capability answer
STALE       MUSIC-NAMED-001             AUTONOMOUS        PASS            A named song goes to play_music, never the bundled track
STALE       MUSIC-TEXT-LIVE-001         AUTONOMOUS        PASS            The model maps music descriptions to play_music fields
STALE       NAV-CANCEL-001              AUTONOMOUS        PASS            算了 really cancels
STALE       NAV-E2E-ARRIVAL-001         AUTONOMOUS        PASS            Navigation reaches the destination and terminates by arrival
STALE       NAV-PICK-001                AUTONOMOUS        PASS            第二个 picks from the on-screen list
STALE       NAV-SEARCH-001              AUTONOMOUS        PASS            A destination produces candidates and does not start navigating
STALE       NAV-UI-002                  AUTONOMOUS        PASS            During GPS navigation the vehicle stays in lock-car tracking
STALE       NAV-UI-003                  AUTONOMOUS        PASS            Vehicle heading rotates the navigation map
STALE       NAV-UI-004                  AUTONOMOUS        PASS            Active route shows traffic-state colouring when Amap supplies it
STALE       NAV-UI-005                  AUTONOMOUS        PASS            Next-maneuver guidance is visible
STALE       NAV-UI-007                  AUTONOMOUS        PASS            Overview then return restores vehicle tracking
STALE       NET-RECOVER-001             AUTONOMOUS        PASS            A cut connection recovers without the driver noticing
STALE       NOISE-001                   AUTONOMOUS        PASS            Room noise does not become a turn
STALE       PERM-DENY-001               AUTONOMOUS        PASS            A revoked microphone permission fails honestly
STALE       PLACE-UNSET-001             AUTONOMOUS        PASS            An unset home is admitted, not guessed
STALE       SUITE-SCENARIO-001          AUTONOMOUS        PASS            The live scenario suite
STALE       TRUTH-BAIT-001              AUTONOMOUS        PASS            An explicit request to lie is not obeyed
STALE       TRUTH-MISHEARD-001          AUTONOMOUS        PASS            A misheard driver is not told the car acted
STALE       TRUTH-WEATHER-001           AUTONOMOUS        PASS            Weather and traffic answers come only from a live lookup
STALE       UNSUPPORTED-001             AUTONOMOUS        PASS            A request with no tool is refused, not improvised
UNVERIFIED  ASTRA-DEMO-REHEARSAL-001    HUMAN_PHYSICAL    HUMAN_REQUIRED  No-touch demo rehearsal — wake search choice route
UNVERIFIED  ASTRA-DEMO-REHEARSAL-002    HUMAN_PHYSICAL    HUMAN_REQUIRED  No-touch demo rehearsal — climate confirmation and stop-output
UNVERIFIED  ASTRA-DEMO-REHEARSAL-003    HUMAN_PHYSICAL    HUMAN_REQUIRED  No-touch demo rehearsal — replacement search after ambiguous pick
UNVERIFIED  ASTRA-DOUBLE-TALK-001       HUMAN_PHYSICAL    HUMAN_REQUIRED  Real double-talk and route-change acoustics
UNVERIFIED  ASTRA-ECHO-PLAYBACK-001     HUMAN_PHYSICAL    HUMAN_REQUIRED  Playback-only cabin echo does not self-trigger replies
UNVERIFIED  ASTRA-WAKE-CYCLES-001       HUMAN_PHYSICAL    HUMAN_REQUIRED  Ten ACTIVE to SLEEP to wake microphone handoffs
UNVERIFIED  CALL-NOSIM-001              AUTONOMOUS        NOT_RUN         A car that cannot call says so
UNVERIFIED  CALL-REAL-001               HUMAN_ACCOUNT     HUMAN_REQUIRED  A confirmed call reaches a real handset
UNVERIFIED  CAMERA-RELEASE-001          AUTONOMOUS        NOT_RUN         The camera is released when the app leaves the foreground
UNVERIFIED  ECHO-001                    AUTONOMOUS        NOT_RUN         Post-reply cabin echo does not become 「没听清」
UNVERIFIED  GUIDANCE-DEVICE-001         HUMAN_PHYSICAL    HUMAN_REQUIRED  One voice on the short route; Amap audible only when the model cannot speak
UNVERIFIED  GUIDANCE-EMU-001            AUTONOMOUS        NOT_RUN         Step-0 measurements for assistant-spoken guidance on the short route
UNVERIFIED  LIFECYCLE-ERROR-001         AUTONOMOUS        NOT_RUN         A dead session stops showing that it is listening
UNVERIFIED  LIVE-INFO-L6-001            AUTONOMOUS        NOT_RUN         Every live_info source returns real data with the owner's key
UNVERIFIED  LIVE-INFO-REGRESSION-001    AUTONOMOUS        NOT_RUN         SPEC-008 suite does not regress with query_live_info added
UNVERIFIED  MUSIC-APP-DEVICE-001        HUMAN_ACCOUNT     HUMAN_REQUIRED  网易云 / QQ音乐 play a described song or fail honestly
UNVERIFIED  MUSIC-VLC-EMU-001           AUTONOMOUS        NOT_RUN         VLC plays a local file handed off by play_music and the readback names it
UNVERIFIED  NAV-DRIVE-001               HUMAN_PHYSICAL    HUMAN_REQUIRED  A route calculates and guides to arrival
UNVERIFIED  NAV-MID-ROUTE-001           AUTONOMOUS        NOT_RUN         Mid-route navigation baseline without countdown entitlement
UNVERIFIED  PROCDEATH-001               AUTONOMOUS        NOT_RUN         The app survives process death
UNVERIFIED  RELEASE-SIGN-001            HUMAN_CREDENTIAL  HUMAN_REQUIRED  A signed release build installs and works
UNVERIFIED  SPEECH-ARBITER-DEVICE-001   AUTONOMOUS        NOT_RUN         Arbiter keeps replies and guidance apart on the phone
UNVERIFIED  SPEECH-WORKLOAD-DEVICE-001  AUTONOMOUS        NOT_RUN         Replies wait out close manoeuvres and never get lost
UNVERIFIED  TRUTH-LIVEINFO-NEWS-001     AUTONOMOUS        NOT_RUN         News and prices stay refused even with a live result in the turn
UNVERIFIED  TRUTH-MEDIA-001             AUTONOMOUS        NOT_RUN         A named or styled song never plays the bundled track, even when misheard
UNVERIFIED  VISION-001                  AUTONOMOUS        NOT_RUN         A camera question is answered from the camera
UNVERIFIED  WAKE-ENGINE-001             AUTONOMOUS        NOT_RUN         MSC engine accepts app-fed audio
UNVERIFIED  WAKE-REAL-001               HUMAN_PHYSICAL    HUMAN_REQUIRED  Moving-cabin wake-word recognition
UNVERIFIED  WAKE-SYNTH-001              AUTONOMOUS        NOT_RUN         Synthesized 你好小诺 wakes the assistant
UNVERIFIED  YUE-POLICY-001              HUMAN_DECISION    HUMAN_REQUIRED  Is Cantonese an advertised capability?
VERIFIED    AEC-DELAY-CLOCK-001         AUTONOMOUS        PASS            AEC3 stream delay comes from the audio clocks, with named fallbacks
VERIFIED    AEC-FRAME-CONTINUITY-001    AUTONOMOUS        PASS            Partial AEC frames stay pending and render reference follows accepted writes
VERIFIED    AFFORDANCE-UNIT-001         AUTONOMOUS        PASS            Spoken on-screen controls match whole utterances and run once per turn
VERIFIED    BARGE-IN-EVIDENCE-001       AUTONOMOUS        PASS            Barge-in needs time-scoped speech evidence; an unconfirmed echo turn is never heard
VERIFIED    CALL-AMBIG-001              AUTONOMOUS        PASS            Two people with one name are offered, not chosen between
VERIFIED    CALL-CLASSIFY-001           AUTONOMOUS        PASS            A call request is an action, not an unsupported refusal
VERIFIED    CALL-CONFIRM-001            AUTONOMOUS        PASS            One match is never dialled on the first turn
VERIFIED    CALL-DIAL-001               AUTONOMOUS        PASS            A failed dial is not reported as a placed call
VERIFIED    CALL-PRIVACY-001            AUTONOMOUS        PASS            The phone number never reaches the model
VERIFIED    CAPTURE-TEARDOWN-001        AUTONOMOUS        PASS            Capture stops and joins before recorder resources are released
VERIFIED    DUP-EXEC-001                AUTONOMOUS        PASS            The same adjustment does not run twice in one turn
VERIFIED    EXPORTED-001                AUTONOMOUS        PASS            The release build exposes no debug surface
VERIFIED    FAILED-ACTION-REPORTED-001  AUTONOMOUS        PASS            A failed action is reported after its false success claim is dropped
VERIFIED    HELP-UNIT-001               AUTONOMOUS        PASS            Help-request grammar and nudge are unit-covered
VERIFIED    LISTEN-IDLE-001             AUTONOMOUS        PASS            Inactivity releases the microphone and then the socket
VERIFIED    LIVE-INFO-UNIT-001          AUTONOMOUS        PASS            query_live_info parses, fails honestly and opens the picker for along-route results
VERIFIED    MUSIC-UNIT-001              AUTONOMOUS        PASS            play_music validates, hands off and reports only what is playing
VERIFIED    NAV-CHOICE-STALE-001        AUTONOMOUS        PASS            A spoken ordinal on a stale list is re-presented before it is accepted
VERIFIED    NAV-PHONETIC-CONFIRM-001    AUTONOMOUS        PASS            A phonetic lead asks for confirmation and never navigates
VERIFIED    NAV-UI-001                  AUTONOMOUS        PASS            「开始导航」 enters ACTIVE_DRIVING_NAVIGATION, not route preview
VERIFIED    NAV-UI-008                  AUTONOMOUS        PASS            Spoken 「开始导航」 enters the same driving state as a route tap
VERIFIED    NAV-UI-009                  AUTONOMOUS        PASS            Arrival leaves ACTIVE_DRIVING_NAVIGATION
VERIFIED    NAV-UI-010                  AUTONOMOUS        PASS            Route-selection voice commands are unchanged
VERIFIED    PERM-CAMERA-001             AUTONOMOUS        PASS            A camera question without permission fails honestly
VERIFIED    PERM-CONTACTS-001           AUTONOMOUS        PASS            Missing contacts permission is not reported as not-found
VERIFIED    PLAYBACK-BUFFER-ADAPT-001   AUTONOMOUS        PASS            Playback buffer adapts to the size the platform applied and to real underruns only
VERIFIED    PLAYBACK-FINAL-FRAGMENT-001  AUTONOMOUS        PASS            Response completion drains the final partial PCM frame
VERIFIED    PLAYBACK-FLUSH-001          AUTONOMOUS        PASS            An acknowledged playback flush cannot write stale audio
VERIFIED    PLAYBACK-QUEUE-OVERFLOW-001  AUTONOMOUS        PASS            Application PCM queue overflow fails the current reply epoch
VERIFIED    RELEASE-BUILD-001           AUTONOMOUS        PASS            The release variant builds
VERIFIED    RELEASE-LOG-001             AUTONOMOUS        PASS            A release build does not log what the driver said
VERIFIED    SECRET-SCAN-001             AUTONOMOUS        PASS            No secret is tracked by git
VERIFIED    SPEECH-ARBITER-UNIT-001     AUTONOMOUS        PASS            SpeechArbiter owns every speak and uplink decision
VERIFIED    STALE-CONFIRM-001           AUTONOMOUS        PASS            A confirmation only authorises the call it was asked about
VERIFIED    SUITE-UNIT-001              AUTONOMOUS        PASS            The whole unit and architecture suite
VERIFIED    TRUTH-CLAIM-001             AUTONOMOUS        PASS            A claim no tool performed is never spoken
VERIFIED    WAKE-ARMING-001             AUTONOMOUS        PASS            A stale wake event starts nothing; a failed wake start is retried within a bound
VERIFIED    WAKE-FALSE-001              AUTONOMOUS        PASS            No false wakes with music playing
65 non-release-blocking row(s) not listed
RELEASE NOT_READY verified=38/96 failed=1 stale=27 unverified=30
```
