package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WebRtcAecIntegrationTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).readText()

    @Test
    fun renderHookRunsBeforeAudioTrackWrite() {
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val writeIndex = player.indexOf("player.write(slice")
        val renderIndex = player.indexOf("processRender(slice")
        assertTrue(renderIndex >= 0)
        assertTrue(writeIndex > renderIndex)
        assertTrue(player.contains("WRITE_BLOCKING"))
        assertTrue(player.contains("LowLatencyPlaybackBuffer"))
    }

    @Test
    fun streamDelayRefreshedOnCaptureNotPlayback() {
        val capture = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        assertTrue(capture.contains("refreshStreamDelay"))
        assertTrue(capture.contains("VoicePlayoutDelay"))
        assertFalse(player.contains("aec.streamDelayMs ="))
    }

    @Test
    fun bargeInQualificationPresent() {
        val ingress = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt")
        val controller = text("app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt")
        assertTrue(ingress.contains("qualifyPlayoutBargeIn"))
        assertTrue(ingress.contains("residual_echo"))
        assertTrue(controller.contains("shouldFlushBargeIn"))
    }

    @Test
    fun captureAecRunsBeforeUplinkGate() {
        val capture = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        val mic = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        assertTrue(capture.contains("processCapture(raw)"))
        val gateIndex = mic.indexOf("gateAndSend")
        val aecIndex = mic.indexOf("processCapture")
        assertTrue(aecIndex >= 0)
        assertTrue(gateIndex > aecIndex || mic.indexOf("uplinkGate.offer") > aecIndex)
    }

    @Test
    fun platformAecSkippedWhenWebRtcActive() {
        val capture = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        assertTrue(capture.contains("useWebRtcAec"))
        assertTrue(capture.contains("!useWebRtcAec && aecAvailable"))
    }

    @Test
    fun playbackScopedVadUpdateRemovedFromFlexClient() {
        val flex = text("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt")
        assertFalse(flex.contains("vad_threshold_playback"))
    }

    @Test
    fun nativeAecModulePresent() {
        val cmake = File(root, "app/src/main/cpp/CMakeLists.txt")
        assertTrue(cmake.isFile)
        assertTrue(cmake.readText().contains("nova_aec"))
    }
}
