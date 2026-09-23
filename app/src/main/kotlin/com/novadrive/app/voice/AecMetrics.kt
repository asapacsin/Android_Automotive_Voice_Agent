package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog

/** Playback/capture RMS diagnostics for WebRTC AEC3; no audio or transcript in logs. */
object AecMetrics {
    @Volatile private var lastRawRms: Double = 0.0
    @Volatile private var lastPostAecRms: Double = 0.0
    @Volatile private var lastRenderRms: Double = 0.0
    @Volatile private var lastStreamDelayMs: Int = 0
    @Volatile private var lastLogElapsedMs: Long = 0L

    fun noteRender(renderRms: Double) {
        lastRenderRms = renderRms
    }

    fun noteCapture(rawRms: Double, postAecRms: Double) {
        lastRawRms = rawRms
        lastPostAecRms = postAecRms
    }

    /**
     * True when [speech_started] during playback should flush the reply. False when post-AEC energy
     * is far below raw — AEC cancelled the echo and Baidu is reacting to residual uplink.
     */
    fun shouldFlushBargeIn(): Boolean {
        if (VoiceAudioSession.aecBackend != "webrtc") return true
        if (lastRawRms < 50.0) return true
        val ratio = lastPostAecRms / lastRawRms
        if (ratio < 0.5) return false
        return true
    }

    fun maybeLog(
        playbackActive: Boolean,
        streamDelayMs: Int?,
        stats: AecStats,
        appQueueMs: Int,
        trackBufferMs: Int,
        underrunCount: Int,
    ) {
        if (!playbackActive || VoiceAudioSession.aecBackend != "webrtc") return
        if (streamDelayMs != null) {
            lastStreamDelayMs = streamDelayMs
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogElapsedMs < 1000L) return
        lastLogElapsedMs = now
        val attenuationDb =
            if (lastPostAecRms > 0.0 && lastRawRms > 0.0) {
                20.0 * kotlin.math.log10(lastRawRms / lastPostAecRms)
            } else {
                0.0
            }
        DebugVoiceLog.log(
            "aec_metrics backend=webrtc raw_rms=${"%.0f".format(lastRawRms)} " +
                "post_aec_rms=${"%.0f".format(lastPostAecRms)} render_rms=${"%.0f".format(lastRenderRms)} " +
                "stream_delay_ms=${streamDelayMs ?: -1} attenuation_db=${"%.1f".format(attenuationDb)} " +
                "app_queue_ms=$appQueueMs track_buffer_ms=$trackBufferMs underrun_count=$underrunCount " +
                "playback_active=$playbackActive render_frames=${stats.renderFrames} " +
                "capture_frames=${stats.captureFrames} render_leftover_drops=${stats.renderLeftoverDrops}",
        )
    }

    fun logBargeIn(streamDelayMs: Int, stats: AecStats, flush: Boolean, suppressed: Boolean = false) {
        if (VoiceAudioSession.aecBackend != "webrtc") return
        val suffix = if (suppressed) " suppressed=true reason=residual_echo" else " flush=$flush"
        DebugVoiceLog.log(
            "aec_barge_in backend=webrtc stream_delay_ms=$streamDelayMs " +
                "raw_rms=${"%.0f".format(lastRawRms)} post_aec_rms=${"%.0f".format(lastPostAecRms)} " +
                "render_rms=${"%.0f".format(lastRenderRms)} render_frames=${stats.renderFrames}$suffix",
        )
    }

    fun reset() {
        lastRawRms = 0.0
        lastPostAecRms = 0.0
        lastRenderRms = 0.0
        lastStreamDelayMs = 0
        lastLogElapsedMs = 0L
    }
}
