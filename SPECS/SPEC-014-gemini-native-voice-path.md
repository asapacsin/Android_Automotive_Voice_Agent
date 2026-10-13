# SPEC-014 — Under Gemini, commands act in about a second and replies are heard as they stream

Status: **Authorised 2026-09-30** (owner approved the plan; N-1 and N-2 decided as recommended). Build per the plan, not the clause-release rows below, which Revision 2 superseded
([ADR-011](../DECISIONS/ADR-011-gemini-native-voice-path.md))

**Clause release — owner decision 2026-10-09:** "Build clause release (Recommended)", asked with the
trade-off stated: each sentence plays once its own words are checked, and a false claim in a later
sentence is cut and corrected after the driver has heard the earlier ones; action replies still wait
for proof. Built for providers that declare `ProviderCapabilities.streamedReplyText` (Qwen; Gemini and
Baidu unchanged) in `DriverTurn.clauseVerdict` + `DriverTurnPipeline.drainClauses`:
- Behaviour 3–4 as written below: 150 ms of audio per checked character; the first clause that fails the
  response end's own predicate closes early release (`TURN_GATE_CLOSED`); the rest gets the end verdict.
- It applies to the holds whose end rule can be judged on a prefix: `UNCLASSIFIED_CLAIM` and
  `CAPABILITY_HELP` (the car-action/done claim and repair predicates), `NO_TOOL_REQUEST` for a live-info
  question (invented data), and `AWAITING_EXECUTION_PROOF` after an unconfirmed music hand-off (a
  now-playing claim). Every other hold, a pending tool and a failed call wait for the end as before.
- The clause check is exactly the end rule; a stricter one only closes the gate on replies the end
  releases, and the wait is heard (measured: a 0.33 s hole). The first release covers ≥ 6 characters.
- Tests: `DriverTurnClauseReleaseTest` (A2/A3/A4). Emulator 2026-10-09: chat 2.9 s → 1.8–2.6 s.
- **Corrected after review, 2026-10-09** (I-1; JVM only, not re-measured on the emulator):
  - Nothing is released before the driver's transcript has classified the turn (`userSpoke`, kind ≠
    UNKNOWN). 「好的，已经开了，」 streamed before the transcript 「把车窗打开」 had been heard and then
    dropped as an unproven claim.
  - `CAPABILITY_HELP` is released only once the words so far already pass its own end rule
    (`answersCapabilityHelp`, two ability nouns, which more words cannot undo); before that it waits.
    The claim check above is not its end rule, so 「我可以帮你导航，」 was heard and then followed by a
    second, full answer.
  - The chat clause check and the `UNCLASSIFIED_CLAIM` end rule call one shared predicate
    (`DriverTurn.chatClaimPredicate`) instead of two copies.
  - An ability enumeration is no longer exempt from the claim check when it claims or promises the
    action (「空调、车窗我都帮你打开。」; `ActionClaimGuard.promisesToAct`).
Raised: 2026-09-29 · Source: [B-028](../BACKLOG.md)
Depends on: I-1, I-5, I-10, I-13, ADR-009, ADR-010, [SPEC-013](SPEC-013-gemini-live-provider.md)
Plan: [GEMINI_NATIVE_PLAN.md](../docs/GEMINI_NATIVE_PLAN.md). Behaviour 3–7 and A2/A3 below describe
clause release, which ADR-011 Revision 2 deferred; they are rewritten in the plan's W0.

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry, the debt list and
> the backlog row, then run `python scripts/discover_work.py` and take the next item. Handing
> control back because a SPEC finished is forbidden by
> [CONSTITUTION.md](../harness/CONSTITUTION.md) rule 12.

## Goal

With Gemini selected, 「把空调打开」 acts about a second after the driver stops. A long answer
starts playing within a moment of its first audio, not after its last. The rule that nothing is
claimed before it happened holds exactly as it does with Baidu. Baidu's behaviour does not change.

## Scope

- Capabilities per (provider, model), with two traits declared from probe evidence.
- `gemini-3.8-live` as a declared model; the setup sends each model only the fields it accepts.
- Clause release in `DriverTurn` for providers whose replies stream in real time.
- Correction timing chosen by trait, in the pipeline.
- The hold-budget fix: the budget releases only words judged clean (TECH_DEBT D-9).

