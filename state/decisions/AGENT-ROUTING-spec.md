# Agent routing - implementation spec (owner request 2026-10-20)

Portable, stdlib-only (Python >= 3.10, no third-party deps) multi-provider executor/reviewer routing with a
deterministic risk-based review gate enforced by a Claude Code Stop hook. Lives in `tools/agent-routing/`
(shared infrastructure, copyable to any repository); per-project config in `<project>/.agent-routing/`.

## Layout (separate concerns)
- `tools/agent-routing/agent_routing/` - package:
  - `config.py` - load `defaults.json` (shipped) + `<project>/.agent-routing/project.json`; validate.
  - `providers/` - adapters: `gemini.py` (official Gemini REST generateContent, `x-goog-api-key` header from
    GEMINI_API_KEY), `openai_compat.py` (OpenRouter `https://openrouter.ai/api/v1`, NVIDIA NIM
    `https://integrate.api.nvidia.com/v1`; Bearer from OPENROUTER_API_KEY / NVIDIA_API_KEY). Common base:
    timeout, bounded retries with exponential backoff + jitter, 429/5xx handling honouring Retry-After,
    token accounting from the response usage, per-call cost from a configurable price table, usage log.
    When an env key is unset the adapter still sends the request without the header if
    `auth: proxy-injected` is configured for that provider (this cloud environment injects keys per host at
    the network proxy); otherwise it fails with PROVIDER_UNCONFIGURED. Keys are never logged.
  - `risk.py` - deterministic LOW/MEDIUM/HIGH from the changed paths + diff content using default rules plus
    project rules and an explicit override; returns the rule ids that fired.
  - `executor.py` - executor adapter: inputs = task text + explicit file list (+ optional read-only context
    files); builds context (file contents, size-capped), sends to the routed executor asking for a JSON
    change proposal `{"summary": str, "edits": [{"path", "search", "replace"} | {"path", "content"}]}`;
    validates strictly; applies only to allowed files (refuse paths outside the list, absolute or `..`,
    gitignored, binaries/archives, secret-looking files, and project `never_send` globs - e.g. licensed vendor
    dirs); runs the project's test command; returns a structured status JSON (APPLIED/REJECTED/FAILED, files,
    tests, provider, model, tokens, cost, errors). Bounded remediation rounds (re-ask with failing output or
    reviewer findings). Applying a patch is not called success unless the tests pass.
  - `review.py` - reviewer: sends the ACTUAL diff (git diff of the change vs base, size-capped, redacted) +
    risk + task summary; requires the review contract JSON (below); strict validation - malformed,
    unstructured or incomplete responses are rejected (verdict ERROR, never PASS).
  - `gate.py` - lifecycle: change fingerprint (sha256 over the diff of relevant paths incl. untracked), risk,
    required tests, review requirement; review cache keyed by fingerprint + policy version + reviewer model;
    any change in the fingerprint invalidates a prior PASS; audit log JSONL of every decision.
  - `stop_hook.py` - Claude Code Stop hook entry: reads the hook JSON on stdin; if the gate requires work
    (tests failing, review missing/FAIL/ERROR/stale) prints `{"decision": "block", "reason": ...}`; honours
    `stop_hook_active` and a per-fingerprint block counter (`max_blocks`, default 3): after the limit it
    allows the stop but records and prints status UNVERIFIED (never PASS). Fail-safe: an internal exception
    allows the stop with an UNVERIFIED system message (never a silent approval, never an infinite loop).
  - `redact.py` - secret redaction for logs and outbound text (API key patterns, Bearer tokens, env values of
    configured key names, PEM blocks).
  - `budget.py` - per-provider usage/cost ledger (JSONL) and configurable daily budgets (tokens and USD);
    a call that would exceed the budget is refused with BUDGET_EXCEEDED.
  - `cli.py` - `python -m agent_routing <cmd>`: `route` (print resolved executor/reviewer for the project),
    `execute`, `review`, `gate` (status/check), `doctor` (config validation + live connectivity per
    provider, clearly labelled LIVE vs SKIPPED), `install` (copy into another project).
- `tools/agent-routing/defaults.json` - provider catalogue and default risk rules.
- `tools/agent-routing/templates/project.json` - per-project template.
- `tools/agent-routing/README.md` + `INSTALL.md` - setup, usage, copying into another repo, adding the hook.
- `tools/agent-routing/tests/` - unittest suite; providers mocked with a local `http.server` (no network).

