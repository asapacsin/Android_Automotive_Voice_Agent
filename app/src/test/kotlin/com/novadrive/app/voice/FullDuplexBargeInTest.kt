package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FullDuplexBargeInTest {
    private val repoRoot: File
        get() = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    @Test
    fun modelPlaybackDoesNotGateMicrophonePort() {
        val controller = repoRoot.resolve("app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt").readText()
        assertFalse(controller.contains("microphone.gated = true"))
        assertFalse(controller.contains("holdPostSpeechEcho"))
        assertTrue(controller.contains("playbackSpeaking = speaking"))
    }

    @Test
    fun flexSessionEnablesInterruptResponse() {
        val protocol = repoRoot.resolve("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProtocol.kt").readText()
        assertTrue(protocol.contains("\"interrupt_response\", true"))
    }
}
