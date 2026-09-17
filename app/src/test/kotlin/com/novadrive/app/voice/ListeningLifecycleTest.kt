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
        var cancels = 0
        override fun setCloudUpload(enabled: Boolean) { uploading = enabled; log += "upload=$enabled" }
        override fun cancelAssistantReply() { cancels++; log += "cancel" }
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

    // ---- ACTIVE ---------------------------------------------------------------------------

    @Test
    fun inactivityAfterTheAnswerLeadsToSleep() = runTest {
        val rig = Rig(this)
        rig.start()
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertTrue(rig.controls.uploading)
        rig.userTurn()
        wait(29_999)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(2)
        assertEquals(ListeningState.SLEEP, rig.state)
        assertFalse(rig.controls.uploading, "sleep really stops upload")
        assertEquals("inactivity_timeout", rig.transitions.last().second)
    }

    @Test
    fun userSpeechResetsTheInactivityTimer() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(25_000)
        rig.userTurn()
        wait(29_000)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(1_001)
        assertEquals(ListeningState.SLEEP, rig.state)
    }

    @Test
    fun noTimerFiresMidUtteranceOrWhileAnswering() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(29_000)
        rig.lifecycle.onBusyChanged(true)
        wait(60_000)
        assertEquals(ListeningState.ACTIVE, rig.state)
        rig.lifecycle.onBusyChanged(false) // noise: old deadline, plus grace
        wait(1_499)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(2)
        assertEquals(ListeningState.SLEEP, rig.state)
    }

    @Test
    fun vadNoiseDoesNotKeepListeningAlive() = runTest {
        val rig = Rig(this)
        rig.start()
        repeat(10) {
            wait(5_000)
            rig.userTurn(meaningful = false)
        }
        assertEquals(ListeningState.SLEEP, rig.state)
    }

    @Test
    fun temporarySuppressionIsNotSleep() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(10_000)
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertEquals(listOf("upload=true"), rig.controls.log)
    }

    // ---- SILENT_WAIT ---------------------------------------------------------------------------

    @Test
    fun shutUpCutsTheReplyOffAndKeepsListening() = runTest {
        val rig = Rig(this)
        rig.start()
        assertTrue(rig.lifecycle.speaks)
        rig.lifecycle.silence("voice_command")
        assertEquals(ListeningState.SILENT_WAIT, rig.state)
        assertEquals(1, rig.controls.cancels, "the reply is cancelled at once")
        assertFalse(rig.lifecycle.speaks, "and nothing is spoken until the next command")
        assertTrue(rig.controls.uploading, "audio still goes up: no wake word needed")
        assertEquals(1, rig.controls.sessionsOpen, "the conversation is not ended")
        assertEquals(listOf("upload=true", "cancel"), rig.controls.log)
    }

    @Test
    fun aRealCommandInSilentWaitReturnsToActive() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        wait(5_000)
        rig.lifecycle.onBusyChanged(true) // 「选最快的」 starts
        rig.lifecycle.onMeaningfulUserTurn()
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertTrue(rig.lifecycle.speaks, "its answer is spoken")
        assertEquals("user_command", rig.transitions.last().second)
        wait(3_000)
        rig.lifecycle.onBusyChanged(false)
        wait(29_999)
        assertEquals(ListeningState.ACTIVE, rig.state, "the normal inactivity rule applies again")
        wait(2)
        assertEquals(ListeningState.SLEEP, rig.state)
        assertEquals("inactivity_timeout", rig.transitions.last().second)
    }

    @Test
    fun silentWaitTimesOutIntoSleep() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        wait(19_999)
        assertEquals(ListeningState.SILENT_WAIT, rig.state)
        wait(2)
        assertEquals(ListeningState.SLEEP, rig.state)
        assertFalse(rig.controls.uploading)
        assertEquals("silent_wait_timeout", rig.transitions.last().second)
    }

    @Test
    fun goToSleepFromSilentWaitIsImmediate() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        rig.lifecycle.sleep("voice_command")
        assertEquals(ListeningState.SLEEP, rig.state)
        assertFalse(rig.controls.uploading)
        wait(60_000)
        assertEquals(ListeningState.SLEEP, rig.state, "no silent-wait timer is left behind")
        assertEquals(1, rig.transitions.count { it.first == ListeningState.SLEEP })
    }

    @Test
    fun speechBeforeTheTimeoutCancelsThePendingSleep() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        wait(19_000)
        rig.lifecycle.onBusyChanged(true) // the driver starts talking at 19 s
        wait(10_000) // still talking / being answered past the 20 s deadline
        assertEquals(ListeningState.SILENT_WAIT, rig.state, "the timer is paused, not fired")
        rig.lifecycle.onMeaningfulUserTurn()
        assertEquals(ListeningState.ACTIVE, rig.state)
    }

    @Test
    fun noiseInSilentWaitDoesNotExtendIt() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        repeat(5) {
            wait(4_000)
            rig.userTurn(meaningful = false)
        }
        assertEquals(ListeningState.SLEEP, rig.state)
        assertEquals("silent_wait_timeout", rig.transitions.last().second)
    }

    @Test
    fun repeatedShutUpKeepsOneTimer() = runTest {
        val rig = Rig(this)
        rig.start()
        repeat(10) {
            rig.lifecycle.silence("voice_command")
            wait(1_000)
        }
        assertEquals(10, rig.controls.cancels)
        assertEquals(1, rig.transitions.count { it.first == ListeningState.SILENT_WAIT })
        wait(18_999) // now 28.999 s; due 20 s after the last 「闭嘴」 at 9 s
        assertEquals(ListeningState.SILENT_WAIT, rig.state)
        wait(2)
        assertEquals(ListeningState.SLEEP, rig.state)
        assertEquals(1, rig.transitions.count { it.first == ListeningState.SLEEP })
    }

    @Test
    fun shutUpWhileAsleepDoesNothing() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.sleep("ui")
        rig.lifecycle.silence("voice_command")
        assertEquals(ListeningState.SLEEP, rig.state)
        assertFalse(rig.controls.uploading)
    }

    @Test
    fun endConversationInSilentWaitSleepsAtOnce() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        rig.lifecycle.onBusyChanged(true)
        rig.lifecycle.sleepAfterReply("end_conversation")
        assertEquals(ListeningState.SLEEP, rig.state)
    }

    // ---- SLEEP ------------------------------------------------------------------------------

    @Test
    fun sleepIgnoresOrdinarySpeech() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.sleep("voice_command")
        rig.userTurn() // a late transcript arriving anyway
        rig.lifecycle.onMeaningfulUserTurn()
        assertEquals(ListeningState.SLEEP, rig.state)
        assertFalse(rig.controls.uploading)
    }

    @Test
    fun wakeReturnsFromSleepOnTheSameConnection() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.sleep("voice_command")
        assertTrue(rig.lifecycle.activate("wake_word"))
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertTrue(rig.controls.uploading)
        assertEquals(listOf("upload=true", "upload=false", "cancel", "upload=true"), rig.controls.log)
        assertEquals(0, rig.controls.sessionsOpened, "same connection: the conversation context is kept")
    }

    @Test
    fun wakeFromSilentWaitGoesStraightToActive() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command")
        assertTrue(rig.lifecycle.activate("ui"))
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(21_000)
        assertEquals(ListeningState.ACTIVE, rig.state, "the silent-wait timer no longer applies")
    }

    @Test
    fun longSleepBecomesDeepIdleAndWakeOpensANewSession() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.sleep("ui")
        wait(299_999)
        assertEquals(ListeningState.SLEEP, rig.state)
        wait(2)
        assertEquals(ListeningState.DEEP_IDLE, rig.state)
        assertEquals(0, rig.controls.sessionsOpen, "socket closed")
        assertTrue(rig.lifecycle.activate("wake_word"))
        assertEquals(ListeningState.ACTIVE, rig.state)
        assertEquals(1, rig.controls.sessionsOpened)
    }

    @Test
    fun aFailedReconnectFromDeepIdleStaysAsleep() = runTest {
        val rig = Rig(this)
        rig.controls.openFails = true
        assertFalse(rig.lifecycle.activate("wake_word"))
        assertEquals(ListeningState.DEEP_IDLE, rig.state)
    }

    @Test
    fun repeatedSleepWakeKeepsOneSessionAndOneTimer() = runTest {
        val rig = Rig(this)
        rig.start()
        repeat(20) {
            rig.lifecycle.silence("voice_command")
            rig.lifecycle.sleep("ui")
            rig.lifecycle.activate("ui")
        }
        assertEquals(0, rig.controls.sessionsOpened)
        assertEquals(1, rig.controls.sessionsOpen)
        assertTrue(rig.controls.uploading)
        wait(30_001)
        assertEquals(ListeningState.SLEEP, rig.state)
        assertEquals(1, rig.transitions.count { it.second == "inactivity_timeout" })
    }

    @Test
    fun aStaleTimerCannotEndANewlyReactivatedSession() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.silence("voice_command") // sleep would be due at 20 s
        wait(19_000)
        rig.lifecycle.sleep("ui")
        rig.lifecycle.activate("wake_word") // new inactivity countdown from 19 s
        wait(1_500)
        assertEquals(ListeningState.ACTIVE, rig.state)
        wait(28_501)
        assertEquals(ListeningState.SLEEP, rig.state)
    }

    @Test
    fun endConversationWaitsForTheGoodbye() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.onBusyChanged(true)
        rig.lifecycle.sleepAfterReply("end_conversation")
        assertEquals(ListeningState.ACTIVE, rig.state)
        rig.lifecycle.onBusyChanged(false)
        assertEquals(ListeningState.SLEEP, rig.state)
        assertEquals("conversation_ended", rig.transitions.last().second)
    }

    @Test
    fun aDroppedConnectionInSleepEndsTheSessionInsteadOfReconnecting() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.lifecycle.onConnectionLost()
        assertEquals(ListeningState.ACTIVE, rig.state, "while listening the session's own reconnect handles it")
        rig.lifecycle.silence("voice_command")
        rig.lifecycle.onConnectionLost()
        assertEquals(ListeningState.SILENT_WAIT, rig.state)
        rig.lifecycle.sleep("ui")
        rig.lifecycle.onConnectionLost()
        assertEquals(ListeningState.DEEP_IDLE, rig.state)
        assertTrue("close" in rig.controls.log)
    }

    @Test
    fun streamingTimeCountsActiveAndSilentWait() = runTest {
        val rig = Rig(this)
        rig.start()
        wait(4_000)
        rig.lifecycle.silence("voice_command")
        wait(2_000)
        rig.lifecycle.sleep("ui")
        wait(10_000)
        rig.lifecycle.activate("ui")
        wait(1_000)
        assertEquals(7_000, rig.lifecycle.cloudStreamingMs)
        assertEquals(6_000, rig.transitions.first { it.first == ListeningState.SLEEP }.third)
    }

    @Test
    fun timeoutsAreConfigurable() = runTest {
        val controls = Controls()
        val lifecycle = ListeningLifecycle(
            backgroundScope, controls,
            ListeningTimeouts(silentWaitMs = 5_000),
            nowMs = { testScheduler.currentTime },
        )
        lifecycle.onSessionStarted("wake")
        lifecycle.silence("voice_command")
        wait(5_001)
        assertEquals(ListeningState.SLEEP, lifecycle.state.value)
        assertEquals(20_000L, ListeningTimeouts.SILENT_WAIT_TIMEOUT_MS)
    }
}
