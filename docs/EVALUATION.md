# Evaluation pipeline

Simulation-first end-to-end evaluation. Status 2026-09-17: **Level A (JVM simulation) is
implemented and running.** Level B (phone runner, synthetic speech through the real audio path)
reuses the same scenarios, oracle and reports but its runner is **not built yet** — see
"Not done yet".

```
scenario ──► driver ──► real app path ──► simulated world ──► probe ──► oracle ──► results ──► metrics / baseline / report
            (text, scripted model,        (vehicle, navigation,        (state, not wording)
             synthetic audio)              media, vision, network)
```

## Pieces

| Where | What |
| --- | --- |
| `evaluation/` (JVM module) | Telemetry schema + recorder, scenario model and catalog, oracle, executor, metrics, baselines, report, benchmark runner. No Android, no SDKs. |
| `app/src/main/.../sim/` | Simulated world used by both levels: `SimulatedNavigationWorld` (fixed places/routes, progress, arrival, faults), `SimulatedMusic`, `SimulatedCamera`, `FixtureVision`, `WorldProbe`. Inert unless a runner installs them. |
| Seams in production code | `NavigationBackends` (switchable navigation backend), `MusicBackends`, `VisionOverrides`, `VehicleControlProvider.simulated`, `NetworkFaults`, `CoreActionExecutor` (tool actions without Android). Telemetry hooks in the Baidu client, tool dispatcher, playback, wake word and listening lifecycle. |
| `app/src/test/.../sim/` | Level A: `ScriptedRealtimeServer` (the realtime protocol with a deterministic model), `SimulationDriver`, `SimulationBenchmarkTest`. |
| `benchmarks/` | `thresholds.json`, `baselines/<MODE>-<SUITE>.json`, `scenario-utterances.json` (input for the audio generator). |
| `tools/bench/run-sim.ps1` | Runs a suite and prints Gradle time and simulation time separately. |

## Modes and levels

| Mode | Level | Drives | Measures | Does NOT measure |
| --- | --- | --- | --- | --- |
| `SIM_LOGIC` | A | real client/protocol/guards/session/dispatcher/navigation state machine/vehicle simulator against a **scripted** model | app logic, recovery, false-success prevention, state transitions, ordering and concurrency | model understanding, ASR, acoustics, real network |
| `TEXT_LIVE` | B | text turns to the **real** Baidu model, simulated world, phone | real tool choice and parameters | ASR, acoustics |
| `AUDIO_E2E` | B | synthetic speech through the real audio path, phone | ASR + model + app | real cabin acoustics |

Tool-selection accuracy in `SIM_LOGIC` is about plumbing: the scripted model emits the expected call
unless the scenario tells it to misbehave.

## Suites and how to run them

| Suite | Content | Default run | Test execution (measured 2026-09-17) |
| --- | --- | --- | --- |
| `SMOKE` | one representative scenario per subsystem + 10-turn session | inside `gradlew test`, debug unit tests only | a few seconds |
| `REGRESSION` | all deterministic functional scenarios | on demand, one pass | — |
| `RELEASE` | everything deterministic except the 50/100-turn soaks | on demand, one pass | **32.2 s** (63 scenarios, 126 turns) |
| `STRESS` | RELEASE repeated; requires an explicit `-Repeat` | never automatic | — |
| `CHAOS`, `NAVIGATION`, `VEHICLE`, `MEDIA`, `VISION`, `LONG_SESSION` | subsets | on demand | — |

```powershell
tools\bench\run-sim.ps1 -Suite RELEASE                 # one pass
tools\bench\run-sim.ps1 -Suite STRESS -Repeat 60        # flakiness: 60 passes catch a 5% flake with 95% confidence
tools\bench\run-sim.ps1 -Scenario CHAOS_STALE_DESTINATION
tools\bench\run-sim.ps1 -Suite RELEASE -UpdateBaseline  # write benchmarks/baselines/SIM_LOGIC-RELEASE.json
```

Every Gradle invocation also costs configuration and, after source changes, compilation — measured
45 s to 4.5 min on this machine. The wrapper prints `total = simulation + Gradle/build/JVM`; only
the simulation part is benchmark time.

