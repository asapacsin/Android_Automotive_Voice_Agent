package com.novadrive.app.voice

import com.novadrive.app.voice.SpeechArbiter.Focus
import com.novadrive.app.voice.SpeechArbiter.Reply
import com.novadrive.app.voice.SpeechArbiter.Uplink
import com.novadrive.app.voice.SpeechArbiter.Volume
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-012 A4: inputs that arrive together or in sequence. The static table in
 * `SpeechRulesCharacterizationTest` covers every simultaneous pair; these cover time.
 */
class SpeechArbiterPairTest {
    private var now = 1_000_000L
    private val arbiter = SpeechArbiter(clock = { now })

    @Test
    fun guidanceStartingWhileFocusIsLostTransientlyStillHoldsAndClosesTheMic() {
        arbiter.onFocus(Focus.TRANSIENT_LOSS)
        arbiter.onGuidanceSpeaking(true)
        assertEquals(Reply.HOLD, arbiter.reply())
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        arbiter.onGuidanceSpeaking(false)
        now += 600
        assertEquals(Reply.HOLD, arbiter.reply(), "focus is still lost")
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun aLostGuidanceEndReopensTheMicAfterTwentySeconds() {
        arbiter.onGuidanceSpeaking(true)
        now += 19_999
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        now += 2
        assertEquals(Uplink.OPEN, arbiter.uplink(), "a lost callback must not leave the assistant deaf")
    }

    @Test
    fun guidanceResumingInTheTailKeepsTheMicClosed() {
        arbiter.onGuidanceSpeaking(true)
        arbiter.onGuidanceSpeaking(false)
        now += 300
        arbiter.onGuidanceSpeaking(true)
        now += 400
        assertEquals(Uplink.CLOSED, arbiter.uplink())
    }

    @Test
    fun anAnswerTheDriverAskedForIsSpokenWhileNavigatingAndNotCutOff() {
        arbiter.onNavigating(true)
        assertEquals(Reply.DROP, arbiter.reply(), "P1: unprompted speech is dropped")
        arbiter.onDriverRequest()
        assertEquals(Reply.PLAY, arbiter.reply())
        // A long reply keeps its own window open while its audio keeps arriving.
        repeat(5) {
            now += 8_000
            arbiter.onReplyAudio()
            assertEquals(Reply.PLAY, arbiter.reply())
        }
        now += 10_001
        assertEquals(Reply.DROP, arbiter.reply())
    }

    @Test
    fun speakingCannotReopenAClosedWindow() {
        arbiter.onNavigating(true)
        arbiter.onReplyAudio()
        assertEquals(Reply.DROP, arbiter.reply())
    }

    @Test
    fun aPermittedReplyIsNotDuckedDuringNavigationButIsOtherwise() {
        arbiter.onFocus(Focus.DUCK)
        assertEquals(Volume.DUCK, arbiter.volume())
        arbiter.onNavigating(true)
        arbiter.onConfirmation()
        assertEquals(Volume.FULL, arbiter.volume())
    }

    @Test
    fun aPermanentLossBeatsEverything() {
        arbiter.onNavigating(true)
        arbiter.onDriverRequest()
        arbiter.onFocus(Focus.PERMANENT_LOSS)
        assertEquals(Reply.DROP, arbiter.reply())
        arbiter.onFocus(Focus.HELD)
        assertEquals(Reply.PLAY, arbiter.reply())
    }

    @Test
    fun stoppingNavigationClosesTheWindowAndResetClearsEverything() {
        arbiter.onNavigating(true)
        arbiter.onDriverRequest()
        arbiter.onNavigating(false)
        arbiter.onNavigating(true)
        assertEquals(Reply.DROP, arbiter.reply(), "a window from an earlier drive does not carry over")
        arbiter.onGuidanceSpeaking(true)
        arbiter.onFocus(Focus.TRANSIENT_LOSS)
        arbiter.reset()
        assertEquals(Reply.PLAY, arbiter.reply())
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }
}
