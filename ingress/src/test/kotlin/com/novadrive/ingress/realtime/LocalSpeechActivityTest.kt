package com.novadrive.ingress.realtime

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR-010: a provider with no speech events of its own (Gemini Live) takes "driver speaking" from
 * the local uplink gate, and gets exactly the handling a server event gets. Every other provider
 * ignores the local signal, so nothing is counted twice. Behaviour varies on the capability flag,
 * never on the provider name (I-13).
 */
class LocalSpeechActivityTest {
    private class FlaggedProvider(
        val fake: FakeRealtimeVoiceProvider,
        override val capabilities: ProviderCapabilities,
    ) : RealtimeVoiceProvider by fake {
        val localActivity = mutableListOf<Boolean>()
        override fun onLocalSpeechActivity(active: Boolean) {
            localActivity += active
        }
    }

    private fun controller(provider: RealtimeVoiceProvider, playback: InMemoryPlaybackPort, scope: kotlinx.coroutines.CoroutineScope) =
        VoiceSessionController(
            provider = provider,
            microphone = InMemoryMicrophonePort(),
            playback = playback,
            scope = scope,
            config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
        )

    @Test
    fun withoutServerSpeechEventsLocalOnsetActsAsSpeechStarted() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeRealtimeVoiceProvider()
            val provider = FlaggedProvider(fake, VoiceCatalog.capabilities(VoiceProviderId.GEMINI_LIVE))
            val playback = InMemoryPlaybackPort()
            val controller = controller(provider, playback, this)
            controller.start()
            fake.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
            controller.onLocalSpeechActivity(true)
            assertEquals(listOf(true), provider.localActivity)
            assertEquals(VoiceUiState.USER_SPEAKING, controller.machine.state)
            controller.onLocalSpeechActivity(false)
            assertEquals(listOf(true, false), provider.localActivity)
            assertFalse(controller.machine.state == VoiceUiState.USER_SPEAKING)
            controller.stop()
        }

    @Test
    fun localOnsetDuringPlaybackIsABargeInCandidate() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeRealtimeVoiceProvider()
            val provider = FlaggedProvider(fake, VoiceCatalog.capabilities(VoiceProviderId.GEMINI_LIVE))
            val playback = InMemoryPlaybackPort()
            val controller = controller(provider, playback, this)
            controller.start()
            fake.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            assertTrue(playback.playbackActive)
            controller.onLocalSpeechActivity(true)
            assertTrue(playback.flushCount >= 1)
            assertTrue(controller.logs.any { it.contains("playout_barge_in") })
            controller.stop()
        }

    @Test
    fun withServerSpeechEventsTheLocalSignalIsIgnored() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeRealtimeVoiceProvider()
            val provider = FlaggedProvider(fake, VoiceCatalog.capabilities(VoiceProviderId.BAIDU_FLEX))
            val playback = InMemoryPlaybackPort()
            val controller = controller(provider, playback, this)
            controller.start()
            fake.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
            val before = controller.machine.state
            controller.onLocalSpeechActivity(true)
            assertTrue(provider.localActivity.isEmpty())
            assertEquals(before, controller.machine.state)
            controller.stop()
        }

    @Test
    fun geminiCapabilitiesAreDeclaredAsMeasured() {
        val caps = VoiceCatalog.capabilities(VoiceProviderId.GEMINI_LIVE)
        assertFalse(caps.serverSpeechActivityEvents)
        assertFalse(caps.clientResponseCancel)
        assertTrue(caps.customTools)
        assertTrue(caps.toolCallCancellation)
        assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceProviderId.fromWire("gemini_live"))
        assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceCatalog.providerForModel(VoiceCatalog.GEMINI_LIVE_DEFAULT))
        // Every provider that exists today keeps the server as its speech source.
        VoiceProviderId.entries.filter { it != VoiceProviderId.GEMINI_LIVE }.forEach {
            assertTrue(VoiceCatalog.capabilities(it).serverSpeechActivityEvents) { "$it" }
        }
    }
}
