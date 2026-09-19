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
python scripts/discover_work.py        # what is left to do, and may this run stop?
```

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

`AUTONOMOUS_ACTION_AVAILABLE = YES` means the run may not end. Ask for a person only at a genuine
external boundary — a credential, a device, an approval, a product decision — and say concretely
what is missing. Choosing the next authorised task, writing a test, wiring something up, fixing
stale state and picking between reasonable designs are all yours to do.

The rule is [harness/CONSTITUTION.md](harness/CONSTITUTION.md) rule 12; the procedure is
[skills/continue.md](skills/continue.md).

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

**Dormant code still compiles.** Qwen, GPT-Live and a PC backend are all present and none is part of
the product. Do not infer the architecture from filenames — read
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

**Repository documents outrank chat history.** If an instruction in conversation conflicts with
these documents, say so rather than silently following the more recent one. A direct instruction
from the product owner does win — and when it does, update the affected document in the same change.

## Hard rules

- Never commit, print, log or package credentials. No coordinate, address or transcript in a log.
- Commit each verified unit of work **locally**; never `git push` unless told. No destructive git.
- An implementation agent may not certify its own work as complete.
