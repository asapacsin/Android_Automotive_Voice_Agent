# ADR-013 — Gemini Live becomes the default realtime provider; Baidu Flex is retired after the device gates

Status: **Accepted** (2026-09-30, product owner: "what about you just simply all use gemini", then
"i confirm this change" to option (a))
Amends: [ADR-010](ADR-010-gemini-live-second-provider.md) decision 2 and gate G-6;
[ADR-002](ADR-002-baidu-flex-default-provider.md) (Baidu default) is superseded once step 2 lands.
Keeps: ADR-001 (no relay), ADR-008 (no dormant providers), ADR-009 (provider-neutral contract),
ADR-011 (Gemini-native voice path).

## Why

The owner's rule (B-029 O-2, 2026-09-30): the driver must never hear two different voices in the
same stage. Baidu (4196 度清影) and Gemini (`Kore`) have no shared voice, so two providers mean two
voices. One provider is the only complete fix.

## Decision

1. **Step 1 — Gemini is the default now.** `VoiceProviderChoice` selects Gemini Live unless the
   owner explicitly selects Baidu in developer settings.
2. **No automatic fallback between providers.** If Gemini has no key, no consent, or cannot connect,
   the session does **not** silently open on Baidu (that would be the second voice). It fails with an
   honest on-screen reason (「语音服务未配置 / 无法连接」) and the setup path. Baidu runs only when the
   owner picked it on purpose.
3. **Consent stays.** Audio, transcripts and the context hint leave the country with Gemini. The
   cross-border notice is still accepted once, in setup; being the default does not skip it.
4. **Step 2 — delete Baidu Flex** (adapter, protocol, settings, voice catalogue, its credentials
   entries and tests), per ADR-008, **after** `GEMINI-DEVICE-REACH-001` and
   `GEMINI-DEVICE-DUPLEX-001` pass on the phone. `ConversationResetPolicy` is re-examined then:
   it exists for Baidu's per-tool-turn reset.
5. The Baidu stock-voice fallback (`BaiduFlexClient`, voice rejected → `default`) goes with step 2;
   until then it only affects a session the owner chose to run on Baidu.

## Consequences

- Before step 2, every device-verified behaviour that was verified only on Baidu (wake-word handover,
  echo hold, sleep / silent mode, claim gate on device) needs a Gemini device row in `TEST_MATRIX.yaml`.
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("which realtime provider"), `docs/CAPABILITIES.md` and
  `PRODUCT.md` are updated when step 1 is implemented, not before, so the docs never describe code
  that does not exist.
- If Gemini later fails the product (reach, latency, cost), the owner's standing instruction (ADR-010
  G-2) applies: switch to another similar realtime model behind the same seam — still one provider.

## Amendment (2026-10-09)

The owner pulled the product-session UX forward: **`VoiceProviderChoice.resolve` always selects
Qwen-Omni** for the product session (Maia via `QwenOmniDialect`). Fail-closed across providers and
consent rules in this ADR still apply to every product start. Stored `gemini` / `baidu` preferences
and the Gemini-default rule in §Decision 1 are **superseded for the product session only**; Gemini
and Baidu stacks remain until Q-5 and explicit owner confirmation. Gates Q-1…Q-5 are unchanged and
not marked passed.
