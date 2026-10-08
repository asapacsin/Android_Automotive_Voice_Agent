# ADR-017 — Qwen-Omni Realtime with the Maia voice: one end-to-end model, no separate TTS

Status: **Accepted — direction** (2026-10-09). The product owner decided: "ok save this and we would use this architecutre". This followed the decision on 2026-10-08 to look for "something that have good chinese voice at the same time has end to end interrruptable agent function and also privde free option that can test with". The owner picked the voice by ear: "Maia save this one".
**Amends:**
- [ADR-013](ADR-013-gemini-default-provider.md): which provider is the default.
- [ADR-016](ADR-016-chinese-voice-for-gemini.md): the separate TTS voice. Both stand until the gates below pass.

**Keeps:**
- ADR-001: a direct connection, no relay.
- ADR-008: no dormant providers.
- ADR-009: the provider-neutral contract and the seam.
- ADR-013: one provider per session and no fallback across providers.
- ADR-014 and ADR-015.
- The claim gate (I-1): the model's words are never evidence.

## Why

ADR-016 put Azure Xiaoyi behind Gemini Live because every Gemini voice was rejected by ear. On the emulator on 2026-10-08 (OPEN_PROBLEMS P48, P49), that layer cost the following on every reply:
- **Azure's first audio:** 0.26–0.74 s.
- **The transcript:** the Azure voice cannot start until Gemini's transcript arrives.
- **A second bill:** Gemini's discarded audio is still generated and charged.
- **A second network dependency.**

Qwen-Omni Realtime, from Alibaba Model Studio, is end to end. It hears the driver, calls tools, can be interrupted (with semantic interruption that ignores coughs and back-channel noises) and speaks in its own Chinese voices. One of those voices, **Maia** ("a blend of intellect and gentleness"), fits the target in B-034. ADR-008 removed an earlier Qwen client because it was dormant and unreachable, not because of the model.

## Decision

1. **The target architecture is one end-to-end model:**
   - the model: `qwen3.8-omni-flash-realtime`;
   - the voice: the stock voice **`Maia`**;
   - the endpoint: the international one in Singapore (`dashscope-intl.aliyuncs.com`, realtime WebSocket);
   - the driver hears the model's own audio;
   - there is no separate TTS layer.
2. **It is a provider behind the existing seam** (ADR-009/010). It is chosen once per session in `openSession`. Behaviour varies on `ProviderCapabilities`, never on the provider name.
3. **One provider and one voice.** There is no automatic fallback to Gemini or Baidu, because the driver must never hear two voices (ADR-013).
4. **The owners do not change:**
   - `DriverTurn` still decides what may be claimed, and holds *audio* the way the Gemini native path did before ADR-016.
   - Turn-taking still decides barge-in.
   - The domain servers still execute.
   - The SPEC-020 wait cues speak through the provider's voice path only if they can be in Maia's voice. Otherwise they become sounds or screen cues. That is decided in the adapter's SPEC.
5. **Keys:** the owner creates the DashScope key and stores it. On the PC it is the user environment variable `DASHSCOPE_API_KEY`; in the app it goes in developer settings and the Keystore. Agents never enter, print, log or commit it.
6. **Stock voices only (B-034).** Qwen offers voice cloning; it is never used to copy a real character's or voice actor's voice.

**Correction (2026-10-09, from the official docs, before Q-1):**
- `qwen3.8-omni-flash-realtime` is served only from the *workspace* endpoint: `wss://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime`. `dashscope-intl.aliyuncs.com` does not serve it.
- So besides the key, the owner supplies the Model Studio workspace ID. On the PC it is the user environment variable `DASHSCOPE_WORKSPACE_ID`; in the app it goes in developer settings.
- The decision itself is unchanged. The details are in [SPEC-021](../SPECS/SPEC-021-qwen-omni-provider.md).

## Gates — Gemini + Xiaoyi stays the default until every gate passes

| Gate | What | Who |
| --- | --- | --- |
| **Q-1** | A PC probe outside the app. Maia says the same driver lines through the real-time WebSocket. It measures the time from the last word to her first audio, a tool turn (weather, climate) and barge-in, side by side with the 2026-10-08 Gemini + Xiaoyi numbers. | agents |
| **Q-2** | Maia against Xiaoyi, judged by ear on the probe's clips | owner |
| **Q-3** | A SPEC for the adapter, then the adapter itself with JVM tests (protocol golden tests, the claim gate on audio, barge-in, tool round trip) | agents |
| **Q-4** | The emulator demo under `docs/DEMO_REQUIREMENTS.md`, with `check_req.py` passing | agents |
| **Q-5** | The phone device gates, with the same rows as ADR-013 | owner + agents |

After Q-5:
- Qwen becomes the default.
- The Gemini + Azure path is deleted in one change (ADR-008), with the owner's confirmation at that point.
- ADR-013 and ADR-016 are marked superseded.

If Q-1 or Q-2 fails, this ADR is revised; it is not pushed through.

## Expected latency (estimate before Q-1, from the 2026-10-08 measurements)

A chat turn today, timed from the driver's last word:

| Part | Gemini + Xiaoyi (measured) | Qwen + Maia (expected) |
| --- | --- | --- |
| The local gate's quiet wait (`HANGOVER_MS`) | 1.2 s | 1.2 s, unchanged (app) |
| The server closing the turn after the gate | 0.15–0.4 s | unknown; measured in Q-1 |
| The model's first output | 0.54–0.83 s (1.29 s on a session's first turn) | unknown. One hands-on report says 1–2 s; not verified |
| The claim gate holding the reply until generation ends | 0.4–1.7 s | **unchanged**, until SPEC-014 |
| Azure's first audio | 0.26–0.74 s | **gone** |
| **Total, chat** | **3.4–4.6 s** | **about 2.9–4.2 s if Qwen responds as fast as Gemini** |

The switch alone removes about **0.3–0.7 s** per reply, plus the second bill. It does not reach the demo target of a 2.0 s p50. That needs SPEC-014 (clause release, 0.4–1.7 s) and possibly a shorter hangover (about 0.4 s). With all three, the estimate is **about 1.8–2.8 s**. If Qwen's own first output is slower than Gemini's, the gain shrinks or disappears; Q-1 answers that first.

## Costs and risks

- **Free quota:** 1M tokens on `qwen3.8-omni-flash-realtime`, Singapore and international only, valid until **2027-01-08**. Stop-on-Exhaust is set by the owner. After that the service is paid.
- **Reach:** the endpoint is in Singapore, not mainland China. Network time is measured in Q-1 against Gemini over the PC proxy.
- **Tool calling:** documented over WebSocket but unproven for our tools. In Qwen3.8-Omni-Flash, web search and tools cannot be on at the same time.
- **Session length:** a cap applies (30–120 min depending on the source), so it reconnects the way Gemini does.
- **The work:** a new provider adapter and re-proving barge-in, the claim gate on audio, and the device gates.
