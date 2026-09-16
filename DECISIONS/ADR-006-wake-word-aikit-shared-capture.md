# ADR-006 — Wake word via iFlytek **AIKit**, sharing our existing capture stream

Status: **PREMISE CONFIRMED for MSC at API level (2026-09-16). Decision stands; vendor names change; one device test outstanding.**
Supersedes: [ADR-005](ADR-005-wake-word-mic-handover.md)

> **The SDK changed after this ADR was accepted** — AIKit was replaced by **MSC v1140** (`com.iflytek.cloud`, `VoiceWakeuper`); see [FINDINGS-2026-09-16-iflytek-msc-sdk.md](../SPECS/FINDINGS-2026-09-16-iflytek-msc-sdk.md). This ADR's decision rests on one premise: *the SDK accepts externally captured audio*. That was proven for AIKit and briefly became unverified for MSC, because the vendor demo has `AUDIO_SOURCE = "-1"` and `writeAudio(...)` **commented out** (`WakeDemo.java:146,155`).
>
> **Resolved 2026-09-16 by reading `Msc.jar` directly** *(evidence: `javap`)*:
>
> ```
> public int com.iflytek.cloud.VoiceWakeuper.writeAudio(byte[], int, int);
> public int com.iflytek.cloud.SpeechRecognizer.writeAudio(byte[], int, int);   // same signature; demo proves this path works
> public static final String com.iflytek.cloud.SpeechConstant.AUDIO_SOURCE;
> ```
>
> `VoiceWakeuper` exposes app-fed PCM with the identical signature to the dictation engine the demo exercises successfully. **So the shared-capture design survives the SDK swap** — `PcmAudioCapture` stays the single `AudioRecord` owner and feeds the detector. Only the vendor type names change.
>
> **Still outstanding (L5):** that the engine *honours* `AUDIO_SOURCE=-1` and detects reliably on fed audio. The API existing is necessary, not sufficient. If the device test fails, the fallback is MSC's `NOTIFY_RECORD_DATA` inversion (engine owns the mic and streams bytes back), which would be a new decision.

> ⚠️ **The SDK changed after this ADR was accepted.** The product owner replaced the **AIKit** bundle with the **MSC v1140** SDK (`com.iflytek.cloud`, `VoiceWakeuper`) — see [FINDINGS-2026-09-16-iflytek-msc-sdk.md](../SPECS/FINDINGS-2026-09-16-iflytek-msc-sdk.md).
>
> This ADR's decision — share our existing PCM stream, no microphone handover — rests entirely on the premise that *the SDK accepts externally captured audio*. That was proven for AIKit. For MSC it is **unverified**: `AUDIO_SOURCE = "-1"` and `writeAudio(...)` both appear in the wake demo (`WakeDemo.java:146,155`) but are **commented out**, though the identical pattern works for dictation in `IatDemo.java:140,154`.
>
> **Do not implement against this ADR until one on-device experiment settles it.** If MSC accepts app-fed PCM the decision stands with only the SDK names changed; if it does not, this ADR is superseded and the choice is between the `NOTIFY_RECORD_DATA` inversion and ADR-005's handover. The reasoning below remains valid; only the vendor premise is in question.
Spec: [SPEC-001](../SPECS/SPEC-001-wake-word.md) · Backlog: [B-003](../BACKLOG.md)

## Context

ADR-005 chose "the detector owns the microphone while idle and hands over on wake." That choice existed to resolve a **microphone-ownership conflict**: the iFlytek SDK was believed to capture device audio itself, with no way to accept audio we had already captured.

On 2026-09-16 the product owner supplied the actual SDK
(`AIKit_AEE_Android_IVW_e867a88f2_1.0.44_SDK2.2.17_rc6`). Reading its demo source establishes that **the premise was wrong**.

The SDK is **AIKit**, not the classic MSC SDK that the earlier research described. AIKit does not touch the microphone. The application owns the `AudioRecord` and pushes PCM into the engine:

```java
AiAudio aiAudio = AiAudio.get("wav").data(part).status(status).valid();
dataBuilder.payload(aiAudio);
AiHelper.getInst().write(dataBuilder.build(), aiHandle);
```

The demo happens to open its own `AudioRecord`, but that is demo convenience, not an SDK requirement. The engine's only input contract is a byte array of 16 kHz / 16-bit / mono PCM.

## Decision

**Use iFlytek AIKit for wake-word detection, and feed it from the capture stream we already own.** There is no microphone handover, because there is never a second microphone owner.

Concretely:

- `PcmAudioCapture` remains the **single** owner of the `AudioRecord`, as it is today.
- While **idle** (no provider session), capture runs and frames go **only** to the wake detector.
- On `func_wake_up`, the provider session starts and the same frames **fan out** to both the detector and the provider uplink.
- The wake phrase stays **你好小诺**, and the matching resource is already built and shipped in the SDK bundle.

## Consequences

**Better than ADR-005:**

- **No handover gap.** ADR-005 accepted losing the start of the first utterance while the mic changed hands. That loss disappears; there is no handover.
- **The wake word can be heard during a session**, which ADR-005 explicitly could not offer. Whether we *act* on it mid-session is a separate product choice, but the capability now exists.
- **One `AudioRecord`, one set of AEC/NS settings.** No risk of two components contending for concurrent capture under Android's narrow concurrency rules.

**Costs and risks:**

- The existing mic **gating** (frames dropped while 小诺 speaks, to stop self-transcription) would also starve the detector. The gate must therefore be applied **per consumer** — the provider uplink stays gated, the detector keeps receiving — otherwise the wake word goes deaf exactly when a user would barge in.
- Idle capture now runs whenever wake-word listening is enabled, which is a **battery and thermal cost that has not been measured**.
- AIKit's auth requires **network on first run only**; it is offline thereafter. A device that has never successfully authenticated cannot wake.

## What would justify revisiting this decision

- Measured battery cost of idle capture proving unacceptable in a real drive.
- A false-accept rate that cannot be brought down by `wdec_param_nCmThreshold` tuning.
- AIKit licensing terms that prove incompatible with shipping.
- Evidence that feeding the engine a `VOICE_COMMUNICATION` stream with AEC/NS attached materially degrades detection versus the plain `MIC` source the demo uses. **This is untested** and is the most likely technical reason to revisit.
