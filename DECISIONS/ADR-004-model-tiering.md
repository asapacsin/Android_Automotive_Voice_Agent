# ADR-004 — Expensive models for architecture and checkpoints, not routine coding

Status: **Accepted**

## Context

This project is built by AI agents of differing capability and cost. Using a high-reasoning model for routine implementation is slow and expensive; using a fast model for architecture produces designs that have to be redone.

Experience on this repository also showed a second failure mode: an implementation agent that reports its own work as complete tends to overstate verification — for example treating a successful build, or a mocked WebSocket test, as evidence that the feature works on a phone.

## Decision

Work is tiered by kind, not by convenience.

| Work | Owner |
| --- | --- |
| Architecture, product direction, hard design calls | High-reasoning model (architect) |
| Protocol interpretation, security-sensitive decisions | High-reasoning model |
| Major debugging after ordinary execution has failed | High-reasoning model |
| Milestone checkpoints and final acceptance | High-reasoning model |
| Routine implementation, tests, build fixes, refactors | **Cursor + Grok Fast** |
| Independent review of a diff | A separate agent from the builder |

Rules:

- Do **not** use a high-reasoning model for implementation that Cursor + Grok Fast can perform.
- An implementation agent may **not** certify its own work as fully complete; independent verification is required.
- The architect does not run a second parallel implementation, rewrite working code for style, or re-run full suites at every checkpoint. It reads the report and the diff, and verifies where a concrete risk remains.

## Consequences

- Task definitions must be precise enough for a fast model: exact files, exact constraints, explicit "do not modify" lists, and objective verification commands.
- Review is a distinct step with its own role definition (`agent/REVIEWER.md`), not a self-assessment.
- Escalation from builder to architect is expected and cheap; silent architecture invention by the builder is the failure this tiering exists to prevent.

## What would justify revisiting this decision

- A fast model becoming reliable enough for architecture-level judgement.
- A high-reasoning model becoming cheap enough that tiering costs more in coordination than it saves.
- Repeated builder escalations on the same area, indicating the architecture — not the model tier — is the problem.
