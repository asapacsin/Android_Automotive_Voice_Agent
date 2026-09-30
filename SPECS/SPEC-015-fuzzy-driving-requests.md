# SPEC-015 — Fuzzy driving requests: windows, seat, comfort scenarios, speaking style

Status: **Draft 2026-09-30**
Raised: 2026-09-30 · Source: [B-029](../BACKLOG.md) · Architecture:
[docs/FUZZY_DRIVING_ARCHITECTURE.md](../docs/FUZZY_DRIVING_ARCHITECTURE.md) ·
[ADR-012](../DECISIONS/ADR-012-fuzzy-driving-requests.md)
Depends on: I-1, I-2, I-10, I-11; SPEC-006 (context, clarify-never-guess); SPEC-016 Part B (domain
servers must exist first — the new tools are built as the `body`, `comfort` and `speech` domains)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

The driver talks about how they feel, not in commands — 「有蚊子」「座位有点高」「好困」「说话能不能嗲一点」
— and 小诺 does the right, bounded thing, then says what it actually did. Everything acted on is
simulated vehicle state (like climate) and every claim still needs an `ok=true` result.

## Decisions taken (owner 2026-09-30; O-1/O-3/O-4/O-5 delegated to the planner, taken as recommended)

| # | Decision |
| --- | --- |
| O-1 | Multi-step phrases use one `run_scenario(name)` call with a closed enum; `ComfortScenarios` owns the steps |
| O-2 | Style is tone only, one voice per stage, sticky until the driver explicitly changes it (owner) |
| O-3 | 有蚊子 opens all four windows to 50 %; closing is the driver's next request, never a timer |
| O-4 | Every result carries `announce` + a chip; spoken coverage of every step is measured, not held |
| O-5 | Seat height may change while driving, in single steps, in simulation |

## Scope

Tools (domains per SPEC-016): `control_window`, `control_seat` (domain `body`); `run_scenario`
(domain `comfort`); `set_speaking_style` (domain `speech`). Port, handlers, playbook, announcement,
context and capability changes exactly as the architecture document §3–§8 lays out.

## Non-goals

Sunroof, doors, boot, lights, wipers, sunshade, lumbar/recline, defrost/air source: still
unsupported and refused in one sentence. Real actuation (needs privileged vehicle permissions).
A voice change for 嗲一点 (O-2). The model chaining tools for named scenarios (O-1).

## Capability ground truth

At `a4e57a1`: `unsupported.windows_seats_doors_lights_wipers` is unsupported; no body tools exist.
This SPEC adds `body.window`, `body.seat_height`, `comfort.scenario`, `speech.speaking_style` and
renames the unsupported id to `unsupported.sunroof_doors_lights_wipers`, in `ProductCapabilities`,
`config/capabilities.yaml` and `docs/CAPABILITIES.md` together, with `TRUTH-BAIT-001` updated.

## Behaviour — the phrase table (the fuzzy benchmark FZ)

The model maps phrases to calls; this table is what the TEXT_LIVE benchmark checks.

| # | Driver says | Expected call | Spoken, from `announce` |
| --- | --- | --- | --- |
| FZ-01 | 有蚊子 / 有虫子飞进来了 | `run_scenario{mosquito}` | 「车窗都开了一半，等它飞出去。」 |
| FZ-02 | 蚊子出去了 / 好了关上吧 (after FZ-01, within TTL) | `run_scenario{mosquito_done}` | 「车窗关好了。」 |
| FZ-03 | 座位有点高 | `control_seat{adjust_height,-1}` | 「座椅降低了一档。」 |
| FZ-04 | 座位有点低 | `control_seat{adjust_height,+1}` | 「座椅升高了一档。」 |
| FZ-05 | 再低一点 (after FZ-03) | `control_seat{adjust_height,-1}` via context | 「又降了一档。」 |
| FZ-06 | 把车窗打开一半 | `control_window{set,all,50}` | 「车窗都开了一半。」 |
| FZ-07 | 开一点主驾车窗 | `control_window{adjust,driver,20}` | 「主驾车窗开了一些。」 |
| FZ-08 | 关窗 | `control_window{close,all}` | 「车窗关好了。」 |
| FZ-09 | 有点闷 / 空气不好 / 有异味 | `run_scenario{stuffy}` | 「空调开了，风量调大一档，前窗开了一点。」 |
| FZ-10 | 好困 / 有点犯困 | `run_scenario{drowsy}` | 「空调调低两度，前窗开了一点，放点音乐提提神。要不要找个服务区歇一下？」 |
| FZ-11 | 说话能不能嗲一点 / 撒个娇 | `set_speaking_style{sweet}` | the next replies are sweeter; voice unchanged |
| FZ-12 | 正常一点 / 恢复 / 别嗲了 | `set_speaking_style{default}` | back to the default tone |
| FZ-13 | 打开天窗 | no call | 「这个操作没有执行，暂时不支持。」 |
| FZ-14 | 再低一点 (after seat **and** temperature changed) | no call; one clarifying question | 「是座椅还是温度？」 |

