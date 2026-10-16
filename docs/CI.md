# CI — what a green check means here

Owner requirement (2026-10-16): **a green required check means the specified verification actually
passed — not that an APK built or a script finished.** Every verdict comes from one tool,
[`scripts/ci_verdict.py`](../scripts/ci_verdict.py), reading machine-readable results against one
manifest, [`config/ci_checks.json`](../config/ci_checks.json).

## Result vocabulary

| Status | Meaning | On a required check |
| --- | --- | --- |
| PASS | the check's assertions held | ok |
| FAIL | an assertion failed | job fails (exit 1) |
| FLAKY | measured instability (e.g. 6/10) | job fails — FLAKY is never a pass |
| SKIPPED | intentionally not run | job fails — a required check may not be skipped |
| BLOCKED | could not run because a documented dependency is missing (a secret, PyYAML, the emulator) | job fails (exit 3), reported as BLOCKED, not FAIL |
| MISSING | no result at all (runner crashed, scenario removed) | job fails |
| QUARANTINED | a measured FAIL/FLAKY covered by a valid quarantine entry | warning only |

A results file that is missing, empty, or has one malformed line (bad JSON, unknown status,
duplicate id) fails the verdict. Optional checks never fail a job; a missing optional check is
reported as SKIPPED "not executed".

### Quarantine

Only in `config/ci_checks.json` `quarantine`, and only with all of: id, suite, reason, owner,
tracking (TEST_MATRIX row or OPEN_PROBLEMS entry), remove_when, expires (at most 30 days ahead). An
expired entry breaks the manifest, so the suite goes red until someone renews it with a reason or
fixes the test. A quarantine covers a measured FAIL/FLAKY only — never a MISSING or BLOCKED check.
A quarantined check that passes prints "quarantine can be lifted".

Safety and global checks can never be quarantined (`voice:quota-guard`, `navpiece:quota-guard`,
`junit:*`, `build:*`, `gate:*`, `ci:*`, `matrix:*`, `gradle:*`), and `--today` is refused outside
the test clock, so a quarantine cannot be stretched past its expiry.

Current entry (2026-10-16, expires 2026-10-30): `cantonese:YUE-HOME` — the expected behaviour of an
ambiguous Cantonese request is undefined until the owner decides YUE-POLICY-001; release stays
blocked through Tier C. `liveinfo:LI-PLACE` (place_details 6/10) is deliberately **not**
quarantined: it is a release-blocking FAIL nobody authorised waiving, so the voice suite stays red
until it is fixed. A quarantined green prints `VERDICT voice PASS (1 quarantined)`.

## Tiers

| Tier | Workflow | Runs | Proves | Does not prove | Cost |
| --- | --- | --- | --- | --- | --- |
| A — mandatory | `Tier A gate` (`unit-tests.yml`): jobs **Tier A gates** and **Tier A build and JVM tests** | every pull request (both jobs); push to `main` (build and tests only — main is gated by the PR checks); manual | the review/delegation gate, CI tool tests, TEST_MATRIX validity, `:app:assembleDebug`, every JVM/architecture/secret-scan test (counts from the JUnit XML, per module) | anything on a device, any voice model | no paid API; ~5 min (gates) and ~14 min cold (build+tests) on a free public-repo runner; caches only Gradle dependency downloads and the SDK, never the build cache (it holds key-bearing outputs) |
| B — emulator | `Device checks` | pull requests touching app source or the script (not fork PRs), `[checks]` commit tag, manual | real app on an API-34 emulator: dispatch rows, route/start/arrival, UI, process death, mic revoked, transcript fade, nav pieces P61/P63 | real cabin acoustics, echo cancellation, voice naturalness, hardware | Amap web search only; ~30 min |
| B — emulator, voice | `Voice checks`, `Nav demo`, `Test worker` | manual or commit tag only (`[voice]`, `[navdemo]`, `[worker:<piece>]`) | scenario suites and nav pieces against the live **Gemini** free tier (debug-only provider override; the quota guard refuses any Qwen session) | as above; Gemini's voice is not Ayano's | Gemini free tier; not deterministic, so never on every commit |
| C — release | `python3 scripts/test_matrix.py --release` ([RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)) | at release checkpoints | which release-blocking rows have current evidence | — | none |

`Drive video` (`emulator.yml`) is a recording workflow, not a gate.

Skipped runs: the emulator workflows listen to pushes but run only when the commit message carries
their tag; GitHub lists the other pushes as "skipped". That is intentional cost control, not a
failure being hidden.

## The review gate on the server

`Tier A gates` runs `scripts/delegation_check.py --ci` on the PR head with full history. It fails on:
module source committed outside an executor merge (including a merge of a non-`exec/` branch made
in another worktree, a fast-forward or a cherry-pick), an executor merge with no review record,
an unresolved record (PENDING, REVISE), or an invalid one (unknown status, short WAIVED reason,
WAIVED on `src/main` after the cutoff). The local Stop hook and the PreToolUse merge hook are early
warnings; the server check is the enforcement.

Conflicts are resolved on the executor branch (merge the base into it, re-review), never inside the
merge commit: each merge is compared with git's own automatic re-merge of its parents, and any
module-source difference is a violation. A violation in first-parent history cannot be waived; the
only way out is a reviewed change of the baseline in `delegation_check.py`.

Acceptance of executor work = reviewer PASS recorded in `state/reviews.log` for the exact branch
tip **and** a green Tier A on the integrated result.

## Repository settings needed to enforce it (owner action)

GitHub → Settings → Branches (or Rules → Rulesets) for the default branch and the integration branch:
require status checks **Tier A gates** and **Tier A build and JVM tests** to pass before merging,
and require branches to be up to date. Do not make **Device checks** required: fork PRs skip it,
and GitHub counts a skipped job as passing. Add CODEOWNERS (or a ruleset) for `scripts/`,
`.github/`, `config/ci_checks.json` and `state/reviews.log`, because a PR can edit the checker
that judges it and the review log is self-attested. None of this is applied by the repository;
check it in the UI.

Secrets used: `AMAP_API_KEY`, `NOVA_VENDOR_ZIP_URL`, `NOVA_VENDOR_ZIP_AUTH` (optional),
`NOVA_DEBUG_KEYSTORE_B64` (Tier A build and all emulator workflows), `AMAP_WEB_KEY`,
`GEMINI_API_KEY` (emulator workflows). Without them the dependent checks are BLOCKED and the job is
red — e.g. a pull request from a fork.
