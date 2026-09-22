# Harness changelog

Newest first. One line per change, with the evidence that motivated it.

## 2026-09-22 — Best-practice design-basis gate

Agents could invent locally plausible mechanisms (e.g. mute the mic during playback when barge-in
requires duplex capture) and pass tests before the architecture was wrong.

- **`harness/design_basis.yaml`** — forward-compatible registry; empty `entries` does not
  invalidate historical evidence.
- **`scripts/design_basis.py`** — `--validate`, `--eval`, `--check`, `--selftest`; clearance
  semantics for `PASS` / `NEEDS_RESEARCH` / `NOVEL_REVIEW`; suppression anti-pattern.
- **`scripts/harness_check.py`** / **`scripts/discover_work.py`** — wired into validation and
  frontier (`needs_compilation` at `P_SPEC`).
- **CONSTITUTION rule 21**, thin `.cursor/rules/design-basis-gate.mdc`,
  **`DesignBasisPolicyTest`**.

## 2026-09-22 — Runtime evidence_bind invalidates stale device/flow/E2E PASS rows

A device, flow, or end-to-end PASS counts only while `evidence_bind` matches the tree.
`change_impact.watched_roots` fails closed (`UNKNOWN_IMPACT`) when a product Kotlin file
is unmapped. Code and registry digests follow `covers` and `protected_by`, so a PASS
that anchors a capability goes stale when that capability's impact files change.
`harness_digest` stales every bound row when the checker scripts change.
`--apply-stale` requeues to `NOT_RUN`. There is no allowlist for missing artifacts.

- **`scripts/test_matrix.py`** — digests, `bind_problems`, `--bind`, `--apply-stale`.
- **`scripts/acceptance.py`** — `local_artifact_paths()`.
- **`scripts/discover_work.py`** — bind-stale PASS rows are frontier work.
- **`TEST_MATRIX.yaml`** — binds only where artifacts still hash; HELP-001 stays NOT_RUN.

## 2026-09-22 — Capability preservation audit wired into existing harness

Prevent ADD FEATURE → LOSE EXISTING FEATURE without a second framework.

- **`config/capabilities.yaml`** — nine navigation UI behaviours + echo/truthfulness as first-class
  capabilities; `protected_by` / `regression_test` on every supported entry; `change_impact` retest map.
- **`scripts/test_matrix.py`** — `protection_audit()` + `--protection`; validation fails when a
  supported capability loses PASS cover.
- **`CapabilityContractTest`** / **`FeaturePresenceRegressionTest`** — registry drift + help/echo wiring.
- **`TEST_MATRIX.yaml`** — invalid `INVESTIGATING` removed; HELP-UNIT-001 PASS; HELP-001 NOT_RUN
  until colloquial 「你能干啥」 device re-verify; `covers:` links for NAV-UI / ECHO / HELP rows.
- P31 / NAV-E2E-ARRIVAL-001 preserved unchanged.

## 2026-09-22 — Prohibit all Fast labor modes

Owner: never Composer Fast / `composer-2.5-fast` / any Grok `*-fast`.

- Rule + `AGENTS.md` + agent blurbs: absolute Fast ban.
- `scripts/model_route.py` — `probe_no_fast_pins` (wired into `probe_grok` + selftest).
- `ModelRoutingPolicyTest` asserts no labor agent pins `*-fast`.
- Stale `agent/BUILDER.md` no longer says “Grok Fast”.

## 2026-09-22 — HIGH-REASONING pin: Grok 4.7 Extra High (abandon 4.6)

Owner: strong reasoning is Grok 4.7 Extra High. Pin and gate probe updated.

- **`.cursor/agents/grok-high.md`** — `model: grok-4.7-xhigh` (CLI slug; was `cursor-grok-4.6-high`, briefly `grok-4.7-high`).
- **`scripts/model_route.py`** / rule / `AGENTS.md` / `ModelRoutingPolicyTest` — pin string and prose.
- `model_route.py --selftest` PASS including `probe_grok_pin`.

## 2026-09-21 — Termination hard gate (MAX_GROK; no self-authorization)

DEFAULT could treat `AUTONOMOUS_ACTION_AVAILABLE = NO`, task completion, or "standing by" as
permission to end a run. That is forbidden.

- **[scripts/model_route.py](../scripts/model_route.py)** — `terminate-request` /
  `terminate-review` / `terminate-consume`. Verdicts: `CONTINUE` | `TERMINAL_APPROVED` |
  `REVIEW_UNAVAILABLE`. Fingerprints bind HEAD + dirty tree + frontier; approvals are
  single-use; `TERMINAL_APPROVED` rejected when `actionable_count != 0`. Fail closed when
  Grok is unavailable or the verdict is malformed/stale.
- **[scripts/discover_work.py](../scripts/discover_work.py)** — always prints
  `TERMINATION_AUTHORIZED = NO` and the gate command.
- Rule / `grok-high` / `continue.md` / `implementer` updated; `ModelRoutingPolicyTest` covers
  the bypass paths.

Does not change product behaviour.

