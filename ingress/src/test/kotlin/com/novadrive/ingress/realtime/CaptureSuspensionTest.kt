package com.novadrive.ingress.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The listening lifecycle's single capture switch (sleep), separate from temporary gates. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSuspensionTest {
    private class Rig(scope: TestScope) {
        val provider = FakeRealtimeVoiceProvider()
        val mic = InMemoryMicrophonePort()
        val playback = InMemoryPlaybackPort()
        val finals = mutableListOf<String>()
        val controller = VoiceSessionController(
            provider = provider, microphone = mic, playback = playback, scope = scope,
            config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
            callbacks = VoiceSessionCallbacks(onUserFinalTranscript = { finals += it }),
        )
    }

    @Test
    fun suspendingStopsCaptureAndUploadAndDropsQueuedAudio() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.controller.start()
        rig.mic.emit(ByteArray(4))
        assertEquals(1, rig.provider.sentChunks.size)

        rig.controller.setCaptureSuspended(true)
        assertTrue(rig.mic.stopped, "the recorder is released, not just muted")
        assertEquals(1, rig.provider.discardAudioCount)
        rig.controller.injectAudioFrame(ByteArray(4))
        rig.mic.emit(ByteArray(4))
        assertEquals(1, rig.provider.sentChunks.size, "nothing reaches the cloud while suspended")

        rig.controller.setCaptureSuspended(false)
        assertTrue(rig.mic.started)
        rig.mic.emit(ByteArray(4))
        assertEquals(2, rig.provider.sentChunks.size)
        rig.controller.stop()
    }

    @Test
    fun aReconnectDoesNotReviveCaptureWhileSuspended() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.controller.start()
        val starts = rig.mic.startCount
        rig.controller.setCaptureSuspended(true)
        rig.provider.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, true))
        assertEquals(starts, rig.mic.startCount)
        assertFalse(rig.mic.started)
        rig.controller.stop()
    }

    @Test
    fun repeatedSwitchingStartsOneCaptureAtATime() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.controller.start()
        repeat(5) {
            rig.controller.setCaptureSuspended(true)
            rig.controller.setCaptureSuspended(true)
            rig.controller.setCaptureSuspended(false)
            rig.controller.setCaptureSuspended(false)
        }
        assertEquals(6, rig.mic.startCount, "one start per resume, plus the session start")
        assertEquals(5, rig.provider.discardAudioCount)
        assertEquals(5, rig.provider.resumeCount)
        assertEquals(1, rig.provider.connectCount, "switching never reconnects")
        rig.controller.stop()
    }

    @Test
    fun finalUserTranscriptIsReportedOnce() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.controller.start()
        rig.provider.emit(DomainVoiceEvent.UserTranscript("关闭", final = false))
        rig.provider.emit(DomainVoiceEvent.UserTranscript("关闭小诺", final = true))
        assertEquals(listOf("关闭小诺"), rig.finals)
        rig.controller.stop()
    }

    @Test
    fun cancelFlushesPlaybackAndCancelsOnTheServer() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.controller.start()
        rig.provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
        assertEquals(VoiceUiState.SPEAKING, rig.controller.machine.state)
        rig.controller.cancelCurrentResponse()
        assertTrue(rig.playback.flushCount >= 1)
        assertEquals(1, rig.provider.cancelCount)
        assertEquals(VoiceUiState.LISTENING, rig.controller.machine.state)
        rig.controller.stop()
    }

    @Test
    fun aServerCloseDuringASessionReconnectsAndKeepsWorking() = runTest(UnconfinedTestDispatcher()) {
        // Error then Closed, as the Baidu clients report a server-side close.
        val rig = Rig(this)
        rig.controller.start()
        rig.provider.emit(DomainVoiceEvent.Error("BAIDU_FLEX_CONNECTION_CLOSED", "closed"))
        rig.provider.emit(DomainVoiceEvent.Closed)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, rig.provider.connectCount, "one reconnect")
        assertEquals(VoiceUiState.LISTENING, rig.controller.machine.state, "the stale Closed must not stop the new session")
        assertTrue(rig.controller.machine.streamingAudio)
        rig.mic.emit(ByteArray(4))
        assertEquals(1, rig.provider.sentChunks.size, "audio flows again")
        rig.controller.stop()
    }

    @Test
    fun aClosedOutsideAReconnectStillEndsTheSession() {
        val machine = VoiceSessionStateMachine()
        machine.userStartSession()
        machine.onSessionReady()
        machine.apply(DomainVoiceEvent.Closed)
        assertEquals(VoiceUiState.DISCONNECTED, machine.state)
    }

    @Test
    fun pendingWorkIsVisibleUntilDelivered() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.controller.start()
        assertFalse(rig.controller.hasPendingWork())
        rig.controller.work.submit("c1", "tool")
        assertTrue(rig.controller.hasPendingWork())
        rig.controller.work.cancel("c1")
        assertFalse(rig.controller.hasPendingWork())
        rig.controller.stop()
    }
}
