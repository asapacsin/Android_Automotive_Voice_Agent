package com.novadrive.app.voice

import com.novadrive.app.voice.SpeechArbiter.Reply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-012 A5: R6a, the workload hold before a manoeuvre. */
class SpeechArbiterWorkloadTest {
    private var now = 1_000_000L
    private val arbiter = SpeechArbiter(clock = { now })

    private fun askWhileNavigating() {
        arbiter.onNavigating(true)
        arbiter.onDriverRequest()
    }

    @Test
    fun aPermittedReplyIsHeldUnder150mAndPlaysAtOrAbove() {
        askWhileNavigating()
        arbiter.onManeuverDistance(150)
        assertEquals(Reply.PLAY, arbiter.reply())
        arbiter.onManeuverDistance(149)
        assertEquals(Reply.HOLD, arbiter.reply())
    }

    @Test
    fun passingTheManoeuvreReleasesTheHold() {
        askWhileNavigating()
        arbiter.onManeuverDistance(120)
        now += 2_000
        arbiter.onManeuverDistance(10)
        assertEquals(Reply.HOLD, arbiter.reply())
        arbiter.onManeuverDistance(800) // next step: the distance jumps up
        assertEquals(Reply.PLAY, arbiter.reply())
    }

    @Test
    fun passingIntoAnotherCloseManoeuvreStartsAFreshHold() {
        askWhileNavigating()
        arbiter.onManeuverDistance(40)
        arbiter.onManeuverDistance(130) // passed; the next turn is also close
        assertEquals(Reply.HOLD, arbiter.reply())
        now += 7_999
        assertEquals(Reply.HOLD, arbiter.reply())
        now += 1
        assertEquals(Reply.PLAY, arbiter.reply())
    }

    @Test
    fun theHoldIsCappedAtEightSecondsEvenIfTheDistanceStopsUpdating() {
        askWhileNavigating()
        arbiter.onManeuverDistance(100)
        now += 7_999
        assertEquals(Reply.HOLD, arbiter.reply())
        now += 1
        assertEquals(Reply.PLAY, arbiter.reply())
        arbiter.onManeuverDistance(60) // same manoeuvre, still approaching: not held again
        assertEquals(Reply.PLAY, arbiter.reply())
    }

    @Test
    fun aHeldAnswerIsNotDroppedByTheP1WindowExpiring() {
        askWhileNavigating()
        now += 5_000
        arbiter.onManeuverDistance(140)
        repeat(7) {
            now += 1_000
            assertEquals(Reply.HOLD, arbiter.reply())
        }
        now += 1_000
        assertEquals(Reply.PLAY, arbiter.reply(), "held 8 s after a 5 s wait: still the driver's answer")
    }

    @Test
    fun aReplyAlreadyPlayingIsNeverCut() {
        askWhileNavigating()
        assertEquals(Reply.PLAY, arbiter.reply())
        arbiter.onReplyAudio()
        arbiter.onManeuverDistance(90)
        assertEquals(Reply.PLAY, arbiter.reply())
        arbiter.onReplyAudio()
        assertEquals(Reply.PLAY, arbiter.reply())
        arbiter.onReplyEnded()
        arbiter.onDriverRequest()
        assertEquals(Reply.HOLD, arbiter.reply(), "the next reply has not started")
    }

    @Test
    fun noHoldWhenNotNavigatingOrDistanceUnknown() {
        arbiter.onManeuverDistance(50)
        assertEquals(Reply.PLAY, arbiter.reply())
        askWhileNavigating()
        arbiter.onManeuverDistance(50)
        arbiter.onManeuverDistance(null)
        assertEquals(Reply.PLAY, arbiter.reply())
    }

    @Test
    fun unpromptedSpeechIsStillDroppedNotHeld() {
        arbiter.onNavigating(true)
        arbiter.onManeuverDistance(50)
        assertEquals(Reply.DROP, arbiter.reply())
    }

    @Test
    fun stoppingNavigationOrResetClearsTheHold() {
        askWhileNavigating()
        arbiter.onManeuverDistance(50)
        arbiter.onNavigating(false)
        assertEquals(Reply.PLAY, arbiter.reply())
        askWhileNavigating()
        arbiter.onManeuverDistance(50)
        arbiter.reset()
        askWhileNavigating()
        assertEquals(Reply.PLAY, arbiter.reply())
    }
}
