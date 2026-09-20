# Phases — and why a human is a boundary, not an exception

## The failure this replaces

```
run → hit something only a person can do → stop and ask → wait
    → resume → hit the next one → stop and ask → wait → …
```

Every human-dependent case was its own interruption. The person was pulled in repeatedly, each
time with one question and no context, and between interruptions the agent sat idle while other
work — work it could have done unaided — went untouched.

The cost is not the asking. It is that the asking happened **at the moment of discovery** rather
than at a point chosen for the person's convenience.

## The model

```
SELECT / REQUIREMENTS
        ↓
IMPLEMENT
        ↓
AUTONOMOUS_TEST ──────────┐
        ↓                 │
TEST_DISCOVERY            │  any autonomous work found
        ↓                 │  sends it back here and
TEST_REVIEW               │  resets the clean-pass count
        ↓                 │
AUTONOMOUS_FIX ───────────┘
        ↓
   (two consecutive clean passes)
        ↓
HUMAN_VALIDATION_READY
        ↓
HUMAN_VALIDATION          ← the person is involved exactly here
        ↓
   failures? ── yes → AUTONOMOUS_FIX → TEST_REVIEW → HUMAN_RETEST_READY
        │
        no
        ↓
RELEASE_REVIEW
        ↓
RELEASE_READY
```

## What each phase is for

**AUTONOMOUS_TEST** — execute everything in [TEST_MATRIX.yaml](../TEST_MATRIX.yaml) owned by
`AUTONOMOUS` that has not settled. Encountering a test only a person can run **does not stop
this phase**: the test is defined precisely, given the right `HUMAN_*` owner and
`status: HUMAN_REQUIRED`, everything already established is recorded against it, and execution
continues.

**TEST_DISCOVERY** — walk the capability list and ask, for each one: *what would have to be true
before we could credibly claim this works?* Every answer either maps to an existing entry or
becomes a new one. Tests found only by writing code are the tests you already thought of.

**TEST_REVIEW** — reconcile the registry against the specs, the capability registry, the
invariants, the ADRs, the bug history and the device evidence. Look specifically for coverage
that would pass with the implementation broken. Do not interrupt anyone during review.

**AUTONOMOUS_FIX** — everything discovery and review turned up that an agent can do.

**HUMAN_VALIDATION_READY** — a gate with ten conditions, not an opinion. See below.

**HUMAN_VALIDATION** — the person works through [HUMAN_VALIDATION.md](../HUMAN_VALIDATION.md)
in one sitting and returns all the results together.

## The gate

`PHASE = HUMAN_VALIDATION_READY` is legal only when every one of these holds:

1. Every known required test is in the registry.
2. Every `AUTONOMOUS` test is `PASS`, `NOT_APPLICABLE`, or `BLOCKED_EXTERNAL` **with evidence**.
3. No unresolved autonomous `FAIL` that could still be investigated.
4. Every remaining executable case is explicitly `HUMAN_REQUIRED` with a `HUMAN_*` owner.
5. Every human item carries: why a human is unavoidable, the exact procedure, what is needed,
   the pass criterion, what was already established without them, and what to report back.
6. Every `HUMAN_DECISION` carries quantified alternatives.
7. `TEST_REVIEW` has run.
8. Two consecutive clean discovery/review passes found no material autonomous work.
9. Registries, docs and generated state are reconciled.
10. The working tree is clean but for intentionally documented artifacts.

Conditions 1–8 are enforced by `python scripts/test_matrix.py --gate`. It prints every reason the
gate is shut. 9 and 10 are `scripts/harness_check.py` and `git status`.

**The old condition — `AUTONOMOUS_ACTION_AVAILABLE = NO` — is no longer the transition.** It was a
judgement about whether anything was left, and judgements drift. The gate is a property of the
registry: either every autonomous test has settled, or it has not.

## Batching is recursive

When results come back, they are applied **together**:

```
human batch → apply every result → triage every failure
            → fix everything autonomous in ONE cycle → rerun the suites
            → produce ONE consolidated retest batch
```

Never ask for a retest of the first failure while other failures are still fixable. The same
principle that put the human at a boundary instead of in the loop applies to every round after
the first.

## Two clean passes, and why

One pass proves the known list is done. It does not prove the list is right — that was the old
"backlog empty, therefore finished", which is how a run ends while obvious work remains.

So after a clean review, do an *independent* gap pass. If it finds anything autonomous, execute it
and the count resets to zero. `scripts/test_matrix.py --record-pass clean|dirty` keeps the count in
`state/DISCOVERY_PASSES.json`.

## The rule in one line

**Encountering `HUMAN_REQUIRED` is not a stop.** It is a queue insertion, and the queue is
delivered once.