## 2026-09-21 — Cursor routing hard gate (DEFAULT / GROK_REQUIRED / BLOCKED)

The H1–H8 split was a recommendation: a Composer parent could still reason through architecture
or two failed fixes. Labor routing is now a fail-closed classifier.

- **[scripts/model_route.py](../scripts/model_route.py)** — hard gate. `--action continue`
  through `GROK_REQUIRED` exits 2; missing/unpinned/unlaunchable Grok is
  `BLOCKED_GROK_UNAVAILABLE` (exit 3), never DEFAULT. Ledger prevents re-escalating the same
  issue+evidence. `--role grok-high` cannot loop.
- **[`.cursor/rules/hybrid-model-routing.mdc`](../.cursor/rules/hybrid-model-routing.mdc)** —
  same policy, now names A–F triggers (H1–H8 aliases) and requires the script.
- `grok-high` remains `cursor-grok-4.6-high` / readonly. `ModelRoutingPolicyTest` runs the
  eight required scenarios plus fail-closed and anti-loop checks.
- [HARNESS_PROPOSAL_003](proposals/HARNESS_PROPOSAL_003-model-route-hard-gate.md).

Does not change product behaviour, acceptance, or human-gate rules.

## 2026-09-21 — Cursor routing: Composer default, Grok High only on H1–H8

The previous Cursor split kept Grok 4.6 High as the parent for every turn. That spent
high-reasoning tokens on mechanical work. Labor routing now lives in one always-apply
rule with explicit H1–H8 escalation, three routes, an output contract, and loop guards.

- **[`.cursor/rules/hybrid-model-routing.mdc`](../.cursor/rules/hybrid-model-routing.mdc)** —
  single policy. DEFAULT is Composer 2.5 Standard; Grok High is not "the task is large."
- **`.cursor/agents/grok-high.md`** — `model: cursor-grok-4.6-high`, readonly planner/reviewer.
- `implementer` / `repo-explorer` remain `composer-2.5[fast=false]`.
- `ModelRoutingPolicyTest` asserts the pins, H-codes, contract fields, and
  "needs Grok High ≠ needs a human."

Does not change product behaviour, acceptance, or human-gate rules.

## 2026-09-21 — HUMAN_REQUIRED needs a concrete automation blocker

A desk validation batch asked a person to run ADB, start emulator navi, watch logcat, and
take screenshots. Those are not reasons a human is required.

- **CONSTITUTION rule 17** — `HUMAN_REQUIRED` is legal only with `automation_blocker` in
  `subjective_perception` / `physical_world` / `credential_permission` / `hardware_interface` /
  `safety`. Taps, ADB, emulator, logs, screenshots/video, and inconvenience stay AUTONOMOUS.
- **[scripts/test_matrix.py](../scripts/test_matrix.py)** — validation rejects a missing blocker
  or a `human_reason` that is only those illegal excuses.
- `HumanBatchingPolicyTest.humanRequiredNeedsAConcreteAutomationBlocker` guards the mechanism.

## 2026-09-21 — required_scope and evidence provenance close two verdict loopholes

Follow-up to the verdict-scope change the same day: a test author could still satisfy an E2E
requirement by declaring an intermediate test, and bare prose ("arrival callback observed")
could pass as E2E evidence.

- **`config/capabilities.yaml`** — owns `required_scope` per requirement (absent = COMPONENT);
  new `navigation.arrival_lifecycle` (END_TO_END) alongside `guidance_voice` (INTERMEDIATE_FLOW)
  and `phone.place_call` (END_TO_END). `ProductCapabilities` carries the matching id.
- **Matrix `covers:` links** — validation rejects `test_scope < required_scope` (scope
  laundering); `--coverage` reports per-requirement cover; uncovered flow/E2E requirements
  without queued human validation hold the gate.
- **Evidence provenance** — device flow/E2E `observed: true` rows must cite `log:` / `video:`
  / `xml:` / `artifact:`; prose-only earns INCOMPLETE.
- CONSTITUTION rule 20 extended; `HARNESS_PROPOSAL_002`; `ACCEPTANCE_TESTS.md` scope subsections.

## 2026-09-21 — verdicts may not out-claim their scope

The 0.6.4 navigation run showed healthy mid-route behaviour and a clean manual stop, and was
reported as a stable navigation baseline — while the recording never reached the destination
(~15 m → ~3.7 km near the end). Intermediate health stood in for end-to-end success because
the registry checked verdict shape but never verdict scope.

- **[scripts/acceptance.py](../scripts/acceptance.py)** — scope/verdict framework: COMPONENT /
  INTERMEDIATE_FLOW / END_TO_END, terminal states, the manual-stop rule, evidence-completeness
  matrix, near-terminal regression detector. Product-neutral; navigation supplies one
  minimum-criteria row.
- **[scripts/test_matrix.py](../scripts/test_matrix.py)** — `scope` on all 83 entries;
  acceptance matrix + `ended_by` on device flow/E2E verdicts; `INCOMPLETE` / `PARTIAL_PASS`;
  unscoped `baseline`/`e2e` wording rejected; `--verdict ID` self-check.
