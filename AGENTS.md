# Agent entry point — Nova Drive / 小诺

An Android voice assistant for driving. Speech goes to **Qwen-Omni Realtime with the voice Ayano
(the owner's own synthetic voice, cloned per model; stock Maia only as a typed-in fallback, owner
2026-10-12), end-to-end speech-to-speech with function calling** (the product session since 2026-10-09,
[ADR-017](DECISIONS/ADR-017-qwen-omni-realtime-end-to-end.md); the Gemini Live and Baidu stacks stay
in the tree until ADR-017's gates pass, but no setting selects them (debug builds: an in-memory debug override for tests and demos, decided 2026-10-15)) — there is no separate ASR or TTS. The map and turn-by-turn
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
all yours to do - and when you do not know which choice to pick, see "Unsure which to pick" below.

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
| What a green CI check means (tiers A/B/C, verdicts, quarantine, review gate) | [docs/CI.md](docs/CI.md), [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) |
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
6. **Research before inventing** (owner, 2026-10-11). Every time you meet a problem - a vendor
   error, a model quirk, a latency or protocol issue, a design question - first search how others
   solved it: the vendor's documentation and changelog (Alibaba Model Studio / DashScope, Amap,
   Android), its GitHub issues and SDK samples, and community write-ups (forums, blogs, Stack
   Overflow, Zhihu/CSDN for Chinese vendors). Record what you found and its source next to the
   fix (commit message, OPEN_PROBLEMS or the SPEC), including "searched, nothing found". Only then
   design your own solution.

## Two things that catch agents out

**One provider, one seam, one voice.** The product session is Qwen-Omni with Ayano (Maia before 2026-10-12)
([ADR-017](DECISIONS/ADR-017-qwen-omni-realtime-end-to-end.md) amendment, 2026-10-09):
`VoiceProviderChoice.resolve` always returns Qwen, and a missing Qwen key, workspace or consent fails
the start with a `QWEN_*` code — **never an automatic fallback** to Gemini or Baidu, because the
driver must not hear two voices ([ADR-013](DECISIONS/ADR-013-gemini-default-provider.md)). Every
provider sits behind the same seam ([ADR-010](DECISIONS/ADR-010-gemini-live-second-provider.md)),
chosen once per session in `openSession`. The Gemini, Azure and Baidu stacks are deleted only after
ADR-017's Q-5 and the owner's confirmation. The older dormant Qwen, GPT-Live and PC-backend
implementations were deleted on 2026-09-19 ([ADR-008](DECISIONS/ADR-008-single-active-realtime-provider.md))
— do not revive them from git history; the current Qwen adapter is SPEC-021's. Behaviour varies on `ProviderCapabilities`, never on the provider name.

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

## Claude Code hierarchical routing

This section applies to Claude Code only; the Cursor and Codex sections above are unchanged. It
does not change architecture ownership, invariants, or who may certify work. Agent definitions:
[`.claude/agents/`](.claude/agents/).

- Start the session as `planner` (`claude --agent planner`; Opus 5.5, high effort). It reads this
  file and the documents under "Where things are", builds a dependency DAG, and runs waves.
- `executor` (Opus 5.5, low effort) implements bounded task packets in its own worktree. It cannot
  spawn agents and must return BLOCKED rather than guess or redesign.
- `reviewer` (Opus 5.5, high effort, read-only) is used selectively; its policy is
  [`agent/REVIEWER.md`](agent/REVIEWER.md). Verdicts: PASS / REVISE / ARCHITECTURE_REVIEW_REQUIRED.
- At most 4 concurrent executors. Writers get worktrees the planner creates from the exact feature
  commit (`scripts/new_worktree.sh <task> <branch> <BASE_COMMIT>`, which also unpacks the gitignored vendor files so `:app` builds), not the Agent tool's
  worktree isolation, which has based worktrees on an older commit here.
- `decider` (Opus 5.5, xhigh effort, read-only) breaks ties: any choice the planner does not know how
  to make goes there first, and only its `OWNER_DECISION_REQUIRED` goes to the owner (hard rule below).
