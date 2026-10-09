# SPEC-021 — Talk to 小诺 through Qwen-Omni Realtime in the Maia voice, opt-in, with the same truth rules

Status: **Steps 1–3 built 2026-10-09 (JVM)**, for ADR-017 gate Q-3. Steps 1–3 are cloud work. Step 4 waits for Q-1 and Q-2, and step 5 is gate Q-4.
Raised: 2026-10-09 · Source: [ADR-017](../DECISIONS/ADR-017-qwen-omni-realtime-end-to-end.md), [B-034](../BACKLOG.md)
Depends on: ADR-009/010 (one seam, one provider per session), ADR-013 (no fallback across providers), ADR-008 (no dormant code), I-1 (the claim gate), I-8 (no words in logs), SPEC-020 (wait cues)

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

The owner chose one end-to-end model with a good Chinese voice: `qwen3.8-omni-flash-realtime` with the stock voice **Maia** (ADR-017). The driver must be able to hold the same conversation with it as with Gemini:
- the same tools;
- the same rule that nothing is claimed before it happened;
- the same listening lifecycle and barge-in.

The product session is Qwen Maia (owner 2026-10-09); gates Q-1 to Q-5 remain open for measurement and stack removal.

## Facts this SPEC rests on

From the official docs (Alibaba Model Studio: realtime, client-events and server-events pages, updated 2026-09-29, read 2026-10-09). These are not yet measured; gate Q-1 measures them.

- **Endpoint.** `qwen3.8-omni-flash-realtime` **requires the workspace endpoint**: `wss://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime` (Singapore). The `dashscope-intl.aliyuncs.com` host named in ADR-017 is the older endpoint and does not serve this model. Besides the key, the owner therefore supplies the **workspace ID**.
- **Auth.** The `Authorization: Bearer <DashScope key>` header.
- **The wire protocol is OpenAI-Realtime-shaped,** the same family as Baidu Flex:
  - `session.update` sets `modalities`, `voice` (also `audio.output.voice`), `instructions`, `turn_detection`, `input_audio_transcription {model: "qwen3-asr-flash-realtime"}` and function `tools`.
  - Audio goes as `input_audio_buffer.append` (16 kHz mono s16le in; 24 kHz s16le out).
  - The client also sends `response.create` and `response.cancel`, and returns tool results with `conversation.item.create {type: function_call_output}` followed by `response.create`.
  - The server sends `input_audio_buffer.speech_started|speech_stopped`, `conversation.item.input_audio_transcription.completed`, `response.created`, `response.audio.delta|done`, `response.audio_transcript.delta|done`, `response.function_call_arguments.done {call_id, name, arguments}`, `response.done` and `error`.
- **Turn detection.** `semantic_vad` filters back-channel speech and background noise. `silence_duration_ms` is 200–6000, default 800.
- **Not documented: a user *text* message.** `conversation.item.create` lists only `function_call_output` and `mcp_approval_response`. Gate Q-1 (`text` mode of `tools/qwen-omni-probe`) tests the OpenAI `message` / `input_text` item.
- **Limits:**
  - A session lasts at most 120 min.
  - The context keeps 100 audio turns and 600 s of audio.
  - Tools and web search are mutually exclusive, so `enable_search` stays off.

## Scope

1. **One OpenAI-Realtime client for both wire dialects** (step 1).
   - The turn handling in `BaiduFlexClient` is not Baidu-specific: the response-turn gate, the empty-response retry, conversation reset, held outbound turns, cancel tracking, the function-call assembler and the `DriverTurnPipeline` wiring.
   - It becomes `OpenAiRealtimeClient`, driven by a `RealtimeDialect` that owns only the wire differences:
     - the endpoint and auth;
     - the session.update body;
     - error classification and codes;
     - the log prefix;
     - a dialect's own recovery: Baidu's voice fallback and token auth;
     - which events it emits.
   - `BaiduFlexDialect` reproduces today's behaviour byte for byte. The goldens, the log lines (`flex_*`) and every existing test stay unchanged.
   - Copying the client for Qwen would be the parallel mechanism `AGENTS.md` forbids. When Baidu is deleted (ADR-013 step 2), its dialect goes with it, and the client stays.
2. **`QwenOmniDialect`, `QwenOmniProvider` and their settings** (steps 2–3), behind the existing seam:
   - selected for every product session via `VoiceProviderChoice.resolve` (2026-10-09);
   - DashScope key in the Keystore; workspace ID, model, voice and consent in preferences;
   - a cross-border notice (Singapore).
