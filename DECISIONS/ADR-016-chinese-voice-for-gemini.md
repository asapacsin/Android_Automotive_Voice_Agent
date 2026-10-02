# ADR-016 — Gemini stays the agent; one Chinese TTS voice speaks

Status: **Accepted — direction** (2026-10-02). The planner decided under the owner's delegation: "you are going to find a solution for voice problem for me".
**Still open:** the vendor and the voice. The owner picks them by ear (`TTS-AUDITION-001`).
**Amends:** "no separate TTS" in [ADR-002](ADR-002-baidu-flex-default-provider.md) and [ADR-013](ADR-013-gemini-default-provider.md), and the audio-out half of [ADR-011](ADR-011-gemini-native-voice-path.md).
**Keeps:**
- ADR-013: Gemini is the single agent and the only provider, with no fallback across providers.
- ADR-014: guidance is spoken in the assistant's voice. That voice becomes the TTS voice.
- ADR-015: the domain servers.
- The claim gate and the rule of one voice per stage.

## Why

[BACKLOG B-034](../BACKLOG.md): the owner wants a 符玄-like voice, meaning young, clear and poised. He has rejected every Gemini voice by ear: "gemini voice are all old woman compare to chinese model and japanese". The measurements below show that no setting on Gemini's side can fix this:

| Lever | Measured 2026-10-02 (`VOICE-AB-001`, `VOICE-AB-002`) | Result |
| --- | --- | --- |
| Another prebuilt voice | The owner's app recordings ranged from 178 to 250 Hz. He rejected all ten. | closed |
| A voice instruction in the prompt | A read-aloud test with a forceful 「十七八岁少女」 line raised Leda from 222 to 258–276 Hz. In conversation with the app persona, a stronger 语音风格 line gave **no lift**: Leda 216–229 vs 211–242 Hz, Erinome 211–242 vs 222–267 Hz. | closed |
| Text output from Gemini Live, to feed our own TTS | Every Live model is native audio. `gemini-3.8-live` refuses `responseModalities: TEXT` (close 1007). | use the transcript instead |
| Gemini's own transcript of what it says | It arrives in the **same message** as the audio. The first clause comes 0–740 ms after the first audio, and generation runs about 3× faster than real time. | workable |
| Pitch-shifting on the device | Shifts beyond +2–3 semitones sound artificial, and the timbre stays the same. | rejected |

## Decision

1. **Gemini Live stays the only agent.** It hears the driver, finds the turn ends, calls the tools and handles barge-in. All of that is unchanged.
2. **One assistant voice: a single Chinese streaming TTS voice.** It speaks everything in a stage: model replies, scripted speech and navigation guidance (ADR-014). While it is active, Gemini's audio is received and discarded. The driver hears exactly one voice.
3. **The text comes from Gemini's output transcription**, streamed clause by clause into the TTS. Where the vendor offers a bidirectional streaming API, chunks are sent as they arrive.
4. **A new port, `AssistantVoice`**, takes three calls: speak a text chunk for a turn epoch, finish, and cancel. One vendor adapter sits behind it at a time (the ADR-008 pattern; no dormant adapters). The audio goes through the existing playback port, so the echo-cancellation reference does not change.
5. **The owners do not change:**
   - `DriverTurn` still decides what may be claimed. It now holds *text* before TTS rather than audio, which is simpler.
   - Turn-taking still decides barge-in. Gemini's `interrupted` signal or new driver speech cancels both the TTS stream and the playback.
   - Guidance goes to the TTS verbatim, so the model cannot reword it. That removes a SPEC-018 risk.
6. **There is no fallback to Gemini's own voice, because that would be a second voice.** If the TTS fails, the subtitle still shows, the reply is not spoken, and the screen says the voice service is unavailable. Spoken guidance falls back to Amap exactly as ADR-014 already allows.
7. **The vendor is chosen by ear.** The owner runs `tools/tts-audition/audition.py` with his own key. The suggested order:
   - Volcengine / Doubao: the largest catalogue of young Chinese voices, a bidirectional streaming API built for LLM text, and short paths from mainland China.
   - MiniMax: also offers *voice design*, a new synthetic voice made from a text description.
   - Baidu TTS: the voices the owner already knows (4196, 6562).
8. **Keys** are entered by the owner in developer settings and stored in the Keystore, like the Gemini key. Agents never enter them.
9. **The TTS voice becomes the default only after the device gate passes** (`TTS-VOICE-DEVICE-001`). Until then it is a developer setting and Gemini's audio stays the default.
10. **Constraint (B-034):** stock or designed voices only. Never clone or imitate a real character's or voice actor's voice.

## Costs

- **Latency per reply:** about 0.2–1.0 s more. That is the time to the first transcribed clause (0–0.74 s measured) plus the vendor's first audio packet, still to be measured on the device.
- **Billing:** two bills. Gemini's audio is still generated and charged.
- **Barge-in:** must be re-proved for audio we synthesise ourselves.
- **Reach:** a second network dependency. The mainland vendors are fast inside China, and Gemini's reach from China is unchanged.

## Alternatives rejected

- **Baidu Flex as the provider (option A).** It has young voices, but Baidu is being retired (ADR-013 step 2), its tool calling is weaker, and the owner heard noise on 2026-09-30. It is still a zero-code stopgap for a demo: pick Baidu and voice 6562 in developer settings.
- **A Chinese end-to-end realtime model (option C).** Its voice catalogue is small, its tool calling is unknown, and the whole voice stack would need re-validating. It stays the ADR-010 G-2 path in case Gemini's *reach* fails, not for the voice.
- **Prompt or pitch tricks (option D and the voice line).** Measured above: no effect, or artificial.

## What would justify revisiting this

- Google ships young Mandarin voices, or custom voices, for Live.
- The device gate fails the latency budget (p50 extra delay > 1.0 s) or barge-in.
- The owner prefers Baidu's own realtime voice to every TTS voice in the audition.

## Steps

0. The owner auditions voices (`TTS-AUDITION-001`) and supplies a key for the chosen vendor.
1. SPEC-019:
   - the `AssistantVoice` port and clause segmentation;
   - discarding Gemini audio and holding text in `DriverTurn`;
   - barge-in cancellation and routing guidance to the TTS;
   - the developer setting;
   - JVM tests with a fake voice.
2. The vendor adapter, with golden tests of its protocol and a live text-to-audio probe.
3. An emulator run on the short route, then `TTS-VOICE-DEVICE-001` on the phone. After that, the TTS voice becomes the default and the docs that say "no separate TTS" are updated.
