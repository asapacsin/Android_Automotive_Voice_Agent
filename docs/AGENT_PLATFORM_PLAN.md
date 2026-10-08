# Plan — one voice, domain servers, fuzzy requests, music, guidance (2026-09-30)

Specs: [SPEC-016](../SPECS/SPEC-016-gemini-default-and-domain-servers.md),
[SPEC-015](../SPECS/SPEC-015-fuzzy-driving-requests.md),
[SPEC-017](../SPECS/SPEC-017-music-from-description.md),
[SPEC-018](../SPECS/SPEC-018-guidance-in-assistant-voice.md).
Base: `claude/fuzzy-logic-9-30`. Baseline at `a4e57a1`: `./gradlew test :app:assembleDebug` green,
2 864 tests, 0 failures (cloud, 2026-09-30).

Test-run policy (owner): short route 横琴创业谷 → 励骏庞都 only; no Gemini for anything that is not
the voice path. Cloud has no emulator (no KVM): cloud earns L2–L4; emulator/device rows are queued.

## Dependency graph

```
W1a Gemini default (SPEC-016 A) ─────────────────────────────┐
W1b Registry: domains + declarations + validation (016 B1) ──► W2 Runtime routing (016 B2) ──► W3a body domain (015)
W1c Vehicle port: cabin state + generic result (015 port) ───────────────────────────────────► W3a
W1d Speaking style store + compose (015 style, no tool) ─────────────────────────────────────► W3b speech tool
                                                                     W3a ──► W4a comfort scenarios + announce (015)
                                                                     W2  ──► W4b media domain: music hand-off (017)
SPEC-018 design review: REVISE → revision 2 ──► W5 step 1 (relay switch OFF) ──► emulator G-1…G-1f ──► switch on
```

Parallel only where files do not overlap:

| Wave | Tasks | Files owned (disjoint within a wave) |
| --- | --- | --- |
| W1 | a Gemini default · b registry declarations · c vehicle port · d style | a: `GeminiSettings.kt`, `MainActivity.kt` (provider choice + error), `DeveloperSettingsActivity.kt`, `RealtimeProviderFactory.kt` · b: `RealtimeToolCatalog.kt`, new `app/tools/*`, `ToolCallGuards.kt` · c: `vehicle/`, `simulator/`, `ClimateToolHandler.kt` · d: `PersonaProfiles.kt`, new `SpeakingStyle*.kt`, the two `sanitize` call sites in `GeminiLiveClient.kt` / `BaiduFlexClient.kt` |
| W2 | runtime routing | `AndroidToolDispatcher.kt`, `app/tools/*` servers |
| W3 | a body domain · b style tool | a: new body domain + handlers, capability files, `ActionClaimGuard`, `DriverContext`/`ContextResolver`/`VoiceContextHints` · b: speech domain file only — **serialised after W3a** because both touch the capability catalog |
| W4 | a comfort scenarios · b music | a: comfort domain, `ActionAnnouncement` · b: media domain, `MusicSource`, manifest listener service — capability files serialised (b after a) |
| W5 | guidance | `GuidanceRelay`, `AmapGuidanceVoice`, `SpeechArbiter`, `DriverTurnPipeline`, `ListeningLifecycle`, `GeminiLiveProtocol` (compression) — one executor, reviewed design first |

Each worker: own worktree from the wave's base commit under `../nova-wt/`, own `NOVA_BUILD_DIR`.
Planner re-runs acceptance, reads the JUnit XML, reviews where the table says, merges with a merge
commit, then replans.

| Wave | Reviewer |
| --- | --- |
| W1a | yes (session start behaviour) |
| W1b, W2 | yes (touches the claim-adjacent dispatch path) |
| W1c | no if tests green (mechanical generic change + new pure state) |
| W1d | no if tests green |
| W3a, W4a, W4b | yes |
| W5 | design review before code, and code review after |

## Queued for a person (not blocking)

`GEMINI-DEVICE-REACH-001`, `GEMINI-DEVICE-DUPLEX-001` (then Baidu deletion, ADR-013 step 2),
`FUZZY-DEVICE-001`, `STYLE-EAR-001`, `MUSIC-VLC-EMU-001`, `MUSIC-APP-DEVICE-001`,
`GUIDANCE-EMU-001`, `GUIDANCE-DEVICE-001`.

## Known pre-existing harness failure (not caused by this plan)

`test_matrix.py --selftest` "the repository registry validates": 30 supported capabilities have no
bind-current PASS cover, `protected_by` or `regression_test` in `TEST_MATRIX.yaml` (the audit is
stricter than the rows). Diagnosed 2026-09-30: this is the evidence-binding mechanism working as
designed — PASS rows carry code digests (`evidence_bind`) and code changed since they were earned,
so the capabilities they protect lost bind-current cover; `device`-verified capabilities need a
device re-run, which no cloud task can earn. Fix **after the last code wave** (binds would go stale
again otherwise): `test_matrix.py --apply-stale` to requeue, re-run and `--bind` the component
rows in the cloud, and queue the device rows for the human batch. Never mark a row PASS to satisfy
the audit.

## Registry-protection check, re-diagnosed 2026-10-01 (T-audit, BLOCKED honestly)

`--apply-stale` finds 0 stale rows: the 30 failing capabilities are all `verified: device`, and
`protection_audit()` accepts only a bind-current **PASS** for those (a queued row counts only for
`verified: human`). Their protecting rows (APPS-001, CLIMATE-*, NAV-UI-*, ECHO-001, SLEEP-001, …) all
need the phone or emulator. Nothing cloud-side can earn them without relabelling a unit test as
runtime protection, which the audit forbids. Decision (planner): leave the check red until the device
batch runs; do not change the script or the `verified` levels.