3. **Capabilities** (`VoiceCatalog`, `VoiceProviderId.QWEN`):
   - `customTools=true`
   - `serverVadInterrupt=true`
   - `clientResponseCancel=true`
   - `optionalInputCommit=true`
   - `workResultInjection=true`
   - `serverSpeechActivityEvents=true`
   - `toolCallCancellation=false`
   - `verbatimPromptSpeech=false`

   The stale `QWEN_FLASH`/`QWEN_PLUS` model ids (`qwen-audio-3.0-realtime-*`, from the client deleted by ADR-008) are replaced by `qwen3.8-omni-flash-realtime`.

## Non-goals

- **Treating Q-1…Q-5 as passed.** The product session is Qwen Maia now; gate rows stay open until measured.
- **Deleting Gemini, Azure or Baidu.** After Q-5, with the owner's confirmation (ADR-017, ADR-013).
- **Voice cloning and non-stock voices** (B-034).
- **Web search** (`enable_search`), because it excludes tools.
- **MCP tools on the server side.** Our tools execute in-process (ADR-015). The model only calls functions.
- **Spoken guidance prompts** (SPEC-018). `verbatimPromptSpeech=false`, so guidance stays with Amap's voice, which ADR-014 provides for when the model cannot speak a prompt.
- **A separate TTS voice.** With Qwen the driver hears the model's own audio (ADR-017 §1). An Azure assistant-voice setting is ignored for a Qwen session and logged once as `assistant_voice ignored reason=provider_speaks`.

## Behaviour

1. **Product session.** Every product session uses Qwen (`VoiceProviderChoice.resolve` → `QWEN`). Stored gemini/baidu preferences do not change that.
2. **Qwen without a key, workspace ID or consent.** The session fails at start with its code (`QWEN_API_KEY_MISSING`, `QWEN_WORKSPACE_MISSING` or `QWEN_CONSENT_MISSING`) and an honest message on the CONFIG card. It never falls back to another provider (ADR-013).
3. **Opening the session.** The session opens the workspace endpoint for `qwen3.8-omni-flash-realtime`. The key travels only in the Authorization header. The workspace ID is part of the host and is never logged. On `session.created` the client sends `session.update` with:
   - `modalities ["text","audio"]`;
   - `voice` and `audio.output.voice` = `Maia` (`QwenAppSettings.DEFAULT_VOICE`; not user-selectable on the product path);
   - `instructions`: the persona, the speaking-style paragraph (SPEC-015) and the context hint;
   - `input_audio_format`/`output_audio_format` = `pcm`;
   - `input_audio_transcription {model: "qwen3-asr-flash-realtime"}`;
   - `turn_detection {type: "semantic_vad", silence_duration_ms: 800}`;
   - the shared tools from `RealtimeToolCatalog` as `{type: "function", function: {name, description, parameters}}`.

   The session is ready on `session.updated`.
4. **The claim gate.** Reply audio (24 kHz) and its transcript go through the same claim gate as Baidu Flex. A reply that claims an action is held until an `ok=true` execution result (I-1). Here the *audio* itself is held, the way the Gemini path held it before ADR-016.
5. **Tool calls.**
   - Tool calls are assembled from `response.function_call_arguments.done` and validated by the shared catalogue before dispatch.
   - A result goes back as `function_call_output`, then `response.create`, through the same response-turn gate, so two replies never overlap.
6. **Turn-taking.**
   - `speech_started`/`speech_stopped` are the provider's speech events (`serverSpeechActivityEvents=true`): utterance protection, turn epochs and the SPEC-020 clock.
   - Barge-in is decided by the session core, exactly as for Baidu Flex. A qualified barge-in flushes playback and sends `response.cancel` (`clientResponseCancel=true`).
   - The local uplink gate keeps streaming its 1.2 s hangover, which is longer than `silence_duration_ms`, so the server always hears enough silence to end the turn. This is the trap P49 hit with Gemini.
7. **Text turns.** Claim corrections (`sendCorrection`) and typed text are sent as the OpenAI `message` item with `input_text`, then `response.create`.
   - This works only if Q-1's `text` mode shows the server accepts it.
   - If the server refuses it, the dialect logs `qwen_text_unsupported` once per session. Text turns are off for the rest of the session, and the correction is dropped. The held claim stays dropped (I-1 holds), and the driver hears nothing false.
   - A refusal is never fatal, whenever the item went out. A correction can go out late, held for a conversation reset or deferred behind a running reply, so the refusal is recognised by the error's `param` (`item.*`), not by timing.
   - A refused `function_call_output` (`item.call_id` / `item.output`) is not a text refusal. It is logged as `qwen_call_output_refused code=<code>` and is non-fatal too: the model answers without that result, and the claim gate still holds any claim.
   - Step 4 then decides the replacement (see Open questions).
