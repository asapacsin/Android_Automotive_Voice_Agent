# Gemini-native voice path: architecture

Status: **Proposed 2026-09-29**, as [ADR-011](../DECISIONS/ADR-011-gemini-native-voice-path.md).
It is not in force until the owner decides N-1 and N-2 (§10). Requirement:
[SPEC-014](../SPECS/SPEC-014-gemini-native-voice-path.md) · Demand: B-028 ·
Plan: [GEMINI_NATIVE_PLAN.md](GEMINI_NATIVE_PLAN.md).

The owner's request: *"design a new architecture that should optimise for the new Gemini API,
because the last test said the old design is slow and poorly suited to it."*

It replaces two parts of [GEMINI_LIVE_ARCHITECTURE.md](GEMINI_LIVE_ARCHITECTURE.md): §3.1's model
and thinking rows, and §4.3's "F6 levers". It also answers ADR-010's open architecture question.
The rest of that document stays as written: the seam, the event mapping, interruption, credentials
and privacy.

Evidence: [reports/2026-09-29-gemini-live-probe.md](reports/2026-09-29-gemini-live-probe.md),
third round (F23–F32). These are cloud measurements, not the phone.

---

## Revision 2 (2026-09-29, after independent review): what supersedes what

Measured after the review: `gemini-3.8-live` delivers reply audio at about **4.4x speaking speed**.
In one run, a 9.3 s reply's audio had all arrived 2.1 s after its first chunk. `generationComplete`
came 25 ms after the last audio, and `turnComplete` came **7.2 s later**, paced to playback. The
extended-thinking model delivered audio at 1.0x. F29 read `turnComplete` as the end of the audio;
that was wrong for the fast model. So the slow conversational replies come from the client
settling the claim gate at `turnComplete`, not from the audio stream.

The design is therefore simplified. Where this section disagrees with the sections below, this
section wins:

1. **The gate settles at `generationComplete`, not `turnComplete`** (Gemini adapter). Every word and
   all the audio are in by then, so today's whole-reply verdict runs unchanged. I-1 wording does not
   change. The cost is the generation time, about a quarter of the reply's length on
   `gemini-3.8-live`, comparable to Baidu's measured 93–515 ms for short replies.
2. **Clause release (§5.1) is deferred.** It is needed only for models that deliver at 1.0x, such as
   extended thinking. The review found it unsafe as written: the kind can be reclassified after
   streaming starts, 「好的，」 streamed before a correction breaks I-2, and alignment by a
   characters-per-second estimate is unproven. Revisit only if a 1.0x model becomes the default.
3. **Default model `gemini-3.8-live`** (N-1), unchanged: 20/20 calls in 0.8–2.4 s. In a 15 s linger
   after the spoken result, 0 of 8 spoken commands sent a late second call.
4. **Fix two orderings the review found in today's gate (both providers, D-10):**
   - A call that opens a response registers after `decideHold`, so `AWAITING_TOOL_RESULT` never
     applies to it. The hold must be re-decided on `onToolCall`.
   - Once `PHANTOM_AUDIO` releases, the rest of that reply is never judged for a claim.
5. **Correction timing (§5.3):** keep today's rule that a correction goes out immediately once a
   call was dispatched in the driver turn. Add a rule that a repeat of a call that already succeeded
   (same name and arguments) in one driver turn is answered `duplicate_call_ignored`, not executed.
6. **One model registry** in `VoiceCatalog`: every declared model has a profile, and an undeclared
   model gets conservative traits. The thinking field becomes a neutral trait there, so there is no
   second list in the adapter.
7. N-2 is now "settle at `generationComplete`", with no invariant change. D-9 (hold budget) stays
   an open owner decision. ADR-008 is downstream of N-1, because the default no longer uses native
   thinking.

Revised build plan: T1 model registry and setup per model (includes `GeminiLiveClient` setup call)
→ T2 settle at `generationComplete` + D-10 fixes + per-driver-turn duplicate rule, with tests for
call-first order and phantom-then-claim → T3 smoke test: L1 calls ≤ 2.5 s; L2 first audio leaving
the gate ≤ audio arrival + generation time; L3 no claim before proof → review.

---

## 0. The answer in one paragraph (original proposal; see Revision 2)

The slowness has two causes, and they need different fixes.

