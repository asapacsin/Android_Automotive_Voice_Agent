# SPEC-016 — One voice provider by default, and car functions as domain servers

Status: **Draft 2026-09-30**
Raised: 2026-09-30 · Source: owner decisions recorded in [ADR-013](../DECISIONS/ADR-013-gemini-default-provider.md)
(Gemini default) and [ADR-015](../DECISIONS/ADR-015-car-domain-servers.md) (domain servers, option A)
Depends on: I-1, I-10, I-11, I-13 ([INVARIANTS.md](../docs/INVARIANTS.md)); ADR-009 (provider-neutral
contract); ADR-010 (session-scoped provider choice); SPEC-013 (Gemini adapter)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

Two platform changes that every later feature (SPEC-015, SPEC-017, SPEC-018) builds on. First, the
driver hears one voice: Gemini Live is the provider every session uses unless the owner
deliberately picks Baidu, and nothing ever swaps providers behind the driver's back. Second, adding a
car function stops meaning "edit the one big tool list and the one big dispatcher": each car domain
owns its tools, their argument rules and their execution, and one registry assembles them.

## Scope

**Part A — Gemini default (ADR-013 step 1).**

- A stored provider preference, `gemini` (default) or `baidu`. A fresh install and every existing
  install resolve to `gemini`; the old opt-in "use Gemini" flag no longer decides anything.
- A session whose preferred provider cannot run (no key, consent not accepted, invalid settings)
  does **not** open on the other provider. `start` returns `ConfigInvalid(code)` and the screen
  shows one honest sentence with the setup path.
- Baidu runs only when the preference says `baidu`.

**Part B — domain servers (ADR-015).**

- `ToolDomain` (pure): `id`, `specs()`, `validate(name, args)`. One object per domain: navigation
  (`navigate_to`, `choose_navigation_option`, `exit_navigation_mode`, `save_place`), apps
  (`open_app`), media (`control_music`), climate (`control_climate`), vision
  (`describe_camera_view`), phone (`place_call`), live_info (`query_live_info`), speech
  (`end_conversation`, `set_speech_output`).
- `ToolSpec` gains `repeatSensitive: Boolean`, declared by the owning domain. It replaces
  `ToolCallGuards.REPEAT_SENSITIVE` (deleted in the same change). This matches MCP's tool
  annotations: the tool's owner declares how it may be retried.
- `ToolRegistry` (pure): the ordered domain list; `tools()`, `validate(name, args)`,
  `domainOf(name)`. A name declared by two domains fails construction.
- `RealtimeToolCatalog.tools()` / `validate()` delegate to the registry. Adapters are unchanged.
- `ToolServer` (runtime): a domain bound to its executors; `call(call, env) -> ToolDispatchResult`.
  `AndroidToolDispatcher` keeps what is cross-cutting — telemetry, the guard chain, the validation
  error path, `UNKNOWN_TOOL` — and routes everything else to the owning server. Its tool-name `when`
  is deleted.

## Non-goals

- The wire MCP protocol (ADR-015 option B). The interface is shaped so it can be added later.
- Deleting Baidu (ADR-013 step 2): waits for `GEMINI-DEVICE-REACH-001` and `GEMINI-DEVICE-DUPLEX-001`.
- Any change to what a tool does, its description text, or its result JSON. Part B is a pure
  restructure; behaviour changes arrive in SPEC-015/017/018 as new or extended domains.
- Changing `CapabilityCatalog`: it stays the capability truth; a record's `tool` still names a tool.

## Capability ground truth

`config/capabilities.yaml` at `a4e57a1`: no capability changes in this SPEC.

## Behaviour

- **B1.** `VoiceProviderChoice.resolve` returns Gemini unless the stored preference is `baidu`.
- **B2.** With preference `gemini` and a missing key or consent, `VoiceSessionGateway.start` returns
  `ConfigInvalid("GEMINI_API_KEY_MISSING" | "GEMINI_CONSENT_MISSING")`; no Baidu session is opened.
- **B3.** `RealtimeToolCatalog.tools()` returns the same set of tools, each with byte-identical
  JSON (name, description, parameters), as at `a4e57a1`. Order may change (grouped by domain).
- **B4.** `RealtimeToolCatalog.validate(name, args)` returns the same code as at `a4e57a1` for every
  case in the existing tests and in a new corpus test covering each tool's valid, missing, extra
  and wrong-type arguments.
- **B5.** Every existing `AndroidToolDispatcher` test passes unchanged: same outputs, chips,
  blocked reasons, deferred behaviour and guard order.
- **B6.** The repeat guard applies to exactly the tools that `REPEAT_SENSITIVE` listed.

## Failure behaviour

- Unknown tool name → `UNKNOWN_TOOL`, unchanged.
- Two domains declaring one name → registry construction throws; a unit test proves it.
- Gemini unusable under preference `gemini` → no session and one sentence on screen; never a silent
  Baidu session (that is the second voice ADR-013 forbids).

## Observability

`session_provider choice=<gemini_live|baidu_flex|unavailable> reason=<code>` — codes only. Tool
routing logs keep today's lines; no arguments are logged (I-8).

## Acceptance criteria

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Gemini is resolved by default; Baidu only by explicit preference | functional | `GeminiSettingsTest` (resolve table) | not built |
| A2 | No silent fallback: missing key/consent → `ConfigInvalid`, no Baidu session | negative | `VoiceSessionGatewayTest` / `GeminiSettingsTest` | not built |
| A3 | Tool declarations identical as a set, byte for byte per tool | regression protection | `ToolRegistryGoldenTest` against a golden captured at `a4e57a1` | not built |
| A4 | Validation codes identical over the corpus | regression protection | `ToolRegistryValidationCorpusTest` | not built |
| A5 | Dispatcher routes through servers; old `when` and `REPEAT_SENSITIVE` deleted | architectural | existing `AndroidToolDispatcher*` tests green; `ArchitectureRulesTest` rule: no tool-name literal branches in `AndroidToolDispatcher` | not built |
| A6 | Duplicate tool name across domains is rejected | negative | `ToolRegistryTest` | not built |
| A7 | APK builds | artifact | `:app:assembleDebug` | not built |
| A8 | A session opens on the emulator/phone with the default settings plus a key | production wiring (L5) | `GEMINI-DEVICE-REACH-001` | not earned |

## Open product decisions

None. The owner decided the provider (ADR-013) and the shape (ADR-015); the rest is engineering.

## Implementation status

| Area | State | Proof |
| --- | --- | --- |
| Part A | not built | — |
| Part B declarations/validation | not built | — |
| Part B runtime routing | not built | — |
