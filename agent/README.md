# Agent control loop — Nova Drive / 小诺

This folder defines how AI agents work on this repository. Read it before acting.

## Authority rule

> Conversations are temporary context. Repository control documents are authoritative persistent context.

If a chat instruction conflicts with what this repository says, **surface the conflict explicitly** instead of silently following whichever instruction you saw most recently.

An explicit new instruction from the human product owner **does** supersede repository documentation — but when that happens, updating the affected control documents is part of the change, not an optional follow-up.

## Read order (every task, before editing anything)

1. `PRODUCT.md` — what we are building right now
2. `ARCHITECTURE.md` — the approved design and what is active vs dormant
3. `CURRENT_MILESTONE.md` — the one thing we are trying to finish
4. `ACCEPTANCE_TESTS.md` — what "done" objectively means
5. `DECISIONS/` — settled questions; do not reopen without new evidence
6. `OPEN_PROBLEMS.md` — known defects with measured root causes; do not re-diagnose what is already proven
7. `BACKLOG.md` + `SPECS/` — what the product owner has asked for, and the specs for it

Then read the actual code you intend to change. Do not infer state from filenames: this repository contains dormant compatibility code that looks active.

## The loop

Demands enter through `agent/INTAKE.md`: recorded in `BACKLOG.md`, specced in `SPECS/` when non-trivial, decided as an ADR when they touch architecture, and only then promoted into `CURRENT_MILESTONE.md`.

```text
Product owner demand  →  BACKLOG.md  →  SPECS/  →  (ADR if architectural)
    ↓
Architect (high-reasoning model)
    ↓  updates PRODUCT / ARCHITECTURE / ADRs / CURRENT_MILESTONE
Builder (Cursor + Grok Fast)  ─ agent/BUILDER.md
    ↓  smallest coherent change + tests
Automated verification (Gradle: unit, protocol, build)
    ↓
Reviewer (independent)  ─ agent/REVIEWER.md
    ↓  correction loop if needed
Device / E2E validation (real phone, real provider)
    ↓
Milestone complete
```

## Role assignment

| Role | Model | Responsibility |
| --- | --- | --- |
| Architect | High-reasoning (Claude high reasoning / Sol / Astra) | Architecture, hard design calls, major debugging, milestone checkpoints, architecture revision |
| Builder | **Cursor + Grok Fast** | Routine implementation, tests, build fixes, refactors within the approved architecture |
| Reviewer | Independent agent (Codex or Claude) | Verify against the control documents and the real diff; never the same run as the builder |

Routine implementation that Cursor + Grok Fast can perform **must not** be given to an expensive high-reasoning model. See `DECISIONS/ADR-004-model-tiering.md`.

## Non-negotiables

- An implementation agent may **not** certify its own work as fully complete. Independent verification is required.
- A successful build is not evidence of working behaviour. See `ACCEPTANCE_TESTS.md`.
- Never commit credentials. Never print, log, or package them.
- Do not delete dormant compatibility code for cleanliness. Clarity of authority is the goal, not refactoring.
- If the architecture appears unable to satisfy a requirement, **escalate** rather than inventing a new design.
