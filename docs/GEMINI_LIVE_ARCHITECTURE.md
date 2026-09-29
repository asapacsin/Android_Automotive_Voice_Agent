# Gemini 3.8 Live Extended Thinking: provider architecture

Status: **Accepted 2026-09-29** as [ADR-010](../DECISIONS/ADR-010-gemini-live-second-provider.md); build order in [GEMINI_LIVE_PLAN.md](GEMINI_LIVE_PLAN.md). **Proposed successor for §3.1 (model, thinking) and §4.3 (F6 levers):** [GEMINI_NATIVE_ARCHITECTURE.md](GEMINI_NATIVE_ARCHITECTURE.md) (ADR-011). Written 2026-09-29 on
`claude/9-29`, after the product owner's decision to use **Gemini 3.8 "extended"**. Against the
model list the key returns, that is `models/gemini-3.8-live-extended-thinking`.

This document specialises [LIVE_CONVERSATION_ARCHITECTURE.md](LIVE_CONVERSATION_ARCHITECTURE.md)
(cited as `A§n`). There, D-G option 2 said "add a GPT-Live adapter behind the seam". The owner chose
Gemini Live instead. Everything else in A§3–A§6 keeps its shape: the four planes, `WorkCoordinator`,
`SpeechArbiter`, `DriverTurn` and the provider-neutral contract.

Evidence: [reports/2026-09-29-gemini-live-probe.md](reports/2026-09-29-gemini-live-probe.md).
Those are measurements against the real API, taken from a cloud container **outside China**, with
the key in the session environment and the repository's recorded 16 kHz test utterances. They are
not measurements from the phone. Probe tools: `tools/gemini-live-probe/`.

---

## 1. What the owner's choice means, precisely

`gemini-3.8-live-extended-thinking` supports **only `bidiGenerateContent`**. It is a realtime
speech-to-speech model, the same kind of thing as Baidu Qianfan Flex, not a text model. So the
choice is a **realtime voice provider** (A§8 option 2 or 3), not the background model of D-C.

| Question | Consequence |
| --- | --- |
| Does it replace Baidu, or sit beside it? | Replacement reopens ADR-002. Adding it next to Baidu meets ADR-008's own revisit trigger ("a capability Baidu lacks"). Either way it needs **ADR-010** (§9, G-1). This document designs it as a **second `RealtimeVoiceProvider`**, chosen at session start. The same design serves a replacement: Baidu simply stops being chosen. |
| Does it still need a separate background model? | Partly not. The model thinks natively and supports **non-blocking function calls whose results it speaks later** (measured, §3). That is the delegation pattern of A§5.3, built into the provider. A `DelegationPort` is still needed for *what computes the result* of `delegate_task` (§5). |

---

## 2. Measured facts this design rests on

All from the probe report (cloud container, not the device; numbers are single-digit samples):

| # | Fact | Value |
| --- | --- | --- |
| F1 | Setup needs `generationConfig.thinkingConfig.thinkingLevel` | Required. `MINIMAL` is rejected; `LOW`/`MEDIUM`/`HIGH` are accepted |
| F2 | Text turn → first reply audio | ~570 ms (LOW) |
| F3 | End of driver speech → first reply audio (streamed 16 kHz mic audio) | ~0.8–1.3 s |
| F4 | Output audio | `audio/pcm;rate=24000` (Baidu path: 16 kHz) |
| F5 | Input audio | `audio/pcm;rate=16000` accepted; the app captures at 16 kHz already |
| F6 | Blocking tool (`navigate_to`) | The model first speaks an honest filler (「马上为您规划路线」). The **tool call arrives 5.5–10.7 s after the driver stopped**. It said 「已为您开启导航」 only after the result |
| F7 | `NON_BLOCKING` tool + `toolResponse.scheduling` `WHEN_IDLE`/`INTERRUPT` | The model says a filler, then calls (5–7 s later), then speaks the late result once, faithful to the payload |
| F8 | `serverContent.interactionStatus` | `IN_PROGRESS` while a tool is pending, `IDLE` after, a native "work pending" signal |
| F9 | Voice barge-in (「闭嘴」 during a reply) | Server `interrupted` about 390 ms after onset. **The model then replied to it** (「好的，我先退下了」) |
| F10 | Cough and knock during a reply (no echo in this test) | **No interruption** |
| F11 | Mid-sentence pause of ~300 ms (「导航到 … 万达」) | One turn at the default settings and at `silenceDurationMs` 1200 |
| F12 | Accepted setup fields | `realtimeInputConfig.automaticActivityDetection` (silence, prefix, start/end sensitivity, `disabled`), `activityHandling: NO_INTERRUPTION`, `contextWindowCompression.slidingWindow`, `sessionResumption`, `speechConfig.voiceConfig`, `thinkingConfig.includeThoughts` |
| F13 | Rejected fields | `proactivity`, `enableAffectiveDialog` (unknown fields) |
| F14 | `googleSearch` tool | **Refused on this key: "exceeded your current quota"** (1011) |
| F15 | Server sends `sessionResumptionUpdate` (handle) unasked, right after setup | yes |
| F16 | ~~Server events have no `speech_started`/`speech_stopped` equivalent~~ **Corrected by F19 (same day):** the server sends `voiceActivity {ACTIVITY_START / ACTIVITY_END}`; the first probe only logged fields it knew. The design keeps the local uplink gate as the primary signal (faster, no network round trip) and uses ACTIVITY_START as the fallback | see probe report F19 |

