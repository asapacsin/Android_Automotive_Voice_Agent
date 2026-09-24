# SPEC-009 — Ordered audio playout and AEC framing

Status: **IMPLEMENTATION IN PROGRESS.** Architecture selected in
[ASTRA_ARCHITECTURE_SELECTION.md](../docs/ASTRA_ARCHITECTURE_SELECTION.md); implementation slices
and evidence are tracked in [ASTRA_IMPLEMENTATION_PLAN.md](../docs/ASTRA_IMPLEMENTATION_PLAN.md).
Depends on: [I-1, I-7, I-9](../docs/INVARIANTS.md),
[ADR-008](../DECISIONS/ADR-008-single-active-realtime-provider.md).

## Goal

Keep each reply's PCM, render reference and completion state in one ordered lifecycle. An
interruption must silence stale output before flush returns; normal completion must preserve and
drain every real sample, including a final partial 10-ms frame; capture must never upload an AEC
fragment once raw and again processed.

## Evidence and decisions

- FC-010's 2026-09-22 field log recorded queued output from about 5 KB to 237 KB while replies were
  already completed. At the configured 16/24-kHz mono PCM16 output rates, 237 KB represents about
  7.4/4.9 seconds of audio. This is historical evidence from an older APK, not a current performance
  baseline.
- Limit application-owned pending PCM to **500 ms** at the negotiated output sample rate, counting
  queued, remainder and partially written bytes. This bounds memory and the stale tail exposed by
  FC-010. The platform AudioTrack buffer is tracked separately and is not included in this app-queue
  limit. This is a safety ceiling, not a latency or acoustic-success claim.
- On overflow, clear and flush the current reply epoch, reject further PCM for that completed epoch,
  and report `AUDIO_PLAYBACK_FAILED` through the existing playback error path. Never discard an
  arbitrary chunk and continue as though the reply were intact. A later reply opens a newer epoch.
- Capture remains 100 ms until the gate, gain, wake/debug injection and Flex aggregation migrate
  together with elapsed-sample tests.

## Acceptance

1. A flush serialized against a blocked/short write returns only after old output is invalidated;
   no old-epoch PCM is submitted afterward.
2. Positive short writes retain their suffix; render reference contains each accepted byte prefix
   exactly once.
3. Explicit reply completion pads only a final sub-10-ms frame with silence and drains it; late
   audio for the completed epoch is rejected.
4. Pending application PCM never exceeds 500 ms at the negotiated rate. Overflow stops that reply,
   reports the playback error, and does not claim successful completion.
5. Capture teardown stops/unblocks and joins its worker before releasing its recorder or effects;
   timeout retains ownership and prevents overlapping capture.
6. Irregular capture/render frame partitions preserve sample order and count exactly once at 16 and
   24 kHz; pending, failure, discontinuity and backend-lifetime behavior are distinct.
7. Device verification separately measures platform tail and tests playback-only, near-end speech,
   double-talk, route/volume changes and repeated stop/restart. No acoustic PASS is inferred from
   compilation, attenuation RMS, or session IDs.

Automated cases are in [TEST_MATRIX.yaml](../TEST_MATRIX.yaml). Device cases remain pending in that
registry until bound to an APK and real-device evidence.
