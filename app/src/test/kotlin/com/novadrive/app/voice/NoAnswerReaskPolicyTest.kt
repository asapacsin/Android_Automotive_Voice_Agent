package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P45 Unit 2: the no-answer re-ask in [EmptyResponseRetryPolicy], on virtual time. */
class NoAnswerReaskPolicyTest {
    private var sent = 0
    private var gaveUp = 0
    private var block: String? = null
    private var socketAlive = true

    private fun TestScope.policy() = EmptyResponseRetryPolicy(
        backgroundScope, "t", blocked = { block }, send = { if (socketAlive) { sent++; true } else false }, giveUp = { gaveUp++ },
    )

    @Test
    fun silenceAfterEndOfSpeechIsReaskedOnceAtThreeSeconds() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(2_999); runCurrent()
        assertEquals(0, sent)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, sent)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, sent, "once per utterance")
    }

    @Test
    fun anInputTranscriptOrOutputCancelsTheDeadline() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech(); p.onHeard()
        p.onSpeechStarted(); p.onEndOfSpeech(); p.onOutput()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, sent)
    }

    @Test
    fun theDriversNextOnsetCancelsTheDeadline() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(2_000)
        p.onSpeechStarted()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, sent)
    }

    @Test
    fun anAppHandledUtteranceIsNeverReasked() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech(); p.appHandled()
        advanceTimeBy(10_000); runCurrent()
        assertFalse(p.fire(EmptyResponseRetryPolicy.EMPTY_CLOSE))
        assertEquals(0, sent)
    }

    @Test
    fun aBlockedOrDeadSocketReaskIsDropped() = runTest {
        val p = policy()
        block = "suspended"
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(4_000); runCurrent()
        block = null; socketAlive = false
        assertFalse(p.fire(EmptyResponseRetryPolicy.EMPTY_CLOSE))
        assertEquals(0, sent)
        assertEquals(0, gaveUp)
    }

    @Test
    fun noAnswerFiveSecondsAfterTheReaskGivesUpOnce() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(3_001); runCurrent()
        assertEquals(1, sent)
        advanceTimeBy(4_998); runCurrent()
        assertEquals(0, gaveUp)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, gaveUp)
        assertTrue(p.gaveUp)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, gaveUp)
        p.onSpeechStarted()
        assertFalse(p.gaveUp, "the next utterance is a new turn")
    }

    @Test
    fun anAnswerToTheReaskCancelsTheGiveUp() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(3_001); runCurrent()
        p.onOutput()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, gaveUp)
    }

    @Test
    fun aBusyErrorWhileTheReaskIsUnmatchedDropsItAndOnlyThen() = runTest {
        val p = policy()
        assertFalse(p.onBusyError(), "no re-ask pending: the caller re-queues as before")
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(3_001); runCurrent()
        assertTrue(p.onBusyError())
        assertFalse(p.onBusyError())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, gaveUp)
    }

    @Test
    fun aDeadlineReaskIsNotFollowedByAnEmptyCloseReask() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech()
        advanceTimeBy(3_001); runCurrent()
        // The late transcript and the re-ask's own empty response.
        assertFalse(p.onUserTranscriptCompleted())
        assertFalse(p.onResponseDone("completed", 0))
        assertFalse(p.fire(EmptyResponseRetryPolicy.EMPTY_CLOSE))
        assertEquals(1, sent)
    }

    @Test
    fun anEmptyCloseReaskIsOnceAndArmsTheGiveUp() = runTest {
        val p = policy()
        p.onSpeechStarted(); p.onEndOfSpeech(); p.onHeard()
        assertTrue(p.fire(EmptyResponseRetryPolicy.EMPTY_CLOSE))
        assertFalse(p.fire(EmptyResponseRetryPolicy.EMPTY_CLOSE))
        assertEquals(1, sent)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, gaveUp)
    }

    @Test
    fun aFinalGiveUpStopsTheWaitCueClockBeforeItsTwelveSecondLine() = runTest {
        val spoken = mutableListOf<String>()
        val clock = WaitCueClock(backgroundScope, { testScheduler.currentTime }, onVisual = {}) { cue, _ -> spoken += cue.text }
        clock.onProviderEvent(DomainVoiceEvent.SpeechStarted)
        clock.onProviderEvent(DomainVoiceEvent.SpeechStopped)
        advanceTimeBy(8_001); runCurrent()
        clock.onStalled(final = true)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf(WaitCues.PROGRESS, WaitCues.STALL_FAILED), spoken)
    }
}
