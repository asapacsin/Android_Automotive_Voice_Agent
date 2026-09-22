package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Model audio is streamed from Flex, not a separate TTS engine. Uplink must stay open during
 * playback so server VAD can barge in; playback PCM must never be injected as microphone input.
 */
class FullDuplexBargeInTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile) { "missing file: $path" }
    }.readText()

    @Test
    fun captureUsesVoiceCommunicationWithSharedSessionAec() {
        val capture = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val session = text("app/src/main/kotlin/com/novadrive/app/voice/VoiceAudioSession.kt")
        assertTrue(capture.contains("VOICE_COMMUNICATION") || capture.contains("AudioSource.VOICE_COMMUNICATION"))
        assertTrue(capture.contains("AcousticEchoCanceler"))
        assertTrue(capture.contains("VoiceAudioSession.applyToRecordBuilder"))
        assertTrue(player.contains("VoiceAudioSession.applyToTrackBuilder"))
        assertTrue(session.contains("sessionsMatch"))
    }

    @Test
    fun replyAudioGoesToPlaybackNotMicUplink() {
        val ingress = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt")
        assertTrue(ingress.contains("is DomainVoiceEvent.AudioDelta ->"))
        assertTrue(ingress.contains("playback.enqueue(pcm, replyEpoch)"))
        val flexClient = text("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt")
        assertTrue(flexClient.contains("fun sendAudio(pcm16le: ByteArray)"))
        assertFalse(
            flexClient.lines().any { line ->
                line.contains("response.audio.delta") && line.contains("sendAudio")
            },
        )
    }

    @Test
    fun bargeInUsesPlayoutClockNotUiState() {
        val ingress = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt")
        assertTrue(ingress.contains("playback.playbackActive"))
        assertTrue(ingress.contains("playout_barge_in"))
        assertTrue(ingress.contains("generationActive"))
    }
}
