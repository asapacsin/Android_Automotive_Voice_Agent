# Fuzzy driving requests (模糊意图): architecture

Status: **Proposed 2026-09-30**, as [ADR-012](../DECISIONS/ADR-012-fuzzy-driving-requests.md).
It is not in force until the owner decides O-1 to O-5 (§11). Demand: [B-029](../BACKLOG.md)
(owner document `fuzzy logic.docx`, 2026-09-30). Next step: SPEC-015, then a plan in waves.

What the owner asked for:

| Driver says | Wanted |
| --- | --- |
| 说话能不能嗲一点 | 小诺 speaks in a sweeter, more coquettish (撒娇) style, until told 正常一点 / 恢复 |
| 座位有点高 / 有点低 | The seat goes down / up |
| 有蚊子 | The windows open to let it out |
| (every action) | 小诺 says what it actually did, e.g. 「已把车窗打开一半」 |
| "and more like these" | Other fuzzy driving phrases |

---

## 1. What this is, in one paragraph

This is [SPEC-006](../SPECS/SPEC-006-complex-voice-commands.md) (B-008, 「有点热」→ climate)
extended in three directions: **new simulated actuators** (windows, seat), **fixed multi-step
comfort scenarios** (有蚊子, 好困), and **one persona control** (speaking style). No new pipeline.
Every action still goes model → `RealtimeToolCatalog` → `AndroidToolDispatcher` → a handler → a
port, and every claim still waits for `ok=true` in `DriverTurn`. The new parts are: two actuators on
the existing vehicle port, three tools, one scenario playbook, a spoken summary built from results,
and a style setting composed into the persona.

## 2. The constraint that shapes the design

Speech goes end to end to the model: there is no ASR stage the app could use to intercept
「有蚊子」 before the model acts ([ADR-002](../DECISIONS/ADR-002-baidu-flex-default-provider.md),
SPEC-006 §On multi-intent). The transcript arrives **after** the tool calls. So:

- **The model maps the phrase to a tool call.** The app cannot pre-empt it. What the app controls is
  the tool *vocabulary*: how few, how bounded, and how easy to pick correctly.
- **Deterministic code owns everything after the call.** That covers the steps a scenario runs, their
  order and dependencies, limits, partial failure, what gets recorded as context, and what the reply
  may claim.
- **The fewer calls a fuzzy phrase needs, the better.** On `gemini-3.8-live` a tool round trip was
  about 1.6 s median ([2026-09-30 smoke report](reports/2026-09-30-gemini-native-smoke.md)). A
  three-call chain the model must order correctly is slower, and gives the model three ways to fail.

This is why §5 turns multi-step phrases into **one call with a bounded name**, not into a chain.

## 3. Components and owners