- **[scripts/harness_check.py](../scripts/harness_check.py)** — runs both selftests, so the
  0.6.4 verdict shape fails the gate instead of reaching a report.
- Records: `NAV-MID-ROUTE-001` PASS (intermediate) + `NAV-E2E-ARRIVAL-001` FAIL (terminal
  regression, P31); CONSTITUTION rule 20; `ACCEPTANCE_TESTS.md` scopes section.
- **[skills/verify.md](../skills/verify.md)** — verdict-check step before reporting on tracked
  scenarios (self-applied: the false PASS is the missing step made visible).

## 2026-09-20 — harness v2: human intervention is batched

The harness asked for a person **at the moment of discovery**. Hit a test only a human could run,
stop, ask, wait, resume, hit the next one, stop again. Every human-dependent case was its own
interruption, and between them the agent sat idle while work it could have done unaided went
untouched.

- **[TEST_MATRIX.yaml](../TEST_MATRIX.yaml)** — the canonical verification registry. Six owner
  categories and ten statuses, because "a person must drive the car", "we need a SIM", "we need a
  keystore" and "you must choose a policy" are four different facts and one generic `BLOCKED`
  loses the only information that matters: who can unblock it.
- **[scripts/test_matrix.py](../scripts/test_matrix.py)** — validation, the gate, and the two
  generated views. `autonomous_work()` selects on `owner == AUTONOMOUS`, so a `HUMAN_REQUIRED`
  entry can never become the next action and can never end a run.
- **[TEST_STATUS.md](../TEST_STATUS.md)** and **[HUMAN_VALIDATION.md](../HUMAN_VALIDATION.md)** —
  generated, never hand-edited; `harness_check.py` regenerates them and fails if they had drifted.
- **[PHASES.md](PHASES.md)** — the lifecycle, with the human as a boundary rather than an
  exception handler, and the batching rule applied recursively to retests.
- **CONSTITUTION rules 17–19** — `HUMAN_INTERVENTION_IS_BATCHED`; the gate is mechanical;
  a decision reaches a person quantified.
- `AUTONOMOUS_ACTION_AVAILABLE = NO` is **no longer the phase transition**. It was a judgement
  about whether anything was left. `HUMAN_VALIDATION_READY` is a property of the registry.
- `HumanBatchingPolicyTest` guards the mechanism, not the prose.

Migrating the existing evidence into the registry and then reviewing it found seven tests that did
not exist, six of them autonomous — and running those found nothing broken but proved several
things that had only been assumed. The review also found `vision`, `apps` and the unsupported
refusals had no coverage at all.

## 2026-09-18 — harness created

Audited at `89c9338` ([AUDIT.md](AUDIT.md)). The repository had strong rules and tests but no
machine-readable current truth, and no record of whether its own procedures worked.

- `config/capabilities.yaml` — capability truth with a verification level per capability, kept
  honest by `behavior-test/CapabilityContractTest`: a tool declared to the model but missing from
  the registry now fails the build.
- `state/PROJECT_STATE.json`, generated by `scripts/collect_state.py` from git, JUnit XML, the
  registry and the issue documents. Never hand-written.
- `skills/{start,reproduce,fix,verify,handoff}.md` — the five workflows performed by hand more than
  three times each during 2026-09-15..18.
- `harness/skill-events.jsonl` + `scripts/skill_event.py` + `scripts/skill_metrics.py` — evidence
  that the procedures themselves work, appended at handoff. Thresholds live in
  [SKILL_POLICY.md](SKILL_POLICY.md).
- `scripts/harness_check.py` — one command that says whether the harness is coherent.

Reused rather than rebuilt: `evaluation/` (57 scenarios, oracle, baselines),
`tools/speech-harness/` (device automation), `behavior-test/` (structural enforcement).

## 2026-09-19 — a run ends when the work does, not when the sentence does

Agents kept stopping with obvious work still in the repository, and "is anything left?" was being
answered from memory. Rule 12 (`AUTONOMOUS_WORK_EXHAUSTION_REQUIRED`), rule 13 (done has stages)
and rule 14 (a blocker names what is missing) are the rule; `skills/continue.md` is the procedure;
`scripts/discover_work.py` is the answer, read from the same documents a person would read.

- `state/PROJECT_STATE.json` now carries `work_frontier` with the stop decision.
- `scripts/harness_check.py` rejects a self-contradictory stop state, and a claim that a human is
  needed without naming what is unavailable.
- `AutonomyPolicyTest` guards the mechanism; both guards were checked against a planted vague
  blocker before being trusted.
- `SPECS/SPEC-TEMPLATE.md` replaces the copy that was inlined in `agent/INTAKE.md`, which had
  started to drift. Acceptance criteria now separate what each one proves, and an unmet one is
  discoverable.

Running it for real immediately found four false positives in its own reading: the SPEC template's
placeholder rows, a `✅` prefix defeating the terminal-status match on four closed problems, and my
own note claiming D-4 was network-blocked when the simulation harness can verify it. All fixed;
the last one was wrong in the direction that lets an agent stop, which is the direction that
matters.
