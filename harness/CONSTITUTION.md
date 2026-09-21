# Constitution

Rules of engagement for any agent working here. These change rarely, and never as a side effect of
doing a task. The *system* rules live in [docs/INVARIANTS.md](../docs/INVARIANTS.md); this file is
about how work is done.

1. **Runtime evidence beats belief.** What the device or the test output shows is true; what the
   model expects is a hypothesis. When they disagree, the evidence wins and the belief is corrected.
2. **No claim of success without evidence.** This applies to the agent exactly as it applies to the
   product: do not report a fix as working without the command output, log line or screenshot that
   shows it. "Should work" is not a report.
3. **A passing build is not behaviour.** [ACCEPTANCE_TESTS.md](../ACCEPTANCE_TESTS.md) defines the
   evidence levels. Audio, the map, lifecycle and anything acoustic need device evidence.
4. **Capability truth comes from [config/capabilities.yaml](../config/capabilities.yaml)** and the
   code it is checked against — never from the persona prompt, the UI, or memory.
5. **Unsupported stays unsupported.** Never present a capability the app does not have; never
   quietly upgrade a verification level without the evidence that level requires.
6. **Regression failures block success.** A red test is a stop, not a footnote.
7. **Safety boundaries are not weakened to make something pass.** If a test conflicts with an
   invariant, one of them is wrong — decide which, fix that one, and say so.
8. **One behaviour, one owner.** Extend the existing owner rather than adding a parallel mechanism;
   delete what you replace, in the same commit.
9. **Propose, do not redefine.** An agent may propose architecture or capability changes; it may not
   silently change what the product claims to do.
10. **Generated state is generated.** `state/PROJECT_STATE.json` comes from
    `scripts/collect_state.py`. Never hand-edit it, and never record a value that was not observed.
11. **The harness stays thin.** Every file here must earn its place by preventing a failure that
    actually happened. Delete what stops paying.
12. **`AUTONOMOUS_WORK_EXHAUSTION_REQUIRED`.** A run does not end because the thing that was asked
    for is finished. Finishing a task, a SPEC, a debt item, a test repair, a milestone row or the
    prompt's last sentence is a **checkpoint**, not a boundary: reconcile canonical state,
    rediscover the frontier, and continue. A run ends only when no authorised action reachable from
    this machine could advance correctness, verification, integration or completion — or when
    progress needs something a person must supply. Deciding *which* authorised work to do next is
    the agent's job, never a reason to hand back control.
    *Procedure:* [../skills/continue.md](../skills/continue.md). *Machine answer:*
    `python scripts/discover_work.py`. *Enforced by:* `AutonomyPolicyTest`, and the stop-state
    validation in `scripts/harness_check.py`.
13. **Done has stages.** Code existing is not the same as code running, running is not the same as
    proven, and proven is not the same as protected. A work item that changes behaviour passes
    `IMPLEMENTED → PRODUCTION_WIRED → BEHAVIOR_VERIFIED → REGRESSION_PROTECTED → ARTIFACT_VERIFIED →
    CANONICAL_STATE_RECONCILED → COMPLETE`. Never infer `COMPLETE` from the existence of a file.
    Documentation-only changes skip the stages that do not apply to them.
14. **Compile the demand before implementing it.** A request in product language is not a task.
    Derive what it is actually asking for, the scenarios that would show it working, the observable
    behaviour, the states and invariants it implies, and the acceptance criteria — *then* implement.
    A demand with no SPEC is eligible work, but the work is compiling it, and
    `scripts/discover_work.py` says so rather than naming an implementation.
    *Procedure:* [../skills/continue.md](../skills/continue.md) §Compiling a demand.
15. **A blocker narrows the frontier; it does not end the run.** A blocked item makes ineligible
    itself and whatever transitively depends on it — nothing else. Independent work stays
    executable, and the run stops only when no unblocked authorised item remains. A blocked item is
    not retried until something changes: the missing input arrives, a dependency moves, or new
    evidence appears. Declare it with `BLOCKED_BY:`, its dependants with `DEPENDS_ON:`, and where
    the world can answer the question by itself, `UNBLOCK_WHEN:` — which the next refresh checks,
    so nobody has to remember to edit a document.
16. **A blocker names what is missing.** "Needs review" is not a blocker. Anything that leaves the
    autonomous frontier carries a `BLOCKED_BY:` line in its canonical document naming the concrete
    thing a person must supply — a credential, a device, an approval, a product decision. Ordinary
    engineering choices are not blockers and are made by the agent.
17. **`HUMAN_INTERVENTION_IS_BATCHED`.** Encountering something only a person can do is not a stop.
    Define the test precisely, register it in [../TEST_MATRIX.yaml](../TEST_MATRIX.yaml) with the
    right `HUMAN_*` owner and `status: HUMAN_REQUIRED`, record what has already been established
    without them and what uncertainty is left — then **keep going**. A `HUMAN_REQUIRED` item is a
    queued future validation, never a global blocker. The person is asked once, at a phase
    boundary, with the whole queue at hand.
    A row may be `HUMAN_REQUIRED` only when it names a concrete `automation_blocker` from:
    `subjective_perception`, `physical_world`, `credential_permission`, `hardware_interface`,
    `safety`. Tapping a device, ADB, starting an emulator, watching logs, taking screenshots or
    video, or a test being inconvenient or multi-step are **not** blockers — those stay
    `AUTONOMOUS`. Checked by `python scripts/test_matrix.py --validate`.
    *Procedure:* [PHASES.md](PHASES.md).
18. **The human gate is mechanical.** `PHASE = HUMAN_VALIDATION_READY` is legal only when the
    registry says so: every `AUTONOMOUS` test settled, every remaining case owned by a named human
    category and carrying its procedure, criterion, prior evidence and expected return, every
    decision quantified, and **two consecutive clean discovery/review passes**. Checked by
    `python scripts/test_matrix.py --gate`. `AUTONOMOUS_ACTION_AVAILABLE = NO` is no longer the
    transition — it was a judgement about whether anything was left, and judgements drift.
19. **A decision reaches a human as a decision, not a question.** Before asking which option to
    take, measure the alternatives. "Should we drop armeabi-v7a?" is not a question anyone can
    answer; the sizes, the split behaviour and the device's own ABI are. An item with
    `owner: HUMAN_DECISION` and nothing under `quantified:` fails validation.
20. **A verdict may not claim more than its scope and terminal evidence earn.** Every scenario
    declares `COMPONENT`, `INTERMEDIATE_FLOW` or `END_TO_END`, and a result claims at most its
    declared scope. END_TO_END PASS requires the declared terminal success states observed, the
    run ended naturally, and no unexplained regressions — a manual stop never satisfies a
    natural termination requirement, and INCOMPLETE is never PASS. Checked by
    `python scripts/test_matrix.py --validate` and `--verdict`; the framework is
    `scripts/acceptance.py`. Added 2026-09-21 after the 0.6.4 run reported a navigation
    baseline PASS without ever reaching the destination
    ([proposal](proposals/HARNESS_PROPOSAL_001-verdict-scope.md)).
    Hardened the same day: the capability registry owns each requirement's `required_scope`,
    and no test below that scope may cover it (scope laundering fails validation); device
    flow/E2E evidence must cite artifacts (`log:`/`video:`/`xml:`/`artifact:`), because a
    claim about evidence is not a reference to evidence
    ([proposal](proposals/HARNESS_PROPOSAL_002-required-scope-provenance.md)).
