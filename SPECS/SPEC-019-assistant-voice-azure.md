# SPEC-019 — 小诺 speaks in the owner's chosen voice (Azure `zh-CN-XiaoyiNeural`)

Status: **Step 1 implemented 2026-10-02**, at L1/L2: JVM tests and an APK build. It is behind a developer setting and off by default. Steps 2–3 wait on the owner's Azure key.
Source: [ADR-016](../DECISIONS/ADR-016-chinese-voice-for-gemini.md) · [BACKLOG B-034](../BACKLOG.md).
Depends on:
- ADR-013 (Gemini is the single agent);
- the claim gate (`DriverTurn`, `DriverTurnPipeline`);
- SPEC-018 (GUIDANCE prompt turns);
- invariants I-1 and I-8.

> **Reaching Done on this SPEC does not end the run.** Reconcile the registry and the backlog row, then run `python scripts/discover_work.py`.

## Goal

The driver hears one voice, Xiaoyi, for every reply, confirmation and guidance prompt. Gemini still listens, decides, calls the tools and handles barge-in. Its own audio is never played while the assistant voice is on.

The speaking styles (嗲 / 傲娇 / 温柔 / 元气) change both the words and the delivery of the same voice.

## Design (as built)

```
Gemini Live ── audio (discarded) ──┐
             └ outputTranscription ─┴─ GeminiLiveClient(speechTextEvents) ─ SpeechText ─▶ claim gate (DriverTurnPipeline)
                                                                                         │ released
AssistantVoiceRevoicer (one ordered lane) ◀──────────────────────────────────────────────┘
   ClauseSegmenter → AssistantVoice (AzureSpeechVoice, SSML express-as) → AudioDelta(24 kHz PCM16)
   → VoiceSessionController → PcmAudioPlayer (unchanged, AEC reference unchanged)
```

| Rule | Owner | Test |
| --- | --- | --- |
| R1. Words reach the voice only after the claim gate released them. A held claim drops its words with its audio. | `DriverTurnPipeline` (SpeechText is holdable) | `GeminiLiveClientTest.aFalseClaimsWordsAreDroppedWithItsAudio` |
| R2. Only words before `generationComplete` are spoken, the same rule as for audio after the verdict. | `GeminiLiveClient.speakText` | `…aReleasedReplyCarriesItsWordsAsSpeechTextInOrder` |
| R3. A GUIDANCE turn's words are spoken unless the turn was voided. | `GeminiLiveClient.speakText(guidance)` | `AssistantVoiceRevoicerTest.aGuidanceTurnIsSpokenBeforeItCompletes` |
| R4. The provider's event order is kept. The voice's audio sits between its reply's `ResponseStarted` and its `AudioDone`/`ResponseDone`. | `AssistantVoiceRevoicer` | `…theReplyIsSpokenInTheVoice…`, `…otherEventsKeepTheirOrder` |
| R5. `Interrupted`, `Error` and `Closed` cancel the clause in flight at once and discard queued words. `SpeechStarted`/`SpeechStopped` bypass the lane. | `AssistantVoiceRevoicer`, `AzureSpeechVoice` (`call.cancel`) | `…anInterruptCancels…`, `…bargeInEventsBypass…`, `AzureSpeechVoiceTest.cancellingMidStream…` |
| R6. On a voice failure, the subtitle stays and the rest of that reply is silent. The driver sees the transient VOICE card once per reply. There is never a fallback to Gemini's voice. | `AssistantVoiceRevoicer`, `AssistantVoiceNotices` → `MainActivity` | `…aFailureIsReportedOnce…` |
| R7. Style mapping: DEFAULT → none, 嗲 → affectionate 1.2, 傲娇 → disgruntled 0.6, 温柔 → gentle 1.0, 元气 → cheerful 1.2. | `AzureSpeechVoice` (`azureStyleFor`) | `AzureSpeechVoiceTest.ssmlPerStyle` |
| R8. The key lives in the Keystore and is never logged or displayed. Logs carry codes, counts and timings only. | `AzureSpeechSettingsRepository`, `AzureSpeechVoice` | `AzureSpeechSettingsTest`, `AzureSpeechVoiceTest`, `:behavior-test` secret scan |
| R9. Off by default. With the switch off, a session is exactly as before: no SpeechText, no re-voicer. If the switch is on but the setup is incomplete, the start fails with a CONFIG card (`AZURE_*`). Gemini's voice is never used silently in that case. | `AzureSpeechSettingsValidator`, `MainActivity.chooseSessionConfig` | `AzureSpeechSettingsTest`, `GeminiLiveClientTest.withoutAnAssistantVoice…` |

## Steps

1. **Done (L1/L2):**
   - the port and the re-voicer;
   - the client and the gate;
   - the Azure adapter, settings, wiring and the VOICE card.
2. **Owner:**
   - Create an Azure Speech resource. F0 is enough to start; use an Azure China region if needed.
   - Check the key with `tools/tts-audition/audition.py azure`.
   - Enter the key and region in 开发者设置 → 小诺的声音, and switch it on.
3. **Agents:**
   - An emulator run on the short route (`TTS-VOICE-EMU-001`). Measure `assistant_voice_first_audio` against Gemini's first audio, barge-in, guidance and a 傲娇 turn.
   - Then the phone (`TTS-VOICE-DEVICE-001`).
   - After both pass: make it the default (ADR-016 §9) and update the docs that say "no separate TTS".

## Not in this step

- **Speaking guidance verbatim, without the model.** ADR-016 wants this. It needs a direct path from the `SpeechArbiter` to the voice, which bypasses the GUIDANCE prompt turn.
- **The DragonHD Xiaoyi variant.** It waits for a latency measurement.
- **Prefetching the next clause while the current one plays.** This would only be needed if the gaps between clauses are audible.
