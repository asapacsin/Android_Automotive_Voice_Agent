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
