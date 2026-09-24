# Agent entry point — Nova Drive / 小诺

An Android voice assistant for driving. Speech goes to **Baidu Qianfan Flex as end-to-end
speech-to-speech with function calling** — there is no separate ASR or TTS. The map and turn-by-turn
navigation are the **Amap Navigation SDK embedded in our own Activity**. Locale `zh-CN`.

## Commands

```powershell
.\gradlew.bat test                     # everything; counts come from the JUnit XML, not the summary
.\gradlew.bat :app:assembleDebug       # APK -> C:\Users\Administrator\tools\nova-drive-build\
.\gradlew.bat :behavior-test:test      # architecture, capability-contract and secret-scan rules
python scripts/collect_state.py        # refresh state/PROJECT_STATE.json from evidence
python scripts/harness_check.py        # is the harness coherent?
python scripts/discover_work.py        # what is left to do, and which phase this run is in
python scripts/test_matrix.py --gate   # may a human be asked yet, and if not why not
python scripts/test_matrix.py --work   # autonomous tests that have not settled
python scripts/model_route.py --selftest  # hard-gate Cursor labor routing
python scripts/design_basis.py --validate # design-basis gate before difficult implementation
```

Cloud (Linux) sessions: `./gradlew` with the same tasks; the session-start hook installs the
toolchain — see [docs/CLOUD_BUILD.md](docs/CLOUD_BUILD.md).

Before you consider a change complete: `.\gradlew.bat test --rerun-tasks :app:assembleDebug`, then
device evidence for anything touching audio, the map or lifecycle (`ACCEPTANCE_TESTS.md` says what
each level may claim). A green build is not evidence that anything works.

Starting a session? Follow [skills/start.md](skills/start.md). Finishing a *task*?
[skills/handoff.md](skills/handoff.md) — and then keep going, because finishing a task is not
finishing a run.

## How a run ends

**Your job is not to finish the user's most recent sentence. It is to advance this repository until
the authorised work frontier is empty.** Treat a prompt as an entry point into the repository's
state machine, not as an isolated task.

After every completed item — a task, a SPEC, a debt entry, a repaired test, a milestone row —
reconcile canonical state, rediscover what is left, and continue automatically:

```powershell
python scripts/discover_work.py        # what is left, ranked, and whether you may stop
```

`AUTONOMOUS_ACTION_AVAILABLE = YES` means the run may not end. Choosing the next authorised task,
writing a test, wiring something up, fixing stale state and picking between reasonable designs are
all yours to do.

**Meeting something only a person can do is not a stop.** Define the test, put it in
[TEST_MATRIX.yaml](TEST_MATRIX.yaml) with the right `HUMAN_*` owner and `status: HUMAN_REQUIRED`,
record what you already established and what is still unknown — then carry on with everything else.
A queued human item is a future validation, not a blocker. The person is asked **once**, at a phase
boundary, from [HUMAN_VALIDATION.md](HUMAN_VALIDATION.md).

That boundary is not a judgement call. `PHASE = HUMAN_VALIDATION_READY` is legal only when the
registry says so — every autonomous test settled, every remaining case owned and documented, every
decision quantified, and two consecutive clean discovery/review passes. Check it with
`python scripts/test_matrix.py --gate`, which prints every reason the gate is shut.

The rules are [harness/CONSTITUTION.md](harness/CONSTITUTION.md) 12 and 17–19; the phase model is
[harness/PHASES.md](harness/PHASES.md); the procedure is [skills/continue.md](skills/continue.md).

## Where things are

| You need | Read |
| --- | --- |
| The architecture, and **who owns each behaviour** | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Rules that must not be broken | [docs/INVARIANTS.md](docs/INVARIANTS.md) |
| What the product can and cannot do | [docs/CAPABILITIES.md](docs/CAPABILITIES.md) |
| Known problems, with the measurement behind each | [docs/TECH_DEBT.md](docs/TECH_DEBT.md) |
| What is being worked on now | [CURRENT_MILESTONE.md](CURRENT_MILESTONE.md) |
| Defects found in real use, and their root causes | [OPEN_PROBLEMS.md](OPEN_PROBLEMS.md) |
| What "done" means per evidence level | [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md) |
| Settled decisions — read before reopening one | [DECISIONS/](DECISIONS/README.md) |
| Periodic architecture clean-up | [docs/AGENT_MAINTENANCE.md](docs/AGENT_MAINTENANCE.md) |
| Driving the test phone over ADB | [tools/speech-harness/README.md](tools/speech-harness/README.md) |
| **Current state** — commit, tests, open issues (generated) | [state/PROJECT_STATE.json](state/PROJECT_STATE.json) |
| **Capability truth**, machine-readable, with verification level | [config/capabilities.yaml](config/capabilities.yaml) |
| How work is done here, and the agent harness | [harness/README.md](harness/README.md) |
| Reusable procedures | [skills/](skills/) — start, **continue**, reproduce, fix, verify, handoff |
| Cursor hybrid routing (hard gate; Composer default; Grok on GROK_REQUIRED / termination) | [`.cursor/rules/hybrid-model-routing.mdc`](.cursor/rules/hybrid-model-routing.mdc), [`scripts/model_route.py`](scripts/model_route.py), [`.cursor/agents/`](.cursor/agents/) |