8. **Wait cues (SPEC-020).** Not spoken in steps 1–3:
   - There is no `AssistantVoice`, so there is no revoicer, and `onLocalSpeechActivity` logs `wait_cue_skipped reason=no_assistant_voice`.
   - ADR-017 §4 allows a spoken cue only in Maia's voice. Step 4 bundles Maia clips of the fixed cue texts, recorded by the probe's `say` mode from stock-voice output, and plays them through the existing `WaitCueAudio` core event. The cue timer moves out of the revoicer into a component both providers use. That is a SPEC-020 amendment, written in step 4.
9. **Listening commands.** 「闭嘴」, sleep and wake behave as with every provider (`VoiceCommandRouter`, `ListeningLifecycle`).
10. **The speaking style changes the instructions only.** The voice stays Maia. Whether Maia's delivery changes by style is a Q-2 ear question.

## Failure behaviour

- **Handshake 401/403, or an `InvalidApiKey`/`AccessDenied` error:** `QWEN_AUTH_FAILED`. Never retried in a loop.
- **Quota or throttling** (`Throttling`, `AllocationQuota`, free quota exhausted): `QWEN_QUOTA_EXHAUSTED`, with an honest message. Not retried in a loop. The owner sets Stop-on-Exhaust in the console (ADR-017).
- **Workspace host not found (DNS):** `QWEN_DNS_FAILED`. It is RETRYABLE, because on a phone being offline looks the same; `ReconnectPolicy` caps the attempts. The message says to check the workspace ID and region, and never contains the host.
- **A socket that is gone when audio or a control is sent:** `QWEN_CONNECTION_CLOSED`. It is RETRYABLE, so it joins the reconnect instead of ending the session.
- **A server-side internal or unavailable error in-band:** `QWEN_SERVER_UNAVAILABLE`. It is RETRYABLE (the P38 lesson from Baidu). Any other in-band error is `QWEN_PROVIDER_ERROR`, which is terminal.
- **Server close at the 120 min cap, or any socket loss:** reported as reconnecting. The existing reconnect owner reopens the session, as for Gemini and Baidu.
- **A refused `response.cancel`** (nothing playing) and **an overlapping-response refusal** are not session-fatal. The same rules as Baidu Flex apply, by error code or phrase, in the dialect.
- **A refused text item, or a refused `function_call_output`:** never fatal (B7). The first turns text off for the session and logs `qwen_text_unsupported`; the second logs `qwen_call_output_refused code=<code>`.
- **A malformed or oversize function call:** rejected by the shared assembler and catalogue, as for Baidu Flex.
- **Logs** hold codes, counts, ids and timings only. Never the key, the workspace ID, a transcript, tool arguments or the instructions (I-8).

## Observability

- `qwen_event type=<type>` (noisy types skipped, as for `flex_event`);
- `qwen_error code=<code>`;
- `qwen_session voice=<voice> vad=<type> model=<model>`;
- `qwen_text_unsupported`;
- the shared turn lines (`TURN_HOLD`/`TURN_RELEASE`/`TURN_DROP`) and the session core's `playout_barge_in`, unchanged.

## Steps

| Step | What | Where | Gate |
| --- | --- | --- | --- |
| 1 | Extract `OpenAiRealtimeClient` + `RealtimeDialect` from `BaiduFlexClient`. `BaiduFlexDialect` keeps byte-identical goldens, logs and tests | cloud | none; a pure refactor |
| 2 | `QwenOmniDialect`: session.update, URL and headers, event and error mapping, text item. Golden tests from the documented event shapes | cloud | — |
| 3 | `QwenAppSettings` + repository (key in the Keystore), validator, developer-settings fields + consent, `VoiceProviderPreference.QWEN`, the factory branch, `VoiceCatalog` capabilities and models, `config/capabilities.yaml` | cloud | — |
| 4 | Adjust to the Q-1 results: the text-turn decision, `silence_duration_ms`, error codes seen live. Maia wait-cue clips (SPEC-020 amendment) | cloud + PC | Q-1, Q-2 |
| 5 | The emulator demo under `docs/DEMO_REQUIREMENTS.md` | PC | Q-4 |

## Acceptance criteria

