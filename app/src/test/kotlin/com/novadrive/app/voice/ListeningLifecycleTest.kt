package com.novadrive.app.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningLifecycleTest {
    private class Controls : ListeningControls {
        val log = mutableListOf<String>()
        var uploading = false
        var sessionsOpen = 0
        var sessionsOpened = 0
        var openFails = false
        override fun setCloudUpload(enabled: Boolean) { uploading = enabled; log += "upload=$enabled" }
        override fun cancelAssistantReply() { log += "cancel" }
        override fun startFreshConversation() { log += "fresh" }
        override fun closeCloudSession() { sessionsOpen = 0; log += "close" }
        override fun openCloudSession(): Boolean {
            if (openFails) return false
            sessionsOpen = 1; sessionsOpened++; log += "open"; return true
        }
    }

    private class Rig(val scope: TestScope) {
        val controls = Controls()
        val transitions = mutableListOf<Triple<ListeningState, String, Long>>()
        val lifecycle = ListeningLifecycle(
            scope.backgroundScope, controls, ListeningTimeouts(),
            nowMs = { scope.testScheduler.currentTime },
            onTransition = { _, to, reason, streamed -> transitions += Triple(to, reason, streamed) },
        )
        val state get() = lifecycle.state.value

        fun start() {
            controls.sessionsOpen = 1
            lifecycle.onSessionStarted("wake")
        }

        /** One full user turn: speech, answer, playback finished. */
        fun userTurn(meaningful: Boolean = true) {
            lifecycle.onBusyChanged(true)
            if (meaningful) lifecycle.onMeaningfulUserTurn()
            scope.advanceTimeBy(3_000)
            lifecycle.onBusyChanged(false)
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun inactivityAfterTheAnswerLeadsToStandby() = runTest {
        val rig = Rig(this)
        rig.start()
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertTrue(rig.controls.uploading)
        rig.userTurn()
        wait(29_999)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(2)
        assertEquals(ListeningState.STANDBY, rig.state)
        assertFalse(rig.controls.uploading, "standby really stops upload")
        assertEquals("inactivity_timeout", rig.transitions.last().second)
    }

    @Test
    fun userSpeechResetsTheTimer() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(25_000)
        rig.userTurn() // 3 s turn, countdown restarts when the answer ends
        wait(29_000)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(1_001)
        assertEquals(ListeningState.STANDBY, rig.state)
    }

    @Test
    fun theTimerNeverFiresMidUtteranceOrWhileAnswering() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(29_000)
        rig.lifecycle.onBusyChanged(true) // driver starts talking at 29 s
        wait(60_000)
        assertEquals(ListeningState.ACTIVE, rig.state)
        rig.lifecycle.onBusyChanged(false) // not a meaningful turn (noise): old deadline, plus grace
        wait(1_499)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(2)
        assertEquals(ListeningState.STANDBY, rig.state)
    }

    @Test
    fun vadNoiseDoesNotKeepListeningAlive() = runTest {
        val rig = Rig(this)
        rig.start()
        repeat(10) {
            wait(5_000)
            rig.userTurn(meaningful = false)
        }
        assertEquals(ListeningState.STANDBY, rig.state, "noise every 8 s must not stream forever")
    }

    @Test
    fun explicitTerminationIsImmediate() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.terminate("voice_command")
        assertEquals(ListeningState.STANDBY, rig.state)
        assertEquals(listOf("upload=true", "upload=false", "cancel"), rig.controls.log)
        wait(60_000)
        assertEquals(ListeningState.STANDBY, rig.state, "no inactivity timer is left running")
        assertTrue(rig.controls.sessionsOpen == 1, "standby keeps the connection; the app is not closed")
    }

    @Test
    fun temporarySuppressionIsNotStandby() = runTest {
        val rig = Rig(this)
        rig.start()
        // Navigation guidance gates the microphone elsewhere; the lifecycle is not told and does
        // not change. Only user turns and answers count as busy.
        wait(10_000)
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertTrue(rig.controls.uploading)
        assertEquals(listOf("upload=true"), rig.controls.log)
    }

    @Test
    fun wakeOrUiReturnsFromStandbyWithAFreshConversation() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.terminate("voice_command")
        assertTrue(rig.lifecycle.activate("wake"))
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertTrue(rig.controls.uploading)
        assertEquals(listOf("upload=true", "upload=false", "cancel", "fresh", "upload=true"), rig.controls.log)
        assertEquals(0, rig.controls.sessionsOpened, "same connection, no new session")
    }

    @Test
    fun standbyBecomesDeepIdleAndWakeOpensANewSession() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.terminate("ui")
        wait(299_999)
        assertEquals(ListeningState.STANDBY, rig.state)
        wait(2)
        assertEquals(ListeningState.DEEP_IDLE, rig.state)
        assertEquals(0, rig.controls.sessionsOpen, "socket closed")
        assertTrue(rig.lifecycle.activate("wake"))
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertEquals(1, rig.controls.sessionsOpened)
    }

    @Test
    fun aFailedReconnectFromDeepIdleStaysIdle() = runTest {
        val rig = Rig(this)
        rig.controls.openFails = true
        assertFalse(rig.lifecycle.activate("wake"))
        assertEquals(ListeningState.DEEP_IDLE, rig.state)
    }

    @Test
    fun repeatedSwitchingKeepsOneSessionAndOneTimer() = runTest {
        val rig = Rig(this)
        rig.start()
        repeat(20) {
            rig.lifecycle.terminate("ui")
            rig.lifecycle.activate("ui")
        }
        assertEquals(0, rig.controls.sessionsOpened)
        assertEquals(1, rig.controls.sessionsOpen)
        wait(30_001)
        assertEquals(ListeningState.STANDBY, rig.state)
        // Exactly one inactivity transition: no leftover timers from the 20 cycles.
        assertEquals(1, rig.transitions.count { it.second == "inactivity_timeout" })
    }

    @Test
    fun aStaleTimerCannotEndANewlyReactivatedSession() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(29_000)
        rig.lifecycle.terminate("ui")
        rig.lifecycle.activate("wake") // new countdown from 29 s
        wait(1_500) // the old deadline (30 s) passes
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(28_501)
        assertEquals(ListeningState.STANDBY, rig.state)
    }

    @Test
    fun endConversationWaitsForTheGoodbye() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.onBusyChanged(true)
        rig.lifecycle.standbyAfterReply("end_conversation")
        assertEquals(ListeningState.ACTIVE, rig.state, "the reply is still playing")
        rig.lifecycle.onBusyChanged(false)
        assertEquals(ListeningState.STANDBY, rig.state)
        assertEquals("conversation_ended", rig.transitions.last().second)
    }

    @Test
    fun aDroppedConnectionInStandbyEndsTheSessionInsteadOfReconnecting() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.onConnectionLost()
        assertEquals(ListeningState.ACTIVE, rig.state, "in ACTIVE the session's own reconnect handles it")
        rig.lifecycle.terminate("ui")
        rig.lifecycle.onConnectionLost()
        assertEquals(ListeningState.DEEP_IDLE, rig.state)
        assertTrue("close" in rig.controls.log)
    }

    @Test
    fun streamingTimeIsMeasured() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(4_000)
        rig.lifecycle.terminate("ui")
        wait(10_000)
        rig.lifecycle.activate("ui")
        wait(1_000)
        assertEquals(5_000, rig.lifecycle.cloudStreamingMs)
        assertEquals(4_000, rig.transitions.first { it.first == ListeningState.STANDBY }.third)
    }
}
