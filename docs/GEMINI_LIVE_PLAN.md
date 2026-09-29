# Gemini Live provider — implementation plan

Status: **Authorised 2026-09-29 by the product owner** ("implement the plan from this architecture").
Architecture: [GEMINI_LIVE_ARCHITECTURE.md](GEMINI_LIVE_ARCHITECTURE.md) (cited `G§n`). Decision:
[ADR-010](../DECISIONS/ADR-010-gemini-live-second-provider.md). Behaviour and acceptance:
[SPEC-013](../SPECS/SPEC-013-gemini-live-provider.md). This plan replaces the phase order of
[LIVE_CONVERSATION_PLAN.md](LIVE_CONVERSATION_PLAN.md): the Gemini adapter is the first build
phase, and that plan's Phase 8 (a GPT-Live adapter) is dropped.

`BASE_COMMIT` for wave 1 is the `claude/9-29` HEAD at the time the wave starts (re-read then).

---

## 1. Decisions this plan builds on

The owner authorised the build without answering G-1 … G-6 one by one. ADR-010 records the
architecture's recommended or most conservative option for each, so nothing is guessed silently
and each can be changed later by editing one setting or one ADR line:

| Gate | Taken as | Consequence for the build |
| --- | --- | --- |
| G-1 role | (a) **second provider**; Baidu Flex stays the default | provider chosen at session start in the composition boundary; Baidu code untouched in behaviour |
| G-2 reachability | no relay (ADR-001 unchanged); reachability is **measured on the phone** (G-M1) | a Gemini session that cannot connect fails with an honest error, like a Baidu one; no automatic fallback yet |
| G-3 billing | no search grounding; no `kind=search` | `googleSearch` is never declared |
| G-4 fast path | **not** widened; everything stays model-routed | the 5–11 s actuating latency (F6) is a known, measured limitation, recorded in SPEC-013 |
| G-5 voice | `Kore` default (accepted in the probe), editable in developer settings | persona voice differs from Baidu 4196 |
| G-6 privacy | **opt-in**: Gemini is used only after the owner turns it on in developer settings and accepts the cross-border notice | default behaviour of an installed APK is unchanged |

Delegation (`delegate_task` with `NON_BLOCKING`, G§4.3) is **not** in this plan's first phase: it
needs the SPEC-012 `DEFERRED_ANSWER` row (D-F) and the WorkCoordinator deadline/TTL work (D-E),
which are owner decisions of their own. §5 keeps it as the next phase.

## 2. Measured facts added since the architecture (probe, 2026-09-29)

| # | Fact |
| --- | --- |
| F17 | `functionDeclarations[].parameters` rejects JSON-Schema keywords (`additionalProperties`); `parametersJsonSchema` accepts the app's schemas **unchanged** (`additionalProperties`, `minLength`, `enum`, `number`) |
| F18 | An explicit `behavior: "BLOCKING"` is rejected ("BLOCKING function calls are not supported for this model"); blocking is the default when `behavior` is omitted |

So one neutral JSON-Schema tool catalogue can feed both adapters without translation.

## 3. Dependency graph

```
 G1.1 contract (planner) ──┬────────────────────────────────┐
                           │                                 │
   ┌───────────────────────┼──────────────┬─────────────────┐│
   ▼                       ▼              ▼                 ▼▼
 G1.2 tool catalogue   G1.3 turn pipeline  G1.4 settings, key, consent
 (BaiduFlexProtocol)   (BaiduFlexClient)   (new files, Keystore, SecretScan)
   └──────────┬────────────┘                     │
              ▼                                  │
        G2.1 Gemini adapter (protocol + client + provider)
              └──────────────────┬───────────────┘
                                 ▼
        G3.1 composition: selection, local speech activity, 24 kHz, boundary rules
                                 ▼
        G4 verification: full suite + APK, review, docs/registry, cloud probes
                                 ▼
        device (owner's PC): G-M1 reachability, G-M2 self-interruption
```

Why this order:
- **G1.1 first, by the planner**: every other node reads the new provider id, capability flag and
  event types; two workers guessing a shared interface is forbidden (planner §4).
- **G1.2 and G1.3 are extractions, not new mechanisms** (AGENTS.md rule 2). The tool list and its
  validation live in `BaiduFlexProtocol`; the per-turn claim gate (`DriverTurn` wiring,
  `ActionClaimGuard` follow-up, superseded-output drop) lives in `BaiduFlexClient`. A second adapter
  must reuse them, not copy them. They touch different files, so they run in parallel. G1.3 also
  pays down D-8 (`BaiduFlexClient` over its line budget).
- **G1.4 touches only new files plus `SecretScanTest`**, so it runs alongside.
- **G2.1 is one executor**: protocol, client and provider are one adapter and change together.
- **G3.1 is sequential**: it edits `VoiceSessionController` (app and core), a core abstraction.

## 4. Tasks

Each row is one executor packet in its own worktree `../nova-wt/<ID>` from the wave's base, with
its own `NOVA_BUILD_DIR`. **R** = independent review before merge.

### Wave 1