1. **Actions are slow because of the model we chose, not our code.** The extended-thinking model
   speaks a filler, finishes the turn, thinks, and calls the tool in a *later* turn: 7–31 s later,
   or never. When it never calls, it sometimes reports a failure that did not happen (F24). The
   non-thinking `gemini-3.8-live`, on the same key and with the same tools and prompt, called the
   right tool 0.8–2.4 s after the driver stopped speaking, 20 times out of 20, before saying
   anything (F23).
2. **Conversation is slow because of our claim gate.** It holds a reply until the reply is complete,
   and Gemini streams replies at speaking speed, so the delay equals the reply's length (F29). Yet
   Gemini sends the transcript of every piece of speech *before* that audio (F25). So the gate can
   check each clause's words before the clause is heard, and let clean clauses through as they
   come.

The design therefore does three things:

- makes the realtime model a **declared profile**, so the shared logic adapts to how a model
  behaves rather than assuming Baidu;
- changes the claim gate to **release clause by clause** for models that stream in real time. No
  claim is released any earlier than today; everything else is released sooner;
- moves the **default Gemini model to `gemini-3.8-live`**. Deeper thinking goes behind a tool later,
  which is where a slow thinker belongs in a car.

I-1 keeps its meaning: nothing that claims an action is heard before proof.

---

## 1. Where the seconds go today, and after

Times are from the end of the driver's speech. Cloud container.

| Path | Today (extended thinking + whole-reply hold) | Proposed (`gemini-3.8-live` + clause release) | Evidence |
| --- | --- | --- | --- |
| 「把空调打开」: tool runs | 7–31.7 s, or never (1 of 12 today) | 0.8–2.4 s, median 1.24 s (20 of 20) | F20, F24, F23 |
| 「把空调打开」: first audio of the confirmation | after the call; never, when the call never comes | 2.0–5.9 s, median 3.5 s. Proof exists before it starts, so it is not held | F23 |
| Reported a failure that never happened | 2 of the 7 no-call replies that were transcribed | 0 of 20 | F24, F23 |
| 「你好，简单介绍一下你能做什么」 (≈9 s reply): first audio heard | provider's first audio **+ the whole reply** ≈ 10.6 s | ≈ the provider's first audio (1.3–2.0 s) + clause check (≈0 when the transcript leads) | F29, F25 |
| Turn end (server VAD) | 0.4–1.2 s | unchanged; the client doing it was not faster | F26 |

The first design's Baidu-shaped assumptions, stated plainly:

| Assumption | Baidu Flex | Gemini (measured) | What breaks |
| --- | --- | --- | --- |
| A reply arrives faster than it is spoken | yes: the hold cost 93–515 ms (P23) | no: real-time pace (F29) | whole-reply hold = reply length |
| A tool call comes in the same response as the words | yes | `gemini-3.8-live`: yes, before the words (F23). Extended thinking: in a later turn, or never (F24) | the claim correction had to wait 20 s |
| The words and the audio arrive together | roughly | the words arrive first (F25) | nothing; this is what makes clause release possible |
| The model needs a conversation reset after tool turns | yes (measured 2026-09-17) | not observed; Gemini is already exempt | nothing |

---

## 2. Principles

1. **A model declares how it behaves; shared policy adapts.** Behaviour varies on declared traits,
   never on a provider or model name (I-13, ADR-009). A new model means declaring its traits after
   probing it, not re-tuning the gate.
2. **I-1's meaning does not change. Its timing does.** "Nothing that claims an action is heard
   before `ok=true` proof" holds for every model. What changes is *when* non-claims are released.
3. **Speed comes from the model tier, not from getting around the model.** No command fast path is
   added (G-4 stays untaken). The fast model already calls tools in about a second. That keeps one
   route to execution, the one the dispatcher validates.
4. **The device-proven Baidu path does not move.** Baidu declares the traits it has today, so every
   existing Baidu behaviour and test stays as it is.
5. **One mechanism per behaviour** (I-10). Clause release is a mode of the one gate in `DriverTurn`,
   not a second gate. Correction timing becomes a trait-driven rule in the pipeline instead of a
   timer inside one adapter.

---

## 3. The trace

