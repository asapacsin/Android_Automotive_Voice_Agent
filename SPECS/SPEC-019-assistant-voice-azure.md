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
| R1. Words reach the voice only after the claim gate released them. A held claim drops its words with its audio. With the voice on, Gemini's audio is never emitted at all, so the gate holds only words and the hold window is never shorter than without the voice. | `DriverTurnPipeline` (SpeechText is holdable), `GeminiLiveClient` | `GeminiLiveClientTest.aFalseClaimsWordsAreDroppedWithItsAudio`, `…theHoldWindowWithTheVoiceIsNoShorterThanWithout`, `…aSupersededHeldReplysWordsAreDropped`, `…aClientCancelledReplysWordsFollowItsAudio`, `…aCleanReplysWordsFollowItsAudio` |
| R2. Only words before `generationComplete` are spoken, the same rule as for audio after the verdict. | `GeminiSpeechText` | `…aReleasedReplyCarriesItsWordsAsSpeechTextInOrder` |
| R3. A GUIDANCE turn's words are spoken unless the turn was voided. Its VOIDED cancels the clause in flight at once and drops its queued and half words. An earlier turn's late transcript is never spoken as a prompt's words (cleared when the prompt is sent). | `GeminiSpeechText`, `GeminiLiveClient.sendPrompt`, `AssistantVoiceRevoicer` | `…aDriverOnsetDuringGuidanceStopsItsWords`, `…anEarlierTurnsLateWordsAreNotSpokenInAGuidanceTurn`, `AssistantVoiceRevoicerTest.anOpenGuidanceTurnVoidedStopsItsWords`, `…aPromptVoidedBeforeItOpenedDoesNotCutTheReplyBeingSpoken`, `…aGuidanceTurnIsSpokenBeforeItCompletes` |
| R4. The provider's event order is kept. Only reply boundaries wait for the voice: ResponseStarted, prompt OPENED/COMPLETED, AudioDone and ResponseDone. A reply's audio therefore sits inside its reply stamp. A ToolCall and other events are forwarded at once and never wait for synthesis. | `AssistantVoiceRevoicer` | `…theReplyIsSpokenInTheVoice…`, `…otherEventsKeepTheirOrder`, `…aToolCallIsNotDelayedByAClauseBeingSynthesised`, `…aLeftoverHalfClauseIsNotSpokenIntoTheNextReply` |
| R5. **The session decides barge-in; the voice follows.** Every playback flush (a qualified barge-in, `cancelCurrentResponse`, a server Interrupted, a reconnect) reaches the provider as `onPlaybackFlushed` and stops the voice. A client cancel (P40) stops it too. Driver speech over a reply that is not audible yet drops that reply: nothing is playing, so it cannot be echo. `Interrupted`, `Error` and `Closed` cancel on arrival. `SpeechStarted`/`SpeechStopped` bypass the lane. | `VoiceSessionController.invalidatePlaybackEpoch` → `RealtimeVoiceProvider.onPlaybackFlushed`; `GeminiLiveProvider`; `AssistantVoiceRevoicer.cancelCurrentReply`; `AzureSpeechVoice` (`call.cancel`) | `LocalSpeechActivityTest.aQualifiedBargeInTellsTheProvider…`, `…cancelCurrentResponseTellsTheProvider…`, `GeminiLiveProviderVoiceTest` (5), `AssistantVoiceRevoicerTest.anInterruptCancels…`, `…cancelCurrentReplyDropsQueuedWords…`, `AzureSpeechVoiceTest.cancellingMidStream…` |
| R6. On a voice failure, the subtitle stays and the rest of that reply is silent. The driver sees the transient VOICE card once per reply. There is never a fallback to Gemini's voice. Each clause has an overall deadline (`CALL_TIMEOUT_S` = 8 s, code `AZURE_TTS_TIMEOUT`), so a trickling or unreachable service cannot stall the lane. | `AssistantVoiceRevoicer`, `AssistantVoiceNotices` → `MainActivity`, `AzureSpeechVoice` | `…aFailureIsReportedOnce…`, `AzureSpeechVoiceTest.aTricklingResponseTimesOutWithAStableCode` |
| R7. Style mapping: DEFAULT → none, 嗲 → affectionate 1.2, 傲娇 → disgruntled 0.6, 温柔 → gentle 1.0, 元气 → cheerful 1.2. | `AzureSpeechVoice` (`azureStyleFor`) | `AzureSpeechVoiceTest.ssmlPerStyle` |
| R8. The key lives in the Keystore and is never logged or displayed. Logs carry codes, counts and timings only. | `AzureSpeechSettingsRepository`, `AzureSpeechVoice` | `AzureSpeechSettingsTest`, `AzureSpeechVoiceTest`, `:behavior-test` secret scan |
| R9. Off by default. With the switch off, a session is exactly as before: no SpeechText, no re-voicer, Gemini audio as before. If the switch is on but the setup is incomplete, the start fails with a CONFIG card (`AZURE_*`). Gemini's voice is never used silently in that case. | `AzureSpeechSettingsValidator`, `MainActivity.chooseSessionConfig`, `RealtimeProviderFactory` | `AzureSpeechSettingsTest`, `GeminiLiveClientTest.withoutAnAssistantVoice…`, `GeminiLiveProviderVoiceTest.withoutAVoiceTheProviderDoesNotRevoice` |

**Port shape.** ADR-016 §4 sketched "speak a chunk for a turn epoch, finish, cancel". The built port is `synthesize(clause, style, onPcm)`. The turn epochs and cancellation live in the re-voicer, and coroutine cancellation is the cancel. The behaviour is the same, with one less stateful vendor object.

**Review.** The independent review (2026-10-02, REVISE) required six changes; all are taken (R1, R3, R4, R5, R6 and the tests above).

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
