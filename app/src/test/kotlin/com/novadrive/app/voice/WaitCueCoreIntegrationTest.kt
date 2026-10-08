package com.novadrive.app.voice

import com.novadrive.app.SpeakingStyle
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.FakeRealtimeVoiceProvider
import com.novadrive.ingress.realtime.InMemoryMicrophonePort
import com.novadrive.ingress.realtime.InMemoryPlaybackPort
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.ingress.realtime.VoiceSessionController
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.ingress.realtime.VoiceUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-020 at integration level: the real ingress session core, fed by the fake provider through the
 * revoicer, on a virtual clock. Cue audio (WaitCueAudio) must reach `playback.played` past the core's
 * reply stamps, and must not hold the session in SPEAKING (tool results are delivered at LISTENING/THINKING).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitCueCoreIntegrationTest {
    /** PCM carries its text, zero-padded to an even length, so what was played is readable. */
    private class TextVoice : AssistantVoice {
        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            val bytes = text.toByteArray()
            onPcm(if (bytes.size % 2 == 0) bytes else bytes + 0)
        }
    }

    private class Rig(val test: TestScope) {
        val fake = FakeRealtimeVoiceProvider()
        val toolGate = CompletableDeferred<String>()
        val playback = InMemoryPlaybackPort()
        val revoicer = AssistantVoiceRevoicer(
            TextVoice(), style = { SpeakingStyle.DEFAULT }, onFailure = {},
            clock = { test.testScheduler.currentTime },
        )
        private val revoiced = object : RealtimeVoiceProvider by fake {
            override fun events(): Flow<RealtimeEvent> = revoicer.revoice(fake.events())
        }
        val core = VoiceSessionController(
            provider = revoiced, microphone = InMemoryMicrophonePort(), playback = playback, scope = test,
            config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
            callbacks = VoiceSessionCallbacks(
                onToolCall = { ToolDispatchResult(null, null, deferredOutput = { toolGate.await() }) },
            ),
        )

        init { core.start(); test.runCurrent() }

        fun emit(vararg events: DomainVoiceEvent) { events.forEach(fake::emit); test.runCurrent() }
        fun after(ms: Long) { test.advanceTimeBy(ms); test.runCurrent() }
        fun played(): List<String> = playback.played.map { String(it).trimEnd('\u0000') }
    }

    @Test
    fun a_cueIsHeardAfterAnEarlierReplyCompleted() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(
            DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("你好。"),
            DomainVoiceEvent.AudioDone, DomainVoiceEvent.ResponseDone("completed"),
        )
        assertEquals(listOf("你好。"), rig.played())
        rig.emit(DomainVoiceEvent.SpeechStopped)
        rig.after(2_000)
        assertEquals(listOf("你好。", WaitCues.ACK_CHAT), rig.played())
        rig.core.stop()
    }

    @Test
    fun b_cueIsHeardAfterAnInterruptedReply() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("很长的一句话。"))
        rig.emit(DomainVoiceEvent.Interrupted("turn_detected"), DomainVoiceEvent.ResponseDone("cancelled"))
        rig.emit(DomainVoiceEvent.SpeechStopped)
        rig.after(1_700)
        assertEquals(emptyList<String>(), rig.played())
        rig.after(200)
        assertEquals(listOf(WaitCues.ACK_CHAT), rig.played())
        rig.core.stop()
    }

    @Test
    fun c_replyAfterTheVerifyingCuePlaysAfterIt() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.SpeechStopped)
        rig.after(500)
        rig.emit(DomainVoiceEvent.ResponseStarted)
        rig.after(4_600)
        assertEquals(listOf(WaitCues.ACK_CHAT, WaitCues.VERIFYING), rig.played())
        rig.emit(DomainVoiceEvent.SpeechText("今天晴。"), DomainVoiceEvent.AudioDone)
        assertEquals(listOf(WaitCues.ACK_CHAT, WaitCues.VERIFYING, "今天晴。"), rig.played())
        rig.core.stop()
    }

    @Test
    fun d_strayTextAfterInterruptedStaysSilentEvenAfterACue() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("很长的一句话。"))
        rig.emit(DomainVoiceEvent.Interrupted("turn_detected"))
        rig.emit(DomainVoiceEvent.SpeechStopped)
        rig.after(2_000)
        assertEquals(listOf(WaitCues.ACK_CHAT), rig.played())
        rig.emit(DomainVoiceEvent.SpeechText("迟到的半句。"))
        rig.after(100)
        assertEquals(listOf(WaitCues.ACK_CHAT), rig.played())
        rig.core.stop()
    }

    @Test
    fun e_aCueDuringADeferredToolDoesNotBlockItsResultDelivery() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.SpeechStopped)
        rig.emit(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
        rig.after(1_100)
        assertEquals(listOf(WaitCues.ACK_ACTION), rig.played())
        assertEquals(VoiceUiState.THINKING, rig.core.machine.state)
        rig.toolGate.complete("""{"ok":true}""")
        rig.after(100)
        assertEquals(listOf("c1"), rig.fake.workInjections.map { it.callId }, "the tool result reached the model")
        rig.core.stop()
    }
}
