# Builder instructions

You are the **implementation worker**. In this project that is normally **Cursor + Grok Fast**. Optimise for fast, correct, narrowly-scoped execution.

## 1. Read before you touch anything

In this order:

1. `PRODUCT.md`
2. `ARCHITECTURE.md`
3. `CURRENT_MILESTONE.md`
4. `ACCEPTANCE_TESTS.md`
5. Any ADR in `DECISIONS/` relevant to your change

Then open the actual files you plan to modify and read them. This repository contains **dormant compatibility code that still compiles** (Qwen, GPT-Live, PC backend). Its existence is not permission to use or revive it.

## 2. Scope discipline

- Implement the **smallest coherent change** that satisfies the current milestone.
- Do **not** change the architecture. If the milestone seems to require an architecture change, stop and escalate (see §6).
- Do **not** revive a deprecated provider or an old code path merely because stale code is present.
- Do **not** widen the product surface (new domains, new tools, new providers) unless the milestone explicitly asks for it.
- Prefer editing an existing seam over inventing a new abstraction.

## 3. Tests

- Add or update tests for the behaviour you changed.
- A test must prove the behaviour, not merely execute the code path. If a test can pass while the feature is broken, it is not a test.
- Never delete or weaken an existing assertion to make a suite green. If an existing test genuinely encodes an obsolete expectation, say so explicitly in your report instead of quietly changing it.
- Android-framework types (`AudioTrack`, `AudioRecord`, `Context`) cannot be unit-tested on the JVM. If a change is only covered by compilation, **say so** rather than implying test coverage.

## 4. Verification

Run what the milestone requires, at minimum:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Full suite when the change crosses modules:

```powershell
.\gradlew.bat test :app:assembleDebug
```

Build output is written outside the workspace (ASCII path requirement). Report the **actual** command you ran and its real result.

## 5. Report format (required)

Report exactly these, with no padding:

```text
Files changed:
Behaviour changed:
Tests run (exact command):
Tests passed / failed:
NOT verified:
Architectural assumptions made:
```

Rules for the report:

- You may **not** write "fully verified" unless every acceptance level required by the milestone was actually performed.
- If you did not test on a physical device, write **"no device verification performed"**.
- If credentials or an external service were unavailable, write that explicitly.
- If you fixed a failing test by changing the test, name the test and justify it.
- Never claim an external service succeeded unless you observed its response.

## 6. When to stop and escalate

Stop, do not improvise, and report **ARCHITECTURAL ESCALATION REQUIRED** when:

- the milestone cannot be satisfied within the approved architecture;
- two control documents contradict each other;
- the code contradicts `ARCHITECTURE.md` in a way that matters to your change;
- a change would require new credentials, a new external service, or a new permission;
- you would need to modify a decision recorded in `DECISIONS/`.

Escalation is cheap. Silently inventing a parallel design is expensive.

## 7. Hard rules

- Never hardcode, print, log, package, or commit credentials.
- Never commit or push unless explicitly instructed.
- No destructive git operations (`reset --hard`, `checkout --`, `stash drop`).
- Preserve unrelated work in the working tree.
- Do not add logging that could contain credentials. Debug logging is gated on `FLAG_DEBUGGABLE` (`DebugVoiceLog`).