---

## 3. Mapping onto the provider-neutral contract (ADR-009)

One new adapter, `GeminiLiveProvider : RealtimeVoiceProvider` in `app` (vendor JSON confined there,
I-9), plus a `GeminiLiveProtocol` (pure JSON build/parse, JVM-tested like `BaiduFlexProtocol`).
Written new, not revived from git history (ADR-008).

### 3.1 Session

| Contract | Gemini wire |
| --- | --- |
| `connect(config)` | WSS `…GenerativeService.BidiGenerateContent`, key in the `x-goog-api-key` header (never in the URL, so it can't reach a log), first message `setup` |
| model / voice | `setup.model`; `generationConfig.speechConfig{languageCode:"zh-CN", voiceConfig.prebuiltVoiceConfig.voiceName}`. The persona voice changes from Baidu 4196 to a Gemini voice (G-5) |
| thinking | `thinkingConfig.thinkingLevel` from settings, default **LOW** (F1, F6). `includeThoughts` is **never** set: thoughts are neither spoken, shown nor logged |
| turn detection | `realtimeInputConfig.automaticActivityDetection` (server VAD stays the turn authority, as today). `silenceDurationMs` and sensitivities come from `turnEndTuning` (§6) |
| tools | `functionDeclarations`, from the same tool registry that builds Baidu's list. `delegate_task` is the only `behavior: NON_BLOCKING` declaration; everything that actuates stays blocking (§4) |
| transcripts | `inputAudioTranscription{}`, `outputAudioTranscription{}`. Needed by `DriverTurn`/`PhantomTurnGate`; never logged (I-8) |
| long drives | `contextWindowCompression.slidingWindow`, `sessionResumption{handle}`. On `goAway`, reconnect proactively with the last handle through the existing reconnect owner. The handle is kept **in memory only** (it addresses the conversation) |
| `SessionReady` | `setupComplete` |

### 3.2 Events: Gemini → `DomainVoiceEvent`

| Gemini | Domain event | Note |
| --- | --- | --- |
| `serverContent.modelTurn.parts[].inlineData` (audio) | `AudioDelta` (+ `ResponseStarted` on the first chunk of a turn) | 24 kHz (F4) |
| part with `thought: true` | dropped in the adapter | defensive: `includeThoughts` is never requested, but the adapter does not rely on that |
| `outputTranscription` | `AssistantTranscript` | feeds the I-1 claim check |
| `inputTranscription` | `UserTranscript` | also the "driver spoke" signal (§3.4) |
| `interrupted` | `Interrupted("server_vad")` | server-owned; the generation has already stopped (§4.2) |
| `generationComplete` / `turnComplete` | `AudioDone` / `ResponseDone` | |
| `interactionStatus` | **new** `ProviderWorkState(pending: Boolean)` | informs the safe delivery point (§5); never replaces `WorkCoordinator` |
| `toolCall.functionCalls[]` | `ToolCall` per call | ids are opaque strings |
| `toolCallCancellation.ids` | **new** `ToolCallCancelled(ids)` | cancels **unfinished** work only; an executed action is never "undone" by it (I-1) |
| `goAway` | `Reconnecting` (proactive) | |
| close codes 1007 / 1008 / 1011 | `Error` → `ErrorClass` MALFORMED / AUTH / RATE_LIMIT-or-RETRYABLE | 1011 "quota" is RATE_LIMIT, **not** retried in a loop (F14) |

### 3.3 Client → Gemini

| Contract | Gemini wire |
| --- | --- |
| uplink audio | `realtimeInput.audio{data, mimeType:"audio/pcm;rate=16000"}`, only what `SpeechUplinkGate` passes, as today |
| `sendText` | `clientContent{turns, turnComplete:true}` |
| `injectWorkResult(WorkInjection)` | `toolResponse.functionResponses[{id, name, response}]`. Blocking tools: the result. `deferred=true`: `response.scheduling = WHEN_IDLE`. Stale/superseded: `SILENT`. **`INTERRUPT` is never used**: the arbiter, not the model, decides when 小诺 may talk over anything |
| `cancelActiveResponse` | **not available** with server VAD (no client cancel). `clientResponseCancel = false`; the app flushes playback locally |

### 3.4 Capability flags for this adapter

`customTools = true`, `serverVadInterrupt = true`, `clientResponseCancel = false`,
`workResultInjection = true`, `deferredResultDelivery = true` (F7), `nativeDelegation = true` (F7/F8),
`nativeBackchannel = false` (no evidence), `turnEndTuning = true` (F12), and **new**
`serverSpeechActivityEvents = false` (F16). Output rate: `RealtimeAudioConfig.outputSampleRateHz = 24_000`.

`serverSpeechActivityEvents = false` matters. Today `SpeechStarted`/`SpeechStopped` from Baidu feed
`SpeechArbiter.onDriverSpeaking` (R0 utterance protection), `DriverTurn` epochs, and the barge-in
*candidate*. With this adapter, the ingress takes "driver speaking" from the **local**
`SpeechUplinkGate` onset/offset (time-scoped evidence the app already computes, Astra D4). The
driver-turn epoch opens on that onset and is confirmed by the first `UserTranscript`. Behaviour
varies on the flag, never on the provider name (I-13).

---

## 4. The planes under Gemini

### 4.1 Acoustic plane: one change

`PcmAudioPlayer` is already rate-configurable, and `aec.processRender(…, sampleRateHz)` already takes
the render rate. The adapter sets 24 kHz. What must be verified, not assumed: AEC3's render
reference at 24 kHz against 16 kHz capture (resampled inside the one APM instance, Astra D1), and
the echo-delay estimate at the new rate. Everything else (uplink gate, gain, `PhantomTurnGate`)
is unchanged.

### 4.2 Turn plane: interruption is server-owned here

With Baidu, server `speech_started` during playback is only a candidate, and the app flushes only
with local evidence (BARGE-IN-EVIDENCE-001). Gemini **stops the generation itself** when its VAD
fires (F9) and there is no client cancel. So:

- When `interrupted` arrives, the app must flush, because nothing more of that reply will come.
  Local qualification can no longer *prevent* a false stop.
- The defence moves upstream, to what reaches the server: `SpeechUplinkGate` (already there) and
  `startOfSpeechSensitivity` (F12). F10 is encouraging (a cough or knock did not interrupt), but it
  had no echo. The deciding measurement is on the device, with the reply playing through the
  speaker (G-M2).
- Fallback if G-M2 fails: `activityHandling: NO_INTERRUPTION` (accepted, F12). The server then keeps
  talking through driver speech, and the app does barge-in locally (flush on local evidence).
  Because there is no client cancel, the rest of that generation is discarded by epoch. The cost is
  wasted tokens, not correctness. This is a setting, not a second mechanism.
- F9's second half: the model *answered* 「闭嘴」. `VoiceCommandRouter` still owns 「闭嘴」 →
  SILENT_WAIT, and the reply to it is dropped by epoch, as for any post-command generation. The
  persona asks for silence; the rule doesn't depend on it (I-11).

### 4.3 Work plane: delegation is native

`delegate_task` is declared `NON_BLOCKING`. The flow in A§5.3 simplifies, because the call id stays
valid in the same session:

1. `toolCall(delegate_task)` → `AndroidToolDispatcher` submits the job to `WorkCoordinator`
   (supersede, deadline, TTL as A§5.3). No immediate "accepted" output is needed; the provider
   already lets the conversation continue (F7).
2. The model's filler plays as ordinary speech. `DriverTurn` still treats anything that claims the
   *answer* before a result exists as unproven (I-1/I-5).
3. The result → `injectWorkResult(deferred=true)` → `toolResponse` with `WHEN_IDLE`. Gemini then
   speaks it when the conversation is idle (F7).
4. `SpeechArbiter` row `DEFERRED_ANSWER` (A§5.4) still gates the *audio*. `WHEN_IDLE` is the model's
   idea of idle, not the car's (Amap guidance, manoeuvre zone), so the arbiter holds the chunks as
   it would any reply. The safe delivery point still uses `WorkCoordinator` + the lifecycle.
   `ProviderWorkState` is extra information, never the decider.
5. `ConversationResetPolicy` exists because **Baidu** degrades after a few tool turns (measured
   2026-09-17). Whether Gemini needs resets at all is G-M5. Until measured, the policy is
   configured per provider through a capability value (`maxToolTurnsBeforeReset`, `null` = never),
   not by provider name. A reset would invalidate pending `NON_BLOCKING` call ids, so it waits for
   pending work, as the policy already waits for owed calls.
6. After a `goAway`/resumption reconnect, whether pending call ids stay valid is unknown (G-M6).
   Until proven, pending deferred results after a resumption are delivered as text (`clientContent`,
   system-labelled), the A§5.3 Baidu path, which the adapter still supports.

**F6 is the most important UX finding.** Actuating tools arrive 5–11 s after the driver stops. The
filler hides it for conversation, but "导航到万达" starting guidance 8 s later is slow in a car.
Levers, in order: persona instruction to call first (tone only, might help); `thinkingLevel` (only
LOW measured so far); keep that class of command on a faster path. The last means routing
deterministic commands (「导航到X」, 「空调调到24度」) via the existing `VoiceCommandRouter`
before the model. It already does this for 「闭嘴」 and cancellation. Widening it is a **new
ownership decision** (G-4), not an adapter detail.

### 4.4 Output plane: unchanged owners

`SpeechArbiter` rows (R0–R6a, plus `DEFERRED_ANSWER` from A§5.4), `PhantomTurnGate`, `DriverTurn`
and playback epochs apply to Gemini audio exactly as to Baidu audio. The adapter never decides
whether something is heard.

---

## 5. What computes a delegated result

`DelegationPort` (A§5.3) stays the seam. Candidates under the owner's Gemini choice:

| Implementation | For | Status |
| --- | --- | --- |
| `LiveInfoTool` kinds (Amap weather, traffic, along-route, place details) | local, mainland facts | exists; becomes a read-only delegate source |
| `GeminiTextDelegationPort`: `gemini-3.8-flash` `generateContent`, same key | `knowledge`, `plan` | the model exists on the key; not probed for latency |
| the same with Google Search grounding | `search` (I-3: grounded or refused) | **blocked on quota** (F14); needs billing on the key (G-3) |
| none: let the Live model answer `knowledge` itself (it thinks natively) | `knowledge` | simplest; G-M4 decides whether its answers are good and fast enough without delegation |

I-14 (background work never actuates) holds for all of them, via the same dependency rule.

---

## 6. Pauses and noise

- Turn end: `silenceDurationMs` + `endOfSpeechSensitivity` via `turnEndTuning`. F11 suggests the
  Gemini default already tolerates a 300 ms pause that Baidu's 200 ms would split. A§7 M-5 applies
  per provider, with values stored per provider in the settings owner.
- Noise: the same decision as A§5.5 (WebRTC NS inside the one APM, only after M-4). F10 is a hint,
  not road-noise evidence.

---

## 7. Credentials, privacy and reachability

- **Key.** Entered on the phone, stored under Android Keystore in a `gemini_*` namespace (ADR-001,
  I-7). Never in source, Gradle, APK or logs. `SecretScanTest` gains the Google key pattern
  (`AIza…`). The `GEMINI_API_KEY` in the cloud session is for probes only and never enters the
  build.
- **Reachability: the largest open risk.** The product is zh-CN, and today's device evidence comes
  from a phone in mainland China. Google's published list of Gemini API regions does not include mainland China (re-check at
  decision time), and `generativelanguage.googleapis.com` is normally not reachable there without an
  approved route. Nothing in this repository has measured it from the device. ADR-001 forbids a
  backend relay unless a new ADR allows it. See G-2.
