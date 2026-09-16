# ADR-002 — Baidu Flex is the default realtime provider

Status: **Accepted** (supersedes `docs/DECISIONS.md` D13)

## Context

D13 recorded Qwen Flash as the product default, with Baidu as an optional compatibility adapter. It also recorded that Baidu custom Function Calling was blocked (`BLOCKED_BAIDU_FUNCTION_CALLING`), because the Pro/Lite API documents neither `tools` on `UpdateSession` nor any tool-result client event.

That reasoning was correct for Baidu **Pro/Lite**, but Baidu ships a separate product — 端到端语音语言大模型 (Flex) — which does document function calling, a client `response.cancel`, and `conversation.item.create` with `function_call_output`.

Without function calling, the assistant can converse but cannot *do* anything, which is the entire point of a car assistant.

## Decision

**Baidu Flex (`qianfan-realtime-flex-v1`) is the default realtime provider.**

In code: `VoiceCatalog.DEFAULT_PROVIDER = VoiceProviderId.BAIDU_FLEX`, `BaiduRuntimeProvider.fromWire` falls back to `FLEX`, and `BaiduAppSettings` defaults to `runtimeProvider = FLEX`, `model = qianfan-realtime-flex-v1`.

Baidu Pro/Lite (`audio-mini-realtime-near` and siblings) remains **selectable** in Developer Settings as a conversation-only path with no function calling, served by `BaiduDirectRealtimeProvider`.

Qwen, GPT-Live and the PC backend remain in the tree as **dormant compatibility code** and are not selectable from the production UI.

## Consequences

- Function calling is available: `navigate_to`, `open_app`, `control_music`.
- Flex is a public-beta product (本接口处于公测阶段). Access may require Baidu enablement; denial surfaces as `BAIDU_FLEX_ACCESS_DENIED`, and Pro/Lite remains the fallback.
- Two Baidu protocol implementations must be maintained (`BaiduFlexProtocol` and `BaiduProtocol`) because their handshakes differ: Flex waits for `session.created` before sending `session.update`; Pro/Lite sends it on open.
- Provider-neutral core (`ingress`) stays provider-independent; vendor JSON remains confined to the Android adapters.

## What would justify revisiting this decision

- Flex leaving public beta with materially different terms, or being withdrawn.
- Measured latency, cost, or reliability on Flex being clearly worse than an alternative that also supports function calling.
- A provider offering function calling plus a documented output sample rate and voice list for the realtime path (both currently undocumented for Flex).
