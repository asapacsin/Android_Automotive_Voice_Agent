# /verify — decide whether it actually works

Self-reported success is not evidence. This skill produces PASS, FAIL or PARTIAL with the output
that justifies it.

## Steps

1. `git diff --stat` — know exactly what changed.
2. Decide which capability families the change can affect. Include the ones it touches indirectly:
   the voice path shares one turn gate.
3. **Define the claim boundary before running the test.** For any lifecycle, end-to-end, baseline,
   arrival or full-flow claim, write down:
   - the start boundary;
   - the observable terminal oracle that means the claimed flow actually completed;
   - abort conditions (manual stop, timeout, recording end, crash, environment reset, unexpected
     reroute/transition, or any other condition that prevents the terminal oracle being observed).
   Evidence collected before the terminal oracle is **prefix evidence only**. It can pass narrower
   subclaims but cannot close the parent lifecycle claim.
4. Build and run the full suite:
   ```powershell
   .\\gradlew.bat test --rerun-tasks :app:assembleDebug
   ```
5. Take the counts from the **JUnit XML of this run**, not from the summary line. Gradle prints
   `BUILD SUCCESSFUL` for an up-to-date cache, and stale XML elsewhere in the tree is not evidence
   about this run — `ACCEPTANCE_TESTS.md` records both traps.
6. Architecture and contract rules:
   ```powershell
   .\\gradlew.bat :behavior-test:test --rerun-tasks
   ```
7. If the change touches audio, the map, lifecycle or the model: install and drive the device, then
   read `NovaVoice` for the specific lines the behaviour produces (`TURN_HOLD` / `TURN_DROP`,
   `map_recenter_check`, `nav_navigation_started`, `tool=`).
8. Compare against the capability contract: does the observed behaviour match the `verified` level
   claimed in `config/capabilities.yaml`? If the change raises a level, the evidence for that level
   must exist now.
9. Check what the driver would actually see and hear — subtitle and audio together, not just a
   return value.
10. Before writing PASS, inspect the **entire evidence window from the declared start boundary through
    the terminal oracle**. If the run was manually stopped, timed out, ended recording, crashed,
    reset, or took an unexpected transition before the terminal oracle, the parent result is
    `PARTIAL` or `FAIL`; do not average the successful intermediate checks into a PASS. Manual
    stop may pass only when manual stop itself is the criterion.

## Output

`PASS` / `FAIL` / `PARTIAL`, the exact counts, and the log lines. If anything failed, say so
separately and plainly; do not fold it into a summary.
