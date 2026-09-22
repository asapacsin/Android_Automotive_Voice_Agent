# Harness

Machinery that makes agent work on this repository reliable and resumable. Deliberately thin: the
durable knowledge lives in `docs/`, the durable evidence in git and the test output.

| File | Layer | What it is |
| --- | --- | --- |
| [CONSTITUTION.md](CONSTITUTION.md) | governance | how work is done; rarely changes |
| [../docs/INVARIANTS.md](../docs/INVARIANTS.md) | governance | rules the system must not break |
| [../config/capabilities.yaml](../config/capabilities.yaml) | contract | machine-readable capability truth + verification level |
| [../state/PROJECT_STATE.json](../state/PROJECT_STATE.json) | state | **generated**; never hand-written |
| [../scripts/model_route.py](../scripts/model_route.py) | labor + termination routing | hard-gate Cursor DEFAULT vs GROK_REQUIRED; MAX_GROK termination review; fail-closed |
| [../scripts/design_basis.py](../scripts/design_basis.py) | design-basis gate | hard-gate implementation clearance for non-trivial tasks; registry in [design_basis.yaml](design_basis.yaml) |
| [../skills/](../skills/) | procedure | start / reproduce / fix / verify / handoff |
| [HARNESS_POLICY.md](HARNESS_POLICY.md) | governance | what may change automatically, what needs a proposal |
| [SKILL_POLICY.md](SKILL_POLICY.md) | governance | when a skill is added, changed, merged, deleted |
| [skill-events.jsonl](skill-events.jsonl) | evidence | one append-only record per completed task |
| [skill-metrics.json](skill-metrics.json) | evidence | **generated** summary of the above |
| [AUDIT.md](AUDIT.md) | evidence | the audit this harness was built from |
| [CHANGELOG.md](CHANGELOG.md) | history | what changed here, and why |

## Commands

```powershell
python scripts/collect_state.py            # refresh generated state from evidence
python scripts/collect_state.py --check    # fail if the stored state is stale
python scripts/harness_check.py            # is the harness itself coherent?
python scripts/model_route.py --selftest   # hard-gate Cursor labor routing
python scripts/design_basis.py --validate  # design-basis gate
python scripts/skill_event.py --help       # append a skill-review record at handoff
python scripts/skill_metrics.py            # summarise the evidence, suggest action
.\gradlew.bat test --rerun-tasks :app:assembleDebug   # the real gate
```

## The loop

```
task -> skill -> verification -> handoff -> one skill event
                                              |
                              repeated evidence crosses a threshold
                                              |
                          script / invariant / skill change -> validate -> keep or roll back
```

Most tasks end at `NONE`. That is the intended outcome, not a failure of the loop.

## Human gaps

Two facts cannot be established from this machine, and are marked `human_verification_pending` in
the capability registry: the **wake word with a real human voice**, and the **open-mic thresholds in
real cabin acoustics**. Everything else about them is automated.