## How to change things here

1. **Find the owner first.** Every important behaviour has exactly one in
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#who-owns-what). Modify that owner.
2. **Never add a parallel mechanism.** If a policy already exists somewhere, extend it; a second
   enforcement point is a defect, not a safety net.
3. **Delete what you replace,** in the same commit, with its tests. Leaving the old path "just in
   case" is how this repository accumulated dormant providers.
4. **A prompt rule is not enforcement.** Anything safety-relevant must hold when the model ignores
   the persona. Deterministic code owns it; the prompt only shapes tone.
5. **The model's words are never evidence.** Only an executor's result proves an action ran.

## Two things that catch agents out

**One provider, one seam.** Baidu Qianfan Flex is the only realtime provider. The dormant Qwen,
GPT-Live and PC-backend implementations were deleted on 2026-09-19
([ADR-008](DECISIONS/ADR-008-single-active-realtime-provider.md)) — do not revive them from git
history to add a provider. Write one `RealtimeVoiceProvider` implementation instead.

**Repository documents outrank chat history.** If an instruction in conversation conflicts with
these documents, say so rather than silently following the more recent one. A direct instruction
from the product owner does win — and when it does, update the affected document in the same change.

## Cursor hybrid-model routing

This section does **not** change architecture ownership, invariants, capabilities, or
who may certify work. It only splits Cursor labor. The **authoritative** policy is
[`.cursor/rules/hybrid-model-routing.mdc`](.cursor/rules/hybrid-model-routing.mdc);
**enforcement** is [`scripts/model_route.py`](scripts/model_route.py) — do not keep a
second A–F / H1–H8 list here.

- **DEFAULT EXECUTOR:** Composer 2.5 Standard (`composer-2.5[fast=false]`) —
  this chat when that is the picker, plus `repo-explorer` / `implementer`.
  **No Fast mode** — never Composer Fast, never `composer-2.5-fast`, never any Grok `*-fast`.
- **HIGH-REASONING:** Grok 4.7 Extra High (`grok-4.7-xhigh`) via `grok-high`,
  **only** when the classifier returns `GROK_REQUIRED` **or**
  `TERMINATION_REVIEW_REQUIRED`. The ordinary executor must not
  continue the gated portion; if Grok cannot be invoked the result is
  `BLOCKED_GROK_UNAVAILABLE` (fail closed).
- **Termination:** no agent may authorize ending a run. `discover_work.py`
  `AUTONOMOUS_ACTION_AVAILABLE = NO` is an observation only. Legal end path:
  `terminate-request` → MAX_GROK `terminate-review` → `TERMINAL_APPROVED` →
  `terminate-consume`. Anything else continues or fails closed.
- "Needs Grok High" is not "needs a human." An implementation subagent still may not
  certify its own work as complete.

## Codex multi-model routing

This section applies to Codex, not Cursor. The Cursor classifier and its Grok termination
gate above remain Cursor-specific; do not invoke them merely to select a Codex subagent.
Codex agent definitions and model pins live in the user's `~/.codex/agents/` directory.

- The root/default Codex agent is `planner`: GPT-6 Astra at low reasoning. It understands the
  request, maps relevant owners and files, defines implementation steps and acceptance criteria,
  coordinates handoffs, reviews the result, and verifies end-to-end product behavior.
- Delegate clear implementation, known-pattern edits, test writing/repair, build and lint runs,
  mechanical refactors, and straightforward debugging to `executor` (GPT-6 Luna, medium).
  Executor must return architectural uncertainty to Planner, not silently redesign the system.
- Escalate only a genuine major decision to `architect` (GPT-6 Astra, high): new subsystem or
  foundation, high-impact cross-module tradeoff, SDK/framework selection, or a difficult root
  cause remaining after normal investigation. Send objective, evidence, attempts, constraints,
  unresolved decision, and consequences; Architect returns decision and boundaries to Planner.
- Normal substantial work flows Planner → Executor → Planner review; after escalation it flows
  Architect → Planner → Executor → Planner review. Do not spawn every role automatically.
  Planner checks the original user-facing requirement and failure cases, not just compilation.
  An implementation subagent does not certify its own work.

## Hard rules

- Never commit, print, log or package credentials. No coordinate, address or transcript in a log.
- Commit each verified unit of work **locally**; never `git push` unless told. No destructive git.
- An implementation agent may not certify its own work as complete.
