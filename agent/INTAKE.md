# Demand intake pipeline

How something the product owner says in conversation becomes shipped, verified work — without depending on chat history surviving.

## Why this exists

Conversations are lost. A demand spoken once and acted on immediately tends to be half-remembered, half-implemented, and impossible to verify later. This pipeline makes every demand land in a file before any code is written.

## The pipeline

```text
1. DEMAND        Product owner says what they want (chat, voice, anywhere)
       ↓
2. RECORD        Architect writes it into BACKLOG.md verbatim-in-substance,
                 with the date and what problem it solves. No design yet.
       ↓
3. SPEC          If it needs more than one obvious change, the architect writes
                 SPECS/SPEC-NNN-<name>.md: scope, constraints, open questions,
                 acceptance level. Conflicts with existing ADRs are named here.
       ↓
4. DECIDE        Open questions that change the architecture get resolved by the
                 architect (or escalated to the product owner) BEFORE building.
                 A settled question becomes an ADR in DECISIONS/.
       ↓
5. MILESTONE     When it becomes the next thing to build, it moves into
                 CURRENT_MILESTONE.md with an objective completion rule.
       ↓
6. BUILD         Cursor + Grok Fast implements it per agent/BUILDER.md.
       ↓
7. VERIFY        Automated levels (L1–L4) run. Device/live levels (L5–L6) are
                 performed, or explicitly recorded as not performed.
       ↓
8. REVIEW        Independent reviewer per agent/REVIEWER.md. Never the builder.
       ↓
9. CLOSE         BACKLOG.md entry marked DONE with the evidence that closed it.
                 A defect found in use goes to OPEN_PROBLEMS.md instead.
```

## Rules

- **Record before building.** Even an urgent demand gets a `BACKLOG.md` line first. It takes seconds and it is the only durable record.
- **Do not skip step 4.** A demand that contradicts an ADR must be resolved as a decision, not quietly implemented. Two silently conflicting designs is the expensive failure.
- **A spec is not a design document.** It states what must be true, the constraints, and what is unknown. It does not specify implementation unless the implementation is the constraint.
- **Open questions are first-class.** Writing "unknown: whether X is available on this device" is more valuable than guessing. Guesses become facts in later sessions if unmarked.
- **Nothing closes on a green build.** See `ACCEPTANCE_TESTS.md`.

## Where things live

| Artefact | File |
| --- | --- |
| Recorded demands, newest first | `BACKLOG.md` |
| Specs for non-trivial demands | `SPECS/SPEC-NNN-*.md` |
| Settled architectural questions | `DECISIONS/ADR-NNN-*.md` |
| The one thing being built now | `CURRENT_MILESTONE.md` |
| Defects found in real use | `OPEN_PROBLEMS.md` |

## Spec template

```markdown
# SPEC-NNN — <short name>

Status: Draft | Ready | In milestone | Done | Dropped
Raised: <date> by <who>
Backlog: BACKLOG.md#<anchor>

## Demand (what was actually asked for)
## Why it matters
## Scope
## Out of scope
## Constraints and conflicts   <- name the ADRs it touches
## Open questions              <- what must be answered before building
## Acceptance                  <- which L-level, per ACCEPTANCE_TESTS.md
```
