package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Owner, 2026-10-11: the driver is never left in silence. A stalled response is announced before it
 * is asked for again, and a turn lost to a dropped connection is answered after the reconnect.
 */
class WaitCueNoticeTest {
    private val spoken = mutableListOf<String>()

    private fun TestScope.clock(speaks: Boolean = true) =
        WaitCueClock(this, { testScheduler.currentTime }, speaks = { speaks }, onVisual = {}) { cue, _ ->
            spoken += "${cue.code}:${cue.text}"
        }

    @Test
    fun aStallIsAnnouncedWithTheDelayLine() = runTest {
        val c = clock()
        c.onSpeech(false)
        c.onStalled()
        assertEquals(listOf("stalled:${WaitCues.DELAY}"), spoken)
        c.close()
    }

    @Test
    fun aStallIsNotAnnouncedInSilentMode() = runTest {
        val c = clock(speaks = false)
        c.onSpeech(false)
        c.onStalled()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun aTurnLostToADroppedConnectionIsAnsweredOnceAfterTheReconnect() = runTest {
        val c = clock()
        c.onConnected()
        assertEquals(emptyList<String>(), spoken, "the first connect owes nothing")
        c.onSpeech(false) // the driver finished a turn
        c.onProviderEvent(DomainVoiceEvent.Interrupted("client_cancelled")) // a stall cancel does not answer it
        c.onProviderEvent(DomainVoiceEvent.Error("QWEN_SERVER_UNAVAILABLE", "server"))
        c.onProviderEvent(DomainVoiceEvent.Closed)
        c.onConnected()
        assertEquals(listOf("reconnected:${WaitCues.RECONNECTED}"), spoken)
        c.onConnected()
        assertEquals(1, spoken.size, "said once")
        c.close()
    }

    @Test
    fun aTurnAlreadyAnsweredOwesNoNotice() = runTest {
        val c = clock()
        c.onSpeech(false)
        c.onUsefulSpeech() // her reply audio arrived
        c.onProviderEvent(DomainVoiceEvent.Error("QWEN_CONNECTION_CLOSED", "closed"))
        c.onConnected()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun aTurnTheAppHandledOwesNoNotice() = runTest {
        val c = clock()
        c.onSpeech(false)
        c.endTurn("local_pick")
        c.onProviderEvent(DomainVoiceEvent.Error("QWEN_CONNECTION_CLOSED", "closed"))
        c.onConnected()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun theDriverSpeakingAgainCancelsAnOwedNotice() = runTest {
        val c = clock()
        c.onSpeech(false)
        c.onProviderEvent(DomainVoiceEvent.Error("QWEN_CONNECTION_CLOSED", "closed"))
        c.onSpeech(true)
        runCurrent()
        c.onConnected()
        assertEquals(emptyList<String>(), spoken)
        c.close()
    }

    @Test
    fun theStallLimitLeavesRoomForASlowToolCall() {
        // qwen3.5-omni-plus-realtime, 2026-10-11: a valid call came 5.35 s after response.created.
        assert(ResponseStallWatchdog.STALL_MS > 5_350L)
        assert(ResponseStallWatchdog.STALL_MS < 15_700L) // the shortest measured server hang is still recovered
    }
}
