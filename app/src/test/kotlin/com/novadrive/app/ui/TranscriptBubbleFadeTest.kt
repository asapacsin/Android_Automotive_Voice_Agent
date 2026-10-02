package com.novadrive.app.ui

import com.novadrive.ingress.realtime.VoiceUiState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** B-035: the last exchange fades after a quiet period, and never while the conversation is live. */
class TranscriptBubbleFadeTest {
    private val idle = VoiceUiState.LISTENING

    /** Polls once a second from [fromMs] to [toMs] inclusive; true if any poll cleared the bubble. */
    private fun TranscriptBubbleFade.pollUntil(fromMs: Long, toMs: Long, state: VoiceUiState = idle, held: Boolean = false): Boolean =
        (fromMs..toMs step TRANSCRIPT_FADE_POLL_MS).any { tick(it, state, held) }

    @Test
    fun aQuietExchangeClearsAfterTheFadeTime() {
        val fade = TranscriptBubbleFade()
        fade.lineShown()
        assertFalse(fade.pollUntil(1_000, 10_000), "quiet since 1 s: not yet at 10 s")
        assertTrue(fade.tick(11_000, idle, held = false))
        assertFalse(fade.showing)
    }

    @Test
    fun itNeverClearsWhileTheConversationIsLive() {
        for (state in listOf(VoiceUiState.USER_SPEAKING, VoiceUiState.THINKING, VoiceUiState.SPEAKING)) {
            val fade = TranscriptBubbleFade()
            fade.lineShown()
            assertFalse(fade.pollUntil(1_000, 60_000, state), "$state")
            assertTrue(fade.showing)
        }
    }

    @Test
    fun itNeverClearsWhileHeldByPlayoutOrAQuestion() {
        val fade = TranscriptBubbleFade()
        fade.lineShown()
        assertFalse(fade.pollUntil(1_000, 60_000, held = true))
        // Released: the full quiet period starts from the release, not from the line.
        assertFalse(fade.pollUntil(61_000, 70_000))
        assertTrue(fade.tick(71_000, idle, held = false))
    }

    @Test
    fun aNewLineRestartsTheQuietPeriod() {
        val fade = TranscriptBubbleFade()
        fade.lineShown()
        assertFalse(fade.pollUntil(1_000, 9_000))
        fade.lineShown() // the driver's next question arrives at 9.5 s
        assertFalse(fade.pollUntil(10_000, 19_000), "the older wait must not clear the newer exchange")
        assertTrue(fade.tick(20_000, idle, held = false))
    }

    @Test
    fun aBusyMomentRestartsTheQuietPeriod() {
        val fade = TranscriptBubbleFade()
        fade.lineShown()
        assertFalse(fade.pollUntil(1_000, 8_000))
        assertFalse(fade.tick(9_000, VoiceUiState.USER_SPEAKING, held = false))
        assertFalse(fade.pollUntil(10_000, 19_000))
        assertTrue(fade.tick(20_000, idle, held = false))
    }

    @Test
    fun anErrorCardOrPlaceholderIsNotTheFadesToClear() {
        val fade = TranscriptBubbleFade()
        fade.lineShown()
        fade.replaced()
        assertFalse(fade.pollUntil(1_000, 60_000))
    }

    @Test
    fun aSleepingOrDisconnectedSessionStillClears() {
        for (state in listOf(VoiceUiState.DISCONNECTED, VoiceUiState.ERROR, VoiceUiState.RECONNECTING)) {
            val fade = TranscriptBubbleFade()
            fade.lineShown()
            assertTrue(fade.pollUntil(1_000, 11_000, state), "$state")
        }
    }
}