- **Privacy.** The driver's voice, transcripts and any `DelegationContext` would go to a vendor
  outside China: a cross-border transfer. What may be sent (A§9 D-D) must be decided **before** the
  adapter ships, not after.

---

## 8. Ownership rows this adds (ARCHITECTURE.md)

| Behaviour | Canonical owner | Not owned by |
| --- | --- | --- |
| Which realtime provider a session uses | the composition boundary, from settings + reachability at session start (never mid-session) | the adapters, the UI |
| Gemini wire format | `GeminiLiveProtocol` / `GeminiLiveProvider` | `ingress`, policy code |
| "Driver speaking" when the provider has no speech events | local `SpeechUplinkGate` onset/offset, fed to `DriverTurn`/`SpeechArbiter` via the capability flag | the adapter |
| Result scheduling hint (`WHEN_IDLE`/`SILENT`) | the adapter, from `WorkInjection` | the model |
| Whether a delegated/deferred answer may sound | `SpeechArbiter` (unchanged) | Gemini's `WHEN_IDLE` |
| Resets per tool turn | `ConversationResetPolicy`, configured per provider capability | a provider-name branch |

---

## 9. Decisions required

```text
ARCHITECTURE_REVIEW_REQUIRED
CONSTRAINT:  ADR-002 (Baidu default), ADR-008 (one active provider), ADR-001 (direct device
             connection, no relay), I-8/D-D (what leaves the phone), Astra D4 (barge-in qualified
             by local evidence before flush)
EVIDENCE:    docs/reports/2026-09-29-gemini-live-probe.md (F1-F16); ADR-008 "What would justify
             revisiting"; DomainVoiceEvent.kt (SpeechStarted/Stopped assumed of every provider)
CONFLICT:    Gemini Live is a second realtime provider; it may be unreachable from the target
             market; it owns interruption server-side; actuating tool calls arrive 5-11 s late
OPTIONS:     G-1 a/b below; G-2 a/b/c; NO_INTERRUPTION fallback (§4.2); command fast path (G-4)
DOWNSTREAM:  ADR-010; ARCHITECTURE.md rows (§8); capabilities.yaml (a gemini_live provider block);
             SPEC-013 re-scoped; SecretScanTest pattern; LIVE_CONVERSATION_PLAN.md re-sequenced
DECISION_REQUIRED: G-1 … G-6
```

