package com.novadrive.app.voice

/**
 * How long a claim correction waits before it is sent to Gemini (moved out of [GeminiLiveClient]).
 *
 * A spoken command's tool call can arrive seconds after its reply, so its correction waits the full
 * configured grace. No call can follow a chat turn, so its correction waits only a moment: on the
 * owner's emulator run of 2026-10-03, a dropped chat reply left 24 s and 28 s of silence (9696ce5).
 */
object GeminiCorrectionGrace {
    /** A chat turn's correction: long enough for turnComplete to settle, no call to wait for. */
    const val CHAT_CORRECTION_GRACE_MS = 1_500L

    fun graceMs(callMayFollow: Boolean, configuredMs: Long): Long =
        if (callMayFollow) configuredMs else minOf(configuredMs, CHAT_CORRECTION_GRACE_MS)
}
