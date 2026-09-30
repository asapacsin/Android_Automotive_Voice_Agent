package com.novadrive.ingress.realtime

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-018 correlation contract, session-core side: send now or fail; markers keep audio order. */
class AppPromptContractTest {
    private class PromptProvider(
        val fake: FakeRealtimeVoiceProvider,
        override val capabilities: ProviderCapabilities,
    ) : RealtimeVoiceProvider by fake {
        val prompts = mutableListOf<String>()
        val texts = mutableListOf<String>()
        override fun sendPrompt(text: String, promptId: String): Boolean {
            prompts += promptId
            return true
        }
        override suspend fun sendText(text: String) {
            texts += text
        }
    }

    private class RecordingPlayback : PlaybackPort {
        val order = mutableListOf<String>()
        override fun start() {}
        override fun enqueue(pcm16le: ByteArray, epoch: Int) { order += "audio@$epoch" }
        override fun flush(epoch: Int) {}
        override fun stop() {}
        override fun onAppPromptTurn(promptId: String, phase: DomainVoiceEvent.AppPromptTurn.Phase, epoch: Int) {
            order += "$promptId:$phase@$epoch"
        }
    }

    private val gemini = VoiceCatalog.capabilities(VoiceProviderId.GEMINI_LIVE)

    private fun controller(
        provider: RealtimeVoiceProvider,
        playback: PlaybackPort,
        scope: kotlinx.coroutines.CoroutineScope,
        callbacks: VoiceSessionCallbacks = VoiceSessionCallbacks(),
    ) = VoiceSessionController(
        provider = provider,
        microphone = InMemoryMicrophonePort(),
        playback = playback,
        scope = scope,
        config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
        callbacks = callbacks,
    )

    @Test
    fun capabilityIsGeminiOnly() {
        assertTrue(gemini.verbatimPromptSpeech)
        assertFalse(VoiceCatalog.capabilities(VoiceProviderId.BAIDU_FLEX).verbatimPromptSpeech)
        assertFalse(VoiceCatalog.capabilities(VoiceProviderId.FAKE).verbatimPromptSpeech)
        assertFalse(FakeRealtimeVoiceProvider().sendPrompt("x", "p"))
    }

    @Test
    fun sendPromptFailsWhenNotConnectedAndNeverQueues() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = PromptProvider(FakeRealtimeVoiceProvider(), gemini)
            val controller = controller(provider, RecordingPlayback(), this)
            assertFalse(controller.sendPrompt("前方左转", "p1"), "no session")
            provider.fake.failNextConnectWith = "SERVER_DISCONNECT"
            controller.start()
            assertFalse(controller.connectedNow)
            assertFalse(controller.sendPrompt("前方左转", "p2"))
            assertTrue(provider.prompts.isEmpty())
            controller.stop()
            // A later connection replays nothing.
            controller.start()
            assertTrue(controller.connectedNow)
            assertTrue(provider.prompts.isEmpty())
            assertTrue(provider.texts.isEmpty())
            controller.stop()
        }

    @Test
    fun sendPromptDelegatesWhenConnectedAndCapable() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = PromptProvider(FakeRealtimeVoiceProvider(), gemini)
            val controller = controller(provider, RecordingPlayback(), this)
            controller.start()
            assertTrue(controller.sendPrompt("前方左转", "p1"))
            assertEquals(listOf("p1"), provider.prompts)
            assertTrue(provider.texts.isEmpty())
            controller.stop()
        }

    @Test
    fun sendPromptFailsWithoutCapability() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = PromptProvider(FakeRealtimeVoiceProvider(), VoiceCatalog.capabilities(VoiceProviderId.BAIDU_FLEX))
            val controller = controller(provider, RecordingPlayback(), this)
            controller.start()
            assertFalse(controller.sendPrompt("前方左转", "p1"))
            assertTrue(provider.prompts.isEmpty())
            controller.stop()
        }

    @Test
    fun appPromptTurnIsForwardedInOrderWithAudio() =
        runTest(UnconfinedTestDispatcher()) {
            val provider = PromptProvider(FakeRealtimeVoiceProvider(), gemini)
            val playback = RecordingPlayback()
            val seen = mutableListOf<String>()
            val controller = controller(
                provider, playback, this,
                VoiceSessionCallbacks(onAppPromptTurn = { id, phase -> seen += "$id:$phase" }),
            )
            controller.start()
            val fake = provider.fake
            fake.emit(DomainVoiceEvent.ResponseStarted)
            fake.emit(DomainVoiceEvent.AppPromptTurn("p1", DomainVoiceEvent.AppPromptTurn.Phase.OPENED))
            fake.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            fake.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            fake.emit(DomainVoiceEvent.AppPromptTurn("p1", DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED))
            fake.emit(DomainVoiceEvent.ResponseDone("completed"))
            val relevant = playback.order
            assertEquals(4, relevant.size, relevant.toString())
            assertTrue(relevant[0].startsWith("p1:OPENED@"))
            assertTrue(relevant[1].startsWith("audio@"))
            assertTrue(relevant[2].startsWith("audio@"))
            assertTrue(relevant[3].startsWith("p1:COMPLETED@"))
            // The marker carries the epoch its audio is stamped with.
            assertEquals(relevant[0].substringAfter('@'), relevant[1].substringAfter('@'))
            assertEquals(listOf("p1:OPENED", "p1:COMPLETED"), seen)
            controller.stop()
        }
}
