# SPEC-012 — One owner decides who may speak

Status: **Draft**
Raised: 2026-09-24 · Source: [B-026](../BACKLOG.md#b-026--one-owner-for-who-may-speak)
Depends on: [I-10, I-12](../docs/INVARIANTS.md), [P1, P3](../OPEN_PROBLEMS.md) (both device-verified and must stay so), [SPEC-002](SPEC-002-navigation-uplink-mute.md), [SPEC-009](SPEC-009-audio-playout-lifecycle-and-aec-framing.md)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

Whether 小诺 may speak, and whether the microphone may reach Baidu, is decided today in four places
that each know part of the rule. That is how a fix to one case silently breaks another. This puts the
decision in one table-driven owner, modelled on Android Automotive's audio-focus interaction table,
keeps today's behaviour exactly, and then adds one new rule: do not start a reply in the seconds
before a turn.

## Scope

The decision points that exist at `846d116`:

| Today | What it decides | Read by |
| --- | --- | --- |
| `NavigationState.shouldMuteSpeech` / `allowConfirmation` / `allowReply` / `extendWhileSpeaking` | P1: unprompted replies are dropped while navigating; an asked-for reply or a confirmation opens a 10 s window | `PcmAudioPlayer.enqueue`, `applyFocusChange` |
| `VoiceSessionController.guidanceListener` | reply playback pauses while Amap speaks, resumes after | `NavigationGuidanceVoice` listener |
| `GuidanceMicGate` | P3: uplink closed during guidance, reopens 500 ms after, or after 20 s regardless | `microphone.guidanceGated` |
| `PcmAudioPlayer.applyFocusChange` | focus loss → duck / pause / stop; a permitted confirmation is not ducked | `AudioFocusController` |
| `voicepolicy.VoicePolicy` | a category table | **nothing** — only its own test. Dead code |

After this SPEC: `SpeechArbiter` (package `voice`) owns all of it. The others either become its
inputs or are deleted.

## Non-goals

- The assistant speaking first (proactive prompts). There is no TTS; asking Flex to say a fixed line
  is unmeasured. It needs its own measurement before a SPEC.
- Incoming calls. There is no call-state input today; the table leaves a row for it.
- Re-speaking Amap's guidance ourselves. Its voice is the SDK's own (`setUseInnerVoice`); we can
  observe and defer around it, not take it over.

## Capability ground truth

`navigation.guidance_voice` device evidence (21 guidance start/end pairs, mic gated throughout) and
P1's device verification are the behaviours that must survive unchanged. No capability is added
except the workload hold (B5).

## Behaviour

B1. **Inputs** (events, not polling): `GuidanceSpeaking(bool)`, `DriverRequest` (from
`VoiceCommandRouter.onDriverRequest`), `ToolConfirmation` (from `NavigationState.allowConfirmation`'s
callers), `FocusChanged(kind)`, `Navigating(bool)`, `ManeuverDistance(meters)` (B5), `Clock`.

B2. **Outputs.** For reply audio: `PLAY`, `HOLD` (keep queued, resume later), `DROP`, and volume
`FULL` / `DUCK`. For the uplink: `OPEN` / `CLOSED`. Every change is logged once:
`speech_arbiter out=<reply|uplink> decision=<d> reason=<r>`.

B3. **Rule table — today's behaviour, unchanged.** Highest row wins.

| # | Condition | Reply | Uplink |
| --- | --- | --- | --- |
| R1 | Guidance speaking | HOLD | CLOSED |
| R2 | Guidance ended < 500 ms ago | continue (HOLD lifts) | CLOSED |
| R3 | Guidance "speaking" for > 20 s with no end | — | OPEN (lost callback) |
| R4 | Focus lost permanently | DROP + flush | — |
| R5 | Focus lost transiently | HOLD | — |
| R6 | Navigating, outside the 10 s permitted window | DROP (P1) | OPEN |
| R7 | Navigating, inside the window (asked-for or confirmation) | PLAY FULL, a duck request is ignored | OPEN |
| R8 | Not navigating, duck requested | PLAY DUCK | OPEN |
| R9 | Otherwise | PLAY FULL | OPEN |
| R10 | Reserved: incoming call | — | — |

B4. **Migration keeps the evidence valid.** Step 1 writes the table as characterisation tests against
the *current* classes; step 3 runs the same tests against the arbiter. Nothing is rewired until they
pass unchanged against both.

B5. **New rule — workload hold (step 4).** While navigating, when the distance to the next manoeuvre
(`NaviInfo.curStepRetainDistance`, forwarded from `app/nav/amap` as a number only) is under
**150 m**, a permitted reply that has **not started** is HELD until the manoeuvre is passed (the
distance jumps up) or **8 s** have gone, whichever is first; then it plays. A reply already playing
is not cut. It is inserted as R6a, above R7.

## Failure behaviour

| Case | Result |
| --- | --- |
| Guidance end callback lost | R3 reopens the uplink after 20 s; the held reply resumes |
| Manoeuvre distance stops updating | the 8 s cap releases the hold |
| Two inputs at once (guidance starts while focus is lost) | table order decides; tested per pair |
| Session ends while a reply is held | the hold is dropped with the session epoch (SPEC-009) |

## Observability

B2's log line. Distances are logged only as a bucket (`<150`, `≥150`), never with position.

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | R1–R9 hold against the current classes | regression protection | `SpeechRulesCharacterizationTest` | not built |
| A2 | The same tests pass against `SpeechArbiter` | functional | `SpeechRulesCharacterizationTest` (arbiter mode) | not built |
| A3 | Player, focus and guidance paths ask only the arbiter; `VoicePolicy` and the mute window in `NavigationState` are gone | architectural | new `ArchitectureRulesTest` rule + `FeaturePresenceRegressionTest` updated | not built |
| A4 | Every pair of simultaneous inputs has a tested outcome | negative | `SpeechArbiterPairTest` | not built |
| A5 | Workload hold: held under 150 m, released on passing or at 8 s, never cuts a playing reply | functional | `SpeechArbiterWorkloadTest` | not built |
| A6 | On a simulated drive, guidance and a reply never overlap and P1 still drops unprompted replies | device | `SPEECH-ARBITER-DEVICE-001` (emulator drive, LOCAL_DEVICE) | not built |
| A7 | No reply starts inside 150 m of a manoeuvre on a simulated drive | device | `SPEECH-WORKLOAD-DEVICE-001` (LOCAL_DEVICE) | not built |
| A8 | Registry and capabilities agree | reconciliation | `harness_check.py`, `test_matrix.py --validate` | not built |

## Open product decisions

- **150 m / 8 s.** Defaults, tunable constants. Urban junctions may want 200 m; measured on the
  simulated drive in A7 before any change.
- **Held reply after the cap: play or drop?** Default **play**: the driver asked, and a late answer
  beats none.

## Implementation status

Nothing built. Steps, each a separate verified commit:

1. `SpeechRulesCharacterizationTest` against today's classes (A1). No production change.
2. `SpeechArbiter` pure class; the same tests in arbiter mode (A2, A4).
3. Rewire `PcmAudioPlayer.enqueue` / `applyFocusChange` and `guidanceListener` to the arbiter;
   `GuidanceMicGate` becomes its internal timer; delete `VoicePolicy`, its test, and the mute window
   from `NavigationState`; A3; full test + build; device A6 (P1/P3 must re-pass — I-12).
4. `ManeuverDistance` input from `NavigationTraceListener.onNaviInfoUpdate`; R6a; A5; device A7.
