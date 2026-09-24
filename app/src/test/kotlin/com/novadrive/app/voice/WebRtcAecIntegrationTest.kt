package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WebRtcAecIntegrationTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).readText().replace("\r\n", "\n")

    @Test
    fun renderHookRunsBeforeAudioTrackWrite() {
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val writeIndex = player.indexOf("player.write(")
        val renderIndex = player.indexOf("processRender(accepted")
        assertTrue(renderIndex >= 0)
        assertTrue(writeIndex >= 0)
        assertTrue(renderIndex > writeIndex)
        assertTrue(player.contains("AudioTrack.WRITE_NON_BLOCKING"))
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
        // Astra P4: time-scoped evidence, one answer for the playback owner and the turn.
        assertTrue(controller.contains("microphone.recentSpeech"))
        assertTrue(controller.contains("qualifyPlayoutBargeIn = { bargeInQualified() }"))
        assertTrue(controller.contains("::bargeInQualified"))
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

    @Test
    fun native16KhzRenderRetainsPartialTenMsFrameAcrossCalls() {
        val native = text("app/src/main/cpp/nova_aec.cpp")
        assertTrue(native.contains("bool render_rate_initialized = false;"))
        assertTrue(native.contains("if (impl_->render_rate_initialized && impl_->render_source_rate_hz == sample_rate_hz)"))
        assertTrue(native.contains("impl_->render_source_leftover.insert("))
        assertTrue(native.contains("while (impl_->render_source_leftover.size() >= kFrameSamples)"))
        assertTrue(native.contains("FeedRenderFrame(impl_->render_source_leftover.data());"))
    }

    @Test
    fun capturePendingResultIsDroppedInsteadOfSendingUnprocessedPcm() {
        val capture = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        val echo = text("app/src/main/kotlin/com/novadrive/app/voice/WebRtcAcousticEcho.kt")
        assertTrue(echo.contains("processed.isEmpty() -> AecCaptureResult.Pending"))
        assertTrue(capture.contains("AecCaptureResult.Pending -> null"))
        assertTrue(capture.contains("if (uploadFrame != null) onFrame(uploadFrame)"))
        assertTrue(capture.contains("AecCaptureResult.Failed -> {"))
    }

    @Test
    fun playerUsesExplicitEpochAndRetainsPositiveShortWriteSuffix() {
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val engine = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/PlaybackEpochEngine.kt")
        assertTrue(player.contains("fun start(epoch: Int = 0)"))
        assertTrue(player.contains("fun flush(epoch: Int)"))
        assertTrue(engine.contains("epoch != acceptEpoch"))
        assertTrue(player.contains("AudioTrack.WRITE_NON_BLOCKING"))
        assertTrue(engine.contains("pendingSliceOffset += written"))
        assertTrue(player.contains("pending.copyOfRange(epochEngine.pendingSliceOffset, epochEngine.pendingSliceOffset + written)"))
        assertTrue(player.contains("synchronized(outputLock) { flushLocked(epoch) }"))
    }

    @Test
    fun responseCompletionPadsOnlyTheFinalPartialPlaybackFrame() {
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val engine = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/PlaybackEpochEngine.kt")
        val port = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/AudioPorts.kt")
        val controller = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt")
        assertTrue(port.contains("fun complete(epoch: Int)"))
        assertTrue(player.contains("fun complete(epoch: Int)"))
        assertTrue(engine.contains("completedThroughEpoch = maxOf(completedThroughEpoch, epoch)"))
        assertTrue(engine.contains("slice.fill(0)"))
        assertTrue(controller.contains("playback.complete(replyEpoch)"))
        assertTrue(controller.contains("playback.beginReply(playbackEpoch)"))
    }

    @Test
    fun captureResourcesRemainOwnedUntilTheReadWorkerStops() {
        val capture = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt")
        val stop = capture.substring(capture.indexOf("fun stop()"))
        val stopRecorder = stop.indexOf("recorder?.run")
        val joinWorker = stop.indexOf("BoundedThreadCleanup.terminate(toJoin)")
        assertTrue(capture.contains("if (previousWorker?.isAlive == true)"))
        assertTrue(stopRecorder >= 0 && joinWorker > stopRecorder)
        assertTrue(stop.substring(stopRecorder, joinWorker).contains("stop()"))
        assertTrue(stop.indexOf("releaseCaptureResources()") > joinWorker)
        assertTrue(stop.contains("if (joined)"))
        assertTrue(stop.contains("toJoin.join()"))
        assertTrue(stop.contains("if (worker === toJoin)"))
        assertTrue(capture.contains("finally {\n                    running.set(false)"))
    }

    @Test
    fun playerEnforcesFiveHundredMillisecondAppQueueCeiling() {
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val engine = text("ingress/src/main/kotlin/com/novadrive/ingress/realtime/PlaybackEpochEngine.kt")
        assertTrue(engine.contains("AppPlaybackQueuePolicy.wouldExceedLimit"))
        assertTrue(player.contains("failReplyLocked(epoch)"))
        assertTrue(engine.contains("completedThroughEpoch = maxOf(completedThroughEpoch, epoch)"))
        assertTrue(player.contains("onError(\"AUDIO_PLAYBACK_FAILED\")"))
    }

    @Test
    fun playerResourcesRemainOwnedUntilTheWriteWorkerStops() {
        val player = text("app/src/main/kotlin/com/novadrive/app/voice/PcmAudioPlayer.kt")
        val stop = player.substring(player.indexOf("fun stop()"))
        val stopTrack = stop.indexOf("track?.run")
        val joinWorker = stop.indexOf("BoundedThreadCleanup.terminate(toJoin)")
        assertTrue(player.contains("if (previousWorker?.isAlive == true)"))
        assertTrue(stopTrack >= 0 && joinWorker > stopTrack)
        assertTrue(stop.substring(stopTrack, joinWorker).contains("stop()"))
        assertTrue(stop.indexOf("releaseTrackLocked()") > joinWorker)
        assertTrue(stop.contains("if (joined)"))
        assertTrue(stop.contains("toJoin.join()"))
        assertTrue(stop.contains("if (worker === toJoin)"))
        assertTrue(stop.contains("nova-pcm-play-cleanup"))
    }
}