| ID | Objective | Owned scope | Who | Acceptance |
| --- | --- | --- | --- | --- |
| G1.1 | `VoiceProviderId.GEMINI_LIVE` + catalogue model `gemini-3.8-live-extended-thinking`; `ProviderCapabilities.serverSpeechActivityEvents` (default true) and `nativeToolCallCancellation`; `DomainVoiceEvent.ToolCallCancelled(ids)` and `ProviderWorkState(pending)`; `RealtimeVoiceProvider.onLocalSpeechActivity(active)` (default no-op); core `VoiceSessionController.onLocalSpeechActivity(active)` that, only when the flag is false, feeds the same path as `SpeechStarted`/`SpeechStopped` | `ingress/.../VoiceCatalog.kt`, `DomainVoiceEvent.kt`, `RealtimeVoiceProvider.kt`, `VoiceSessionController.kt` (core), ingress tests | planner | `:ingress:test` green; new tests for the flag both ways |
| G1.2 | Move the tool declarations (name, description, JSON Schema) and argument validation out of `BaiduFlexProtocol`/`FlexFunctionCallAssembler` into a neutral `RealtimeToolCatalog`; Baidu wraps them exactly as before | new `app/.../voice/RealtimeToolCatalog.kt`, `BaiduFlexProtocol.kt` | executor | Baidu `session.update` JSON **byte-identical** before/after (characterisation test written first); `BaiduFlexProtocolTest`, `:behavior-test:test` green |
| G1.3 | Move the per-turn claim gate out of `BaiduFlexClient` into a neutral `DriverTurnPipeline` (begin turn, response started, user transcript, assistant text, tool call, execution result, response done → verdict, hold/emit, superseded drop, action-claim follow-up); the Baidu client delegates | new `app/.../voice/DriverTurnPipeline.kt`, `BaiduFlexClient.kt` | executor | every existing `app` voice test green unchanged; new `DriverTurnPipelineTest` drives it without a socket | R |
| G1.4 | `GeminiSettings` (model, voice, thinking level, turn-end silence, enabled, consent accepted) in prefs; key in Keystore as `gemini_api_key`; validator; `VoiceProviderChoice` resolver (Baidu unless enabled + consent + key); `SecretScanTest` learns the Google key shape | new `app/.../GeminiSettings.kt`, `behavior-test/.../SecretScanTest.kt` | executor | `GeminiSettingsTest` (validator, resolver), `SecretScanTest` fails on a planted `AIza…` literal and passes on the tree |

### Wave 2

| ID | Objective | Owned scope | Who | Acceptance |
| --- | --- | --- | --- | --- |
| G2.1 | `GeminiLiveProtocol` (pure JSON: setup, realtimeInput audio, clientContent text, toolResponse, server-message parse → `DomainVoiceEvent`), `GeminiLiveClient` (OkHttp socket, key in `x-goog-api-key` header, `DriverTurnPipeline`, resumption handle in memory, `goAway` → reconnect event), `GeminiLiveProvider` | new `app/.../voice/GeminiLive*.kt` + tests | executor | `GeminiLiveProtocolTest` (setup shape incl. F1/F17/F18, every server message in G§3.2, thoughts dropped), `GeminiLiveClientTest` on `MockWebServer` (handshake, audio, tool round trip, interruption, claim held until execution proof, close-code mapping, key never in URL) | R |

### Wave 3

| ID | Objective | Owned scope | Who | Acceptance |
| --- | --- | --- | --- | --- |
| G3.1 | Composition: `openSession` chooses the provider from `VoiceProviderChoice`; output rate 24 kHz for Gemini; microphone uplink-gate transitions reach `onLocalSpeechActivity`; developer settings section (enable, consent text, key, voice, thinking level); `ProviderBoundaryTest` adapter list gains the Gemini files; `capabilities.yaml`/`CAPABILITIES.md`/`ARCHITECTURE.md` rows | `app/.../voice/VoiceSessionController.kt`, `VoiceSessionGateway.kt`, `MainActivity.kt`, `DeveloperSettingsActivity.kt`, `PcmAudioCapture.kt` (callback only), `ProviderBoundaryTest.kt`, docs | executor, planner for docs | `./gradlew test --rerun-tasks :app:assembleDebug` green; a wiring test proves a Gemini choice reaches `GeminiLiveProvider` and a Baidu default still reaches `BaiduFlexProvider` | R |

### Wave 4 (planner)

- Full suite and APK; the planner reads the JUnit XML.
- Reviewer on the whole range G1.2–G3.1 (`agent/REVIEWER.md`).
- Cloud probes still open: G-M7 (does `outputTranscription` arrive before the audio it describes —
  the I-1 hold depends on it), G-M6 (call ids after resumption), G-M3 (actuating latency by
  thinking level). Results go to the probe report.
- Registry: `GEMINI-UNIT-001` (autonomous), `GEMINI-DEVICE-REACH-001` (G-M1) and
  `GEMINI-DEVICE-DUPLEX-001` (G-M2) as `HUMAN_REQUIRED` device rows; `BACKLOG.md` B-027 state;
  `python scripts/collect_state.py`.

## 5. Next phase (not in this run): native delegation

Builds on this plan once D-E and D-F are answered: `delegate_task` declared `NON_BLOCKING`,
`WorkInjection(deferred=true)` → `toolResponse.scheduling = WHEN_IDLE`, `SILENT` for stale results,
`ToolCallCancelled` cancelling unfinished work, the SPEC-012 `DEFERRED_ANSWER` row, and a
`DelegationPort` (G§5). Tasks T3a–T4d of [LIVE_CONVERSATION_PLAN.md](LIVE_CONVERSATION_PLAN.md)
apply, with T4c replaced by the Gemini adapter's native scheduling.

## 6. Risks

| Risk | Mitigation in this plan |
| --- | --- |
| The phone cannot reach Google (G-2) | opt-in; Baidu stays default; G-M1 is the first device test |
| The extraction in G1.3 changes the claim gate's behaviour | extraction only; every existing test must pass unchanged; independent review |
| Server-owned interruption flushes on echo (G§4.2) | uplink gate still decides what reaches Gemini; `NO_INTERRUPTION` stays a documented fallback, decided by G-M2 |
| Tool calls 5–11 s late (F6) | recorded as a known limitation; G-4 fast path is an owner decision, not built here |
| Key leakage | Keystore only; header, never URL; `SecretScanTest` pattern; no logs of content (I-8) |
