# ADR-014 — Navigation guidance is spoken by the assistant's voice; Amap's voice only as a fallback

Status: **Accepted** (2026-09-30, product owner: "the amap stuff would be mute and instead
everything come from the amap would handle by gemini", then "amap can allow if gemini unable to
function"). One sub-question open (G-2 below).
Amends: the guidance-voice row of `docs/ARCHITECTURE.md` (`AmapGuidanceVoice`, 2026-09-17).
Builds on: [ADR-013](ADR-013-gemini-default-provider.md) (Gemini default), B-029 O-2 (one voice
per stage).

## Decision

1. **Amap's built-in voice is muted** (`setUseInnerVoice(false)`) while the realtime session can speak.
2. **Every guidance sentence Amap produces** (`onGetNavigationText`, discarded today) is spoken by
   the realtime model in the assistant's own voice, through the existing app-initiated speech path
   (`VoiceSessionGateway.speak`, as the camera look already does), with a verbatim instruction.
3. **Fallback — owner decision:** when the model cannot speak it (no connection, session failed,
   timeout before audio starts), the same sentence is played by Amap's offline voice. Safety over
   the one-voice rule; this is the only exception to it.
4. **Guidance has priority.** A guidance prompt pre-empts assistant chatter; the speech arbiter owns
   that ordering. The mic stays gated while guidance plays, whichever voice speaks it (P3).
5. **Fidelity is checked, not trusted.** The model's output transcription is compared with the
   Amap text on direction words and distances; a mismatch is logged (no text in the log) and counted.
   The model's words are never the source of the instruction — Amap's text is (I-1 in spirit).

## Open

- **G-1 (measure):** time from `onGetNavigationText` to first model audio on the phone; the
  fallback timeout is set from it.
- **G-2 (owner):** keep the Gemini session connected for the whole navigation (continuous cost),
  or let a sleeping assistant hand guidance to Amap's voice (fallback becomes routine while asleep).
  Recommended: stay connected while navigating.
