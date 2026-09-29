# Live conversation architecture — GPT-Live-class behaviour for 小诺

Status: **Proposed — architecture only.** No implementation, milestone, SPEC or backlog row is
authorised by this document. It is not in `BACKLOG.md` on purpose: a row there would enter the
autonomous work queue (`scripts/discover_work.py`) before the decisions in §9 are taken.

Written 2026-09-29 on `claude/9-29` (from `demo-1.0` at `7ac69f5`).
Source of the demand: the mentor's note introducing OpenAI GPT-Live (sent about 2026-09-19, shown by
the product owner 2026-09-29). It lists four properties: full-duplex audio, natural interruptions
with small acknowledgements, background delegation of hard work to a stronger model, and noise /
long-pause robustness.

Read with: [ARCHITECTURE.md](ARCHITECTURE.md) (owners), [INVARIANTS.md](INVARIANTS.md),
[ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md),
[ADR-008](../DECISIONS/ADR-008-single-active-realtime-provider.md),
[ADR-009](../DECISIONS/ADR-009-provider-neutral-realtime-contract.md),
[ASTRA_ARCHITECTURE_SELECTION.md](ASTRA_ARCHITECTURE_SELECTION.md) (decisions 1–4: the duplex audio
front end), [SPEC-012](../SPECS/SPEC-012-speech-arbiter.md) (who may speak),
[REALTIME_PROTOCOL_REFERENCES.md](REALTIME_PROTOCOL_REFERENCES.md) (GPT-Live protocol notes).

---

## 1. Where the product is today (measured against the four properties)

| GPT-Live property | 小诺 at `7ac69f5` | Evidence level |
| --- | --- | --- |
| **End-to-end speech** | Yes. Mic PCM → Baidu Qianfan Flex over one WebSocket → the model's own audio. No ASR, no TTS stage (ADR-001/002). | device |
| **Full duplex** — listens while speaking | Yes, since `28c42f2`. Reply playback does not gate the uplink; WebRTC AEC3 removes the render signal before `SpeechUplinkGate` (ARCHITECTURE.md "Audio capture"). | unit + partial device; `ECHO-001` re-run pending |
| **Interruption by voice** | Implemented: server `speech_started` during playback is a *candidate*; playback flushes only with time-scoped local speech evidence (`playoutBargeInQualified`, BARGE-IN-EVIDENCE-001). 「闭嘴」→ SILENT_WAIT in 2 ms (device). | unit; real cabin double-talk `ASTRA-DOUBLE-TALK-001` is HUMAN_REQUIRED |
| **Small acknowledgements** ("嗯", "mhmm") | **Absent.** Output is either a full reply or silence. | — |
| **Background delegation** | **Absent as a product feature.** Tools run inline: call → execute → result → the model speaks. The seam exists but is unused for this: `WorkCoordinator` ("asynchronous work that must not block audio … delivered once, only at a safe conversation point"), `RealtimeToolDispatcher.deferredOutput`, `RealtimeVoiceProvider.injectWorkResult`, capability `workResultInjection`. | seam: unit |
| **Noise filtering** | AEC3 (single native backend), `SpeechUplinkGate` (≥200 ms voice-like onset before upload, adaptive floor), `PhantomTurnGate` (drops content-free replies to doubtful turns), `MicInputGain`. No noise suppressor. | device for impulses (P20); real road noise HUMAN |
| **Long, messy pauses** | Turn end is the server's: `turn_detection.silence_duration_ms = 200`. The uplink gate streams through pauses (hangover), but a pause over 200 ms is, by configuration, enough for the server to close the turn. How often that splits a real driver sentence is **not measured** anywhere in the repository. | unknown |

Stale documentation found while measuring (not fixed here, reported): `docs/CAPABILITIES.md`
("Barge-in by voice does not exist. The microphone is gated while the assistant speaks") and
`config/capabilities.yaml` `speech.interrupt_tts_by_voice: unsupported` both describe the design
before `28c42f2` and contradict ARCHITECTURE.md.

