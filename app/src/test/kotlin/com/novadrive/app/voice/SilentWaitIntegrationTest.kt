package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.FakeRealtimeVoiceProvider
import com.novadrive.ingress.realtime.InMemoryMicrophonePort
import com.novadrive.ingress.realtime.InMemoryPlaybackPort
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.ingress.realtime.VoiceSessionController
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

/**
 * The real session core + the listening lifecycle + the command router, wired as in the app's
 * VoiceSessionController, against the deterministic fake realtime provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SilentWaitIntegrationTest {
    private class Rig(val test: TestScope) {
        val provider = FakeRealtimeVoiceProvider()
        val mic = InMemoryMicrophonePort()
        val playback = InMemoryPlaybackPort()
        val executed = mutableListOf<String>()
        lateinit var core: VoiceSessionController
        lateinit var router: VoiceCommandRouter

        val lifecycle = ListeningLifecycle(
            scope = test.backgroundScope,
            controls = object : ListeningControls {
                override fun setCloudUpload(enabled: Boolean) = core.setCaptureSuspended(!enabled)
                override fun cancelAssistantReply() = core.cancelCurrentResponse()
                override fun closeCloudSession() = core.stop()
                override fun openCloudSession(): Boolean {
                    core.start()
                    return true
                }
            },
            nowMs = { test.testScheduler.currentTime },
        )

        init {
            core = VoiceSessionController(
                provider = provider, microphone = mic, playback = playback, scope = test,
                config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
                callbacks = VoiceSessionCallbacks(
                    onToolCall = { call ->
                        executed += call.name
                        ToolDispatchResult(null, null, output = """{"ok":true}""")
                    },
                    onUserFinalTranscript = { router.onUserUtterance(it) },
                ),
            )
            router = VoiceCommandRouter(lifecycle, context = { ListeningIntent.Context() })
            core.start()
            lifecycle.onSessionStarted("wake_word")
        }

        fun say(text: String) {
            provider.emit(DomainVoiceEvent.SpeechStarted)
            provider.emit(DomainVoiceEvent.SpeechStopped)
            provider.emit(DomainVoiceEvent.UserTranscript(text, final = true))
        }

        /**
         * The finished utterance only. While 小诺 talks, uplink stays open for full-duplex barge-in;
         * voice, so no speech-started barge-in precedes 「闭嘴」 here: the cancel must come from it.
         */
        fun transcriptOnly(text: String) {
            provider.emit(DomainVoiceEvent.UserTranscript(text, final = true))
        }

        fun assistantTalks() {
            provider.emit(DomainVoiceEvent.AudioDelta("AAAA"))
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun shutUpStopsTheReplyAndTheNextCommandRunsWithoutTheWakeWord() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        // 「有三条路线……」 is being spoken.
        rig.assistantTalks()
        assertEquals(1, rig.playback.played.size)
        val flushesBefore = rig.playback.flushCount

        rig.transcriptOnly("闭嘴")
        assertEquals(ListeningState.SILENT_WAIT, rig.lifecycle.state.value)
        assertTrue(rig.playback.flushCount > flushesBefore, "playback is flushed at once")
        assertTrue(rig.playback.played.isEmpty())
        assertEquals(1, rig.provider.cancelCount, "the reply is cancelled on the server")
        assertFalse(rig.core.captureSuspendedNow, "still listening")
        rig.mic.emit(ByteArray(4))
        assertEquals(1, rig.provider.sentChunks.size, "audio still reaches the cloud")

        rig.say("选最快的那条")
        assertEquals(ListeningState.ACTIVE, rig.lifecycle.state.value)
        rig.provider.emit(DomainVoiceEvent.ToolCall("call_1", "choose_navigation_option", mapOf("preference" to "fastest")))
        assertEquals(listOf("choose_navigation_option"), rig.executed, "the command executes normally")
        assertEquals(1, rig.provider.connectCount, "same session, context kept")
        rig.core.stop()
    }

    @Test
    fun silentWaitFallsAsleepAndSleepIgnoresOrdinarySpeech() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.say("shut up")
        rig.provider.emit(DomainVoiceEvent.ResponseDone("cancelled"))
        wait(20_001)
        assertEquals(ListeningState.SLEEP, rig.lifecycle.state.value)
        assertTrue(rig.core.captureSuspendedNow)
        assertTrue(rig.mic.stopped, "microphone released")

        val sent = rig.provider.sentChunks.size
        rig.mic.emit(ByteArray(4))
        rig.core.injectAudioFrame(ByteArray(4))
        assertEquals(sent, rig.provider.sentChunks.size, "nothing goes to the cloud")
        rig.say("空调调到24度") // a late transcript must not wake it
        assertEquals(ListeningState.SLEEP, rig.lifecycle.state.value)
        rig.core.stop()
    }

    @Test
    fun goToSleepWorksFromSilentWaitAndWakeResumesTheSameSession() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.say("闭嘴")
        rig.say("休眠")
        assertEquals(ListeningState.SLEEP, rig.lifecycle.state.value)
        assertTrue(rig.core.captureSuspendedNow)

        assertTrue(rig.lifecycle.activate("wake_word"))
        assertEquals(ListeningState.ACTIVE, rig.lifecycle.state.value)
        assertFalse(rig.core.captureSuspendedNow)
        assertTrue(rig.mic.started)
        assertEquals(1, rig.provider.resumeCount, "the provider is told listening resumed")
        rig.mic.emit(ByteArray(4))
        assertEquals(1, rig.provider.sentChunks.size)
        rig.core.stop()
    }

    @Test
    fun sleepWakeCyclesLeaveNoExtraSessionsCaptureOrPlayback() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        repeat(10) {
            rig.assistantTalks()
            rig.say("闭嘴")
            rig.say("去睡觉")
            assertTrue(rig.playback.played.isEmpty(), "nothing left playing in sleep")
            assertFalse(rig.mic.started, "no capture in sleep")
            rig.lifecycle.activate("wake_word")
        }
        assertEquals(1, rig.provider.connectCount, "one realtime session throughout")
        assertEquals(1, rig.core.collectorStarts, "one event collector")
        assertEquals(11, rig.mic.startCount, "exactly one capture per wake (+ the first start)")
        assertTrue(rig.mic.started)

        // Long sleep closes the session; the wake word opens exactly one new one.
        rig.say("休眠")
        wait(300_001)
        assertEquals(ListeningState.DEEP_IDLE, rig.lifecycle.state.value)
        assertFalse(rig.core.sessionActiveNow)
        assertTrue(rig.provider.closed)
        rig.lifecycle.activate("wake_word")
        assertEquals(2, rig.provider.connectCount)
        assertTrue(rig.core.sessionActiveNow)
        rig.core.stop()
    }
}