- **G-1: Role.** (a) Gemini as a second provider chosen at session start, with Baidu kept
  (recommended: it keeps the only device-proven path and a mainland fallback). Or (b) Gemini
  replaces Baidu, reopening ADR-002 and eventually deleting the Baidu adapter under ADR-008's rule.
- **G-2: Reachability from the car.** (a) The target users are outside mainland China. (b) There is
  an approved network route from the device. Or (c) a relay server, which reopens ADR-001. Until
  this is answered, the phone test (G-M1) is the first thing to run.
- **G-3: Billing and quota** on the key: the Live model for every conversation, plus search
  grounding if `kind=search` is kept (F14). A cost ceiling per drive-hour.
- **G-4: Fast path for deterministic commands** (F6). Keep everything model-routed and accept
  5–11 s, or let `VoiceCommandRouter` take a closed list of actuating commands before the model.
  The second changes who owns "which tool runs".
- **G-5: Voice.** Which Gemini prebuilt voice replaces the Baidu 4196 persona voice.
- **G-6: Privacy.** Consent and scope for sending audio and context to Google (answers A§9 D-D
  for this provider).

A§9 D-A, D-B, D-E and D-F still stand as written. D-C is partly answered (§5), and D-G is answered
by the owner: Gemini.

## 10. Measurements that gate the build