New or extended rows for [ARCHITECTURE.md § Who owns what](ARCHITECTURE.md#who-owns-what). They
are added there only when ADR-012 is accepted.

| Behaviour | Canonical owner | Not owned by |
| --- | --- | --- |
| Window and seat state, and their limits | `VehicleControlPort` (extended: `cabinState`), implemented by `SimulatedVehicleControl`, selected only in `VehicleControlProvider` | a second port, the UI, the handler |
| `control_window` / `control_seat` → port | `WindowToolHandler` / `SeatToolHandler` (same shape as `ClimateToolHandler`) | the dispatcher's `when` body, the model |
| Which steps a named comfort scenario runs, in which order, and what depends on what | `ComfortScenarios` (a fixed playbook: pure data plus a runner that calls the handlers above) | the model, the tool description, `ContextResolver` |
| The spoken summary of what was done | `ActionAnnouncement` (pure), built from the handlers' read-back results and carried in the result as `announce` | the model's wording, the persona |
| Speaking style (default / sweet) and how long it lasts | `SpeakingStyleStore` (app preferences) + `PersonaProfiles.compose(instructions, style)` | a provider adapter, the Gemini voice name, anything mid-turn |
| Fuzzy phrase → which tool (for the model) | `RealtimeToolCatalog` descriptions, bounded by the phrase table in SPEC-015 | the persona prompt, a new synonym list |
| Follow-ups that depend on what was just done (「再低一点」, 「好了，关上吧」) | `DriverContext` (new dimensions) + `ContextResolver` (new table rows) | the model's memory, which does not exist |
| Whether 车窗 / 座椅 are supported | `ProductCapabilities` + `config/capabilities.yaml`; `UtteranceIntentResolver` maps the words | `ActionClaimGuard.FALLBACK_UNSUPPORTED_CUES` |

Unchanged owners that must be *extended*, not duplicated:

- **`DriverTurn` / `DriverTurnPipeline`**: the claim gate. There is no new hold reason in the
  recommended design (see O-4).
- **`ActionClaimGuard.DEVICE_NOUNS`**: today it has no 车窗, 窗户, 座椅 or 座位. So a reply
  「已把车窗打开一半」 with **no tool call** would not be recognised as a device claim. This is a
  **must-fix in the first wave**, in the same change that makes windows supported. Otherwise a new
  capability opens a new false-claim path (I-1, I-2).
- **`ToolCallGuards.REPEAT_SENSITIVE`**: add `control_window`, `control_seat` and `run_scenario`.
  Relative adjustments are not idempotent.
- **`VoiceContextHints`**: describes window and seat state and referents in the same way it
  describes climate today.

## 4. Simulated actuators: windows and seat

Real window and seat actuation on a phone is impossible, and on a car it needs privileged vehicle
permissions. So both are **simulated vehicle state**, exactly like climate. `CAPABILITIES.md`
already says climate "is a real state machine with real limits, but it drives nothing physical". The
same sentence will apply to these.

**Port.** `VehicleControlPort` remains *the one vehicle abstraction*
(ARCHITECTURE § Vehicle control). It gains:

```kotlin
val cabinState: StateFlow<CabinState>
suspend fun setWindow(window: WindowId, openPercent: Int): VehicleActionResult<CabinState>
suspend fun changeWindow(window: WindowId, deltaPercent: Int): VehicleActionResult<CabinState>
suspend fun setSeatHeight(seat: SeatId, level: Int): VehicleActionResult<CabinState>
suspend fun changeSeatHeight(seat: SeatId, delta: Int): VehicleActionResult<CabinState>

data class CabinState(val windows: Map<WindowId, Int>, val seats: Map<SeatId, SeatState>)
enum class WindowId { FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT }   // group "all" is expanded by the handler
enum class SeatId { DRIVER, PASSENGER }
data class SeatState(val heightLevel: Int)                               // 0..10, default 5
```

- **One result vocabulary.** `VehicleActionResult` becomes generic in its state
  (`Success<S>(state: S, limitReached)`); the failure variants are shared. There must be no second
  error taxonomy for body actuators. This is a mechanical change to `ClimateToolHandler` and its tests.
- **Limits.** Windows 0–100 %, in steps of 10 %; the default relative step is 25 %. Seat height
  0–10, default step 1 (「有点」 means one step). An absolute value out of range is rejected, never
  clamped. A relative change is clamped and reports `limit_reached`, as climate does.
- **Real backend later.** An AAOS or OEM adapter maps "not while moving" and "child lock" onto
  `Unavailable` / `PermissionDenied`. Nothing above the port changes.

**Tools** (declared once in `RealtimeToolCatalog`, validated there, routed in `AndroidToolDispatcher`):

| Tool | Actions | Arguments |
| --- | --- | --- |
| `control_window` | `open`, `close`, `set`, `adjust`, `get_state` | `window`: `all` \| `front` \| `rear` \| `driver` \| `passenger` \| one of the four; `value`: percent (for `set` / `adjust`) |
| `control_seat` | `adjust_height`, `set_height`, `get_state` | `seat`: `driver` (default) \| `passenger`; `value`: level or signed step |

Each result reads back the state, like `control_climate`: `{ok, tool, action, windows|seat, limit_reached, announce}`.
Each success also produces a `successChip` (「✓ 车窗 50%」, 「✓ 座椅 高度 4」), shown through the
existing action-feedback path. The bottom bar stays as it is: it is phone-width and already full.

## 5. Multi-step phrases: the comfort-scenario tool

**Recommended (O-1 = A):** one tool, `run_scenario(name)`, where `name` is a **closed enum**. The
steps for each name are fixed in `ComfortScenarios`, not chosen by the model.

```kotlin
object ComfortScenarios {
    data class Step(val tool: String, val args: Map<String, String>, val requires: Int? = null)
    val PLAYBOOK: Map<String, List<Step>>        // name -> ordered steps; `requires` = index that must be ok=true first
    suspend fun run(name: String, handlers: Handlers): ScenarioOutcome   // per-step results + one announce line
}
```

The runner calls **the same handlers** a direct call uses (`ClimateToolHandler`, `WindowToolHandler`,
`SeatToolHandler`, the music executor). A step therefore gets the same validation, limits, result
rendering and `DriverContext` recording as if the driver had asked for it directly. There is no
second execution path (I-10).

First-wave scenarios. The complete table, and the phrases for each, go in SPEC-015.

| `name` | Driver phrases | Steps (in order) | Dependencies |
| --- | --- | --- | --- |
| `mosquito` | 有蚊子, 有虫子飞进来了 | `control_window{set, all, 50}` | — |
| `mosquito_done` | 蚊子出去了, 好了关上吧 (only within the context TTL after `mosquito`) | `control_window{close, all}` | — |
| `stuffy` | 有点闷, 空气不好, 有异味 | `control_climate{power_on}` → `control_climate{adjust_fan,+1}`; `control_window{set, front, 20}` | fan step needs power ok |
| `drowsy` | 好困, 有点犯困 | `control_climate{power_on}` → `control_climate{adjust_temperature,-2}`; `control_window{set, front, 20}`; `control_music{play}` | temperature step needs power ok |

**Partial failure.** Each step runs even if an *independent* step failed; a *dependent* step is
skipped when the step it requires failed. The outcome lists every step. `ok` is true only if every
step succeeded. Otherwise the result is `ok=false` with `status=partial`, and `announce` names both
what was done and what was not: 「车窗开了一半，空调没打开。」 (SPEC-006 F-6, "never 都弄好了").

This also settles SPEC-006's recorded trigger: "a second dependent pair would justify revisiting
ordering". The D1 power→adjust dependency moves from advice in a result into the playbook, for
scenarios. A direct `control_climate` call keeps today's D1 behaviour unchanged.

**Why not let the model chain the tools (O-1 = B).** That is SPEC-006's current stance on
multi-intent, and per-call safety would still hold. But ordering, dependencies and the
partial-failure summary would all be the model's job, and a fuzzy phrase would cost 2–4 round
trips. Measured Gemini call latency makes that a clear loss, and nothing would hold the dependency
when the model ignores it (I-11). The enum costs one tool and a closed list of names. A phrase the
model maps to no scenario still falls back to the direct tools.

**Safety boundary.** Scenario steps are comfort actions that can be undone, within the same limits
as direct calls. No scenario navigates, calls anyone, or opens a window fully. 好困 **also** offers a
rest stop in its `announce` (「要不要找个服务区歇一下？」). It only offers: it never navigates by
itself. Finding one is `query_live_info{along_route, 服务区}` on the driver's next turn.

## 6. "Say what you did": announcing from the result

The owner's example 「已把车窗打开一半」 is a sentence about **state**, and the state is in the result.
So:

- `ActionAnnouncement` is a pure function: (tool, action, read-back state, limit_reached) → one short
  Mandarin clause. Scenarios join their clauses into one sentence. It never runs for `ok=false`
  steps, except to say they were not done.
- The result carries it as `announce`, with the instruction 「用这句话告诉用户做了什么，可以换口气，不要加没做的事」.
  The model speaks from the result, which is the flow I-1 already relies on.
- **Enforcement stays where it is.** A reply claiming an action is released only after `ok=true`
  (`DriverTurn`), and `DEVICE_NOUNS` must know about windows and seats (§3). What is *not* enforced
  in the recommended design is that the reply mentions **every** step. That is measured by the
  benchmark (§9), and the chip shows every step on screen regardless.

**Invariant candidate I-14** (added to INVARIANTS.md once accepted): *every executed action is
reported to the driver from its result: on screen always (chip), and in speech through `announce`.
A reply may omit a step; it may never add one that has no `ok=true` result.* The second half is I-1
restated; the first half is new.

If the owner wants spoken coverage *enforced* (O-4 = B), the precedent is `CAPABILITY_HELP`: hold
the reply until it names every step, and otherwise speak `announce` as a scripted line. Cost: a hold
on every action reply until settle. On Gemini that is `generationComplete`, which is cheap since
ADR-011. On Baidu it is the whole reply. Not recommended for the first wave.

## 7. 嗲一点: speaking style, not a car action

- **Tool:** `set_speaking_style(style: sweet | default)`. Phrases: 嗲一点, 撒个娇, 可爱一点 → `sweet`;
  正常一点, 恢复, 别嗲了 → `default`. A tool, because nothing can intercept the phrase before the
  model (§2), and a tool result is the only thing that proves the setting changed.
- **Owner:** `SpeakingStyleStore` persists the choice in app preferences until changed (O-2). There
  is one composition point: `PersonaProfiles.compose(instructions, style)`. Both clients already call
  `PersonaProfiles.sanitize` when they build instructions, so `compose` replaces that call. There is
  no provider branch.
- **How it takes effect, per provider.** No capability flag is needed, because both paths are taken
  every time:
  1. the tool result carries the style line (「从现在起用更甜、更撒娇一点的语气说话，句子仍然简短」),
     which the model follows for the rest of the live conversation;
  2. every *new* conversation composes the style into its instructions. On Baidu that happens at the
     reset after this very tool turn (`ConversationResetPolicy`). On Gemini it happens at the next
     connect. Whether a resumed Gemini session re-reads `systemInstruction` must be measured on
     device; until then, (1) carries it.
- **One voice per stage — decided (O-2, owner 2026-09-30).** The driver must never hear two
  different voices in the same stage (a session, a scenario, a reply). Consequences, all binding:
  - 嗲一点 changes the tone only; `session.voice` stays 4196 度清影 on Baidu and the Gemini prebuilt
    voice is unchanged. No voice swap, no `ProviderCapabilities` flag for it.
  - `announce`, scenario summaries and partial-failure lines are spoken **by the realtime model's
    own voice**, never by a local/system TTS. If spoken coverage is ever enforced (O-4 = B), the
    fallback line must also go through the model, not a second synthesiser.
  - The provider (and so the voice) is fixed for the whole session (ADR-010); nothing in this feature
    may switch provider or voice mid-session.
- **The persona conflict must be resolved in the text, not left as a contradiction.** The default
  persona says 「不撒娇，不卖萌」. `compose` must *replace* the tone paragraph when style is `sweet`,
  not append a line that contradicts it. The model follows contradictions unpredictably.
- **Boundaries** (stated in the style text and in SPEC-015; this is tone only, so I-11 is not
  engaged): playful and sweet, never sexualised; replies stay at most two sentences; the navigation
  quiet rules are unchanged; the style never changes what may be claimed.

## 8. Context: follow-ups after a fuzzy action

`DriverContext.Dimension` gains `WINDOW` and `SEAT_HEIGHT`. `onWindowResult` and `onSeatResult`
record only `ok=true` results, as `onClimateResult` does. The existing machinery then applies
unchanged:

- 「再低一点」 right after the seat was lowered resolves to the seat. After the seat *and* the
  temperature were both changed, it is ambiguous, and SPEC-006's clarify-never-guess policy asks.
- 「好了，关上吧」 / 「蚊子出去了」 within the TTL of a `mosquito` result resolves to `mosquito_done`.
  Outside the TTL it is an ordinary request.
- A scenario records each successful step as its own referent. It is not an opaque "scenario ran".

## 9. Verification (oracles, cheapest first)

| Level | What it proves | New tests |
| --- | --- | --- |
| JVM unit | Port limits and clamping, handlers' results, playbook order, dependencies and partial failure, `ActionAnnouncement` text, `compose` replacing the tone paragraph, context resolution | `SimulatedVehicleControlTest` (+cabin), `WindowToolHandlerTest`, `SeatToolHandlerTest`, `ComfortScenariosTest`, `ActionAnnouncementTest`, `PersonaProfilesTest`, `ContextResolverTest` (+rows) |
| Contract / rules | Catalog ↔ yaml ↔ tools in step; 车窗/座椅 no longer refused; 天窗/车门/后备箱/车灯/雨刷 still refused; `DEVICE_NOUNS` covers the new actuators | `CapabilityContractTest`, `RealtimeToolCatalogTest`, `FalseCapabilityClaimTest` (+「已把车窗打开」 with no call is dropped), `ArchitectureRulesTest` (handlers depend on the port only) |
| Scripted pipeline | A scenario's reply is released only after `ok=true`; a partial reply is not released as full success | `DriverTurnTest` / pipeline cases |
| TEXT_LIVE (both providers) | The **model** maps each phrase in the SPEC-015 table to the right call: the fuzzy benchmark `FZ-01…` | new rows in `TEST_MATRIX.yaml`, `AUTONOMOUS` |
| Device | Chip on screen, style audible, latency of one scenario call | `FUZZY-DEVICE-001` (AUTONOMOUS, harness), `STYLE-EAR-001` (HUMAN_PHYSICAL: "does it sound 嗲 and still appropriate") |

`TRUTH-BAIT-001` is updated in the same change that flips windows and seats to supported, as the
BACKLOG review note requires.

## 10. What changes where (for the plan)

| Area | Change |
| --- | --- |
| `vehicle` | `CabinState`, window/seat methods on `VehicleControlPort`, generic `VehicleActionResult` |
| `simulator` | `SimulatedVehicleControl` implements cabin state |
| `app/vehicle` | `WindowToolHandler`, `SeatToolHandler`, `ComfortScenarios`, `ActionAnnouncement` |
| `app/voice` | `RealtimeToolCatalog` (+4 tools), `DriverContext` / `ContextResolver` / `VoiceContextHints` (+dimensions), `ActionClaimGuard` (`DEVICE_NOUNS`, remove 车窗/窗户/座椅 from the fallback), `UtteranceIntentResolver` (cues → new ids) |
| `app` | `AndroidToolDispatcher` routes, `ToolCallGuards.REPEAT_SENSITIVE`, `PersonaProfiles.compose`, `SpeakingStyleStore` |
| `contracts` + `config/capabilities.yaml` + `docs/CAPABILITIES.md` | new ids `body.window`, `body.seat_height`, `comfort.scenario`, `speech.speaking_style`; `unsupported.windows_seats_doors_lights_wipers` becomes `unsupported.sunroof_doors_lights_wipers` |
| docs | ARCHITECTURE owner rows, I-14, SPEC-015, TEST_MATRIX rows |

Dependency order, roughly: port + simulator → handlers + catalog + capability flip + `DEVICE_NOUNS`
(one wave, one owner of the catalog) → scenarios + announcement → context rows. The style work is
independent and can run in parallel from the start.

Deferred (candidate phrases from B-029 that need an actuator not in this design): 太晒 / 刺眼
(sunshade), 腰不舒服 (lumbar, recline), 起雾了 (defrost and air source on `control_climate`). Each
stays *unsupported* until built, and says so in one sentence (I-2).

## 11. Decisions required from the owner

| # | Question | Recommendation | If the other way |
| --- | --- | --- | --- |
| O-1 | Multi-step phrases (有蚊子, 好困): a fixed scenario tool, or the model chaining tools? | **A: `run_scenario` with a closed enum** (§5) | B: no new tool, but 2–4 round trips per phrase, and ordering, dependencies and the "never 都弄好了" rule depend on the model |
| O-2 | 嗲一点: prompt tone only, or also switch the Baidu voice? Persist across sessions? | **Decided 2026-09-30: one voice per stage — voice stays 4196, tone only, no second synthesiser for `announce`.** Persistence across sessions: recommended, still open | Voice swap: audibly stronger on Baidu, but nothing changes on Gemini, and it needs a `ProviderCapabilities` flag |
| O-3 | 有蚊子: which windows, and how far? | **All four to 50 %**, matching your 「打开一半」 example; closing again is the driver's next sentence, never a timer | Front only, or a smaller opening |
| O-4 | "Say what you did": result-built `announce` + chip, or a hold until the reply names every step? | **A: `announce` + chip; coverage measured by the benchmark** | B: enforced coverage, at the cost of a hold on every action reply (heavier on Baidu) |
| O-5 | May the seat move while driving? | **Yes in simulation, in single steps**; a real backend may refuse with `Unavailable` | Refuse seat changes while navigating |

Nothing here reopens a settled ADR. It extends ADR-002's end-to-end stance, ADR-008/-010's single
seam, and SPEC-006's bounded implicit-intent table. It adds to SPEC-006's multi-intent stance only for
named scenarios, and only because SPEC-006 itself recorded that trigger.