```
microphone → AEC3 → AndroidMicrophonePort → SpeechUplinkGate ─ onset/offset → session core
                                                  │                  ("driver speaking", ADR-010)
                                                  ▼
                         GeminiLiveClient (WSS, model = gemini-3.8-live, server VAD)
                                                  │
     inputTranscription + ACTIVITY_END (0.4–1.2 s) ┤
                     toolCall (0.8–2.4 s, before any speech)
                                                  ▼
             RealtimeToolCatalog.validate → AndroidToolDispatcher → executor → ToolDispatchResult
                                                  │  toolResponse (proof: ok=true)
                                                  ▼
            reply: outputTranscription chunk (ahead) … audio chunks (real time) … turnComplete
                                                  ▼
          DriverTurn clause gate: judge each complete clause's words → release its audio+subtitle
                                   first claim without proof → hold the rest (existing verdicts)
                                                  ▼
                        SpeechArbiter → AndroidPlaybackPort → PcmAudioPlayer (24 kHz)
```

Deep reasoning (later, §5.4): `delegate_task` → `DelegationPort` → a thinking model → the result
goes back as that call's `toolResponse`.

---

## 4. The model profile

Today `ProviderCapabilities` is resolved per provider. It becomes resolved per **(provider,
model)**: `VoiceCatalog.capabilities(provider, model)`. Two traits that policy needs are added:

| Trait (ingress, policy-relevant) | Baidu Flex | `gemini-3.8-live` | `gemini-3.8-live-extended-thinking` | Read by |
| --- | --- | --- | --- | --- |
| `replyStreamsInRealTime` | false (P23) | true (F29) | true (F21, F29) | `DriverTurn` release mode |
| `toolCallsMayFollowInLaterTurn` | false | false (F23, F26: none of 29 calls came late) | true (F20, F24) | correction timing (§5.3) |
| `serverSpeechActivityEvents` (exists) | true | false (by choice, ADR-010) | false | session core |
| `toolCallCancellation` (exists) | false | true (protocol) | true | session core |

Conversation resets are not a new trait. `ConversationResetPolicy` is only wired into the Baidu
adapter, and that stays true.

Wire-only facts stay in the adapter's model table (`GeminiLiveModels`, in `app`) and never reach
`ingress`:

| Model | Thinking field in `setup` | Output | Notes |
| --- | --- | --- | --- |
| `gemini-3.8-live` | none; `thinkingLevel` is rejected with 1007 (F27) | 24 kHz | proposed default (N-1) |
| `gemini-3.8-live-extended-thinking` | `thinkingLevel` required, `MINIMAL` rejected (F1) | 24 kHz | kept selectable (N-3) |

Declaring a model means one row in each table, taken from a `latency_probe.py` run. It never means
an `if (model == …)` in policy code (`ProviderBoundaryTest` gains that rule).

---

## 5. Components

### 5.1 Clause release in `DriverTurn` (the owner of what may be claimed)

For a turn whose provider has `replyStreamsInRealTime`:

- **R-S1 Unit.** A *clause* is reply text up to and including a clause mark
  (`，。！？；：、` or a newline), or everything left at the end of the response.
- **R-S2 Judge on words, before the audio.** Each time a clause completes, the prefix up to and
  including it is judged with the **same drop predicates** the end-of-response verdict uses for the
  current hold reason (table below). One classifier, applied earlier; not a second one.
- **R-S3 Release what is clean.** While no predicate has fired, audio is released up to
  `min(audio received, clean characters × 150 ms)`. Clean characters are the letters and digits of
  the judged prefix, which excludes punctuation. 150 ms per character is below the fastest turn
  measured (203 ms, F25). So released audio never runs past the words that were checked, even if a
  future reply's transcript stops leading its audio. The subtitle for the released clauses goes out
  with that audio (I-5: they are released together).
- **R-S4 First claim closes the gate.** From the first prefix that trips a predicate, nothing more
  of this response is released early. The held remainder gets **exactly today's end-of-response
  verdict**: released on proof, a tool call or a later honest refusal, or dropped with today's
  correction. What was already heard contained no claim, by R-S2. Some predicates can trip on a
  prefix and then clear by the end of the reply. `fabricatesRealtimeInfo` is "does not decline", so
  「好的，」 trips it and 「好的，不过我查不到天气」 clears it. For those predicates the gate closes
  early and the remainder falls back to today's whole-reply timing. That is never unsafe, only not
  faster.
