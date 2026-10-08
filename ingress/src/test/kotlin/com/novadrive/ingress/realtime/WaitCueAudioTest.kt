package com.novadrive.ingress.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

/** SPEC-020: a wait cue plays in a stamp of its own and is not a model reply. */
@OptIn(ExperimentalCoroutinesApi::class)
class WaitCueAudioTest {
    private class Rig(scope: TestScope) {
        val provider = FakeRealtimeVoiceProvider()
        val playback = InMemoryPlaybackPort()
        val core = VoiceSessionController(
            provider = provider, microphone = InMemoryMicrophonePort(), playback = playback, scope = scope,
            config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
        )
        init { core.start() }
        fun audio(tag: String) = DomainVoiceEvent.AudioDelta(b64(tag))
        fun cue(tag: String) = DomainVoiceEvent.WaitCueAudio(b64(tag))
        fun emit(vararg e: DomainVoiceEvent) = e.forEach(provider::emit)
        fun played() = playback.played.map { String(it) }
        private fun b64(tag: String) = Base64.getEncoder().encodeToString(tag.toByteArray())
    }

    @Test
    fun aCueAfterACompletedReplyIsPlayed() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.ResponseStarted, rig.audio("r1"), DomainVoiceEvent.AudioDone, DomainVoiceEvent.ResponseDone("completed"))
        rig.emit(rig.cue("cu"))
        assertEquals(listOf("r1", "cu"), rig.played())
        rig.emit(rig.audio("st"))
        assertEquals(listOf("r1", "cu"), rig.played(), "the completed reply stays closed")
        rig.core.stop()
    }

    @Test
    fun aCueAfterInterruptedIsPlayedAndStrayAudioStillDropped() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.ResponseStarted, rig.audio("r1"), DomainVoiceEvent.Interrupted("x"))
        rig.emit(rig.cue("cu"))
        assertEquals(listOf("cu"), rig.played())
        rig.emit(rig.audio("st"))
        assertEquals(listOf("cu"), rig.played())
        rig.core.stop()
    }

    @Test
    fun aCueInsideAnOpenReplyIsFollowedByThatReply() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.ResponseStarted, rig.cue("cu"), rig.audio("r1"), DomainVoiceEvent.AudioDone)
        assertEquals(listOf("cu", "r1"), rig.played())
        rig.core.stop()
    }

    @Test
    fun aCueChangesNoSessionStateAndIsNotFirstAudio() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this)
        rig.emit(DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.SpeechStopped)
        val before = rig.core.machine.state
        rig.emit(rig.cue("cu"))
        assertEquals(listOf("cu"), rig.played())
        assertEquals(before, rig.core.machine.state)
        assertNull(rig.core.diagnostics.metrics.speechEndToFirstAudioMs)
        rig.core.stop()
    }
}