**Conclusion.** Two of the four properties are already architecture here (duplex, interruption) and
owe device evidence, not design. Two are missing (acknowledgements, delegation). Noise/pause
robustness is partly present and partly unmeasured. This document designs the missing pieces and
names the evidence the present ones still owe.

---

## 2. Constraints this design may not break

| Constraint | What it forces |
| --- | --- |
| ADR-002 / ADR-008 — Baidu Flex is the one active realtime provider | The design must work **on Baidu Flex**. A second provider is an option (§8), never a prerequisite. |
| ADR-009 / I-13 — logic depends on the contract | Every new behaviour is a `ProviderCapabilities` flag plus neutral events; no `if (provider == …)`. |
| I-1 — only execution evidence proves an action | "Work accepted" is not "work done". A delegated answer is spoken from its result, never before. |
| I-3 — no source, no answer | Delegation *adds* a source; it does not relax the rule. Anything the delegate cannot ground is refused. |
| I-4 — noise never causes an action | Background work never actuates the car (§5.3, proposed I-14). |
| I-5 — correct before heard | Deferred answers pass through the same hold (`DriverTurn` / `PhantomTurnGate`) as live ones. |
| I-8 — no transcript / coordinate in a log | A delegated task carries the driver's words and possibly a place: never logged, only ids, durations, sizes. |
| I-10 — one owner per behaviour | Extend `WorkCoordinator`, `SpeechArbiter`, `DriverTurn`, `ListeningLifecycle`. No parallel mechanism. |
| I-11 — a prompt is not enforcement | The persona may ask for "我查一下"; the rules in §5 must hold if it doesn't. |
| SPEC-012 non-goal — no proactive speech yet ("asking Flex to say a fixed line is unmeasured") | A deferred answer **is** speech the driver did not just trigger. That measurement is a prerequisite (§7, M-2). |
| P1 (SpeechArbiter R6) — while navigating, unprompted replies are dropped outside the 10 s window | A deferred answer arriving after 10 s would be dropped. The arbiter needs a new, explicit row (§5.4). |
| `ConversationResetPolicy` — fresh conversation after every tool turn (measured: the model degrades from about the third tool turn) | The conversation that asked may no longer exist when the answer arrives. Delivery cannot depend on it (§5.3). |
| PRODUCT.md non-goal — no speech synthesis of our own | Acknowledgements may not be locally synthesised or recorded *speech* (§5.2). |

---

## 3. Target shape

Four planes, one identity threading them. Everything named already exists unless marked **new**.

```
                          ┌───────────────────────── ACOUSTIC PLANE ─────────────────────────┐
 mic ─► PcmAudioCapture ─►│ AEC3 (render ref from PcmAudioPlayer) ─► [NS, optional §5.5] ─►   │
                          │ MicInputGain ─► SpeechUplinkGate (+ WebRTC VAD, Astra D4)        │
                          └──────────────┬───────────────────────────────────────────────────┘
                                         │ time-scoped speech evidence
                          ┌──────────────▼─────────── TURN PLANE ────────────────────────────┐
 provider ◄──── uplink ───│ RealtimeVoiceProvider (Baidu Flex adapter; others via ADR-009)    │
          ────► events ──►│ DriverTurn (one per utterance, epoch)  ListeningLifecycle          │
                          │ interruption owner: ingress VoiceSessionController (Astra D4)     │
                          └───────┬──────────────────────────────┬───────────────────────────┘
                                  │ tool calls                    │ reply audio / cues
                 ┌────────────────▼──── WORK PLANE ───┐   ┌──────▼──────── OUTPUT PLANE ──────────┐
                 │ FlexFunctionCallAssembler          │   │ SpeechArbiter (via SpeechAuthority)   │
                 │ AndroidToolDispatcher              │   │   + DEFERRED_ANSWER row      (new)    │
                 │   inline tools (as today)          │   │   + ACK_CUE row              (new)    │
                 │   delegate_task ──► WorkCoordinator│   │ PhantomTurnGate / DriverTurn holds    │
                 │        └─► DelegationPort   (new)  │   │ AndroidPlaybackPort ─► PcmAudioPlayer │
                 │   deferred result ─► delivery ─────┼──►│ AssistantOverlayView (subtitle)       │
                 └────────────────────────────────────┘   └───────────────────────────────────────┘
```

