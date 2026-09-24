package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PlayoutClockBargeInTest {
    private val repoRoot: File
        get() = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    @Test
    fun playerTracksQueuedFramesAndEpoch() {
        val player = repoRoot.resolve("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt").readText()
        assertTrue(player.contains("fun enqueue(pcm16le: ByteArray, epoch: Int)"))
        assertTrue(player.contains("val queuedFrames: Int"))
        assertTrue(player.contains("val playbackActive: Boolean"))
        assertTrue(player.contains("fun flush(epoch: Int)"))
        assertTrue(player.contains("epochEngine.acceptEpoch") || player.contains("acceptEpoch = epoch"))
        assertTrue(player.contains("private val outputLock = Any()"))
        assertTrue(player.contains("VoiceAudioSession.applyToTrackBuilder"))
    }

    @Test
    fun modelPlaybackDoesNotGateMicrophonePort() {
        val controller = repoRoot.resolve("app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt").readText()
        assertFalse(controller.contains("microphone.gated = true"))
        assertFalse(controller.contains("holdPostSpeechEcho"))
        assertTrue(controller.contains("playbackSpeaking = speaking"))
        assertTrue(controller.contains("VoiceAudioSession.allocate"))
    }

    @Test
    fun flexSessionEnablesInterruptResponse() {
        val protocol = repoRoot.resolve("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProtocol.kt").readText()
        assertTrue(protocol.contains("\"interrupt_response\", true"))
    }
}