## Non-goals

- Deep thinking behind `delegate_task`: blocked on F30/F31.
- A command fast path (G-4): not needed once calls take about a second (ADR-011, alternatives).
- Client activity detection: not faster (F26).
- Any change to the Baidu adapter's timing.

## Capability ground truth

`config/capabilities.yaml`'s `gemini_live` block stays at `code` level. Nothing is claimed at
device level until the device rows pass.

## Behaviour

1. `VoiceCatalog.capabilities(GEMINI_LIVE, "gemini-3.8-live")` declares real-time replies and
   same-response tool calls. The extended-thinking model declares real-time replies and tool calls
   that may come in a later turn. Baidu declares neither.
2. The `setup` for `gemini-3.8-live` carries no `thinkingConfig`. The extended-thinking model's
   `setup` carries `thinkingLevel`.
3. With real-time replies, a reply that claims nothing is released clause by clause, with its
   subtitle. Audio is never released past the words that were checked (at 150 ms per character).
4. The first clause that would make the end verdict drop the reply stops early release. The rest
   gets today's end-of-response verdict.
5. Without real-time replies (Baidu), every hold behaves exactly as before.
6. A claim correction goes out at the end of the response when calls come in the same response. It
   waits for the provider to be idle (at most 20 s) when calls may come later.
7. The hold budget releases only clauses judged clean. Past a 60 s hard cap the remainder is
   dropped, not released (TECH_DEBT D-9).

## Failure behaviour

- Transcript behind its audio: early release waits for the words. Slower, never unsafe.
- A claim split across clauses: the prefix is judged, so it is caught before its audio.
- A reply whose first clause is the claim: nothing is released early.
- The fast model claims without calling: today's correction, sent at the end of the response.
- The provider goes silent mid-reply: the held remainder is dropped on disconnect, as today.

## Observability

`TURN_RELEASE … reason=clause chars=<n> audioMs=<n>`, `TURN_GATE_CLOSED epoch=<n> predicate=<name>`
and the existing `TURN_HOLD/RELEASE/DROP`. Never the text (I-8).

## Acceptance criteria

The state column says **blocked (N-1/N-2)** until the owner decides. The criteria are written now,
so the build can start from them.

| # | Criterion | Kind | Proven by | State |
| --- | --- | --- | --- | --- |
| A1 | Capabilities and `setup` per model as in Behaviour 1–2 | functional | `VoiceCatalog` capability tests, `GeminiLiveProtocolTest` | blocked (N-1/N-2) |
| A2 | A clean streamed reply is released clause by clause, bounded by its checked words | functional | `DriverTurnClauseReleaseTest` | blocked (N-1/N-2) |
| A3 | A claim (first clause, split clauses, late clause) is never released before proof | negative | `DriverTurnClauseReleaseTest`, `GeminiLiveClientTest` | blocked (N-1/N-2) |
| A4 | Baidu holds are unchanged | regression protection | every existing `DriverTurnTest`, `BaiduFlexClientTest`, golden files, unmodified | blocked (N-1/N-2) |
| A5 | No provider- or model-name branch in policy | architectural | `ProviderBoundaryTest` | blocked (N-1/N-2) |
| A6 | Cloud latency L1–L3 of the architecture §8, through the app's client | functional | `GeminiLiveSmokeTest` (opt-in) | blocked (N-1/N-2) |
| A7 | The APK builds | artifact | `:app:assembleDebug` | blocked (N-1/N-2) |
| A8 | End of speech → first audio heard, on the phone | device | `GEMINI-DEVICE-LATENCY-001` | blocked (N-1/N-2) |

## Open product decisions

```
BLOCKED_BY: N-1 (default Gemini model) and N-2 (I-1 wording for clause release), ADR-011
```

## Implementation status

Built 2026-09-30 on `claude/9-30` per [GEMINI_NATIVE_PLAN.md](../docs/GEMINI_NATIVE_PLAN.md): P1 model
registry, P2 D-10, P3 repeat guard, P4 settle at `generationComplete`, P5 default model, P6 live
smoke ([report](../docs/reports/2026-09-30-gemini-native-smoke.md)). Evidence level: JVM + live cloud
API through the app's client. Device: `GEMINI-DEVICE-LATENCY-001`, `GATE-D10-DEVICE-001` (not run).