Rules:

- **B1.** Window 0–100 % (any integer); relative default 20; seat height 0–10 (default 5), step 1.
  Absolute out of range → rejected; relative → clamped with `limit_reached`.
- **B2.** A scenario runs its steps through the same handlers as direct calls. Independent steps
  run even when another failed; a dependent step is skipped when its prerequisite failed.
- **B3.** `ok` is true only when every step succeeded; otherwise `ok=false status=partial` and
  `announce` says what was done and what was not (never 「都弄好了」).
- **B4.** Every successful action result carries `announce` (one short clause, from the read-back
  state) and a chip. A reply may omit a step; it may never add one without `ok=true` (I-1, I-14).
- **B5.** `ActionClaimGuard.DEVICE_NOUNS` knows 车窗/窗户/座椅/座位 in the same change that makes
  them supported, so 「已把车窗打开一半」 with no call is dropped.
- **B6.** The style persists across turns, resets, sessions, sleep and restarts, and changes only
  through `set_speaking_style`. `PersonaProfiles.compose` *replaces* the tone paragraph; the
  sweet text is playful, never sexualised, replies still at most two sentences.
- **B7.** `DriverContext` gains `WINDOW` and `SEAT_HEIGHT`; only `ok=true` results are recorded;
  a scenario records each successful step as its own referent.
- **B8.** `control_window`, `control_seat`, `run_scenario`, `set_speaking_style` are repeat-sensitive.

## Failure behaviour

Unsupported actuator → one honest sentence, no call executes. Invalid arguments → the domain's
validation code. Port failure → `VEHICLE_*` code with `instruction` to say it did not happen.
Partial scenario → B3. Ambiguous follow-up → one question (FZ-14). Duplicate in turn →
`DUPLICATE_IN_TURN`.

## Observability

`fz_scenario name=<enum> steps=<n> ok=<n> skipped=<n>`; window/seat results log levels only. No
transcript text.

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Port: window/seat limits, clamping, generic result; climate unchanged | functional | `SimulatedVehicleControlTest`, `ClimateToolHandlerTest` | not built |
| A2 | Handlers and declarations for window/seat; results read back state + `announce` | functional | `WindowToolHandlerTest`, `SeatToolHandlerTest`, `ActionAnnouncementTest` | not built |
| A3 | Scenarios: order, dependencies, partial failure, per-step context | functional | `ComfortScenariosTest` | not built |
| A4 | Capability flip in all three places; sunroof etc. still refused | contract | `CapabilityContractTest`, `FalseCapabilityClaimTest`, `TRUTH-BAIT-001` | not built |
| A5 | 「已把车窗打开一半」 with no call is not released | negative | `FalseCapabilityClaimTest` (+case) | not built |
| A6 | Style: compose replaces the tone paragraph; persists; both clients use it | functional | `PersonaProfilesTest`, `SpeakingStyleStoreTest` | not built |
| A7 | Context follow-ups FZ-05, FZ-14 | functional | `ContextResolverTest` (+rows) | not built |
| A8 | The model maps FZ-01…FZ-14 correctly (≥ 12/14 on Gemini) | TEXT_LIVE | new `TEST_MATRIX.yaml` row `FUZZY-TEXT-LIVE-001` | not earned |
| A9 | Chip visible, scenario latency one call, style audible and appropriate | device | `FUZZY-DEVICE-001` (emulator), `STYLE-EAR-001` (HUMAN) | not earned |
| A10 | APK builds; harness coherent | artifact | `:app:assembleDebug`, `harness_check.py` | not built |

## Open product decisions

None open. Tunables (percentages, TTL for `mosquito_done`: 10 min) are engineering defaults.

## Implementation status

| Area | State | Proof |
| --- | --- | --- |
| Port + simulator | not built | — |
| Body domain (window/seat) | not built | — |
| Comfort domain (scenarios) | not built | — |
| Speaking style | not built | — |
