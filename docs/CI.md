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

### Stand-in vendor builds (STUB_VENDOR)

Decided by the decider 2026-10-19, while the vendor zip URL is dead (TEST_MATRIX `CI-VENDOR-ZIP-001`).
Device checks, Voice checks, Nav demo and Test worker set `NOVA_VENDOR_STUB_OK=yes`, so when the zip
cannot be fetched they build on the compile-only stand-in (`tools/vendor-stub/README.md`) instead of
not building at all. The required check `build:vendor-real` (device, voice and navpiece suites;
`ci_verdict.py vendor`) passes only when the tested APK packages `lib/arm64-v8a/libmsc.so`,
`lib/arm64-v8a/libw_ivw.so` and `assets/ivw/wakeword.jet` and carries no stub mark; otherwise it is
BLOCKED `STUB_VENDOR: <why>`. A stub run's verdict is therefore BLOCKED (red), `verdict.json` says
`"vendor": "STUB"`, the evidence directory ends in `_stubvendor`, `wake:*` rows are recorded BLOCKED
and not run, and `build:*` cannot be quarantined. Every other check still runs and is recorded.

What a stub run may and may not do for TEST_MATRIX rows:

- **May:** verify a change that does not depend on the vendor files (P59 screenshots, nav pieces,
  dispatch rows, process death, mic revoked, transcript fade, voice scenarios through the debug
  Gemini override), cited as "STUB_VENDOR run <id>"; add it to a row's evidence marked "(stub build,
  not product evidence; awaits real-vendor run)"; set a row to FAIL.
- **May not:** set PASS, PARTIAL_PASS or HUMAN_PASS; write `evidence_bind`; justify or lift a
  quarantine; count for Tier C; be cited for any `speech.wake_word` row, any claim about APK
  contents, size or ABI, any L4 claim, a full demo run or a demo video.

Tier A (`unit-tests.yml`, `ci_tier_a.sh`) and the Drive video never build on the stand-in.

### Quarantine

Only in `config/ci_checks.json` `quarantine`, and only with all of: id, suite, reason, owner,
tracking (TEST_MATRIX row or OPEN_PROBLEMS entry), remove_when, expires (at most 30 days ahead). An
expired entry breaks the manifest, so the suite goes red until someone renews it with a reason or
fixes the test. A quarantine covers a measured FAIL/FLAKY only — never a MISSING or BLOCKED check.
A quarantined check that passes prints "quarantine can be lifted".

Safety and global checks can never be quarantined (`voice:quota-guard`, `navpiece:quota-guard`,
`junit:*`, `build:*`, `gate:*`, `ci:*`, `matrix:*`, `gradle:*`), and `--today` is refused outside
the test clock, so a quarantine cannot be stretched past its expiry.

Current entries: none. The `cantonese:YUE-HOME` quarantine was removed 2026-10-19 with the whole cantonese suite (owner 2026-10-19, YUE-POLICY-001 option A, Mandarin-only). `liveinfo:LI-PLACE` (place_details 6/10) is deliberately **not**
quarantined: it is a release-blocking FAIL nobody authorised waiving, so the voice suite stays red
until it is fixed. A quarantined green prints `VERDICT voice PASS (1 quarantined)`.

## Tiers

| Tier | Workflow | Runs | Proves | Does not prove | Cost |
| --- | --- | --- | --- | --- | --- |
| A — mandatory | `Tier A gate` (`unit-tests.yml`): jobs **Tier A gates** and **Tier A build and JVM tests** | every pull request (both jobs); push to `main` (build and tests only — main is gated by the PR checks); manual | the review/delegation gate, CI tool tests, TEST_MATRIX validity, `:app:assembleDebug`, every JVM/architecture/secret-scan test (counts from the JUnit XML, per module) | anything on a device, any voice model | no paid API; ~5 min (gates) and ~14 min cold (build+tests) on a free public-repo runner; caches only Gradle dependency downloads and the SDK, never the build cache (it holds key-bearing outputs) |
| B — emulator | `Device checks` | pull requests touching app source or the script (not fork PRs), `[checks]` commit tag, manual | real app on an API-34 emulator: dispatch rows, route/start/arrival, UI, process death, mic revoked, transcript fade, nav pieces P61/P63 | real cabin acoustics, echo cancellation, voice naturalness, hardware | Amap web search only; four parallel shards, ~20 min wall clock (the drive shard) instead of ~30 min |
| B — emulator, voice | `Voice checks`, `Nav demo`, `Test worker` | manual or commit tag only (`[voice]`, `[navdemo]`, `[worker:<piece>]`) | scenario suites and nav pieces against the live **Gemini** free tier (debug-only provider override; the quota guard refuses any Qwen session) | as above; Gemini's voice is not Ayano's | Gemini free tier; not deterministic, so never on every commit |
| C — release | `python3 scripts/test_matrix.py --release` ([RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md)) | at release checkpoints | which release-blocking rows have current evidence | — | none |

**Device checks shards** (DEV-SPLIT). `device-checks.yml` is a plan job plus a matrix
(`fail-fast: false`) of four shards, the Voice checks pattern: each builds its own APK (never an
artifact: the debug APK is key-bearing), boots its own emulator and runs only its sections of
`scripts/github_device_checks.sh` (`DEVICE_SHARD=<shard>`; unset or `all` runs every section in the
original order, so a local full run is unchanged). Each shard has its own verdict, suite
`device-<shard>`; the shard suites partition suite `device` (kept for full runs), with
`build:vendor-real` required in every shard (recorded by the shared setup). A shard that skips the
dispatch section installs the Amap web key itself. Evidence goes to `ci/device-evidence` under
`runs/run_<date>_<sha7>_r<run id>_a<attempt>[_stubvendor]/<shard>/` (fetch-rebase-retry push). A `[checks]` push
and a pull request run every shard; a dispatch takes the input `shards` (comma list, default `all`).

