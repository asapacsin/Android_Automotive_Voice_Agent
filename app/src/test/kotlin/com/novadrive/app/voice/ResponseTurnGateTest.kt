package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseTurnGateTest {
    private var clock = 1_000L
    private val gate = ResponseTurnGate(now = { clock }, staleMs = 30_000, afterSpeechMs = 1_500)
    private fun turn(name: String) = ResponseTurnGate.Turn(listOf(name))

    @Test
    fun anIdleConversationSendsAtOnce() {
        assertTrue(gate.submit(turn("a")))
        assertTrue(gate.isBusy(), "our own reply is now running")
    }

    @Test
    fun whileTheDriverSpeaksTheTurnWaitsForBaidusReplyToFinish() {
        // The measured failure: camera answer submitted mid-question.
        gate.onSpeechStarted()
        assertFalse(gate.submit(turn("read-aloud")))
        gate.onSpeechStopped()
        assertNull(gate.next(), "Baidu is about to answer the driver")
        gate.onResponseCreated()
        clock += 5_000
        assertNull(gate.next(), "the driver's answer is still playing")
        gate.onResponseDone()
        assertEquals(turn("read-aloud"), gate.next())
        assertTrue(gate.isBusy())
    }

    @Test
    fun speechWithNoReplyReleasesTheTurnShortlyAfter() {
        gate.onSpeechStarted()
        gate.submit(turn("a"))
        gate.onSpeechStopped()
        clock += 1_499
        assertNull(gate.next())
        clock += 2
        assertEquals(turn("a"), gate.next())
    }

    @Test
    fun turnsLeaveOneAtATimeInOrder() {
        gate.onResponseCreated()
        gate.submit(turn("a"))
        gate.submit(turn("b"))
        gate.onResponseDone()
        assertEquals(turn("a"), gate.next())
        assertNull(gate.next(), "b waits for a's reply")
        gate.onResponseCreated()
        gate.onResponseDone()
        assertEquals(turn("b"), gate.next())
    }

    @Test
    fun aQueuedTurnIsNotOvertakenByANewOne() {
        gate.onResponseCreated()
        gate.submit(turn("a"))
        gate.onResponseDone()
        assertFalse(gate.submit(turn("b")), "a is still queued")
        assertEquals(turn("a"), gate.next())
    }

    @Test
    fun aRejectedReplyIsRetriedFirst() {
        gate.onResponseCreated()
        gate.submit(turn("later"))
        gate.onBusyRejected(turn("retry"))
        gate.onResponseDone()
        assertEquals(turn("retry"), gate.next())
    }

    @Test
    fun lostEventsCannotBlockForever() {
        gate.onResponseCreated() // response.done never arrives
        gate.submit(turn("a"))
        clock += 29_999
        assertNull(gate.next())
        clock += 2
        assertEquals(turn("a"), gate.next())
    }

    @Test
    fun aNewSocketIsFreeButKeepsTheQueue() {
        gate.onSpeechStarted()
        gate.submit(turn("a"))
        gate.onConnectionReset()
        assertEquals(1, gate.pending())
        assertEquals(turn("a"), gate.next())
    }
}