The identity is the one the Astra review already chose: **turn epoch + playback epoch**. A barge-in,
a cue, a delegated job and its deferred answer all carry the epoch of the driver utterance they
belong to, so a late event can always be recognised as late and discarded rather than relabelled.

---

## 4. Contract changes (provider-neutral, ADR-009)

`ProviderCapabilities` gains flags. Behaviour varies on these, never on provider name:

| New capability | Meaning | Baidu Flex (today's knowledge) |
| --- | --- | --- |
| `deferredResultDelivery` | The provider can take a result for a *past* request and speak it as a new response in the current conversation. | Likely yes via `conversation.item.create` (`input_text`) + `response.create`, already used by `userTextMessage`. **Unmeasured for this purpose** (M-2). |
| `nativeDelegation` | The provider hands long work to the client (or its own backend) and continues talking. | No. |
| `nativeBackchannel` | The provider can emit short acknowledgement audio while the user speaks without taking the turn. | No evidence. |
| `turnEndTuning` | The client may set end-of-turn silence per session. | Yes (`silence_duration_ms`), already sent. |

`DomainVoiceEvent` gains one event, **`AckCue(epoch, kind)`**, for providers with
`nativeBackchannel`. `WorkInjection` gains `deferred: Boolean` and `originEpoch: Long`, so the
adapter chooses between "tool output for an open call" (today) and "deferred answer" (new) without
policy code knowing which wire message that is.

No other contract change. In particular there is no "delegation provider" enum: the background model
is an implementation of `DelegationPort` (§5.3), chosen at the composition boundary like the
realtime provider.

---

## 5. Component design

### 5.1 Full duplex and interruption — no new architecture

Astra decisions 1–4 are the design; this document adopts them unchanged:
AEC3 on the official two-stream contract (D1), hardware-clocked playback lifecycle (D2), state-driven
mic hand-off (D3), and separate acoustic evidence / turn identity / output invalidation with the
ingress controller as the interruption owner (D4). What is owed is **evidence**, listed in §7:
playback-only silence, real double-talk, stop before first audio, stop during the post-generation
tail, and a correct next command.

One refinement at the owner, not beside it: after an accepted barge-in, late audio from the
interrupted generation is discarded by epoch (already D4), **and** any deferred answer whose origin
epoch the driver has just talked over is re-queued, not dropped (§5.3 delivery rules). Interrupting
speech never cancels background work — that is already `WorkCoordinator`'s contract.

### 5.2 Acknowledgement cues

What GPT-Live does ("mhmm" while you talk) is a model capability. Three designs were considered:

| Option | Verdict |
| --- | --- |
| **A. Provider-native cues** — play `AckCue` audio from a provider with `nativeBackchannel` | Adopt as the path for verbal cues. Baidu has no such capability today, so on Baidu this is inert. |
| **B. Local non-verbal earcon** — a short tone owned by the app | Adopt, narrowly: only for *state changes the driver cannot otherwise perceive* — delegation accepted, deferred answer about to play. Not while the driver speaks. |
| **C. Local recorded "嗯/好的" clips** | **Rejected.** It is speech of our own (PRODUCT.md non-goal), in a voice that does not match the model's 4196 voice, and it would answer a turn the model has not understood. |

Rules (all deterministic, I-11):

1. A cue is an output category in **`SpeechArbiter`** (new row `ACK_CUE`). It obeys R1 (Amap guidance
   speaking → suppressed, never held — a late cue is noise), R4/R5 focus, and R6a workload hold
   (suppressed near a manoeuvre). It never opens the P1 window.
2. A cue never plays while the driver is mid-utterance on Baidu (no native capability, so the app
   cannot know it would not be heard as a turn-taking signal). With `nativeBackchannel` the provider
   decides timing and the arbiter only vetoes.
3. Cue audio goes through `PcmAudioPlayer`, so AEC3 has it as render reference like any reply.
4. Default **off** until M-3 (§7) shows it does not trip server VAD or distract. Product decision D-B.

### 5.3 Background delegation

The largest new piece. The pattern is GPT-Live's `delegation.type=client` (REALTIME_PROTOCOL_REFERENCES.md):
the realtime model keeps the conversation; hard work runs elsewhere; the result comes back as
commentary the model speaks. Here it is built from existing owners.

**Trigger.** One new tool, `delegate_task(task, kind)` — `kind` a closed enum
(`knowledge`, `search`, `plan`), `task` a bounded string. Validated by `FlexFunctionCallAssembler`
like every tool. The persona asks the model to use it for questions it cannot answer from a tool
it already has; the gate that matters is not the prompt but rules 4–6 below.

**Acceptance is immediate.** `AndroidToolDispatcher` returns at once
`{ok: true, status: "accepted", work_id}` and submits the job to **`WorkCoordinator`**
(`RealtimeToolDispatcher.deferredOutput` already exists for exactly this). The model answers the
acceptance with a short filler ("我查一下"). Because the tool result is delivered at once, the call
is not "owed" and `ConversationResetPolicy` is not blocked.

**Truth.** `DriverTurn` treats `status=accepted` as **acceptance proof only**. A reply that claims the
*answer* ("查到了…", figures, names) before a deferred result exists is an unproven claim and is held
and dropped under I-1/I-5, exactly as a navigation claim before its result is today.

**Execution.** `DelegationPort` (**new**, neutral, in `ingress` beside `WorkCoordinator`):

```
interface DelegationPort {
    val capabilities: DelegationCapabilities          // kinds supported, max latency, grounded search?
    suspend fun run(job: DelegatedJob, progress: (String) -> Unit): DelegatedResult
}
DelegatedJob    = { workId, originEpoch, kind, task, context: DelegationContext, deadlineMs }
DelegationContext = { coarse place (district, never coordinates unless D-D allows),
                      navigation phase, destination category — no transcript history }
DelegatedResult = Answer(text, sources, grounded: Boolean) | Refused(reason) | Failed(errorClass)
```

Implementations live in `app` as adapters, vendor JSON confined there (I-9). Candidates (decision
D-C): a Qianfan text model on the existing Baidu account; a search-grounded model; or, if §8 option 2
is taken, the realtime provider's own delegation channel.

4. **Background work never actuates (proposed I-14).** A `DelegationPort` implementation is given
   read-only tools at most (e.g. `LiveInfoTool` kinds). It cannot reach `AndroidToolDispatcher`'s
   actuating executors. An action the delegate recommends comes back as text; only the driver's next
   turn, through the normal path, can execute it. Enforced by a module/dependency rule, not by review.
5. **Ungrounded is refused (I-3 kept).** A `kind=search` result with `grounded=false` is delivered as
   an honest "查不到可靠信息", never as an answer.
6. **Bounded.** One running job per kind; a new `delegate_task` of the same kind supersedes the old
   one (`WorkCoordinator.refine`). A deadline (default proposal 20 s, decision D-E); on expiry the
   result is `Failed` and one honest sentence is delivered. The driver's explicit cancel (「算了」,
   routed by `VoiceCommandRouter`) cancels running work; interruption by speech does not.