Repetitions: the scripted model is deterministic, so repeating RELEASE only exposes timing flakes
(n ≥ ln(0.05)/ln(1−p): 60 passes for p = 5%, 300 for p = 1%). One pass gives ~115 samples per
latency metric: enough for P50/P95, **not** for P99, which is only reported from ≥ 1000 samples
(STRESS).

## Oracle

Judged per turn from telemetry and a state snapshot:

- tool selection and parameters (alternatives allowed, e.g. set vs power-on + set; `~` name match,
  `!x`, `>=n`, numeric tolerance);
- execution success, final state (`StateKeys`), unexpected and duplicate calls;
- **false success**: the final reply claims an action that did not happen, or mentions content it
  could not know; a claim later retracted is reported as a *premature/corrected claim* instead;
- spoken reply present; timeout.

## Timing (in results.jsonl, results.csv and the report)

Scenario wall / setup / teardown / explicit wait steps / deliberate simulated delay; per-turn wall
and settle wait; latency per turn (speech end → tool, tool → execution, execution, speech end →
verified state, speech end → TTS, interruption → audio stop). Report: P50, P95, max, slowest
scenarios.

### How a turn is known to be finished (SIM_LOGIC)

All of these hold, then 40 ms with no new telemetry event (bounded by the turn timeout):

1. the scripted server has no response running, nothing scheduled or streaming, and no tool result owed;
2. the client has recorded as many response completions as the server sent — frames arrive
   asynchronously, so "server finished sending" alone would race;
3. the session has no pending tool work;
4. the client has no own turn, held message or conversation reset in flight
   (`BaiduFlexClient.ownWorkInFlight`, a read-only diagnostic).

Overlapping turns move on as soon as their tool call is dispatched. Fixed pauses were replaced by
condition waits (`Step.WaitFor`: reconnected, delayed search returned, vision returned, routes shown).
Deliberate delays kept because the duration is what is tested: 0.5 s and 2 s latency, barge-in at
100/300/700/2000 ms; others shortened to 0.3–0.6 s.

Before → after (RELEASE, one pass, same seed): **90.0 s → 32.2 s**; settle wait ≈ 30 s (estimated)
→ P50 142 ms / P95 199 ms per turn; 63/63 → 63/63 with identical per-turn verdicts.

## Scripted server assumptions (not verified against Baidu)

- A tool result whose call id belongs to an earlier connection is ignored.
- `response.create` during a running reply is refused with the measured error text.
- Speech during a reply (interrupt_response) cancels it with status `cancelled`.
- A server-side close is a normal close frame (1011).

## Defects the simulation found (all fixed, with tests)

| Found | Fix |
| --- | --- |
| After arrival, a failed search or a tapped cancel, the navigation speech mute stayed on → replies dropped 10 s later | controller clears it on every flow end (not on replacement); set before the search starts |
| Tool failed but the model said 「已经调好了」 → nothing corrected it | `ActionClaimGuard.onToolResult` → correction turn |
| Same call emitted twice in one response → executed twice | client ignores identical repeats within a response and answers them |
| Fast tool result before its `response.done` → conversation never reset again | reset policy matches results by call id |
| Server-side close never reported (close frame unanswered) → session silently dead | clients answer `onClosing` |
| Stale `Closed` after an error-triggered reconnect → session stuck DISCONNECTED, audio dropped, tool results never delivered | state machine keeps RECONNECTING |

## Level C (real world, human) — keep small

Real cabin wake-word accuracy; real road/cabin noise and echo; GPS during movement and genuine
arrival; driver UX and speech quality; guidance loudness; long-drive battery/thermal; real vehicle
integration; how soon Baidu closes idle connections.

## Not done yet

- Level B phone runner (`TEXT_LIVE`, `AUDIO_E2E`), speech variants and noise mixing, navigation-
  announcement feedback loop (×100), phone barge-in, app lifecycle scenarios, real-API vision
  fixtures, diagnostics screen. The scenarios exist (`modes` include them); the driver does not.
- Baselines are not committed yet: create one with `-UpdateBaseline` once the suite is agreed.
