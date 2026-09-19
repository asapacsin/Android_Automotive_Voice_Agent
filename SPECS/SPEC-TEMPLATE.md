# SPEC-NNN — <one line: the behaviour, not the mechanism>

Status: **Draft | Partly implemented <date> | Done <date> | Superseded by <what>**
Raised: <date> · Source: <B-NNN in BACKLOG.md, or the problem this answers>
Depends on: <invariants, capabilities, ADRs this must not break>

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

What problem this solves, for whom, in a paragraph. If it cannot be said without listing classes,
it is not understood yet.

## Scope

What is included, as behaviour.

## Non-goals

What is deliberately out, and why. A non-goal with a reason is a decision; one without is a gap.

## Capability ground truth

Read from [config/capabilities.yaml](../config/capabilities.yaml) at the commit this was written,
and re-read at implementation time. A requirement that depends on a tool this product does not have
is a requirement to *refuse honestly*, not to build the tool.

## Behaviour

The rules, each one testable. Prefer "given this, that is observable" over "supports X".

## Failure behaviour

Unsupported request · malformed or failed call · missing context · ambiguous context · partial
failure · cancellation race · duplicate execution. Say what the driver gets in each case.

## Observability

What a person must be able to reconstruct from a log, and what must **never** appear in one
([I-8](../docs/INVARIANTS.md): no coordinate, address or transcript).

## Acceptance criteria

Executable wherever feasible, and separated by what each one actually proves. An unchecked box here
is a work item that [discover_work.py](../scripts/discover_work.py) will find, so write them as
rows, mark the state plainly, and put `not built` or `not earned` in the state column when that is
the truth.

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | <the behaviour does what it should> | functional | `<test name>` | not built |
| A2 | <the production path actually reaches it> | production wiring | `<test or log line>` | not built |
| A3 | <it refuses / fails honestly> | negative | `<test name>` | not built |
| A4 | <a change that breaks it fails a test> | regression protection | `<test name>` | not built |
| A5 | <it does not violate an invariant> | architectural | `<architecture test>` | not built |
| A6 | <the APK builds and contains it> | artifact | `:app:assembleDebug` | not built |
| A7 | <registry, debt, issues and state agree> | reconciliation | `harness_check.py` | not built |

Not every SPEC needs all seven. A SPEC that needs none of A2–A7 is probably a documentation change.

## Open product decisions

Anything a reasonable engineer could decide two ways with materially different user-visible results.
**Pick a default, record it here, and continue** — a tunable number is not a reason to stop. Only a
choice that changes what the product *means* is escalated, and then it carries:

```
BLOCKED_BY: <the concrete decision only the owner can make>
```

## Implementation status

Filled in as it is built, so the SPEC never claims more than the evidence. One row per area, with
the state and what proves it.
