# Harness audit — 2026-09-18

What exists, what is duplicated, what cannot be checked mechanically, and the smallest harness that
closes the gaps. Evidence is the repository at commit `89c9338`.

## Sources of truth that already exist and are good

| Layer | Owner | Status |
| --- | --- | --- |
| Governance / rules | `docs/INVARIANTS.md` (12 rules, each naming its enforcement) | keep |
| Architecture + ownership | `docs/ARCHITECTURE.md` (canonical; root file is a pointer) | keep |
| Capability prose | `docs/CAPABILITIES.md` | keep — but **not machine-readable** |
| Measured debt | `docs/TECH_DEBT.md` | keep |
| Defect history + root causes | `OPEN_PROBLEMS.md` (P1–P21) | keep |
| Evidence levels | `ACCEPTANCE_TESTS.md` (L1–L6, and the traps that caused false "done") | keep |
| Deterministic evals | `evaluation/` — 57 scenarios, suites, `Oracle`, `Metrics`, baselines, `tools/bench/run-sim.ps1` | **reuse; do not rebuild** |
| Structural enforcement | `behavior-test/` — `ArchitectureRulesTest` (9), `DependencyBoundaryTest`, `SecretScanTest`, `FeaturePresenceRegressionTest` | keep |
| Device automation | `tools/speech-harness/` — injects synthetic Mandarin into the live session | **reuse** |

This repository is not short of rules or tests. It is short of **machine-readable current truth**.

## Gaps found

**G-1. Capability truth is prose only.** `docs/CAPABILITIES.md` is authoritative for humans, but
nothing mechanically compares it to the tool declarations in `BaiduFlexProtocol`, so a tool can be
added or removed without the registry noticing. `ArchitectureRulesTest.declaredToolsAndDispatchedToolsAgree`
checks tools are *routed and mentioned*; it cannot check the *support status* of a capability.

**G-2. "Implemented" and "proven on the device" are the same word.** Several capabilities are
implemented and unit-tested but their device evidence is scattered across `ACCEPTANCE_TESTS.md`
prose and commit messages. A fresh agent cannot answer "is this proven in the target environment?"
without reading history.

**G-3. Current state lives in commit messages.** Build status, test counts, what is broken now, what
was last verified on the phone — all recoverable, none queryable. Every session re-derives it by
reading git log, which is exactly the cost this harness should remove.

**G-4. Repeated workflows are re-improvised every session.** Observed this week, each performed
manually more than three times: resume-and-orient; reproduce a reported failure on the device;
verify a change (build → install → harness run → read `NovaVoice` log); write a handoff. These are
stable sequences with judgement in them — skill-shaped.

**G-5. No record of whether the procedures themselves work.** Nothing captures that a step was
forgotten, that the user had to repeat an instruction, or that a workflow was done by hand again.
Without that evidence the skill library cannot improve on anything but taste.

## Contradictions found (and their status)

- `README.md` claimed Qwen Flash was the default provider. **Fixed** at `8d35b8a`; the product is
  Baidu Flex.
- `agent/CURRENT_TASK.md`, `PROJECT_STATE.md`, `WORKER_REPORT.md` were frozen at 2026-09-15 and
  described the superseded deep-link architecture. **Deleted** at `8d35b8a`.
- Persona prompt restated policies that code enforces. **Annotated** at `8d35b8a`; the prompt now
  names the deterministic owner of each rule. Residual risk tracked as `docs/TECH_DEBT.md` D-2.
- Support status appeared in three places (tool declarations, `ActionClaimGuard` keyword lists,
  persona). Now one registry + one classifier; G-1 adds the mechanical check.

## Failure classes this repository actually produces

Taken from `OPEN_PROBLEMS.md` and this week's device runs, not from imagination:

1. **False claims of external action** — the model asserts something happened before, or without,
   execution. P21, D-7. Now structurally prevented (`DriverTurn`, invariant I-1).
2. **Fabricated information with no source** — weather. P21.
3. **Phantom turns from stray sound** — P20.
4. **Capability drift** — the model offers what the app cannot do.
5. **Stale documentation misleading the next agent** — D-6.
6. **Verification theatre** — a green build reported as working behaviour. `ACCEPTANCE_TESTS.md`
   records three separate occasions.

## Minimum harness proposed

Thin, and mostly *generated*:

- `harness/CONSTITUTION.md` — governance that rarely changes; points at `docs/INVARIANTS.md` rather
  than restating it.
- `config/capabilities.yaml` — machine-readable capability truth with an explicit verification level
  per capability, checked against the tool declarations by a test (closes G-1, G-2).
- `state/PROJECT_STATE.json` — **generated** from git, JUnit XML and the capability check; never
  hand-edited (closes G-3).
- `scripts/collect_state.py` + `scripts/harness_check.py` — the only writers of state.
- `skills/{start,reproduce,fix,verify,handoff}.md` — procedure only, no project truth (closes G-4).
- `harness/skill-events.jsonl` + `harness/skill-metrics.json` — evidence that the procedures work,
  appended at handoff (closes G-5).
- `harness/{HARNESS_POLICY,SKILL_POLICY,CHANGELOG}.md` + `harness/proposals/`.

## Deliberately **not** built

- No new eval engine. `evaluation/` already has scenarios, an oracle, metrics and baselines; the
  harness points at it.
- No planner, reflection loop, or multi-agent hierarchy. No evidence here that they would help.
- No analytics service. Metrics are a generated JSON summary.
- No duplicate regression corpus. The T01–T12 campaign maps onto existing tests and scenarios.