**Delivery.** `WorkCoordinator.claimForDelivery(isSafeWorkDeliveryPoint)` already exists. The safe
point is tightened to: no driver utterance open, no reply playing or held, `ListeningLifecycle` in
ACTIVE (never SLEEP/DEEP_IDLE; in SILENT_WAIT the answer is shown as subtitle only). The adapter
delivers with `WorkInjection(deferred = true, originEpoch)`:
on Baidu, a short system-labelled text item carrying the result, then `response.create`, **in the
current conversation** (the origin conversation may have been reset — the result carries its own
context, so it does not need the old history). The deferred answer counts as a plain turn for
`ConversationResetPolicy`.

**Staleness.** A result older than its TTL (proposal 60 s, D-E), or whose origin is superseded by a
newer request of the same kind, or that arrives after navigation state changed materially (arrived,
new destination), is dropped unheard; if the driver asked, one short "刚才的问题已过时" is allowed
(D-E).

### 5.4 Who may speak a deferred answer — `SpeechArbiter` row

A deferred answer is neither an "asked-for reply" inside the 10 s P1 window nor an unprompted reply.
It gets its own row, **`DEFERRED_ANSWER`**, placed so today's rows are unchanged:

R4 permanent focus loss → DROP (result kept, re-offered once) · R1 guidance speaking → HOLD ·
R5 transient loss → HOLD · **R6a workload zone → HOLD** · navigating → **PLAY as asked-for** (not
subject to R6's window: the driver asked; the arbiter admits it once per `work_id`) · otherwise PLAY.

This changes the SPEC-012 table, so it is a SPEC-012 amendment, decided before any build (D-F).

### 5.5 Noise and long pauses

- **Noise suppression**: if added, it is WebRTC NS **inside the same APM instance** as AEC3 (one
  backend, Astra D1), after AEC, before gain. Not before M-4 shows it does not lower Baidu's
  transcription accuracy — an end-to-end model may do worse on suppressed audio than on raw.
- **Pauses**: no local semantic endpointer (a second turn authority would break the "server VAD owns
  turn ends" row). The lever is `turnEndTuning`: measure split-sentence rate vs response latency at
  200 / 500 / 800 ms (M-5) and set the value from data. If a later provider offers semantic end-of-
  turn detection it is a capability, not a local mechanism.

---

## 6. Ownership rows this would add (ARCHITECTURE.md "Who owns what")

| Behaviour | Canonical owner | Not owned by |
| --- | --- | --- |
| Whether a request is delegated | the model's `delegate_task` call, validated by `FlexFunctionCallAssembler` | keyword lists, the UI |
| Running, cancelling, superseding and timing out background work | `WorkCoordinator` | the tool handler, the provider adapter |
| How background work is computed | a `DelegationPort` implementation (read-only) | `AndroidToolDispatcher`'s actuating executors |
| Whether a deferred answer / cue may sound now | `SpeechArbiter` rows `DEFERRED_ANSWER`, `ACK_CUE` | `WorkCoordinator`, the adapter |
| Whether a deferred answer's content may be claimed | `DriverTurn` (+ `PhantomTurnGate`) against the `DelegatedResult` | the model's wording |

Proposed invariants: **I-14** background work never actuates the vehicle, navigation, phone or media;
**I-15** a deferred answer is spoken at most once, only from its result, and never after its TTL.

---

## 7. What would prove it (acceptance shape, not a plan)

Measurements that gate design choices (must precede any SPEC that depends on them):

| Id | Question | Why it gates |
| --- | --- | --- |
| M-1 | Duplex evidence: `ECHO-001` re-run, playback-only silence, stop in tail, double-talk (`ASTRA-DOUBLE-TALK-001`) | Everything else assumes duplex holds on the device. |
| M-2 | Does Flex reliably speak a deferred result injected as text + `response.create`, in the persona voice, within what latency, without confabulating beyond it? | SPEC-012 lists this as unmeasured; §5.3 delivery depends on it. |
| M-3 | Does a local earcon during/after a reply trip server VAD or `speech_started`? | §5.2 option B. |
| M-4 | Transcript accuracy with vs without WebRTC NS on recorded cabin noise | §5.5. |
| M-5 | Split-sentence rate and reply latency at `silence_duration_ms` 200 / 500 / 800 | §5.5. |

Levels (ACCEPTANCE_TESTS.md): contract and policy rows at L2/L3 (`WorkCoordinator` supersede /
cancel / TTL, `SpeechArbiter` rows, `DriverTurn` acceptance-vs-answer, I-14 dependency rule in
`behavior-test`); delivery and arbitration at L5 on device; real cabin double-talk and road noise
remain human (L6).

---

## 8. Provider strategy

| Option | What it means | Consequences |
| --- | --- | --- |
| **1. Stay on Baidu Flex; build §5 client-side** (recommended) | Delegation via `DelegationPort`; cues earcon-only; duplex as today | Works in mainland China on the existing account. No ADR reopened. Verbal backchannel not available. |
| **2. Add a GPT-Live adapter behind the seam** | A second `RealtimeVoiceProvider`; `nativeDelegation` maps `delegation.type=client` onto the same `WorkCoordinator` / `DelegationPort`; `session.commentary.append` is its `deferredResultDelivery` | Meets ADR-008's own revisit trigger ("a capability Baidu lacks"), so it needs a new ADR, not a revival of deleted code. Open: availability and latency from the target market, cost, zh-CN voice quality, OpenAI credentials on device (I-7 unaffected: Keystore, `openai_*` namespace). Repo notes say GPT-Live has **no documented speech cancel** — interruption would be local flush only. Those notes date from 2026-09-14 and must be re-verified. |
| **3. Replace Baidu with GPT-Live** | Reopens ADR-002 | Not recommended: loses the only provider with device evidence, and the market constraints above apply to 100 % of use, not an option. |

The design in §3–§6 is identical under options 1 and 2; that is the point of building it on the
ADR-009 contract. Choosing option 2 later is one adapter plus capability flags, not a redesign.

---

## 9. Decisions required before any SPEC

```text
ARCHITECTURE_REVIEW_REQUIRED
CONSTRAINT:  SPEC-012 non-goal (proactive speech unmeasured); SpeechArbiter R6 (P1 window);
             PRODUCT.md non-goals (no own speech synthesis); ADR-008 (one provider);
             I-3 (no source, no answer)
EVIDENCE:    SpeechArbiter.kt header rows R1–R6a; ConversationResetPolicy.kt (reset per tool
             turn, measured 2026-09-17); BaiduFlexProtocol session.update silence_duration_ms=200;
             WorkCoordinator.kt contract; REALTIME_PROTOCOL_REFERENCES.md GPT-Live section
CONFLICT:    a deferred answer is speech the driver did not just trigger, arriving outside the P1
             window, possibly after the asking conversation was reset
OPTIONS:     §5.3/§5.4 (new DEFERRED_ANSWER row + deferred delivery) · §8 options 1/2/3
DOWNSTREAM:  SPEC-012 amendment; capabilities.yaml new rows; ARCHITECTURE.md owner rows; I-14/I-15
DECISION_REQUIRED: see D-A … D-F below
```

- **D-A — Scope.** Which of the four properties does the mentor actually want first? Proposal:
  delegation (largest visible gain), then duplex evidence, then pause tuning; cues last.
- **D-B — Acknowledgement cues.** Earcon-only on Baidu (option B), or wait for a provider with native
  backchannel? Or none: in a car a sound while the driver talks may be read as "it answered".
- **D-C — The background model.** Which service computes delegated answers (Qianfan text model,
  a search-grounded model, the realtime provider's own delegation)? Its credentials, cost ceiling,
  and whether search grounding is required for `kind=search`.
- **D-D — What leaves the phone.** May a delegated task carry the destination name or a coarse
  district? Coordinates? (I-8 covers logs; this is about a second vendor receiving them.)
- **D-E — Timing.** Deadline (proposal 20 s), TTL (proposal 60 s), and whether a stale answer is
  announced as stale or silently dropped.
- **D-F — SPEC-012 amendment.** Accept the `DEFERRED_ANSWER` and `ACK_CUE` rows as specified in
  §5.2/§5.4, including "PLAY as asked-for while navigating, outside the 10 s window, once per job".
- **D-G — Provider.** §8 option 1 now, with option 2 evaluated by an ADR if the mentor wants
  GPT-Live itself rather than its behaviour.

## 10. What this document does not do

It does not authorise implementation, create a SPEC, change a capability, edit an ADR, or fix the
stale capability documents listed in §1. It does not revive any code deleted by ADR-008. Once D-A to
D-G are answered, the intake path is `agent/INTAKE.md`: backlog row → SPEC (with §7 as its evidence
section) → ADR for D-F/D-G → milestone.
