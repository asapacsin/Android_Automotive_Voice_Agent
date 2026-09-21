# /continue — the default operating mode

This is not a skill you invoke when asked to keep going. It is how a run behaves unless something
stops it. A prompt is an entry point into the repository's state machine, not a task with an end.

> Your job is not to finish the user's last sentence. It is to advance the repository until the
> authorised work frontier is empty.

[CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12 is the rule; this is the procedure.

## The loop

```
recover canonical state            /start
while true:
    reconcile observed state       collect_state.py, then read it
    candidates = discover_work.py  the frontier, ranked, blockers removed
    if candidates:
        take the highest-ranked one
        implement it                /fix
        verify it                   /verify
        reconcile canonical state   /handoff, without ending the run
        continue
    hostile stop audit             below
    if the audit finds anything:  continue
    stop
```

```bash
python scripts/discover_work.py            # what is left, ranked, and may I stop?
python scripts/discover_work.py --json     # the same for a machine
python scripts/model_route.py --eval architecture_decision=true   # Cursor labor hard gate (not a human stop)
```

Before implementing a candidate, and again if a fix fails twice or root cause stays unclear,
classify labor with `scripts/model_route.py`. `GROK_REQUIRED` means stop the gated portion and
delegate `grok-high`; `BLOCKED_GROK_UNAVAILABLE` means fail closed — never continue as DEFAULT.
The trigger table lives in `.cursor/rules/hybrid-model-routing.mdc`; do not copy it here.

The discovery script answers the question from the documents; it does not replace judgement about *how* to do
the work. If it lists something already finished, the document it read is wrong — fixing that
document is itself the next work item, not a reason to ignore the tool.

## Priority

Lower first. Nothing cosmetic outranks something broken.

| | |
| --- | --- |
| 1 | broken build, or a required artifact missing |
| 2 | failing regression tests |
| 3 | an invariant violated (architecture, capability contract, secret scan, feature presence) |
| 4 | an unmet acceptance criterion of an active SPEC |
| 5 | implemented but not reachable from the production path |
| 6 | behaviour with no regression protection |
| 7 | a row of the active milestone |
| 8 | recorded debt with an implementation path |
| 9 | an open problem, or work started and not closed |
| 10 | stale canonical state, or a harness defect |
| 11 | the next authorised backlog item |

## Done has stages

A behaviour change is not finished because it compiles. Ask which stage it has actually reached:

| Stage | Means | The mistake it prevents |
| --- | --- | --- |
| `IMPLEMENTED` | the code exists | a file is not a feature |
| `PRODUCTION_WIRED` | the production path reaches it | a class nothing calls |
| `BEHAVIOR_VERIFIED` | it was observed doing the thing | a green build is not behaviour |
| `REGRESSION_PROTECTED` | a test fails if it breaks | proven once, unprotected after |
| `ARTIFACT_VERIFIED` | the APK builds with it in | passing tests, broken package |
| `CANONICAL_STATE_RECONCILED` | registry, debt, issues, state agree | documents that contradict the code |
| `COMPLETE` | all of the above that apply | — |

Documentation-only work skips the stages that cannot apply. Nothing skips the last one.

## Compiling a demand

A request in product language — "make cancellation work properly", "support wake word", "make the
provider layer generic" — is not a task. Going straight from that sentence to editing code is how a
repository ends up with a feature nobody can prove. Derive these first, and record them where the
repository already keeps them:

| Derive | Where it goes |
| --- | --- |
| what the request literally said, and what outcome it is actually after | the SPEC's Goal |
| the scenarios that would show it working — initial state, action, expected transition, tool behaviour, what the driver hears, failure, cancellation | the SPEC's Behaviour |
| the **observable** evidence of success — a state transition, a tool called exactly once, audio muted at the right phase, a callback firing on real hardware | the SPEC's Acceptance criteria |
| the states, transitions and invariants it implies | [../docs/INVARIANTS.md](../docs/INVARIANTS.md), or the SPEC |
| what is deliberately out of scope | the SPEC's Non-goals |
| conflicts with existing decisions | an ADR, before building |

"Should work correctly" and "implementation complete" are not acceptance criteria. If a behaviour
can be written as a scenario, write the scenario.

A backlog demand with no SPEC is **eligible work whose next action is to compile it**, and
`discover_work.py` names it that way rather than naming an implementation. That is the difference
between a plan and a guess.

Then, in order, and not out of it:

```
SPEC -> TEST -> IMPLEMENTATION -> VERIFICATION -> canonical state
```

A test existing is not the feature existing. A green build is not behaviour. The stages in
[CONSTITUTION.md](../harness/CONSTITUTION.md) rule 13 are executable — `completion_stage()` in
`discover_work.py` — so "is this done?" has an answer that does not depend on who is asked.

## Declaring a blocker

Three one-line markers, read from the item's own section in its canonical document:

```
BLOCKED_BY: the concrete thing a person must supply
DEPENDS_ON: B-003, D-2
UNBLOCK_WHEN: file_differs app/src/main/assets/ivw/wakeword.jet sha256:<digest recorded when blocked>
```

`BLOCKED_BY` takes the item off the frontier. `DEPENDS_ON` takes its dependants off too, and
**nothing else** — one external dependency narrows the frontier, it does not end the run.
`UNBLOCK_WHEN` is checked on every refresh, so when the missing thing arrives the item and its
dependants reopen without anyone remembering to edit a document.

A blocked item is not reselected while it stays blocked. Retrying it needs a reason: the input
arrived, a dependency moved, new evidence appeared, or you were asked to.

## What counts as a candidate

Only work the repository already authorises:

an accepted SPEC · canonical backlog or debt · the active milestone · an invariant · failing
executable evidence · a partially implemented feature · an explicit repository requirement · a
repair made necessary by work already done · a harness or state inconsistency that makes execution
untrustworthy.

Everything else is an idea. Record it if [INTAKE.md](../agent/INTAKE.md) allows; do not implement it
to avoid stopping. **The loop exhausts authorised work — it does not invent scope.**

## When a human is genuinely required

Only when progress needs something an agent cannot legitimately obtain or decide:

a credential or secret · a physical interaction that cannot be automated or emulated · an external
account approval · inaccessible infrastructure · an irreversible operation needing approval · a
product or business choice the requirements deliberately leave open · input only a person or
external system can provide · a real conflict between authoritative requirements with no precedence
rule · a legal or compliance sign-off · a third-party response.

**These are not blockers.** One SPEC finished · the prompt finished · uncertainty about an
implementation detail · choosing between reasonable engineering alternatives · a test needs writing
· another backlog item exists · state files need updating · code is unwired · a script is stale ·
more inspection is needed · picking the next authorised task · a new milestone should start.

Ordinary engineering judgement belongs to the agent. A blocker is declared by putting a line in the
item's canonical document:

```
BLOCKED_BY: the test phone has no network route to the provider
```

**One line.** The value is read to the end of the line and no further, so a wrapped blocker is a blocker truncated mid-sentence in `BLOCKING_DEPENDENCY`.

`discover_work.py` removes that item from the frontier while the line stands, and
`harness_check.py` rejects a stop state that claims a human is needed without one.

## The hostile stop audit

Before even *attempting* to return control, run the termination hard gate — prose alone is not
enough:

```powershell
python scripts/model_route.py --action terminate-request
```

Then MAX_GROK (`grok-high` / `--already-grok`) must perform an independent hostile termination
review over the **full** project frontier (SPECs, milestones, OPEN_PROBLEMS, TEST_MATRIX,
backlog, tech debt, failing/skipped tests, missing E2E evidence, unwired code, unprotected
production paths, build/artifacts, dirty tree, stale state, and blockers that leave independent
work executable). The reviewer returns exactly one of `CONTINUE` | `TERMINAL_APPROVED` |
`REVIEW_UNAVAILABLE` via `--action terminate-review`.

Walk every one of these and answer out loud (the reviewer must; DEFAULT must not self-certify):

- active SPECs and their acceptance criteria;
- the active milestone rows;
- backlog and debt;
- invariant and architecture-rule status;
- TODO/FIXME inside the current scope;
- failing, skipped or disabled tests;
- code that exists but nothing calls;
- production paths with no test;
- build and artifact status;
- canonical state versus executable evidence;
- items whose blocker may have disappeared since it was written;
- blockers that only block one branch while independent authorized work remains.

The question is: **is there any authorised action reachable from this machine that could plausibly
improve correctness, verification, integration or completion?** If yes, stopping is forbidden
(`CONTINUE` + `NEXT_ACTION`).

## Stale state outranks generated summaries

When a generated file disagrees with executable evidence, the evidence is right and the generator is
suspect. Investigate the generator; do not hand-patch the symptom. A state-generation defect is
harness debt and is itself a work item — that is how `collect_state.py` came to record 56 tests for
a 631-test suite while every run was green.

## Stopping

`discover_work.py` prints a machine-readable stop *observation*:

```
AUTONOMOUS_ACTION_AVAILABLE = YES|NO
HUMAN_ACTION_REQUIRED       = YES|NO
STOP_REASON                 = NOT_STOPPING | WORK_FRONTIER_EXHAUSTED | BLOCKED_ON_EXTERNAL_DEPENDENCY
BLOCKING_DEPENDENCY         = none | <the concrete thing a person must supply>
NEXT_ACTION                 = NONE | <the next item>
TERMINATION_AUTHORIZED      = NO
TERMINATION_GATE            = python scripts/model_route.py --action terminate-request
```

`AUTONOMOUS_ACTION_AVAILABLE = YES` means the run may not end.

`AUTONOMOUS_ACTION_AVAILABLE = NO` is **not** permission to terminate. No agent may authorize
its own ending — not after frontier exhaustion, a human blocker, task/milestone completion,
or a final summary / "standing by". The only legal terminal transition is:

```
DEFAULT → terminate-request → MAX_GROK terminate-review → TERMINAL_APPROVED → terminate-consume → end
```

```powershell
python scripts/model_route.py --action terminate-request
# Task grok-high (or --already-grok) with hostile frontier review, then:
python scripts/model_route.py --action terminate-review --verdict CONTINUE|TERMINAL_APPROVED|REVIEW_UNAVAILABLE --role grok-high --fingerprint <fp> --next-action "…"
python scripts/model_route.py --action terminate-consume --token <token>   # only after TERMINAL_APPROVED
```

`CONTINUE` forbids termination and returns `NEXT_ACTION` to DEFAULT. `REVIEW_UNAVAILABLE`,
malformed/stale fingerprints, or Grok unavailable **fail closed** — never self-approve as
Composer/DEFAULT. A blocker narrows the frontier; it does not end the run.

## Meeting something only a person can do

Do not stop. That was the old behaviour and it cost the person one interruption per case, each
arriving at the moment of discovery rather than at a time that suited them.

1. Define the test precisely — procedure, prerequisites, pass criterion.
2. Add it to [TEST_MATRIX.yaml](../TEST_MATRIX.yaml).
3. Give it the owner that says *which kind* of human: `HUMAN_PHYSICAL`, `HUMAN_ACCOUNT`,
   `HUMAN_CREDENTIAL`, `HUMAN_DECISION`, or `EXTERNAL_RESOURCE`. Not one generic blocked.
4. `status: HUMAN_REQUIRED`, with `automation_blocker` set to exactly one of
   `subjective_perception`, `physical_world`, `credential_permission`,
   `hardware_interface`, `safety`. Tapping, ADB, emulator, logs, screenshots/video,
   or inconvenience are not blockers.
5. Record under `autonomous_evidence:` everything already established without them.
6. Record under `remaining_uncertainty:` exactly what is still unknown, and under `returns:` what
   you need them to tell you.
7. **Continue with every independent piece of work.**

For a `HUMAN_DECISION`, measure the alternatives first. A question with no numbers behind it is
not ready to be asked — `quantified:` is required, and validation enforces it.

## Asking, once

Only when `python scripts/test_matrix.py --gate` prints `HUMAN_VALIDATION_READY = TRUE`. It will
list every reason it is shut otherwise. Then regenerate the packet and hand over that one document:

```powershell
python scripts/test_matrix.py --status
python scripts/test_matrix.py --packet
```

When results come back, apply them **all**, triage every failure **together**, fix everything
autonomous in **one** cycle, and only then produce a single retest batch. Never ask for a retest of
the first failure while others are still fixable — the batching applies to every round, not just
the first ([harness/PHASES.md](../harness/PHASES.md)).

