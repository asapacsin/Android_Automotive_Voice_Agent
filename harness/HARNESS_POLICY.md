# Harness update policy

The harness may evolve. It may not drift.

## May be refreshed automatically, no approval

Anything derived from evidence: build status, test counts, git state, capability *observations*,
open issues, tech-debt status, blockers, generated indexes and metrics. These are outputs of
`scripts/collect_state.py` and `scripts/skill_metrics.py`; refreshing them is not a decision.

## Requires a written proposal

Anything that changes what "correct" means:

- constitution rules or architectural invariants;
- capability **semantics** — what a capability means, or its verification level;
- verification standards, release criteria, or what counts as evidence;
- safety rules and permissions;
- the source-of-truth hierarchy or harness topology;
- automatic enforcement behaviour — what the architecture tests assert.

Write `harness/proposals/HARNESS_PROPOSAL_<id>.md` containing: the observed repeated failure · the
evidence · root cause · proposed change · expected benefit · added complexity · token and latency
cost · risk · how to test it · how to roll it back.

Promote a proposal only when it fixes a demonstrated problem. Sophistication is not a reason.

## Adding a realtime provider

The rules, so the next integration does not have to rediscover them
([ADR-009](../DECISIONS/ADR-009-provider-neutral-realtime-contract.md),
[SPEC-007](../SPECS/SPEC-007-provider-neutral-realtime.md)):

1. A new provider is an implementation of `RealtimeVoiceProvider`. Not a fork of the voice path.
2. Its protocol stays inside its adapter. Vendor event names, field names and JSON never travel
   further; `ProviderBoundaryTest` fails the build if they do.
3. Application logic must not gain a provider-name branch. Differences are `capabilities.<feature>`,
   normalized events, normalized errors, or adapter configuration. If a difference genuinely cannot
   be expressed that way, record it in ADR-009 — do not work around it locally.
4. It must pass `RealtimeProviderContractTest`, which every provider runs against. Add it to that
   test's provider list; whatever fails is the work.
5. Existing providers keep passing their own tests. Baidu is the behavioural baseline and does not
   change to accommodate a newcomer.
6. Credentials are namespaced per provider in `AndroidKeystoreCredentialStore`, so one provider's
   credentials cannot overwrite another's, and [I-7](../docs/INVARIANTS.md) still holds.
7. Do not expose a provider in any UI until it has a usable implementation.

A provider that cannot be authenticated yet is **not** a reason to stage a dormant adapter —
[ADR-008](../DECISIONS/ADR-008-single-active-realtime-provider.md). Record it as blocked on the
credential and do the independent work instead.

## Deleting harness components

Ask the maintenance question: **does removing this make outcomes measurably worse?** If not, delete
it. A component that has never caught anything is cost without benefit.

## Self-evaluation

The target is *maximum verified engineering output for minimum harness complexity and human
attention* — not maximum harness. Signals worth watching, all cheap:

- how often a human had to repeat an instruction (`human_reminders` in the skill events);
- how often a completed task later turned out to be wrong (`task_outcome` vs a later FAIL);
- whether the same manual workflow keeps reappearing (`manual_repeated_work`);
- stale-state incidents — the state file disagreeing with the repository.

`scripts/skill_metrics.py` summarises these. If a component shows no signal over many tasks,
[delete it](#deleting-harness-components).