- **R-S5 The hold budget releases only judged-clean words.** Today `onHoldBudgetExceeded`
  releases *any* hold once 120 events are held, including a reply still waiting for proof, and
  `DriverTurnTest.theHoldBudgetAlwaysReleasesRatherThanStalling` pins that on purpose ("a real
  reply is never lost to a stuck gate"). That is an I-1 gap on both providers: the released text
  was never judged. It is narrow for Baidu (120 events ≈ 6 s of audio) but real. Proposed: at the
  budget, release what the clause check shows to be clean and keep holding the rest until the end
  verdict. A hard cap of 60 s of held audio *drops* the remainder rather than releasing it (silence
  over a false claim). This changes a deliberate Baidu behaviour, so it is part of N-2, not an
  autonomous fix. Recorded as [TECH_DEBT.md](TECH_DEBT.md) D-9.
- **R-S6 Unchanged for Baidu.** With `replyStreamsInRealTime = false`, the gate behaves exactly as
  today. Every existing `DriverTurnTest` must pass unmodified.

Which hold reasons stream:

| Hold reason | Streams? | Opens when | Closes on (the end verdict's own drop predicate) |
| --- | --- | --- | --- |
| `UNCLASSIFIED_CLAIM` (all chat) | yes | at once | `carActionClaimMatch`; a repeat request to heard speech (`repairForHeardDriver`) |
| `AWAITING_EXECUTION_PROOF` | yes | at once | `claimsDone` |
| `NO_TOOL_REQUEST` (no tool, realtime info) | yes | at once | `claimsDone`; `fabricatesRealtimeInfo` (so a realtime-info reply streams only once a clause declines; one answered from a successful lookup is not held at all, as today) |
| `CAPABILITY_HELP` | yes | once the prefix names at least two supported groups (`answersCapabilityHelp`) | a claim |
| `PHANTOM_AUDIO` | already releases early, on the first reply text with content | unchanged | unchanged |
| `ECHO_CANDIDATE` | no | not until a driver is confirmed | whole-response, as today |
| `AWAITING_TOOL_RESULT` | no | never before the result: anything said now is invented (camera) | whole-response, as today |

Cost: a clean reply is heard when its first clause's words are in, and they arrive ahead of its
audio. A reply that claims an action is cut before the claim, and today's correction follows.

### 5.2 `DriverTurnPipeline` and the adapters

- The pipeline accounts audio duration per held `AudioDelta` at the session's output rate, and
  performs partial releases (`Verdict.ReleaseUpTo`). Holding stays inside `DriverTurn`.
- `GeminiLiveClient` passes each `outputTranscription` chunk to the pipeline as it arrives (it
  already does), and emits the subtitle per released clause, not once at `generationComplete`.
- Session core: `AssistantTranscript(final = false)` updates the current subtitle line instead of
  being ignored (today only `final` subtitles reach the overlay).

### 5.3 Correction timing by trait

Today a correction for "claimed but never called" goes out at once (Baidu) or after a 20 s timer
inside `GeminiLiveClient`. It becomes one rule in the pipeline, keyed by a trait:

- `toolCallsMayFollowInLaterTurn = false` (Baidu, `gemini-3.8-live`): sent when the response ends,
  as Baidu does today.
- `true` (extended thinking): deferred until the provider reports it is idle
  (`ProviderWorkState(pending = false)`), capped at 20 s. Cancelled by a tool call, a new onset, a
  disconnect or suspended listening. *Not* shortened on `IN_PROGRESS`: F24 shows `IN_PROGRESS`
  followed by no call.

The timer code leaves `GeminiLiveClient` in the same change (delete what you replace).

### 5.4 Model tier

- **Realtime front:** `gemini-3.8-live` is the default Gemini model (N-1). It handles every
  actuating tool synchronously, which is the shape I-1's gate was built for.
- **Extended thinking:** stays selectable in developer settings, labelled with its measured
  behaviour. Its traits route it through §5.3. Its reliability problem (F24: fabricated failures,
  no calls) is the model's own. The gate still keeps any unproven *success* claim from being heard,
  but a spoken *failure* claim is not a success claim. That is why it is not the default. N-3
  decides whether to keep it at all (ADR-008: nothing dormant).
- **Deep thinking behind a tool (next phase, not in this build):** `delegate_task` →
  `DelegationPort` (A§5.3) → a thinking text model, with the result as the call's `toolResponse`.
  It is blocked on two measurements: F31 (`gemini-3.8-flash` returned 503 today) and F30
  (`NON_BLOCKING` on `gemini-3.8-live` did not speak the result). Until then the fast model answers
  knowledge questions itself.

### 5.5 What stays exactly as it is

Server VAD as the turn authority (F26: the client was not faster). The local uplink gate as the
"driver speaking" source. Server-owned interruption, with `NO_INTERRUPTION` as the documented
fallback if G-M2 fails on the phone. The tool catalogue and dispatcher. `SpeechArbiter` and
playback epochs. `DriverContext` and its hint at setup. Resumption and `goAway` handling. Opt-in,
consent and the Keystore key. The Baidu adapter.

---

## 6. Invariants

| Invariant | Change |
| --- | --- |
| I-1 | **Wording of the "Consequence" paragraph only** (N-2). Today: "the reply's audio and subtitle wait until that proof exists". Proposed: "any part of the reply that claims the action happened waits until that proof exists. On a provider whose replies stream in real time, the parts before it that claim nothing may be heard as each clause is judged ([ADR-011](../DECISIONS/ADR-011-gemini-native-voice-path.md))." The rule itself, and "the model's words are never evidence", do not change |
| I-5 | Unchanged: audio and subtitle are released together, clause by clause |
| I-10 | One gate (clause release is its mode); one correction-timing rule (it leaves the adapter) |
| I-11 | `CALL_FIRST_HINT` stays tone only. The fast model calls first without depending on it (F23 used it; not measured without) |
| I-13 | Traits per (provider, model); `ProviderBoundaryTest` also forbids model-name branches |

---

## 7. Ownership rows ([ARCHITECTURE.md](ARCHITECTURE.md), on acceptance)

| Behaviour | Canonical owner | Not owned by |
| --- | --- | --- |
| How a model behaves (pacing, tool-call timing, speech events) | `VoiceCatalog.capabilities(provider, model)`, filled from probe evidence | a provider- or model-name branch |
| Wire fields a model accepts (thinking) | the adapter's model table (`GeminiLiveModels`) | `ingress`, settings UI |
| What may be heard of a reply, and when | `DriverTurn` (whole-reply or clause release, by trait) via `DriverTurnPipeline` | the adapters |
| When a claim correction is sent | `DriverTurnPipeline`, by `toolCallsMayFollowInLaterTurn` | a timer in an adapter |

---

## 8. Acceptance targets

Cloud, through the app's own client (`GeminiLiveSmokeTest`, extended):

- **L1** spoken command → tool call dispatched ≤ 2.5 s after the end of speech, in at least 9 of 10
  runs (`gemini-3.8-live`).
- **L2** a clean conversational reply: the first audio *leaving the gate* ≤ 300 ms after the
  provider's first audio. It no longer grows with the reply's length.
- **L3** no completed-action claim leaves the gate before its tool result (the smoke test's
  existing assertion, now for both models), and the unit tests of §9.

Device: time from the end of speech to the first audio heard, and self-interruption, recorded in
`GEMINI-DEVICE-LATENCY-001` and `GEMINI-DEVICE-DUPLEX-001`. No device number is claimed before it
is measured.

---

## 9. Risks and unknowns

| Risk | Handling |
| --- | --- |
| The transcript stops leading its audio (model update, typed turns) | R-S3's bound makes the gate wait for words; slower, never unsafe. A replayed-trace test pins this |
| A claim split across two clauses (「空调已经，打开了」) | the prefix, not the clause, is judged, so the second clause trips the predicate before its audio is released; tested |
| The first clause is itself the claim (「已经为您打开空调。」) | nothing released; today's verdict applies; tested |
| A local speaking rate above 6.7 characters per second | the 150 ms floor has margin under the 203 ms minimum; tunable; a trace test with a fast reply |
| `gemini-3.8-live` persona and answer quality vs extended thinking | not measured beyond the transcripts in F23. The owner hears it on the phone (the existing device rows) |
| Reachability from the phone's network | G-2 answered: the owner uses a VPN |
| Echo self-interruption at 24 kHz render | G-M2, unchanged; the `NO_INTERRUPTION` fallback stands |

---

## 10. Decisions required

```text
ARCHITECTURE_REVIEW_REQUIRED
CONSTRAINT:  ADR-010 decision 1 (the owner chose gemini-3.8-live-extended-thinking); I-1
             "Consequence" wording (reply audio and subtitle wait until proof exists)
EVIDENCE:    probe report F23 (20/20 calls in 0.8-2.4 s on gemini-3.8-live), F24 (1/12 calls on
             extended thinking, 2 spoken failures that never happened), F25 (transcript leads its
             audio in 45/45 turns), F29 (real-time pacing on both models)
CONFLICT:    the chosen model cannot meet a car's action latency; the whole-reply hold cannot meet
             conversational latency on any real-time-paced model
OPTIONS:     N-1 (a) default gemini-3.8-live (recommended) (b) keep extended thinking as default
             N-2 (a) clause release by trait, I-1 wording as in §6 (recommended)
                 (b) keep the whole-reply hold (conversation stays as slow as its length)
             N-3 (a) keep extended thinking selectable (recommended until the phone has tried both)
                 (b) remove it from the model list
DOWNSTREAM:  ADR-010 (decision 1, open question), ADR-011, INVARIANTS I-1, SPEC-013 limitations,
             SPEC-014, ARCHITECTURE rows (§7), capabilities.yaml
DECISION_REQUIRED: N-1 and N-2 (N-3 can follow the phone test)
```

The owner said on 2026-09-29 that if Gemini causes problems he will "switch to another similar
model". N-1 (a) is such a switch, within the same provider and seam.

---

## 11. Build plan (after N-1/N-2)

Dependency graph: T1 ∥ T2 → T3 → T4 → T5. At most two parallel writers, in separate worktrees
created from the feature head.

| Wave | Task | Owned scope | Proven by |
| --- | --- | --- | --- |
| 1 | **T1 Model profile.** `capabilities(provider, model)` plus the two traits; `GeminiLiveModels` table; `setup` thinking field per model; model picker default; `ProviderBoundaryTest` model-name rule | `ingress` `VoiceCatalog` (+tests), `GeminiLiveProtocol`, `GeminiSettings`, `DeveloperSettingsActivity`, `GeminiLiveProvider`, `behavior-test` | setup JSON per model; capability-per-model tests; a 1007-shaped setup is impossible for either model |
| 1 | **T2 Clause release, pure.** R-S1…R-S6 in `DriverTurn` behind a constructor flag; `Verdict.ReleaseUpTo`; the R-S5 budget fix | `DriverTurn.kt`, `DriverTurnTest`, new `DriverTurnClauseReleaseTest` | every existing `DriverTurnTest` unchanged and green; the new cases in §9 |
| 2 | **T3 Pipeline and subtitles.** Audio duration accounting, partial release, per-clause subtitle, core `final=false` subtitle update, trait wiring from the session's capabilities | `DriverTurnPipeline`, `GeminiLiveClient`, `ingress` `VoiceSessionController` subtitle branch | `GeminiLiveClientTest` streamed cases; replayed F25 traces; Baidu golden and client tests unchanged |
| 3 | **T4 Correction timing by trait.** Move the deferral into the pipeline, keyed on `toolCallsMayFollowInLaterTurn` plus `ProviderWorkState`; delete the client timer | `DriverTurnPipeline`, `GeminiLiveClient` | the existing deferral tests moved; a Baidu immediate-correction test unchanged |
| 4 | **T5 Evidence.** Smoke test measures L1–L3 for both models; planner-owned docs: I-1 wording, ARCHITECTURE rows, capabilities.yaml, SPEC-013/014 states, TEST_MATRIX (`GEMINI-DEVICE-LATENCY-001`), TECH_DEBT R-S5 | smoke test; docs | `NOVA_GEMINI_LIVE_SMOKE=1` runs; full `test --rerun-tasks :app:assembleDebug`; independent review |

The reviewer is required after T3/T4, because they change a state machine and the correction
semantics.

---

## 12. What this document does not do

It changes no code, no invariant text and no setting. It records a proposal, the evidence behind it
and the decisions it needs. On acceptance the intake path is [agent/INTAKE.md](../agent/INTAKE.md):
ADR-011 → Accepted, SPEC-014 → the milestone, then the waves above.
