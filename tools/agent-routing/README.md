# agent-routing

Portable, standard-library-only (Python >= 3.10) routing of coding work to one external **executor**
model and one **reviewer** model, with a deterministic risk-based review gate enforced by a Claude Code
Stop hook. Shared tool code lives here; each project keeps its own config in `<project>/.agent-routing/`.

| provider | API | model | role |
|---|---|---|---|
| `gemini` | Gemini REST `generateContent` (`x-goog-api-key` from `GEMINI_API_KEY`) | `gemini-3.6-flash` | executor |
| `poolside` | OpenRouter `https://openrouter.ai/api/v1` (Bearer `OPENROUTER_API_KEY`) | `poolside/laguna-s-2.1:free` (available until 2026-10-31, then `PROVIDER_EXPIRED`) | executor |
| `nvidia` | NVIDIA NIM `https://integrate.api.nvidia.com/v1` (Bearer `NVIDIA_API_KEY`) | `nvidia/nemotron-3-ultra-550b-a55b` | reviewer |

One executor per project (`"executor": "gemini" | "poolside"`), reviewer `nvidia`. Models cannot be
overridden. No automatic switching: `fallback` defaults to null; a configured fallback is used only with
`--use-fallback` and is logged.

## Commands (`python3 tools/agent-routing/agent_routing_cli.py <cmd>` or `python -m agent_routing`)
- `route` - resolved executor/reviewer.
- `execute --task "..." --files a.py b.py [--context c.py] [--tests "pytest -q"] [--findings f.json]` -
  sends the task and the listed files, validates the JSON change proposal strictly, applies it only to the
  listed files, runs the tests, re-asks with failing output (bounded `executor_rounds`). Status
  `APPLIED` only when tests pass; otherwise `REJECTED` / `FAILED`.
- `review [--summary "..."]` - sends the actual diff (redacted, size-capped) + risk to the reviewer and
  caches a validated PASS/FAIL by change fingerprint. Any provider error or malformed reply is `ERROR`.
- `gate status|check|resolve --id F --evidence "..."|clear [--force --reason "..."]` - evaluate the gate;
  record HIGH-risk resolution evidence; `clear` works only when the gate is PASS/CLEAN, otherwise it needs
  `--force --reason` (audited; the hook then reports that change as UNVERIFIED).
- `doctor [--live]` - validate config; with `--live` one tiny call per provider: `LIVE OK`,
  `LIVE AUTH FAILED`, `LIVE UNCONFIGURED`, `LIVE FAILED <code>`; without it `SKIPPED`.
- `install --target DIR [--executor poolside]` - copy into another project (see INSTALL.md).
- `stop-hook` - Claude Code Stop hook entry (reads hook JSON on stdin; project root = `$CLAUDE_PROJECT_DIR`,
  else the git toplevel of the hook cwd). Hook command:
  `python3 "$CLAUDE_PROJECT_DIR/tools/agent-routing/agent_routing_cli.py" stop-hook`, timeout 600 s;
  `limits.test_timeout_s` (default 480) must stay below 540 so a slow test run ends as UNVERIFIED, not a kill.

## Risk policy
LOW (docs, formatting-only): deterministic checks only. MEDIUM (any other code): tests + reviewer PASS.
HIGH (auth/security, concurrency, networking, build/architecture, hooks, agent orchestration, routing
and review config): reviewer PASS + a test command + recorded resolution evidence for every actionable
finding raised in the session. Rules are in `defaults.json`; projects add `risk_rules`
(`{"id","level","paths":[globs],"path_words":[words]}`; words match at /_-. and camelCase
boundaries, so `Preview` is not `review` or `{"id","level","diff_regex"}`) and may set
`"risk_override": {"level", "reason"}`.

## Gate
Fingerprint = sha256 over the diff of the in-scope paths including untracked files, against the gate base
(scope `all`: HEAD at the first non-clean evaluation, advanced only after PASS/CLEAN or an authorised clear,
so committing an unreviewed change does not clear the gate; scope `sessions`: the session base). Build
artifacts (`**/__pycache__/**`, `*.pyc`, `*.pyo`, `.agent-routing/state/**`) and the project `ignore` globs
are excluded; tests run with `PYTHONDONTWRITEBYTECODE=1`. Paths that may not be sent (secrets, never_send,
binaries) enter the fingerprint only as a sha256 and block with "path cannot be sent for review". A diff
larger than `limits.max_diff_bytes` is an ERROR ("split it"), never a PASS. Review cache key = fingerprint + `policy_version` + reviewer model, so any edit makes a
prior PASS stale. Tests are an independent signal: a PASS review never overrides failing tests.
`gate.scope`: `all` (every working-tree change) or `sessions` (only files changed through `execute`).
The Stop hook blocks at most `gate.max_blocks` times per fingerprint and per session while
`stop_hook_active` is true, then allows the stop with an
`UNVERIFIED` message; internal errors also allow the stop as `UNVERIFIED` (never PASS).

## Auth and secrets
`auth: "env"` (default) requires the key env var, else `PROVIDER_UNCONFIGURED`. `auth: "proxy-injected"`
sends without a key header when the env var is unset (for environments whose egress proxy injects keys
per host). Keys are never logged; outbound text and logs are redacted. Never sent: secret-looking files,
gitignored files, binaries/archives, project `never_send` globs.

## Project config keys (`.agent-routing/project.json`)
`executor`, `reviewer`, `fallback`, `providers.<id>.{auth, base_url, price_per_mtok, timeout_s}`,
`never_send`, `ignore`, `risk_rules`, `risk_override`, `tests.{command (argv list), from_session}`,
`gate.{scope, max_blocks}`, `budgets.daily.<id>.{tokens, usd}` (exceeding => `BUDGET_EXCEEDED`),
`http.{timeout_s, max_retries, backoff_base_s, backoff_max_s}`, `limits.*`, `executor_rounds`.
State (usage ledger, audit log, review cache) is written to `.agent-routing/state/` (gitignored).

## Tests
`python3 -m unittest discover -s tools/agent-routing/tests -t tools/agent-routing` (providers mocked with
a local `http.server`; no network).