| Id | Question | Where |
| --- | --- | --- |
| G-M1 | Can the phone, on its normal network, open the Live session? Setup time, first-audio latency | device |
| G-M2 | With the reply playing through the speaker (AEC3 at 24 kHz render), how often does Gemini self-interrupt (`interrupted` with no driver speech)? And true barge-in latency | device; decides §4.2 default vs `NO_INTERRUPTION` |
| G-M3 | Actuating tool-call latency over the harness commands at LOW/MEDIUM/HIGH, with and without the "call first" persona line | cloud probe, then device |
| G-M4 | `knowledge` answer quality and latency with no delegation vs `gemini-3.8-flash` | cloud probe |
| G-M5 | Does Gemini degrade across many tool turns (the Baidu reset reason)? | cloud probe with recorded audio |
| G-M6 | Are `NON_BLOCKING` call ids still valid after a `sessionResumption` reconnect? | cloud probe |
| G-M7 | Does `outputTranscription` arrive before, with, or after the matching audio? (The I-1 claim hold needs text before audio is heard) | cloud probe |

G-M3 to G-M7 can run from this environment with the probe tools, within the key's quota. G-M1 and
G-M2 need the phone.

## 11. What this document does not do

It authorises no code, does not edit an ADR, SPEC, capability or invariant, and does not put the
key anywhere but the session environment. Once G-1 … G-6 are answered, the intake path is
`agent/INTAKE.md`: ADR-010 → SPEC-013 (re-scoped) → a new version of
[LIVE_CONVERSATION_PLAN.md](LIVE_CONVERSATION_PLAN.md), where the adapter becomes the first build
phase and Phase 8 disappears.