| Shard | Suite | Sections | Tests (approx.) |
|---|---|---|---|
| dispatch | device-dispatch | dispatch rows (app-up, PLACE-UNSET/SAVE, CALL-NOSIM, APPS), P53 notes, LIVE-INFO-SDK | ~3 min |
| drive | device-drive | simulated drive to arrival, NAV-ROUTE/START, NAV-UI-002..007, arrival, crash | ~7 min |
| lifecycle | device-lifecycle | P59, process death, revoked microphone, transcript bubble probe | ~4 min |
| navpiece | device-navpiece | nav demo pieces P61/P63 | ~2 min |

Each shard adds the APK build and emulator boot (~12 min).

**Voice checks shards** (VC-SPLIT, owner 2026-10-19: each demo <=5 min, function tests in small units).
`voice-checks.yml` is a matrix (`fail-fast: false`) of nine shards; each builds its own APK (never
uploaded as an artifact: the debug APK is key-bearing), boots its own emulator and runs only its
tests (`scripts/github_voice_checks.sh <apk> <shard>`). Each shard has its own verdict, suite
`voice-<shard>` in `config/ci_checks.json`, listing only that shard's ids (a check of the shard with
no result is MISSING = FAIL); `build:vendor-real` and `voice:quota-guard` are required in every shard
(each builds and opens Gemini sessions). Together the shards cover suite `voice` (the full-run union,
used by `<shard>` = `all` locally).

| Shard | Suite | Checks |
|---|---|---|
| voice-a | voice-a | `voice:runner`, `voice:S1`..`S7` (incl. S4b, S5b) |
| voice-b | voice-b | `voice:runner`, `voice:S8`..`S14` |
| voice-c | voice-c | `voice:runner`, `voice:S15`..`S21` |
| device | voice-device | `devrows:runner`, every `scenarios-device.json` row |
| li-weather | voice-li-weather | `liveinfo:runner`, `LI-WEATHER` (x10) |
| li-place | voice-li-place | `liveinfo:runner`, `LI-PLACE` (x10) |
| li-traffic | voice-li-traffic | `liveinfo:runner`, `LI-TRAFFIC` (x10) |
| li-along | voice-li-along | `liveinfo:runner`, `LI-ALONG` (x10) |
| demo | voice-demo | optional `voice:demo-recording`, `wake:WAKE-SYNTH-001` |

A `[voice]` push runs every shard; a dispatch takes the input `shards` (comma list, default `all`) to
rerun one shard alone (duplicate names are dropped).

**Live-info exception to the 5-minute target** (planner 2026-10-20): LIVE-INFO-DEVICE-001 is judged
as >=9/10 per kind, so each live-info shard runs one kind with `--repeat 10` (~8 min of tests). A
10-repeat rate cannot be split across shards and still be judged per shard.

**Gemini sessions per full run.** Counting basis: a *new* session is opened by (1) the script's
quota-guard `voice start` (once per shard); (2) `run_scenarios.py` `start_session()` at the start of
every runner pass (the pass force-stops the app first), plus once per `fresh` scenario (S16, S17
force-stop the app); the per-scenario `start_session()` otherwise resumes or no-ops on the live
session and is not counted; (3) the wake check (one); (4) the demo bridge (one, after a force-stop).

| | guard | S rows | device rows | live info | wake | demo bridge | total |
|---|---|---|---|---|---|---|---|
| before (one job) | 1 | 1 pass + 2 fresh = 3 | 1 | 10 passes = 10 | 1 | 1 | **17** |
| shards | 9 (one per shard) | a 1 + b 1 + c (1 + 2 fresh) = 5 | 1 | 4 kinds x 10 passes = 40 | 1 | 1 | **57** |

The increase (+40) is only per-shard guard sessions (+8) and runner pass starts (+2 for the S rows,
+30 for live info, which used to share one session per pass across the four kinds). Model turns
(one per scenario per pass) are unchanged. Up to 9 sessions can be open at once.
Evidence goes to `ci/voice-evidence` under `runs/run_<run id>_a<attempt>_<sha>/<shard>/`; shards push
concurrently with a fetch-rebase-retry loop.

**Concurrency limit (2026-10-20).** All 9 shards at once on the free Gemini key hit
`GEMINI_LIVE_QUOTA_EXCEEDED` (run 39351864713, voice-c S17; 3 codes, an infrastructure failure, not the
app). Dispatch at most the 3 voice shards (`voice-a,voice-b,voice-c`) together, then the rest.

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

**Applied 2026-10-17 (owner, in the web UI; agent sessions cannot write repository settings - the
GitHub proxy refuses it):** ruleset `CI gate` (id 25288309), Active, targets the default branch
(`main`) and `claude/10-13`; rules: restrict deletions, block force pushes, require status checks
`Tier A gates` and `Tier A build and JVM tests` (GitHub Actions). Verified read-only through
`gh api repos/<owner>/<repo>/rules/branches/<branch>`. CODEOWNERS is not set yet.
Retarget the ruleset when the PR base branch changes.

Secrets used: `AMAP_API_KEY`, `NOVA_VENDOR_ZIP_URL`, `NOVA_VENDOR_ZIP_AUTH` (optional),
`NOVA_DEBUG_KEYSTORE_B64` (Tier A build and all emulator workflows), `AMAP_WEB_KEY`,
`GEMINI_API_KEY` (emulator workflows). Without them the dependent checks are BLOCKED and the job is
red — e.g. a pull request from a fork.