| # | Criterion | Proven by | State |
| --- | --- | --- | --- |
| A1 | Step 1 changes no behaviour: every Baidu Flex test and golden (`baidu_session_update.json`) passes unchanged, and `flex_*` log lines are identical | the existing Baidu Flex suites; `git diff` on the goldens is empty | JVM PASS 2026-10-09: `OpenAiRealtimeClient` + `RealtimeDialect` merged (`d0d9e01`): app tests unmodified, all green; the review accepted it as equivalent for Baidu, and the absence rules now cover every dialect (mutation-checked) |
| A2 | Qwen session.update matches a golden built from the documented shape (Maia, semantic_vad, transcription model, function tools, no `enable_search`) | `QwenOmniDialectTest` + `golden/qwen_session_update.json` | JVM PASS 2026-10-09: `QwenOmniDialectTest` (golden + an independent shape test) |
| A3 | The URL is built from the workspace ID; the key appears only in the Authorization header and never in a URL, log or error message; the workspace ID never appears in a log | `QwenOmniDialectTest`, `SecretScanTest` (behavior-test) | JVM PASS 2026-10-09: `QwenOmniDialectTest` (header only, no host in the DNS error, case-insensitive redaction, an unparseable workspace never reaches a message), `SecretScanTest` DashScope pattern |
| A4 | Documented server events map to the neutral events, including a tool call from `function_call_arguments.done` and an error mapped to a `QWEN_*` code | `QwenOmniDialectTest` | JVM PASS 2026-10-09: `QwenOmniDialectTest`, including `everyQwenCodeHasTheIntendedErrorClass` (transient faults are RETRYABLE) |
| A5 | The claim gate holds Qwen *audio* that claims an action until `ok=true`, and drops it on a refusal (I-1) | `QwenOmniClientTest`, in the Baidu Flex claim-gate test shape | JVM PASS 2026-10-09: `QwenOmniClientTest` (held until ok=true; dropped on refusal) |
| A6 | Tool round trip: call → dispatch → `function_call_output` → `response.create`, with no overlapping response | `QwenOmniClientTest` with a mock socket | JVM PASS 2026-10-09: `QwenOmniClientTest` |
| A7 | Barge-in: a qualified barge-in sends `response.cancel` and flushes; a refused cancel is not fatal | `QwenOmniClientTest` | JVM PASS 2026-10-09: `QwenOmniClientTest` (client cancel once; a refused cancel is non-fatal). The core's barge-in qualification is shared and unchanged |
| A8 | Selection (amended 2026-10-09): `resolve` always `QWEN` for the product session; a missing key, workspace or consent fails with its code and never opens Gemini or Baidu; direct `SessionProviderConfig.Gemini` / `.Baidu` factory tests remain | `RealtimeProviderFactoryTest`, `GeminiSettingsTest` | JVM PASS 2026-10-09: `GeminiSettingsTest`, `QwenSettingsTest`, `RealtimeProviderFactoryTest`, `QwenOmniDialectTest` |
| A9 | Capabilities in all three places (VoiceCatalog, `config/capabilities.yaml`, the behaviour), and no branch on the provider name | `CapabilityContractTest`, `ArchitectureRulesTest` | JVM PASS 2026-10-09: behavior-test (`ArchitectureRulesTest`, `CapabilityContractTest`, `ProviderBoundaryTest`) |
| A10 | `:app:assembleDebug` builds; `harness_check.py` has no new findings | build, harness | JVM PASS 2026-10-09: `assembleDebug` OK; `harness_check.py` has no new finding |
| Q-1 | The probe numbers, side by side with Gemini + Xiaoyi | `tools/qwen-omni-probe` on the owner's PC | open (PC, key) |
| Q-4 | The emulator demo passes `check_req.py` | the PC | open |

## Open questions (answered by Q-1 or the owner, not guessed here)

1. Does the server accept a user `input_text` message item? If not, a claim correction needs another route:
   - a `session.update` that appends the correction to the instructions, followed by `response.create`;
   - or no correction, as now when listening is suspended.

   This is decided in step 4, with the probe's evidence.
2. Is `semantic_vad` at 800 ms fast enough? ADR-017's latency table needs the server's end-of-turn time. Q-1 measures it, and step 4 tunes it within 200–800 ms.
3. Does Maia's delivery change with the speaking-style instruction? This is answered by ear in Q-2.
4. Does Qwen need `ConversationResetPolicy`? It is shared with Baidu (step 1), and it opens a fresh conversation after a tool turn. That rule was measured on Baidu on 2026-09-17. For Qwen it costs a reconnect per tool command and drops multi-turn context. Q-1's tool runs show whether Qwen degrades without it. Step 4 decides, and the decision belongs to the dialect, not to a branch on the provider name.
