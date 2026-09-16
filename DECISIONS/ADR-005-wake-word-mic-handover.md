# ADR-005 — Wake word via iFlytek with microphone handover

Status: **Superseded by [ADR-006](ADR-006-wake-word-aikit-shared-capture.md)** (2026-09-16, the same day it was accepted)

> **Why it was superseded, in one line:** this decision solved a microphone-ownership conflict that **does not exist**. The SDK the product owner actually supplied is **AIKit**, which accepts an external PCM stream, so there is no conflict and no handover is needed.
>
> The decision itself was sound given what was known; the *premise* was wrong. It was drawn from vendor documentation for the **MSC** SDK rather than from the delivered artifact. This is preserved as a record of why the handover design was once chosen — **do not implement it.**

## Context

Starting a session required pressing 按住麦克风开始. In a car that is the wrong interaction — the driver's hands should stay on the wheel. Everything else already works hands-free once a session runs; starting it was the last manual step.

Research ([SPEC-001](../SPECS/SPEC-001-wake-word.md)) established that an offline Chinese wake-word engine is available: iFlytek's 语音唤醒 SDK runs fully offline after APPID activation, supports up to 8 custom phrases of 4–6 Chinese characters, is created self-service in their console, and is licensed **per application** with no device-count limit. 你好小诺 is exactly 4 characters and fits. The alternatives were worse: Porcupine charges per device and needs online activation, Snowboy is abandoned, and Baidu/思必驰 offer no self-service path.

The blocker was not availability but **microphone ownership**. The iFlytek SDK captures audio itself and exposes no API to accept an external PCM stream, while `PcmAudioCapture` already owns a `VOICE_COMMUNICATION` `AudioRecord` with AEC/NS attached and gating. Two components cannot both own the microphone.

> ⚠️ **This paragraph is factually wrong and is the reason this ADR was superseded.** Verified against the delivered SDK on 2026-09-16: AIKit does **not** capture audio. The application owns the `AudioRecord` and pushes PCM into the engine with `AiHelper.write(...)`. There is no ownership conflict. See [ADR-006](ADR-006-wake-word-aikit-shared-capture.md).

## Decision

**Resolution 1 — the detector owns the microphone while idle and hands over on wake.**

- While no session is active, the iFlytek detector holds capture and listens for 你好小诺.
- On detection it releases the microphone, `PcmAudioCapture` starts, and a realtime session begins exactly as the button does today.
- When the session ends, the detector reclaims the microphone.
- The button remains as a reliable fallback and is not removed.

The wake phrase is **你好小诺**. 「小诺」 alone is 2 characters, below the engine's minimum, and too acoustically thin to discriminate against navigation guidance and music.

## Consequences

- Hands-free activation from idle, which is the demand.
- **The wake word cannot be heard during an active session**, because the SDK owns capture exclusively while listening. Accepted: a running session already hears the driver.
- A handover gap exists between release and capture start; it must not drop the first utterance.
- Adds a vendor dependency and `libmsc.so` for armeabi-v7a and arm64-v8a.
- Commercial pricing is unpublished and must be obtained before shipping. Trial is 10 installs / 90 days.
- False accepts are the main quality risk, since Amap guidance and our own music both play into the same microphone. Must be measured on device (L5), not assumed.

## What would justify revisiting this decision

- Measured false-accept rate on the road proving unacceptable despite the 4-character phrase.
- iFlytek pricing turning out to be prohibitive, which would push toward training our own keyword spotter over the existing PCM stream (resolution 3 in SPEC-001) — more work, no licence cost, and it would also work during a session.
- Android exposing a third-party always-on hotword API, removing the ownership conflict entirely.
- A decision to require wake-word detection *during* an active session, which this design cannot provide.
