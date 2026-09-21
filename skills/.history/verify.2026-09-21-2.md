# /verify — decide whether it actually works

Self-reported success is not evidence. This skill produces PASS, FAIL or PARTIAL with the output
that justifies it.

## Steps

1. `git diff --stat` — know exactly what changed.
2. Decide which capability families the change can affect. Include the ones it touches indirectly:
   the voice path shares one turn gate.
3. Build and run the full suite:
   ```powershell
   .\gradlew.bat test --rerun-tasks :app:assembleDebug
   ```
4. Take the counts from the **JUnit XML of this run**, not from the summary line. Gradle prints
   `BUILD SUCCESSFUL` for an up-to-date cache, and stale XML elsewhere in the tree is not evidence
   about this run — `ACCEPTANCE_TESTS.md` records both traps.
5. Architecture and contract rules:
   ```powershell
   .\gradlew.bat :behavior-test:test --rerun-tasks
   ```
6. If the change touches audio, the map, lifecycle or the model: install and drive the device, then
   read `NovaVoice` for the specific lines the behaviour produces (`TURN_HOLD` / `TURN_DROP`,
   `map_recenter_check`, `nav_navigation_started`, `tool=`).
7. Compare against the capability contract: does the observed behaviour match the `verified` level
   claimed in `config/capabilities.yaml`? If the change raises a level, the evidence for that level
   must exist now.
8. Check what the driver would actually see and hear — subtitle and audio together, not just a
   return value.
9. Before reporting on any tracked scenario, run the verdict the evidence earns and attach it:
   ```powershell
   python scripts/test_matrix.py --verdict <ID>
   ```
   A stored verdict above the earned one is a harness violation, not a result. Manual stops,
   missing terminal states and unscoped "baseline" wording fail validation — see
   `ACCEPTANCE_TESTS.md` §Acceptance scopes.

## Output

`PASS` / `FAIL` / `INCOMPLETE` / `PARTIAL`, the scope each verdict is claimed at, the exact
counts, and the log lines. If anything failed, say so separately and plainly; do not fold it
into a summary.
