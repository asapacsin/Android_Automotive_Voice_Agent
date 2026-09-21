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

## Spec template and spec policy

The template is [SPECS/SPEC-TEMPLATE.md](../SPECS/SPEC-TEMPLATE.md). Copy it; do not invent a
shape. It used to be inlined here, which meant two versions drifting apart.

### A finished SPEC is a checkpoint, not the end of a run

When every acceptance criterion passes:

1. reconcile the SPEC's own status and its implementation-status table;
2. update the backlog row, the capability registry, the debt list and `OPEN_PROBLEMS.md`;
3. regenerate canonical state (`python scripts/collect_state.py`);
4. rediscover the frontier (`python scripts/discover_work.py`);
5. **take the next item.**

Handing control back because a SPEC reached Done is forbidden by
[CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12. The same applies to a finished debt item, a
repaired test, a milestone row, or the last sentence of a prompt.

### Acceptance criteria are executable where feasible

Separate what each criterion proves — functional behaviour, production wiring, negative and failure
behaviour, regression protection, architectural invariants, artifact build, and state
reconciliation. "Supports X" is not a criterion when an assertion can say what X looks like when it
works. A criterion whose state column says `not built` or `not earned` is picked up automatically by
`scripts/discover_work.py`, so leaving it honest is what keeps it from being forgotten.

A lifecycle, end-to-end, baseline, arrival or completion criterion is not compiled until its
**terminal oracle** and abort conditions are written down. Intermediate success is prefix evidence,
not proof of the parent flow. Manual stop, timeout, recording end or an unexpected transition before
the terminal oracle cannot close the parent criterion. This is
[CONSTITUTION.md](../harness/CONSTITUTION.md) rule 20 and is enforced for lifecycle registry entries by
`scripts/test_matrix.py`.

### Finishing one SPEC must not hide another

Closing a SPEC does not close: unfinished criteria in older SPECs · implemented-but-unwired code ·
unverified behaviour · unresolved debt · broken invariants · stale state · missing tests · a failing
build · or deferred work whose blocker has since disappeared. All of those are on the frontier the
discovery script reads, which is why the script reads the documents rather than the transcript.
