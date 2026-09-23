package com.novadrive.app.voice

/**
 * Cross-thread playout delay snapshot for AEC3. Updated on the playback thread after each 10 ms
 * write; read on capture before [WebRtcAcousticEcho.processCapture].
 */
object VoicePlayoutDelay {
    data class Snapshot(
        /** Null when [AudioTrack.getTimestamp] is unavailable — do not report 0 ms delay. */
        val playoutDelayMs: Int?,
        val appQueueMs: Int,
        val trackBufferMs: Int,
        val underrunCount: Int,
        val outputSampleRateHz: Int,
        val playbackActive: Boolean,
    )

    @Volatile
    private var latest: Snapshot? = null

    fun publish(snapshot: Snapshot) {
        latest = snapshot
    }

    fun snapshot(): Snapshot? = latest

    fun clear() {
        latest = null
    }

    /** PushSincResampler kernel delay for render resampling into 16 kHz AEC frames. */
    fun resamplerDelayMs(sourceRateHz: Int): Int {
        if (sourceRateHz == PcmAudioCapture.SAMPLE_RATE) return 0
        return (1000.0 / sourceRateHz * RESAMPLER_KERNEL_HALF).toInt()
    }

    private const val RESAMPLER_KERNEL_HALF = 16
}
