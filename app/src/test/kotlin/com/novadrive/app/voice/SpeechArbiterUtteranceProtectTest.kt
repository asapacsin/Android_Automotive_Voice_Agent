package com.novadrive.app.voice

import com.novadrive.app.voice.SpeechArbiter.Uplink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-012 R0: guidance must not cut off the driver mid-utterance. Replays the owner's demo of
 * 2026-09-28 (times in ms from 08:37:00), where 「有点热」 was lost because guidance started 1.9 s
 * after `speech_started` and closed the uplink.
 */
class SpeechArbiterUtteranceProtectTest {
    private var now = 0L
    private val arbiter = SpeechArbiter(clock = { now })

    private fun at(ms: Long) { now = ms }

    @Test
    fun loggedSequenceKeepsTheDriverUtteranceOpen() {
        // Three guidance prompts before the driver speaks: the uplink is gated as before (P3).
        at(3_626); arbiter.onGuidanceSpeaking(true)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        at(10_851); arbiter.onGuidanceSpeaking(false)
        at(11_401); assertEquals(Uplink.OPEN, arbiter.uplink(), "the gate reopens after play_end + tail")
        at(20_616); arbiter.onGuidanceSpeaking(true)
        at(23_200); arbiter.onGuidanceSpeaking(false)
        at(23_713); assertEquals(Uplink.OPEN, arbiter.uplink())

        at(27_051); arbiter.onDriverSpeaking(true) // input_audio_buffer.speech_started, 「有点热」
        at(28_930); arbiter.onGuidanceSpeaking(true) // nav_guidance_play_start chars=14
        assertEquals(Uplink.OPEN, arbiter.uplink(), "guidance must not cut the utterance in progress")
        assertTrue(arbiter.uplinkProtected())

        at(29_900); arbiter.onDriverSpeaking(false) // speech_stopped: the turn is captured
        assertEquals(Uplink.CLOSED, arbiter.uplink(), "once the turn is captured, P3 applies again")
        assertFalse(arbiter.uplinkProtected())

        at(33_000); arbiter.onGuidanceSpeaking(false)
        at(33_499); assertEquals(Uplink.CLOSED, arbiter.uplink())
        at(33_501); assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun protectionIsBoundedFromTheFirstPrompt() {
        arbiter.onDriverSpeaking(true)
        arbiter.onGuidanceSpeaking(true)
        at(SpeechArbiter.UTTERANCE_PROTECT_MAX_MS - 1)
        assertEquals(Uplink.OPEN, arbiter.uplink())
        // A back-to-back prompt does not extend the bound.
        arbiter.onGuidanceSpeaking(false)
        arbiter.onGuidanceSpeaking(true)
        at(SpeechArbiter.UTTERANCE_PROTECT_MAX_MS)
        assertEquals(Uplink.CLOSED, arbiter.uplink(), "a server that never says speech_stopped cannot hold it open")
    }

    @Test
    fun speechThatStartsAfterGuidanceDoesNotOpenTheUplink() {
        arbiter.onGuidanceSpeaking(true)
        arbiter.onDriverSpeaking(true) // e.g. a server echo turn while gated: P3 must hold
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        assertFalse(arbiter.uplinkProtected())
    }

    @Test
    fun aFinishedUtteranceDoesNotProtectTheNextPrompt() {
        arbiter.onDriverSpeaking(true)
        arbiter.onGuidanceSpeaking(true)
        arbiter.onDriverSpeaking(false)
        arbiter.onGuidanceSpeaking(false)
        at(2_000)
        arbiter.onGuidanceSpeaking(true)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
    }

    @Test
    fun guidanceStillHoldsTheReplyWhileTheUplinkIsProtected() {
        arbiter.onDriverSpeaking(true)
        arbiter.onGuidanceSpeaking(true)
        assertEquals(SpeechArbiter.Reply.HOLD, arbiter.reply(), "R1 for reply audio is unchanged")
    }

    @Test
    fun gateNeverStaysClosedAfterPlayEnd() {
        arbiter.onGuidanceSpeaking(true)
        at(5_000); arbiter.onGuidanceSpeaking(false)
        at(5_000 + SpeechArbiter.TAIL_MS); assertEquals(Uplink.OPEN, arbiter.uplink())
        at(60_000); assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun resetClearsProtection() {
        arbiter.onDriverSpeaking(true)
        arbiter.reset()
        arbiter.onGuidanceSpeaking(true)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
    }

    /**
     * Emulator replay 2026-09-28 15:36: the session went to SLEEP with the UI state still
     * USER_SPEAKING, and the next prompt logged `decision=OPEN reason=driver_utterance` for an
     * utterance that no longer existed. Sleep now reports the driver as not speaking.
     */
    @Test
    fun anUtteranceEndedBySleepDoesNotProtectTheNextPrompt() {
        at(1_000); arbiter.onDriverSpeaking(true)
        at(30_000); arbiter.onDriverSpeaking(false) // listening ACTIVE->SLEEP
        at(40_000); arbiter.onGuidanceSpeaking(true)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        assertFalse(arbiter.uplinkProtected())
    }
}
