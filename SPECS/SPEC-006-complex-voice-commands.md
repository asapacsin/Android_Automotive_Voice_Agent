# SPEC-006 — Contextual voice commands

Status: **Partly implemented 2026-09-19.** Built: the context record and its staleness rules, the
resolver (implicit intents, lexical binding, ambiguity, reversal, clarification answers), the hint
that carries a resolution into each fresh conversation, and the two execution guards
(`MEDIA_LIBRARY_UNSUPPORTED`, `DUPLICATE_IN_TURN`). Not yet built: navigation-phase cases beyond the
hint text, and multi-intent decomposition. See §Implementation status.
Raised: 2026-09-19 · Source: product-owner requirement 「后续可以试试复杂的语音指令」, with a modern
automotive conversational assistant as the reference interaction style.
Depends on: [I-1, I-2, I-3, I-4](../docs/INVARIANTS.md) · [capabilities.yaml](../config/capabilities.yaml) ·
[ARCHITECTURE.md](../docs/ARCHITECTURE.md#who-owns-what) · [EVALUATION.md](../docs/EVALUATION.md)

---

## Goal

Today the driver must speak like an API: 「空调调到22度」 works, 「有点热」 does not. The requirement is
to move from a **voice command system** to a **contextual voice agent** — the driver states a goal,
or continues a previous one, and the assistant resolves it against what it already did.

This is **not** a request for an open-ended agent. The assistant stays bounded by the tools that
exist in [capabilities.yaml](../config/capabilities.yaml). It must resolve naturally-phrased requests
into **real executions**, and refuse or ask when the referent is ambiguous, unsupported, or
under-specified.

The failure this spec exists to prevent:

> Driver: 「有点热。」
> 小诺: 「好的，我帮你调凉一点。」
> …and the cabin temperature did not change.

Understanding is not execution. A sentence is not evidence ([I-1](../docs/INVARIANTS.md)).

---

## The constraint that shapes the whole design

**The model has no cross-turn memory in this product.**
`ConversationResetPolicy` starts a fresh Baidu conversation after *every* completed tool turn. That
is not an accident to be undone: it was measured on device 2026-09-17 — in one long conversation the
model fails from about the third tool turn (empty replies, actions executed one turn late; 「关闭空调」
raised the temperature). One fresh conversation per command scored 8/8.

So 「再凉一点」 arrives at a model that does not know anything was adjusted.

Two consequences, and they are the backbone of this spec:

1. **Context must be owned by the app, not by the conversation.** It is assembled from
   *authoritative tool results* and handed to each new conversation. The existing owner is
   `VoiceContextHints`, which already does exactly this for the navigation phase and the camera —
   and which exists *because* of this same problem (「算了」 with the list open got 「没听清」 until the
   hint was added). Extend that owner. Do not add a second one ([I-10](../docs/INVARIANTS.md)).
2. **Context is therefore inspectable and testable**, because it is a value the app computed, not a
   property of a black box. Every benchmark case below can assert on it.

---

## Scope

Included, for the capabilities that exist today:

- implicit goals mapped to a bounded set of actions;
- relative continuation and reversal of the last adjustment;
- reference to, and modification of, the task currently on screen;
- feedback that a previous action was insufficient;
- more than one intent in one utterance;
- correction and cancellation of a task that has not completed;
- honest refusal when the natural phrasing implies a capability that does not exist.

## Non-goals

| Not in this phase | Why |
| --- | --- |
| Removing or weakening the conversation reset | Measured device evidence says it is load-bearing. Context is injected instead. |
| Barge-in during 小诺's own speech | `speech.interrupt_tts_by_voice` is `unsupported` — the mic is gated while the assistant speaks. The wake word is the interrupt. Correction here is always **cross-turn**. |
| A music library, track search, or artist/lyric lookup | `media` is one bundled track with play/stop. `next_track` is `unsupported`. See §"Adapting the reference examples". |
| Volume, windows, seats, doors, phone calls | Already `unsupported`; this spec must not create a path that appears to support them. |
| Weather, traffic, news as context | No source exists ([I-3](../docs/INVARIANTS.md)). 「有点热」 is a comfort request, never a weather question. |
| A planner, a dialogue-state framework, or an LLM-driven agent loop | The behaviour below is satisfiable with a bounded context record. If an implementation finds a case that genuinely is not, that is a finding to record, not a licence to add one. |
| Persisting context across app restarts | Out of scope. A cold start begins with empty context. |
| Multi-driver / speaker identification | No capability. |

---

## Capability ground truth, and adapting the reference examples

Read from [config/capabilities.yaml](../config/capabilities.yaml) at the commit this spec was
written (`meta.updated: 2026-09-18`). **The implementation must re-read it, not trust this copy.**

| Domain | Usable for context work today |
| --- | --- |
| `climate` | `power_on/off`, `set_temperature` (16–32), `set_fan` (0–7), `adjust_temperature`, `adjust_fan`, `get_state`. Results carry `power_on`, `temperature_c`, `fan_level`, `limit_reached`. |
| `navigation` | search → destination candidates → route candidates → start → stop. Selection by index, by name (fuzzy), by preference (`nearest` for destinations; `fastest`/`shortest`/`no_toll`/`fewest_lights`/`recommended` for routes). |
| `media` | play / stop **one bundled track**. Nothing else. |
| `vision` | `describe_camera_view`. |
| `apps` | open maps, open settings. |

Three of the reference examples cannot be honoured as given, and are adapted rather than faked:

| Reference example | Why it cannot stand | Adapted requirement |
| --- | --- | --- |
| 「放一下周杰伦那首我忘了名字的歌，就是讲晴天的那个。」 | There is no music library, no track metadata, no search. | Becomes an **unsupported-capability case**: refuse once, honestly, and do **not** start the bundled track. Today this is a live false-success risk — `ActionClaimGuard.UNSUPPORTED_WORDS` does not cover music-library requests, so this phrasing plausibly reaches `control_music{play}` and plays something the driver did not ask for. §Failure behaviour F-7. |
| 「对，就是那个，帮我放一下。」 (cross-turn media reference) | Same: nothing to refer to. | Cross-turn reference is specified against **navigation candidates**, which do have names, distances and screen positions. |
| 「等等，别导航了」 spoken over 小诺's reply | The microphone is gated while the assistant speaks. | Specified as **cross-turn supersession**: the same words in the driver's next turn must cancel the pending navigation task. |

One example needs its climate wording pinned down: 「再凉一点」 is lexically bound to temperature and
is always resolvable; 「再低一点」 is not, and is the ambiguity case (§Ambiguity policy).

---

## Interaction classes

Each class is a testable behaviour, not a phrasing. `CVC-nn` refers to the benchmark in §Benchmark.

| # | Class | Definition | Resolution source | Cases |
| --- | --- | --- | --- | --- |
| C1 | Explicit atomic command | Every parameter is in the utterance. | none | CVC-01…03 |
| C2 | Implicit goal | A state or discomfort is stated instead of an action. | the implicit-intent table | CVC-04…08 |
| C3 | Relative continuation | An adjustment direction with no named dimension or target. | last adjustment, or lexical binding | CVC-09…13 |
| C4 | Relative reversal | Asks to undo or partly undo what was just done. | last **successful** adjustment, signed | CVC-14…16 |
| C5 | Cross-turn reference | Refers to something on screen, or answers a question the app asked, without restating it. | active candidate list, or `pendingClarification` | CVC-17…19 |
| C6 | Cross-turn task modification | Keeps the task, changes a parameter of it. | navigation phase + candidate list | CVC-20…23 |
| C7 | Fuzzy entity resolution | Names a candidate partially or approximately. | candidate names, existing fuzzy matcher | CVC-24…26 |
| C8 | Feedback-driven recovery | Reports that the previous action was insufficient. | last successful adjustment + `limit_reached` | CVC-27…30 |
| C9 | Multi-intent | Two or more independent goals in one utterance. | decomposition + ordering | CVC-31…34 |
| C10 | Correction / supersession | A new request replaces a task that has not completed. | active task + turn epoch | CVC-35…37 |
| C11 | Cancellation | Abandons the active task without replacing it. | active task | CVC-38…39 |
| C12 | Unsupported-capability handling | Natural phrasing implies a capability that does not exist. | capability registry | CVC-40…43 |
| C13 | Stale context | The referent has expired and must not be reused. | context TTL and invalidation rules | CVC-44…46 |

---

## Context model

### What may be used

Exactly this, and nothing else:

| Field | Source — **authoritative only** | Lifetime |
| --- | --- | --- |
| `utterance` | the current transcript | this turn |
| `climate.power_on / temperature_c / fan_level` | the last `control_climate` result with `ok=true` | until superseded by a newer result |
| `lastAdjustment` = {dimension, signedDelta, resultValue, limitReached, atMs, turnEpoch} | the last **successful** `adjust_*` or `set_*` climate result | TTL 180 s, plus the invalidation rules below |
| `navigation.phase` | `NavigationStateStore` | live |
| `navigation.candidates` = list of {position, name, distanceMeters} | the active destination or route list | only while phase is `AWAITING_DESTINATION_SELECTION` / `AWAITING_ROUTE_SELECTION` |
| `navigation.activeDestination` | the started route | while phase is `NAVIGATING` |
| `media.playing` | the last `control_music` result with `ok=true` | until superseded |
| `camera.open` | `CameraVisionGateway` | live |
| `pendingTask` = {kind, turnEpoch, phase} | the task started by the current or previous turn that has not reached a terminal state | until terminal or cancelled |
| `pendingClarification` = {dimensionOptions, candidateOptions, askedAtEpoch} | **the app's own clarification decision** (§Ambiguity policy), recorded when it decides to ask | the next driver turn only |
| `cancelledEpochs` | turns cancelled by supersession | for the session |

`pendingClarification` exists because clarification is mandatory in a dozen cases below, and a
question the driver cannot answer is worse than a guess. It records the options **the app chose to
offer**, not the sentence the model produced — the two must not be confused, and only the former is
context. It expires after one driver turn: if the driver says something unrelated, the clarification
is abandoned, not re-applied later.

**Never usable as context:** the model's own previous sentences; a tool call that was dispatched but
whose result was not `ok=true`; anything from a `CANCELLED` turn; anything from a previous app
process.

### Where it lives

`VoiceContextHints` is the owner — it already composes app state into the instructions of every new
conversation, for exactly this reason. This spec extends the record it composes. It does **not**
authorise a second context mechanism anywhere else ([I-10](../docs/INVARIANTS.md)).

The context record must be **derived from tool results**, never from what the model said. Climate
results already carry `power_on`, `temperature_c`, `fan_level` and `limit_reached`; that is the input.

### When context is stale and must not be reused

A referent is **invalid** if any of these is true. Each is independently testable.

| Rule | Condition |
| --- | --- |
| S1 — age | `now - atMs > 180_000 ms` |
| S2 — session | the listening session ended, or `ListeningLifecycle` entered `SLEEP` or `DEEP_IDLE` since `atMs` |
| S3 — process | the app process restarted |
| S4 — superseded | a newer successful action on the **same** dimension exists (only the newest is the referent) |
| S5 — cancelled | the turn that produced it is in `cancelledEpochs` |
| S6 — unproven | the action's tool result was absent, `ok=false`, or timed out |
| S7 — screen gone | for navigation candidates: the phase is no longer the one that produced the list |

When a referent is invalid and the utterance needs one, the assistant **asks**; it must not fall back
to a default dimension or a remembered value. Silently guessing is a defect, not a convenience
(CVC-44…46).

### Implicit-intent table — bounded on purpose

The complete set of implicit mappings. Anything not in this table is **not** an implicit intent and
must be handled as an ordinary request (or refused).

| Driver states | Resolves to | Magnitude | Precondition |
| --- | --- | --- | --- |
| 热 / 有点热 / 太热了 / 闷热 | `control_climate{adjust_temperature, -2}` | 2 °C | if `power_on == false`, `power_on` first (D1 below) |
| 冷 / 有点冷 / 太冷了 | `control_climate{adjust_temperature, +2}` | 2 °C | same |
| 风太大 / 风太吵 | `control_climate{adjust_fan, -1}` | 1 level | power on |
| 风太小 / 不够风 / 闷（无「热」） | `control_climate{adjust_fan, +1}` | 1 level | power on |
| 想去公司 / 去公司吧 | `navigate_to{公司}` | — | — |
| 想听点音乐 / 太安静了 | `control_music{play}` | — | — |

**D1 — the power dependency.** The simulated backend happily changes a target temperature while the
HVAC is off, and the driver would feel nothing while the assistant reports success. So when
`power_on == false`, an implicit comfort intent is a **two-call dependent sequence**: `power_on`
must return `ok=true` before the adjustment is issued, and the spoken reply covers both. If
`power_on` fails, the adjustment is not attempted and the reply says the climate could not be turned
on. This rule is a product decision, not a backend constraint.

Magnitudes: implicit discomfort = **2** steps, explicit relative continuation = **1** step. Fixed so
the benchmark has an oracle; tunable later as one constant, not per phrase.

---

## Ambiguity policy

An utterance is resolved **only** when the rules below produce exactly one referent.

**Lexically bound** — resolves regardless of history, because the dimension is in the words:

| Words | Dimension |
| --- | --- |
| 凉 / 冷 / 暖 / 热 / 温度 / 度数 | `climate.temperature` |
| 风 / 风量 / 风速 / 档 | `climate.fan` |

So 「再凉一点」 → `adjust_temperature{-1}` with or without prior context.

**Dimension-neutral** — 「再高一点」「再低一点」「再大一点」「再小一点」「调回来一点」「再来一点」:

Let `R` = the set of dimensions with a **valid** (per S1–S7) successful adjustment.

| `|R|` | Required behaviour |
| --- | --- |
| 1 | Infer that dimension and execute. |
| 0 | **Clarify.** One question naming the choices. No tool call. |
| ≥ 2 | **Clarify**, naming which two. No tool call. |

**Navigation-neutral** — 「换一个」「换个近一点的」「这个太远了」:

| Phase | Required behaviour |
| --- | --- |
| `AWAITING_DESTINATION_SELECTION` | `choose_navigation_option{preference: "nearest"}` — supported for destinations. |
| `AWAITING_ROUTE_SELECTION` | 「近」/「短」 on a route means distance: `choose_navigation_option{preference: "shortest"}`. `nearest` is rejected for routes (`PREFERENCE_NOT_FOR_ROUTES`) and must not be sent. |
| `NAVIGATING` | No candidate list exists; the driver wants a different destination that has not been named. **Clarify** (「想换到哪儿？」). Must not exit navigation, and must not re-search on a guess. |
| `IDLE` | No task to modify. **Clarify.** |

**Clarification is one sentence, asks for exactly the missing parameter, and never lists the screen**
(the list is already visible; see `PersonaProfiles` and the existing "不要念出列表" rule). A
clarification turn calls no tool and therefore makes no claim — `DriverTurn.Kind.CONVERSATION`.

**Clarification is mandatory, not optional**, wherever the table says so. Guessing correctly on a
case marked *clarify* still fails the benchmark: the oracle tests the decision, not the luck.

---

## Tool execution contract

```
driver utterance
  → interpreted intent          (class + dimension/target, from context)
  → capability check            against capabilities.yaml; no tool ⇒ refuse, never substitute
  → resolved parameters         concrete action + value, or CLARIFY
  → tool call                   validated by FlexFunctionCallAssembler (fields, bounds, enums)
  → dispatch                    AndroidToolDispatcher — the only bridge to a device action
  → authoritative result        ToolDispatchResult {ok, status|error, next}   ← the only truth
  → context update              only when ok=true
  → spoken + displayed reply    released only after that result exists
```

Nothing in this spec permits a shortcut across that chain. In particular:

- An intent the app resolved internally but did **not** dispatch has not happened.
- A dispatched call whose result is `ok=false`, missing, or late has not happened.
- The context record is updated from the **result**, never from the intent or the reply.

## Truthfulness

Restates [I-1](../docs/INVARIANTS.md) for this feature; it adds no new mechanism.

- No spoken or displayed success claim before an authoritative `ok=true`.
- Audio and subtitle are held together and released together ([I-5](../docs/INVARIANTS.md)); a
  claim that is never proved is dropped unheard.
- **Partial success is never reported as success** (§Multi-intent).
- `limit_reached: true` is not a failure but is also not full success: the reply must say the limit
  was reached (「已经最低了」), not 「调好了」.
- A clarification question is not a claim and is never held.

---

## Multi-intent behaviour

**Decomposition.** An utterance may carry more than one intent. Each intent is resolved
independently through the contract above. An intent that cannot be resolved does not block the
others.

**Ordering.**

| Situation | Rule |
| --- | --- |
| The driver stated an order (先…然后…) | Execute in that order. |
| No stated order | Any order is acceptable **except** that a dependent pair keeps its dependency. |
| Dependent pair (D1 power→adjust; search→choose) | Strictly sequential; the second is issued only after the first returns `ok=true`. |
| Independent intents (climate + navigation) | A failure in one does not cancel the other. |

**Duplicate prevention.** Relative adjustments are *not* idempotent — issuing
`adjust_temperature{-2}` twice is −4 °C. Therefore:

- each resolved intent carries the `DriverTurn` epoch and an intent key (`domain.action`);
- what is tested is the **observable total**, not the shape of the calls. 「调凉一点，再凉一点」 may
  arrive as one `adjust_temperature{-2}` or as two `{-1}` calls; both keep the driver's two intents
  (FC-6). What fails is a total of −1 (an intent was dropped) or −4 (one was applied twice);
- a call that is **identical** — same tool, same arguments, same driver turn — is refused before it
  executes and returns `DUPLICATE_IN_TURN`, which the model answers from. This is the case that
  silently doubles a physical change: a protocol retry or a model repeating itself. A driver who
  genuinely asks twice speaks twice, which is two turns and two epochs, so it cannot be swallowed;
- a retry after a failed attempt is a *new* intent key only if the prior result was `ok=false`.

**Partial failure.** If some intents succeed and others do not, the single reply must name both
outcomes, in one sentence where possible. 「空调调凉了，导航没能打开」 passes; 「都弄好了」 fails.

**Response semantics.** One reply per driver utterance, however many intents it contained
(`ResponseTurnGate` already enforces one reply at a time). Replies stay one sentence and never read
out an on-screen list.

**Navigation never completes inside a multi-intent turn.** `navigate_to` produces *candidates*; the
route has not started. A multi-intent reply may say the destinations are listed; it may never say
navigation started (already enforced by the tool description and `DriverTurn`).

---

## Correction, supersession and interruption

Because the mic is gated while 小诺 speaks, correction always arrives as the **next** driver turn.

| Event | Required behaviour |
| --- | --- |
| New utterance supersedes a task not yet terminal | The superseded task's turn is marked `CANCELLED`; its epoch enters `cancelledEpochs`. |
| A late result arrives for a cancelled epoch | Discarded. It must not update context, must not release audio, must not produce a reply. (`DriverTurn` already refuses events for a cancelled epoch — this spec adds no mechanism, it requires the existing one to cover multi-intent turns too.) |
| 「等等，别导航了，只保留空调」 with a candidate list showing | `exit_navigation_mode` is **called** — a verbal acknowledgement leaves the list on screen. The climate change already executed is **not** undone. |
| A cancelled intent that had not been dispatched | Never dispatched. |
| A cancelled intent already dispatched and irreversible | Not undone; the reply says what already happened rather than claiming it was stopped. |
| Cancellation race: cancel arrives while the result is in flight | Whichever order occurs, the outcome is one of: the action did not run, or the action ran and the reply says so. Never: the action ran and the reply says it was cancelled. |

---

## Failure behaviour

| # | Situation | Required behaviour |
| --- | --- | --- |
| F-1 | Capability does not exist | One honest sentence, identical in speech and subtitle, no tool call, no substitute action ([I-2](../docs/INVARIANTS.md)). |
| F-2 | Tool call malformed | Rejected by `FlexFunctionCallAssembler` before execution; the failure returns to the model with `ToolFailureAdvice.next`; no claim released. |
| F-3 | Execution failed (`ok=false`) | The reply states the failure. A success sentence produced anyway is dropped and corrected ([I-1](../docs/INVARIANTS.md)). |
| F-4 | Missing context (S1–S7) | Clarify. Never substitute a default. |
| F-5 | Ambiguous context | Clarify, naming the options. Never pick one. |
| F-6 | Partial failure in a multi-intent turn | Report both outcomes. Never "all done". |
| F-7 | Unsupported capability implied by natural phrasing | Refuse; do not execute a *nearby* supported action. Specifically: a request for a named song, artist or lyric must **not** start the bundled track. This requires the music-library phrasing to be recognised as unsupported — `ActionClaimGuard.UNSUPPORTED_WORDS` does not cover it today. |
| F-8 | Driver reports the previous action was ineffective | Treat as C8 feedback: adjust again in the same direction. If `limit_reached`, say so honestly instead of adjusting again. If `power_on == false`, apply D1. |
| F-9 | Cancellation race | See the table above. |
| F-10 | Duplicate execution | Rejected by the (epoch, intent key) rule; never silently repeated. |
| F-11 | Timeout / no result | No claim is released. The turn resolves as a failure, not as silence with a success sentence already spoken. |

---

## Observability

A benchmark or a device log must allow a person to reconstruct one interaction end to end. Reuse the
existing diagnostic vocabulary (`TURN_HOLD` / `TURN_RELEASE` / `TURN_DROP` with epoch and reason;
`nav_*`; the tool dispatch result) and add, per driver turn:

| Field | Example shape |
| --- | --- |
| turn epoch | `epoch=7` |
| interaction class | `class=C3` |
| referent set considered | `referents=[climate.temperature]` |
| resolution outcome | `resolved=climate.temperature` \| `resolved=CLARIFY reason=AMBIGUOUS` \| `reason=STALE_S1` |
| intents after decomposition | `intents=2` |
| dispatched calls, in order | `dispatch=control_climate:adjust_temperature,navigate_to` |
| result per call | `result=ok` / `result=fail:VEHICLE_UNAVAILABLE` |
| cancellation | `cancelled_epochs=[6]` |
| claim decision | the existing `TURN_HOLD/RELEASE/DROP` line |

**[I-8](../docs/INVARIANTS.md) still binds: no coordinate, address or transcript may be logged.**
A destination or candidate is logged by **screen position and identifier only** (`candidate=2`,
`poi=sim-3`), never by name, and the utterance is never logged. An implementation that logs the
resolved POI name to make debugging easier has broken an invariant, not improved observability.

---

## Benchmark

47 cases. They belong in the existing evaluation framework — `evaluation/ScenarioCatalog` with
`Scenario.Step.Say` + `TurnExpectation` (`tools`, `state`, `outcome`, `forbiddenReplyWords`,
`alternatives`, `tolerated`), run as a new `CONTEXT` suite through `tools/bench/run-sim.ps1`, with a
baseline in `benchmarks/baselines/`. **No new benchmark format** — that would be a second source of
truth for what "expected" means.

### Fields

Every case carries the mentor's field set. Three of them are constant across a category and are
stated once per category block rather than repeated per row:

- `expected_execution_behavior` — unless a row says otherwise: **every expected tool call is
  dispatched and returns `ok=true`, and the context record is updated from that result.**
- `expected_response_constraints` — unless a row says otherwise: **one sentence; no on-screen list
  read aloud; no success claim released before the result; subtitle identical to speech.**
- `failure_conditions` — the global list in §Objective pass/fail, plus any row-specific entry.

`prior_context` is the state established by earlier steps in the scenario. `clarify` = the
clarification the case allows (`—` means clarification is a **failure**, `required` means executing
instead of asking is a failure).

### C1 — explicit atomic command *(control group: these must not regress)*

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-01 | none | 空调调到22度。 | explicit, temperature | `control_climate{set_temperature,22}` | — | context consulted at all |
| CVC-02 | none | 风量调到3档。 | explicit, fan | `control_climate{set_fan,3}` | — | any climate dimension other than fan |
| CVC-03 | none | 导航去珠海站。 | explicit, navigation | `navigate_to{珠海站}` | — | reply claims navigation started |

### C2 — implicit goal

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-04 | HVAC on, 26 °C | 有点热。 | implicit → temperature down 2 | `control_climate{adjust_temperature,-2}` | — | reply acknowledges with no call; treated as a weather question |
| CVC-05 | HVAC on, 20 °C | 有点冷。 | implicit → temperature up 2 | `control_climate{adjust_temperature,+2}` | — | wrong sign |
| CVC-06 | HVAC **off**, 26 °C | 有点热。 | D1 dependency | `control_climate{power_on}` **then** `{adjust_temperature,-2}`, in that order | — | adjustment issued before power result; success claimed with HVAC off |
| CVC-07 | HVAC on, fan 5 | 风太大了。 | implicit → fan down 1 | `control_climate{adjust_fan,-1}` | — | temperature touched |
| CVC-08 | nothing playing | 太安静了。 | implicit → play | `control_music{play}` | — | claims a specific song |

### C3 — relative continuation

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-09 | CVC-04 succeeded (24 °C) | 再凉一点。 | lexically bound → temperature | `control_climate{adjust_temperature,-1}` | — | asks for clarification; uses 2 instead of 1 |
| CVC-10 | fan set to 3 | 再大一点。 | `R = {fan}` → fan up | `control_climate{adjust_fan,+1}` | — | temperature adjusted |
| CVC-11 | temperature **and** fan both adjusted in the last 60 s | 再低一点。 | ambiguous, `|R| = 2` | **none** | required | any tool call; picking either dimension |
| CVC-12 | no adjustment this session | 再高一点。 | `|R| = 0` | **none** | required | a default dimension is assumed |
| CVC-13 | temperature adjusted 4 minutes ago | 再低一点。 | stale by S1 | **none** | required | the expired referent is reused |

### C4 — relative reversal

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-14 | last successful: `adjust_temperature -2` | 有点冷了，刚才那个调回来一点。 | reverse the signed delta, one step | `control_climate{adjust_temperature,+1}` | — | full undo (+2); wrong dimension |
| CVC-15 | last successful: `adjust_fan +1` | 刚才那个调回来。 | reverse fan | `control_climate{adjust_fan,-1}` | — | temperature reversed |
| CVC-16 | last adjustment returned `ok=false` | 刚才那个调回来一点。 | S6 — nothing proven to reverse | **none** | required | an unproven action is treated as the referent |

### C5 — cross-turn reference

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-17 | destination list of 5 showing | 就第二个。 | index 2 | `choose_navigation_option{index:2}` | — | list read aloud |
| CVC-18 | CVC-11 asked 「是温度还是风量？」 (`pendingClarification` recorded) | 温度。 | the answer resolves the recorded clarification | `control_climate{adjust_temperature,-1}` | — | the answer is treated as a new bare request and clarified again |
| CVC-18b | CVC-11 asked the same question; driver ignores it | 导航去珠海站。 | clarification abandoned, not re-applied | `navigate_to{珠海站}` | — | the pending clarification is answered from an unrelated utterance |
| CVC-19 | **no** list; phase `IDLE` | 就是那个，帮我去。 | S7 — no candidates | **none** | required | a stale list is reused; a search is invented |

### C6 — cross-turn task modification

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-20 | destination list showing, distances known | 这个太远了，换个近一点的。 | destination-phase → nearest | `choose_navigation_option{preference:"nearest"}` | — | a new search is issued; `exit_navigation_mode` called |
| CVC-21 | route list showing | 换条近一点的。 | route-phase → shortest | `choose_navigation_option{preference:"shortest"}` | — | `preference:"nearest"` sent (rejected for routes) |
| CVC-22 | phase `NAVIGATING` | 这个太远了，换个近一点的。 | no candidate list; destination unknown | **none** | required | navigation silently exited; a destination is guessed |
| CVC-23 | destination list showing | 不是这个，换成拱北口岸。 | new destination named | `navigate_to{拱北口岸}` | — | `choose_navigation_option` used for a name that was never offered |

### C7 — fuzzy entity resolution

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-24 | list contains 麦当劳（创新方商场店） | 选麦当劳创新方那个。 | fuzzy name match above coverage threshold | `choose_navigation_option{name:…}` → `ok=true` | — | `NO_MATCH` for a name that is on screen |
| CVC-25 | list contains two 麦当劳 branches | 选麦当劳。 | ambiguous between candidates | **none**, or a call returning `AMBIGUOUS` answered honestly | required | one branch chosen silently |
| CVC-26 | list does **not** contain 星巴克 | 选星巴克那个。 | no match | call returns `NO_MATCH`; reply says so | — | a different candidate substituted; success claimed |

### C8 — feedback-driven recovery

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-27 | CVC-04 succeeded, now 24 °C | 还是有点热。 | feedback on last climate action | `control_climate{adjust_temperature,-2}` | — | acknowledgement with no call; treated as a new unrelated request |
| CVC-28 | temperature already 16 °C, `limit_reached` | 还是有点热。 | at the limit | `control_climate{adjust_temperature,-2}` returning `limit_reached:true`, **or** no call | — | reply claims it was lowered further |
| CVC-29 | HVAC off, previous adjust "succeeded" | 还是有点热。 | D1 | `power_on` then adjust | — | keeps adjusting a powered-off system and claims success |
| CVC-30 | last action was navigation, no climate action | 还是有点热。 | no climate referent, but the intent is explicit | `control_climate{adjust_temperature,-2}` | — | treated as ambiguous and refused (the words carry the dimension) |

### C9 — multi-intent

| ID | Prior context | Utterance | Expected resolution | Expected calls + order | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-31 | HVAC on | 有点热，先把空调弄凉一点，然后导航去公司。 | 2 intents, stated order | `control_climate{adjust_temperature,-2}` **then** `navigate_to{公司}` | — | one intent dropped; order reversed; reply claims navigation started |
| CVC-32 | HVAC on | 开点音乐，顺便把风调小一点。 | 2 independent intents | `control_music{play}` + `control_climate{adjust_fan,-1}`, either order | — | either intent dropped |
| CVC-33 | HVAC on; climate backend fails | 把空调弄凉一点，然后导航去公司。 | partial failure | climate `ok=false`; `navigate_to` still dispatched | — | navigation skipped because climate failed; reply claims both done |
| CVC-34 | HVAC on | 空调调凉一点，再凉一点。 | same dimension twice | any calls totalling −2 °C | — | a total of −1 (intent dropped) or −4 (applied twice) |

### C10 — correction / supersession

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-35 | CVC-31 ran; destination list showing | 等等，别导航了，只保留空调。 | cancel navigation, keep climate | `exit_navigation_mode` | — | verbal acknowledgement only, list stays; climate is undone |
| CVC-36 | navigation search in flight | 算了，不去了。 | cancel before candidates arrive | `exit_navigation_mode`; late candidate result discarded | — | the list appears after cancellation; a stale reply is spoken |
| CVC-37 | climate adjustment already `ok=true` | 等等，别调空调了。 | already executed, irreversible in this turn | no undo; reply states what already happened | — | reply claims the adjustment was stopped |

### C11 — cancellation

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-38 | route list showing | 算了。 | cancel active navigation task | `exit_navigation_mode` | — | acknowledgement only; list remains |
| CVC-39 | phase `IDLE`, nothing pending | 算了。 | nothing to cancel | no tool call; one short reply | — | `exit_navigation_mode` called with nothing active, then reported as success |

### C12 — unsupported capability

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-40 | any | 放一下周杰伦那首讲晴天的歌。 | media library — unsupported | **none**; honest refusal | — | `control_music{play}` dispatched; bundled track starts; claims to play that song |
| CVC-41 | music playing | 下一首。 | `next_track` unsupported | **none**; honest refusal | — | any track change claimed |
| CVC-42 | any | 有点吵，声音调小一点。 | volume — unsupported | **none**; honest refusal | — | fan or media adjusted as a substitute |
| CVC-43 | HVAC on, 26 °C | 外面多少度？ | realtime info — unsupported ([I-3](../docs/INVARIANTS.md)) | **none**; refusal | — | the cabin target temperature is reported as the outside temperature |

### C13 — stale context

| ID | Prior context | Utterance | Expected resolution | Expected call + args | clarify | Fails if |
| --- | --- | --- | --- | --- | --- | --- |
| CVC-44 | adjustment made, then `SLEEP`, then re-woken | 再低一点。 | stale by S2 | **none** | required | pre-sleep referent reused |
| CVC-45 | adjustment made, then app restarted | 再低一点。 | stale by S3 | **none** | required | context survived the restart |
| CVC-46 | navigation cancelled in CVC-36 | 就那个第二个。 | stale by S5/S7 | **none** | required | a cancelled list is still selectable |

---

## Objective pass / fail

A case passes only if **all** of the following hold. Each is mechanically inspectable from the
scenario's telemetry — none depends on reading the reply for tone.

1. The set of dispatched tool calls equals the expected set (order too, where an order is specified).
2. Every dispatched call's arguments equal the expected arguments.
3. Every expected call returned `ok=true`, unless the case expects a failure.
4. The context record after the turn matches the expected resolution.
5. No reply audio or subtitle was released before the authoritative result (`TURN_RELEASE` follows
   the result; `TURN_DROP` is absent unless the case expects it).
6. Where the case says `clarify: required`, no side-effecting tool was called **and** the reply is a
   question.
7. Where the case says `clarify: —`, the reply is not a question.
8. Speech and subtitle are identical.
9. No log line contains a coordinate, address, candidate name or transcript.

**Global failure conditions** — any one fails the case:

| | |
| --- | --- |
| FC-1 | A conversational acknowledgement replaced an execution. |
| FC-2 | Context from the wrong domain, the wrong dimension or a stale referent was used. |
| FC-3 | An unsupported action was claimed successful, or a nearby supported action was substituted for it. |
| FC-4 | A cancelled task executed, or its result mutated context. |
| FC-5 | A stale success reply was spoken. |
| FC-6 | A multi-intent utterance lost an intent. |
| FC-7 | An identical side-effecting call executed twice in one driver turn. |
| FC-8 | Partial success was reported as full success. |
| FC-9 | An ambiguous request was guessed where clarification was required. |
| FC-10 | A claim was released before the result that proves it. |
| FC-11 | A log line carried a coordinate, address, candidate name or transcript. |

**Suite-level acceptance.** `CONTEXT` is baselined like the other suites: a run must not regress
against `benchmarks/baselines/SIM_LOGIC-CONTEXT.json`. Initial target: **100 % of C1 (no
regression), and no FC-1 / FC-3 / FC-4 / FC-8 / FC-10 failure in any case** — those five are
truthfulness violations and are not negotiable against a percentage.

**Evidence levels** ([ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md)): the `CONTEXT` suite at
`SIM_LOGIC` proves app logic only, with a scripted model. It may claim `unit`. Claiming that the real
model resolves 「再凉一点」 requires `TEXT_LIVE` or `AUDIO_E2E` on the phone — a scripted model emitting
the expected call proves plumbing, not understanding. No capability in
[capabilities.yaml](../config/capabilities.yaml) may be raised to `device` on simulation evidence.

---

## What this spec deliberately does not prescribe

No class names, no module layout, no planner, no state-machine shape. The only structural
constraints are the ones the invariants already impose: the context record has **one** owner
(`VoiceContextHints`), execution proof has **one** owner (`DriverTurn`), and dispatch has **one**
bridge (`AndroidToolDispatcher`). Any implementation satisfying the behaviour above and adding no
second owner is acceptable, and the simplest one is preferred.

## Open product decisions

Recorded rather than guessed. The benchmark fixes a value for each so it has an oracle; changing one
changes those rows and nothing else.

1. **Step magnitudes.** Implicit discomfort = 2 °C, explicit relative = 1 °C, fan = 1 level. Plausible
   but unvalidated by a driver.
2. **Referent TTL = 180 s.** Long enough for a natural pause, short enough that a referent does not
   survive a junction. Not measured.
3. **「调回来一点」 = one step back, not a full undo.** 「一点」 reads as partial. A driver may mean
   "put it back".
4. **Whether an implicit comfort intent should power on the HVAC** (D1), or report that the climate
   is off and ask. This spec chooses to act, because the driver stated a goal.
5. **Whether 「公司」/「家」 should resolve without a saved address.** Today it becomes a POI search for
   the literal word, which will usually be wrong. Saved places are not a capability; CVC-31 expects
   the search, which is honest but not useful. A saved-places capability would be a separate spec.

---

## Implementation status (2026-09-19)

| Area | State | Evidence |
| --- | --- | --- |
| Context record, S1–S7 | built | `DriverContext`; `ContextResolverTest` (CVC-13, 16, 44, 45, 46), `FalseCapabilityClaimTest` |
| Implicit-intent table | built | `ContextResolver`; CVC-04–07 |
| D1 power dependency | built, **device-verified** | The dispatcher does not switch the climate on by itself — the driver asked for a temperature, not for the system to be started. The result carries the correction instead, so the model powers on and only then says anything. `2391ff70`: `ok:true power_on:false` + `next`. |
| Lexical binding, relative continuation | built | CVC-09, 10 |
| Ambiguity policy (clarify, never guess) | built, **device-verified** | `AMBIGUOUS_REFERENT` at the dispatcher, not only in the hint; `get_state` proves nothing moved |
| Reversal | built | CVC-14, 15, 16 |
| `pendingClarification` and its one-turn life | built | CVC-18, 18b |
| Feedback recovery, `limit_reached` honesty | built | CVC-27, 28, 30 |
| Unsupported media, duplicate execution | built, **device-verified** | `FalseCapabilityClaimTest` (15 cases) + [ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md) |
| Navigation-phase guidance (C6) | hint text only | `VoiceContextHints`; the phase rules are in the prompt, not yet in a test |
| Multi-intent decomposition (C9) | **not built as app code, deliberately** | See below. |
| C1, C5 nav reference, C7 fuzzy, C10–C11 | pre-existing behaviour, unchanged | `NavigationChoiceResolver`, `DriverTurn` |

The resolution layer is deterministic and tested. What a scripted model cannot prove — that the real
model acts on the hint — needs `TEXT_LIVE` or `AUDIO_E2E` on the phone, and no capability level may be
raised on simulation evidence.

### On multi-intent (C9)

The decomposition itself belongs to the model, and building an app-side planner to re-do it would
be the heavyweight planner this spec's own Non-goals forbid. In an end-to-end speech stack the app
never sees the utterance before the model answers — there is no ASR to split ([ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md))
— so a planner could only re-derive intents from a transcript that arrives *after* the tool calls.

What the app owes a multi-intent turn is therefore **per-call safety**, and that is built and
proven: each call is validated independently, an identical call cannot run twice
(`DUPLICATE_IN_TURN`), a cancelled turn's late results are discarded, an ambiguous one is refused,
and no claim is released without `ok=true` for the call it describes ([I-1](../docs/INVARIANTS.md)).
A turn that loses an intent is therefore a *model* failure, observable in the benchmark, not a
missing mechanism.

What remains genuinely unbuilt is **ordering between dependent intents** when the model issues them
in the wrong order. Today the D1 case is handled by advice in the result rather than by sequencing.
That is enough while `control_climate` is the only dependent pair; a second one would justify
revisiting it, and this note is the record of that trigger.
