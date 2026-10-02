package com.novadrive.app.voice

import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.SpeakingStyle
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.RealtimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * ADR-016 §5 / SPEC-019 R5: the session decides barge-in; the provider only carries that decision to
 * the voice. With no voice configured, nothing about the provider changes.
 */
class GeminiLiveProviderVoiceTest {
    private val config = GeminiApiConfig(GeminiAppSettings(consentAccepted = true), apiKey = "k", instructions = "i")

    private class BlockingVoice : AssistantVoice {
        val inFlight = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            inFlight.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
    }

    /** Starts a reply whose first clause blocks, runs [action] on the provider, reports whether the voice was cancelled. */
    private fun cancelledBy(playbackActive: Boolean, action: suspend (GeminiLiveProvider) -> Unit): Boolean = runBlocking {
        val voice = BlockingVoice()
        val revoicer = AssistantVoiceRevoicer(voice, style = { SpeakingStyle.DEFAULT }, onFailure = {}, clock = { 0L })
        val provider = GeminiLiveProvider(config, GeminiLiveClient(), revoicer)
        provider.onPlaybackActiveChanged(playbackActive)
        val upstream = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val job = async(Dispatchers.Default) { revoicer.revoice(upstream.consumeAsFlow()).collect {} }
        upstream.send(RealtimeEvent(0L, DomainVoiceEvent.ResponseStarted))
        upstream.send(RealtimeEvent(0L, DomainVoiceEvent.SpeechText("很长的一句话。")))
        withTimeout(3_000) { voice.inFlight.await() }
        action(provider)
        delay(200)
        val result = voice.cancelled.isCompleted
        upstream.close()
        job.cancel()
        result
    }

    @Test
    fun driverSpeechOverAReplyNotYetAudibleDropsIt() {
        assertTrue(cancelledBy(playbackActive = false) { it.onLocalSpeechActivity(true) })
    }

    @Test
    fun driverSpeechWhilePlaybackRunsLeavesTheDecisionToTheSession() {
        assertFalse(cancelledBy(playbackActive = true) { it.onLocalSpeechActivity(true) })
    }

    @Test
    fun aFlushBySessionStopsTheVoice() {
        assertTrue(cancelledBy(playbackActive = true) { it.onPlaybackFlushed() })
    }

    @Test
    fun aClientCancelStopsTheVoice() {
        assertTrue(cancelledBy(playbackActive = true) { it.cancelActiveResponse() })
    }

    @Test
    fun withoutAVoiceTheProviderDoesNotRevoice() {
        assertFalse(GeminiLiveProvider(config, lastAudioSegment = { null }).revoicing)
        assertTrue(GeminiLiveProvider(config, lastAudioSegment = { null }, assistantVoice = BlockingVoice()).revoicing)
        assertEquals(false, GeminiLiveProvider(config).revoicing)
    }
}
