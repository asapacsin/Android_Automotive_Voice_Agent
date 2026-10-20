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
- `gate status|check|resolve --id F --evidence "..."|clear` - evaluate the gate; record HIGH-risk
  resolution evidence; clear the session after the work is committed.
- `doctor [--live]` - validate config; with `--live` one tiny call per provider: `LIVE OK`,
  `LIVE AUTH FAILED`, `LIVE UNCONFIGURED`, `LIVE FAILED <code>`; without it `SKIPPED`.
- `install --target DIR [--executor poolside]` - copy into another project (see INSTALL.md).
- `stop-hook` - Claude Code Stop hook entry (reads hook JSON on stdin).

## Risk policy
LOW (docs, formatting-only): deterministic checks only. MEDIUM (any other code): tests + reviewer PASS.
HIGH (auth/security, concurrency, networking, build/architecture, hooks, agent orchestration, routing
and review config): reviewer PASS + a test command + recorded resolution evidence for every actionable
finding raised in the session. Rules are in `defaults.json`; projects add `risk_rules`
(`{"id","level","paths":[globs]}` or `{"id","level","diff_regex"}`) and may set
`"risk_override": {"level", "reason"}`.

## Gate
Fingerprint = sha256 over the diff (vs `HEAD`, or the session base) of the in-scope paths including
untracked files. Review cache key = fingerprint + `policy_version` + reviewer model, so any edit makes a
prior PASS stale. Tests are an independent signal: a PASS review never overrides failing tests.
`gate.scope`: `all` (every working-tree change) or `sessions` (only files changed through `execute`).
The Stop hook blocks at most `gate.max_blocks` times per fingerprint, then allows the stop with an
`UNVERIFIED` message; internal errors also allow the stop as `UNVERIFIED` (never PASS).

## Auth and secrets
`auth: "env"` (default) requires the key env var, else `PROVIDER_UNCONFIGURED`. `auth: "proxy-injected"`
sends without a key header when the env var is unset (for environments whose egress proxy injects keys
per host). Keys are never logged; outbound text and logs are redacted. Never sent: secret-looking files,
gitignored files, binaries/archives, project `never_send` globs.

## Project config keys (`.agent-routing/project.json`)
`executor`, `reviewer`, `fallback`, `providers.<id>.{auth, base_url, price_per_mtok, timeout_s}`,
`never_send`, `risk_rules`, `risk_override`, `tests.{command (argv list), from_session}`,
`gate.{scope, max_blocks}`, `budgets.daily.<id>.{tokens, usd}` (exceeding => `BUDGET_EXCEEDED`),
`http.{timeout_s, max_retries, backoff_base_s, backoff_max_s}`, `limits.*`, `executor_rounds`.
State (usage ledger, audit log, review cache) is written to `.agent-routing/state/` (gitignored).

## Tests
`python3 -m unittest discover -s tools/agent-routing/tests -t tools/agent-routing` (providers mocked with
a local `http.server`; no network).
