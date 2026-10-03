# ADR-012 — Fuzzy driving requests: simulated body actuators, fixed comfort scenarios, speaking style

Status: **Accepted 2026-09-30.** O-2 decided by the owner; O-1, O-3, O-4, O-5 delegated by the owner
("you should make the decision and architecture") and taken as recommended. Requirement: SPEC-015.
Amended by ADR-015: the new tools are built as domain servers (`body`, `comfort`, `speech`).
Keeps: ADR-002 (end to end; no ASR stage), ADR-008 / ADR-010 (one seam), ADR-009 (behaviour varies
on capabilities, not provider names), SPEC-006 (bounded implicit-intent table; per-call safety).
Architecture: [docs/FUZZY_DRIVING_ARCHITECTURE.md](../docs/FUZZY_DRIVING_ARCHITECTURE.md) ·
Demand: B-029 · Requirement: [SPEC-015](../SPECS/SPEC-015-fuzzy-driving-requests.md)

## Context

The owner (2026-09-30, `fuzzy logic.docx`) wants 小诺 to act on fuzzy driving phrases:
嗲一点 (speaking style), 座位有点高/低 (seat), 有蚊子 (windows), more of the same kind, and every
action announced from what was actually done. Today windows and seats are `unsupported` (no tool),
the voice style is fixed per session, and multi-intent ordering is left to the model (SPEC-006).
Measured: a Gemini tool round trip is about 1.6 s median (2026-09-30 smoke report), so a
fuzzy phrase that needs a chain of calls is slow. `ActionClaimGuard.DEVICE_NOUNS` has no window or
seat nouns, so a new actuator without that change opens a false-claim path.

## Decision (proposed)

1. **Windows and seat are simulated vehicle state** on the existing `VehicleControlPort`
   (`cabinState`), implemented by `SimulatedVehicleControl`, with one generic `VehicleActionResult`.
   There is no second port and no second error taxonomy. They are exposed as `control_window` /
   `control_seat`.
2. **Multi-step fuzzy phrases are one call with a closed name**, `run_scenario(name)`. Deterministic
   `ComfortScenarios` owns the steps, their order, their dependencies and partial failure, and it
   executes through the same handlers a direct call uses.
3. **The reply is announced from the result.** `ActionAnnouncement` builds an `announce` clause from
   the read-back state; the chip shows every step. The claim gate is unchanged. The candidate
   invariant I-14 says a step may be omitted from speech but never invented.
4. **Speaking style is persona, not a car action.** `set_speaking_style(sweet|default)`, persisted by
   `SpeakingStyleStore`, composed once by `PersonaProfiles.compose`. It is carried by the tool
   result for the live conversation and by the instructions for every new conversation. It is
   playful and never sexualised, and replies stay short.
5. 车窗 / 座椅 become supported ids. 天窗, 车门, 后备箱, 车灯 and 雨刷 stay unsupported. `DEVICE_NOUNS`,
   `FALLBACK_UNSUPPORTED_CUES`, `UtteranceIntentResolver`, `capabilities.yaml` and `TRUTH-BAIT-001`
   change in the same wave.

## Consequences

- Three new tools plus one persona tool in `RealtimeToolCatalog`, on both providers.
- SPEC-006's multi-intent stance is narrowed for named scenarios only. SPEC-006 had itself recorded
  that a second dependent pair would justify revisiting ordering.
- Sunshade, lumbar and defrost stay unsupported until they have their own actuator.

## What would justify revisiting this decision

- The fuzzy benchmark (FZ rows) shows the model picks scenario names worse than it chains direct
  tools.
- A real vehicle backend whose body actuators cannot be expressed through `VehicleControlPort`.
- The owner wants the style to change the voice itself on every provider.
