# Architecture Decision Records

These record **major decisions that are already settled**, so future agents do not reopen them every session.

If you are about to argue for a different provider, a different navigation strategy, or a different model-cost policy — read the relevant ADR first. If it still looks wrong, the ADR's "What would justify revisiting this decision" section tells you what evidence would actually change the answer.

## Status values

- **Accepted** — currently in force.
- **Superseded** — replaced by a later ADR, which is named in the file.
- **Proposed** — under consideration, not yet in force.

## Index

| ADR | Title | Status |
| --- | --- | --- |
| [ADR-001](ADR-001-direct-provider-connection.md) | Direct on-device provider connection | Accepted |
| [ADR-002](ADR-002-baidu-flex-default-provider.md) | Baidu Flex is the default realtime provider | Accepted |
| [ADR-003](ADR-003-amap-navigation-delegation.md) | Delegate navigation to Amap via coordinate deep link | **Superseded by ADR-007** |
| [ADR-004](ADR-004-model-tiering.md) | Expensive models for architecture and checkpoints, not routine coding | Accepted |
| [ADR-005](ADR-005-wake-word-mic-handover.md) | Wake word via iFlytek with microphone handover | **Superseded by ADR-006** |
| [ADR-006](ADR-006-wake-word-aikit-shared-capture.md) | Wake word via iFlytek AIKit, sharing our existing capture stream | Accepted |
| [ADR-007](ADR-007-embedded-amap-navigation-sdk.md) | Embed the Amap Navigation SDK; the app owns the screen | Accepted |

## Relationship to `docs/DECISIONS.md`

`docs/DECISIONS.md` holds the original Checkpoint-1 decision log (D1–D13). It remains useful history, but **two of its entries are superseded by the ADRs here**:

- **D12** ("Baidu E2E credentials and protocol stay behind the backend") — superseded by ADR-001.
- **D13** ("Qwen Flash default") — superseded by ADR-002.

Where `docs/DECISIONS.md` and this folder disagree, **this folder wins**. The remaining D1–D11 entries are still accurate and are not duplicated here.