- Architecture is upstream of planning: a conflict stops that path with
  `ARCHITECTURE_REVIEW_REQUIRED`; it is decided through `agent/INTAKE.md`, never inside a wave.

## Hard rules

- **Production and test source is written by executors** (owner 2026-10-16): the main/planner agent may not edit `<module>/src/**` in the main checkout;
  settings deny rules and the guard-source-writes hook enforce it, `scripts/delegation_check.py` audits the branch.
  Planner keeps docs, state, scripts/tools and integration.
  Review comes after the executor finishes and before its merge: `git merge exec/*` is refused until
  `state/reviews.log` has PASS (the `reviewer`) or WAIVED (with a reason) for the branch tip; the Stop hook
  (`delegation_check.py --stop-hook`) backs it up after the merge.
- **Keep executing; the owner only chooses** (owner, 2026-10-16). Keep working without waiting for
  a go-ahead: everything you can do yourself is your task. Bring the owner only a real choice: give
  two or more distinct options, each with its consequence and its time cost, plus your
  recommendation, so the owner can pick one. Never ask the owner to do something you can do yourself.
  Never stop at a status report or an open question while autonomous work remains.
- **Unsure which to pick: Opus 5.5 at xhigh effort first, then the owner** (owner, 2026-10-12). When you
  do not know which of two or more options to choose, and the documents do not settle it, do not guess
  and do not take the first plausible one. Send the question to a reasoning pass on Opus 5.5 at xhigh
  effort: in Claude Code the `decider` agent
  ([`.claude/agents/decider.md`](.claude/agents/decider.md)), with the options, the evidence and the
  binding documents. If it answers `OWNER_DECISION_REQUIRED`, because it still cannot determine the
  best way, ask the owner: one precise question, each option with its consequences, and a
  recommendation. Meanwhile carry on with independent work. Record the decision and who made it (the
  decider, or the owner) where the change is recorded: commit message, SPEC, ADR or OPEN_PROBLEMS.
  This does not replace `ARCHITECTURE_REVIEW_REQUIRED`: changing an ADR, an invariant or a contract
  still goes to the owner through `agent/INTAKE.md`.

- **Measure the time every piece of work took, and make the slow parts faster** (owner, 2026-10-17).
  When a task, fix or test finishes, record its wall-clock minutes and attempts with the
  efficiency verdict in the handoff record (`scripts/skill_event.py --minutes --attempts
  --efficient`, [skills/handoff.md](skills/handoff.md) step 5). If it was not a time-efficient way
  to do it, name what lost the time and change the process now (a script, a skill step, a settings
  or environment change) or, when only the owner can change it, bring that as a choice. A cause
  seen twice (`scripts/skill_metrics.py` `TIME_IMPROVEMENT`) is due work.
- Never commit, print, log or package credentials. No coordinate, address or transcript in a log.
- **Commit and push each implemented function as soon as it is verified** (owner, 2026-10-11; this
  replaces "commit locally, never push unless told"): one verified unit, one commit, pushed to the
  working branch right away. Never push unverified or red work; never force-push. No destructive git.
- An implementation agent may not certify its own work as complete.
- **Do not spend the live Qwen (DashScope) quota unless a test truly needs it** (owner, 2026-10-10:
  800K of a 1M free quota went in two days of emulator takes). Prove everything you can first with the
  JVM suites, the scripted/mock servers (`qwen_probe.py --selftest`, `QwenOmniClientTest`) or the Gemini
  live harness. A live Qwen run (app session, emulator take, `record_demo.py`, `qwen_probe.py`,
  `tools/demo/live` with qwen) needs the owner's go for that run: state what it will prove, how many
  sessions and minutes it opens, then set `NOVA_SPEND_QWEN_QUOTA=yes` for that run only. The tools
  refuse to open a Qwen session without it. One good take beats five retries: fix with offline
  evidence, then re-record once.
- **Never spend `qwen3.8-omni-flash-realtime` on a test** (owner, 2026-10-11): its quota is for the
  final demo. Test runs name their model (`NOVA_QWEN_MODEL` / `MODEL`; there is no default) and use
  `qwen3.5-omni-plus-realtime`; the tools refuse 3.8 unless `NOVA_ALLOW_QWEN_38=yes`, which only the
  owner's explicit, per-run say allows. A plan that says "take 2 on 3.8" is not that say.
