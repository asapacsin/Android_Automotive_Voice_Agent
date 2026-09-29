# ADR-011 — Gemini-native voice path: model profiles, clause release, fast model by default

Status: **Proposed** (2026-09-29). Not in force until the owner decides N-1 and N-2.
Amends, on acceptance: [ADR-010](ADR-010-gemini-live-second-provider.md) decision 1 (the default
Gemini model), and closes its "Open architecture question".
Keeps: ADR-001, ADR-002 (Baidu stays the default provider), ADR-008, ADR-009.
Architecture: [docs/GEMINI_NATIVE_ARCHITECTURE.md](../docs/GEMINI_NATIVE_ARCHITECTURE.md) ·
Requirement: [SPEC-014](../SPECS/SPEC-014-gemini-native-voice-path.md) · Demand: B-028

## Revision 2 (after review)

The gate settles at `generationComplete`, not `turnComplete`. `gemini-3.8-live` delivers audio at
4.4x, and `turnComplete` is paced to playback. Clause release (decision 2) is deferred, and I-1's
wording is unchanged. D-10 (two gate orderings) and a per-driver-turn duplicate rule are added. See
the architecture's "Revision 2".

## Context

The owner, after the first Gemini build: *"design a new architecture that should optimise for the
new Gemini API, because the last test said the old design is slow and poorly suited to it."*
Measured (probe report, third round):

- `gemini-3.8-live-extended-thinking`: 1 tool call in 12 spoken commands (at 31.7 s). Two replies
  reported failures that never happened (F24).
- `gemini-3.8-live`, same key, tools and prompt: 20 of 20 spoken commands called the right tool,
  0.8–2.4 s after the end of speech, before speaking (F23).
- Replies stream at real-time pace on both models (F29). The whole-reply claim hold therefore
  delays a reply by its full length.
- The transcript arrives ahead of the audio it describes, in 45 of 45 turns (F25).

## Decision (proposed)

1. **Model profiles.** `ProviderCapabilities` is resolved per (provider, model) and gains
   `replyStreamsInRealTime` and `toolCallsMayFollowInLaterTurn`. Wire-only facts (the thinking
   field) live in the adapter's model table. Policy never branches on a model name.
2. **Clause release (N-2).** For `replyStreamsInRealTime`, `DriverTurn` judges each completed
   clause with the drop predicates its end-of-response verdict already uses, and releases clean
   audio and subtitle up to what the checked words cover. The first claim holds the rest for
   today's verdict. Baidu is unchanged.
3. **Correction timing by trait.** At the end of the response when calls come in the same response;
   deferred to provider-idle (capped at 20 s) when they may come in a later turn. One rule in the
   pipeline replaces the timer in `GeminiLiveClient`.
4. **Default Gemini model `gemini-3.8-live` (N-1).** Extended thinking stays selectable (N-3).
   Deep thinking moves behind `delegate_task` in a later phase (blocked on F30/F31).
5. **Found while designing (part of N-2):** the hold budget releases a reply still waiting for
   proof without judging its words (`onHoldBudgetExceeded`, both providers, pinned deliberately by
   `DriverTurnTest.theHoldBudgetAlwaysReleasesRatherThanStalling`). Proposed: the budget releases
   only judged-clean clauses; a 60 s hard cap drops the remainder rather than releasing it.
   [TECH_DEBT.md](../docs/TECH_DEBT.md) D-9.

## Alternatives considered

- **Keep the whole-reply hold (ADR-010 option (a)).** Conversation stays as slow as its length on
  any real-time model.
- **Release conversational turns without judging them (ADR-010 option (c)).** This drops I-1
  coverage of misclassified turns (the 「返屋企啦」 → 「发诺克拉。」 case). Clause release keeps
  that coverage.
- **A deterministic command fast path (G-4).** This would add a second route to execution. The fast
  model already calls tools in about a second, so there is no latency left to justify it.
- **Client activity detection.** Measured: not faster (F26). Kept as an echo fallback only.

## Consequences

- I-1's "Consequence" paragraph is reworded (text in the architecture, §6). The rule itself does
  not change.
- `ProviderBoundaryTest` also forbids model-name branches.
- A new model is added by probing it with `latency_probe.py` and declaring two small rows.
- The extended-thinking model remains available but is not recommended for actuating commands.

## What would justify revisiting

A model whose transcript lags its audio by more than a clause (the gate then waits; measure first).
A fast model that starts calling tools in later turns (declare the trait; the correction then
waits). Device evidence that `gemini-3.8-live` self-interrupts or answers poorly in the cabin
(G-M2, owner listening).
