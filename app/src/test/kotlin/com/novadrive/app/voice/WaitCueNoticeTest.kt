package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Owner, 2026-10-11: the driver is never left in silence. A stalled response is announced before it
 * is asked for again; a given-up retry ends with an honest line; a turn lost to a dropped connection
 * is answered after the reconnect - never by inviting a repeat of an action that already ran (I-14).
 * Event sequences are the real ones (review of 1f4604d, R2).
 */
class WaitCueNoticeTest {
    private val spoken = mutableListOf<String>()

    private fun TestScope.clock(speaks: Boolean = true) =
        WaitCueClock(this, { testScheduler.currentTime }, speaks = { speaks }, onVisual = {}) { cue, _ ->
            spoken += cue.text
        }

    private fun WaitCueClock.driverTurn() {
        onProviderEvent(DomainVoiceEvent.SpeechStarted)
        onProviderEvent(DomainVoiceEvent.SpeechStopped)
    }

    /** The real wire after a stall: our cancel, the cancelled done, then the retry starts. */
    private fun WaitCueClock.stallCancelAndRetry() {
        onProviderEvent(DomainVoiceEvent.ResponseStarted)
        onProviderEvent(DomainVoiceEvent.Interrupted("client_cancelled"))
        onProviderEvent(DomainVoiceEvent.ResponseDone("cancelled", "client_cancelled"))
        onProviderEvent(DomainVoiceEvent.ResponseStarted)
    }

    private fun WaitCueClock.drop() {
        onProviderEvent(DomainVoiceEvent.Error("QWEN_SERVER_UNAVAILABLE", "server"))
        onProviderEvent(DomainVoiceEvent.Closed)
    }

    @Test
    fun aStallIsAnnouncedBeforeTheRetry() = runTest {
        val c = clock()
        c.driverTurn()
        c.onStalled()
        assertEquals(listOf(WaitCues.STALLED), spoken)
        c.close()
    }

    @Test
    fun aStallIsNotAnnouncedInSilentMode() = runTest {
        val c = clock(speaks = false)
        c.driverTurn()
        c.onStalled()
        c.onStalled(final = true)
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun aGivenUpRetrySaysItDidNotWorkOrPointsToTheScreenWhenAnActionRan() = runTest {
        val c = clock()
        c.driverTurn()
        c.onStalled(final = true)
        c.driverTurn()
        c.onProviderEvent(DomainVoiceEvent.ToolCall("call_1", "control_seat", mapOf("action" to "adjust_height")))
        c.onStalled(final = true)
        assertEquals(listOf(WaitCues.STALL_FAILED, WaitCues.RESULT_ON_SCREEN), spoken)
        c.close()
    }

    @Test
    fun aStallThenADropIsAnsweredOnceAfterTheReconnect() = runTest {
        val c = clock()
        c.onConnected()
        assertEquals(emptyList<String>(), spoken, "the first connect owes nothing")
        c.driverTurn()
        c.stallCancelAndRetry()
        c.drop()
        c.onConnected()
        assertEquals(listOf(WaitCues.RECONNECTED), spoken)
        c.onConnected()
        assertEquals(1, spoken.size, "said once")
        c.close()
    }

    @Test
    fun aFailedResponseThenADropIsAnswered() = runTest {
        val c = clock()
        c.driverTurn()
        c.onProviderEvent(DomainVoiceEvent.ResponseStarted)
        c.onProviderEvent(DomainVoiceEvent.ResponseDone("failed", "server_error"))
        c.drop()
        c.onConnected()
        assertEquals(listOf(WaitCues.RECONNECTED), spoken)
        c.close()
    }

    @Test
    fun aDropAfterTheActionRanNeverAsksForARepeat() = runTest {
        val c = clock()
        c.driverTurn()
        c.onProviderEvent(DomainVoiceEvent.ResponseStarted)
        c.onProviderEvent(DomainVoiceEvent.ToolCall("call_1", "control_climate", mapOf("action" to "adjust_temperature")))
        c.onProviderEvent(DomainVoiceEvent.ResponseDone("completed")) // the call's response ends first
        c.onToolResultDelivered() // the climate changed
        c.onProviderEvent(DomainVoiceEvent.ResponseStarted) // the spoken confirmation never arrives
        c.drop()
        c.onConnected()
        assertEquals(listOf(WaitCues.RESULT_ON_SCREEN), spoken)
        c.close()
    }

    @Test
    fun aTurnAlreadyAnsweredOwesNoNotice() = runTest {
        val c = clock()
        c.driverTurn()
        c.onProviderEvent(DomainVoiceEvent.ResponseStarted)
        c.onUsefulSpeech() // her reply audio arrived
        c.onProviderEvent(DomainVoiceEvent.ResponseDone("completed"))
        c.drop()
        c.onConnected()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun aTurnTheAppHandledOwesNoNotice() = runTest {
        val c = clock()
        c.driverTurn()
        c.endTurn("local_pick")
        c.drop()
        c.onConnected()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun theDriverSpeakingAgainCancelsAnOwedNotice() = runTest {
        val c = clock()
        c.driverTurn()
        c.drop()
        c.onProviderEvent(DomainVoiceEvent.SpeechStarted)
        runCurrent()
        c.onConnected()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun aLongForgottenTurnIsNotAnnouncedByALaterDrop() = runTest {
        val c = clock()
        c.driverTurn() // e.g. noise that never got a response
        advanceTimeBy(61_000) // the ordinary 7 s and 12 s cues may play meanwhile
        c.drop()
        c.onConnected()
        assertTrue(WaitCues.RECONNECTED !in spoken && WaitCues.RESULT_ON_SCREEN !in spoken, "$spoken")
        c.close()
    }

    @Test
    fun theStallLimitLeavesRoomForASlowToolCall() {
        // qwen3.5-omni-plus-realtime, 2026-10-11: a valid call came 5.35 s after response.created.
        assert(ResponseStallWatchdog.STALL_MS > 5_350L)
        assert(ResponseStallWatchdog.STALL_MS < 15_700L) // the shortest measured server hang is still recovered
    }
}