- **Cheapest test first** (owner, 2026-10-11). Every test that could call a paid API is planned
  for cost before it runs: no API call when an offline test can prove the point (JVM suites, mock
  servers, recorded fixtures); then the free tier (the Gemini live harness); then the cheapest
  model that can do what the test needs, with the smallest input (one clip, a setup-only session,
  no audio when the question is the session); the more expensive model only after the cheap one
  passed, and only for what only it can show. State the expected cost in the plan.
- **Gemini first, Ayano last** (owner, 2026-10-13). Until the feature work is done, every live test,
  rehearsal and demo video runs on Gemini (free), even when the question is how she sounds - say in
  the hand-over that the voice is Gemini's, because Gemini cannot use a cloned voice. Ayano on Qwen
  comes back once, after everything is done: the owner says when, and that is the go for the Ayano
  takes. Until then open no Qwen session at all (no 3.5, no 3.8, no clone), and do not ask for one.
  The free models that take a cloned voice, for that later switch, are in
  [docs/VOICE_CLONE_FREE_MODELS.md](docs/VOICE_CLONE_FREE_MODELS.md).
- **Gemini's free tier resets every day: use it, oldest model first** (owner, 2026-10-11; oldest-first and
  daily reset 2026-10-12). Every live test or rehearsal whose question is not Ayano's own voice runs on
  Gemini, and a used-up or low Qwen quota is never a reason to leave it untested: rehearse on Gemini
  now, keep Qwen for the one take. It uses the **oldest Live model that works** first
  (`VoiceCatalog.geminiExperimentOrder`: `gemini-3.1-flash-live-preview`, the harness default; the older
  `gemini-2.5-flash-native-audio-preview-12-2025` rejects our setup with 1007), and a newer one
  (`gemini-3.8-live`, then `-extended-thinking`) only when the older cannot show what the test needs -
  record which and why. The daily reset allows a full-demo rehearsal after a change; still never
  repeated runs to chase latency variance (a control run is one more run, so plan it), and stop for
  the day if the free tier answers with a quota error. A test whose question only the
  Qwen voice can answer (a custom or cloned voice: how she sounds, reading the car lines) skips
  Gemini, whose voice is not ours, and goes straight to the cheapest Qwen model with the
  smallest input: text-only turns reading the lines, one short session, no full demo. (Until the owner says the work is done this waits: "Gemini first, Ayano last" above.)
- **Test each piece before a full demo run** (owner, 2026-10-16). Before any full real-run demo (a
  recorded end-to-end drive or session), break the demo into its sub-tasks, one per function it
  shows. For a navigation demo these are: destination search and pick, route start, guidance voice,
  each driver request, the over-speed warning, arrival and reconnect. Test each sub-task on its own
  with the cheapest check that proves it (JVM suite, scripted or mock server, a short targeted
  emulator check), and have it reviewed. Run the full demo only after every sub-task has passed. A
  full run is the final proof, never the way to find bugs: when it fails, go back to the failing
  sub-task, fix and verify it alone, then run the full demo again.
- **Demo-oriented tests, no API the function does not need** (owner, 2026-10-11). Write test cases
  from the demo's user scenarios: the driver lines and expected outcomes of `docs/DEMO_COMMUTE.md`
  and `docs/DEMO_REQUIREMENTS.md` (say this, the car does that, she says this, within this time),
  not from internal call shapes alone. A test calls an external API (Qwen, Gemini, Amap, edge-tts)
  only when that API is part of the function under test; otherwise it uses a fake, a scripted
  server or a recorded fixture. Testing the claim gate, a tool, a scenario or a UI never needs a
  live voice model.
- **Live Qwen only when the voice model itself is under test** (owner, 2026-10-11): what she hears,
  says, calls or sounds like, or how fast she answers. Everything else - UI and settings screens,
  claim-gate and turn logic, tool and scenario code, harness or recorder changes, a rehearsal of a
  demo script - is proved with the JVM suites, the mock servers or the Gemini live harness, and a
  demo is rehearsed on Gemini before its one Qwen take.