## Model assignments (exact)
| provider id | API | model id | role |
| gemini | Gemini API | gemini-3.6-flash | executor |
| poolside | OpenRouter `https://openrouter.ai/api/v1` | poolside/laguna-s-2.1:free | executor |
| nvidia | NVIDIA NIM `https://integrate.api.nvidia.com/v1` | nvidia/nemotron-3-ultra-550b-a55b | reviewer |
Exactly one model per provider-role. Exactly one active executor per project (`executor: "gemini" | "poolside"`);
reviewer `nvidia`. Projects are independent files. No automatic switching: `fallback` policy defaults to
`{"executor": null, "reviewer": null}`; a configured fallback must name a provider explicitly and every use is
logged. Poolside free endpoint: `available_until: "2026-10-31"`; after that date routing to it fails with
PROVIDER_EXPIRED (never silently switching to the paid `poolside/laguna-s-2.1`).

## Review policy
LOW (docs, formatting, trivial isolated): deterministic checks only, no LLM review.
MEDIUM (app logic, multiple related files, nontrivial fixes): tests + Nemotron review; findings addressed.
HIGH (auth/security, concurrency, architecture, networking, agent orchestration, hooks, review gates, routing
config, critical behaviour): Nemotron review + regression tests + recorded evidence each finding was resolved.
Project rules (path globs / regexes -> level) and an explicit `risk_override` with a reason are allowed.

## Review contract (reviewer JSON)
`{"verdict": "PASS"|"FAIL", "risk": "LOW"|"MEDIUM"|"HIGH", "findings": [{"id", "severity":
"critical"|"major"|"minor"|"info", "file", "line" (int|null), "evidence", "recommendation", "actionable": bool}],
"confidence": 0..1, "model": str, "provider": str}`. The adapter overwrites/validates model and provider
from what it actually called. Missing fields, wrong types, FAIL with no findings, PASS with an actionable
critical/major finding, or non-JSON -> REJECTED (gate treats as not passed). Only actionable findings with
evidence trigger remediation. Tests stay an independent signal: a PASS review never overrides failing tests.

## Validation (tests A-L, mocked; plus live smoke where credentials work)
A Gemini route -> gemini-3.6-flash; B Poolside route -> poolside/laguna-s-2.1:free; C reviewer -> nemotron;
D two projects independent; E MEDIUM/HIGH change triggers review; F FAIL blocks; G fix + PASS permits;
H code change invalidates PASS; I unavailable reviewer (connection refused, 401, 5xx after retries,
malformed JSON) never yields PASS; J Stop hook bounded (stop_hook_active + max_blocks); K unrelated configs
unchanged (checksums of given files before/after an install); L fresh temp git repo adopts via INSTALL steps
(`install` command) and its gate works. Live smoke (`doctor --live`) labelled separately.

## Security
Never send: secrets (.env*, *.pem, *.key, keystore, credentials files), gitignored files, binaries, or
project `never_send` globs. Redact before any outbound request and in every log. Never print keys.

## Final report (2026-10-21)

**Configured here:** executor `cohere/north-mini-code:free`, reviewer `nvidia/nemotron-3-ultra-550b-a55b:free`
(both OpenRouter, proxy-injected key), no fallback. Gemini no longer writes code; Poolside dropped (owner).
Claude subagents: executor/reviewer/planner Sonnet 5.5, decider Opus, HIGH-risk review Opus.

**Mocked (59 -> 64 unit tests, `python3 -m unittest discover -s tests -t .` in `tools/agent-routing`):** routing,
adapters, risk classification, gate fingerprint/stale detection, Stop-hook bounded retries, redaction, install.

**Live (real APIs, 2026-10-21):**
- Cohere executor applied a bounded edit with passing tests: 4/4 trials (about 5 s); the `risk.py` fix job (70 s);
  the end-to-end demo below.
- Nemotron (via OpenRouter) returned structured reviews: PASS on 3 small changes, FAIL with findings on the whole
  package (one major finding fixed: indentation-only Python edits are no longer "formatting-only").
- End-to-end demo (scratch repo): execute -> Stop hook blocked (review missing) -> review PASS -> Stop hook allowed
  -> edit after approval -> Stop hook blocked again (review stale).

**Failures found and fixed:** review output cap 4096 truncated a reasoning reviewer (now config 8192); the executor
asked for whole files and was cut off at 8192 tokens (now small search/replace edits, oversize-rewrite guard,
`TRUNCATED` detection with one guided retry, redacted raw reply kept in `.agent-routing/state/last_reply.txt`).

**Not verified / blockers:** direct NVIDIA NIM (`nvidia`) returns 401 (no key); the OpenRouter copy of the same
model is used instead. Nemotron reported its identity as "gpt-4o" in one review (recorded in `reported_identity`);
treat a free-tier PASS as advisory. Executor was only exercised on small single-file edits. Gemma/xingchen
candidates failed (rate limit / bad response); MiniMax M3.1 Flash applied a test edit 4/4 but is not selected.
