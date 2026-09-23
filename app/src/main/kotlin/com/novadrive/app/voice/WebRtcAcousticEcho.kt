package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog

/**
 * WebRTC AEC3 echo cancellation for the Baidu PCM path. Render is fed immediately before
 * [android.media.AudioTrack.write]; capture is cleaned before gain and [SpeechUplinkGate].
 */
class WebRtcAcousticEcho private constructor(private val nativeHandle: Long) {
    val isAvailable: Boolean = nativeHandle != 0L

    @Volatile
    var streamDelayMs: Int = 0

    fun processRender(pcm16le: ByteArray, sampleRateHz: Int) {
        if (!isAvailable || pcm16le.isEmpty()) return
        nativeProcessRender(nativeHandle, pcm16le, sampleRateHz)
        AecMetrics.noteRender(pcmRms(pcm16le))
    }

    fun processCapture(pcm16le: ByteArray): ByteArray {
        if (!isAvailable || pcm16le.isEmpty()) return pcm16le
        nativeSetStreamDelayMs(nativeHandle, streamDelayMs)
        val rawRms = pcmRms(pcm16le)
        val processed = nativeProcessCapture(nativeHandle, pcm16le)
        val out = if (processed.isEmpty()) pcm16le else processed
        AecMetrics.noteCapture(rawRms, pcmRms(out))
        return out
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
        }
    }

    fun snapshotStats(): AecStats {
        if (!isAvailable) return AecStats()
        val values = nativeGetStats(nativeHandle)
        return AecStats(
            rawRms = values.getOrElse(0) { 0.0 },
            postAecRms = values.getOrElse(1) { 0.0 },
            renderRms = values.getOrElse(2) { 0.0 },
            renderFrames = values.getOrElse(3) { 0.0 }.toLong(),
            captureFrames = values.getOrElse(4) { 0.0 }.toLong(),
            renderLeftoverDrops = values.getOrElse(5) { 0.0 }.toLong(),
        )
    }

    companion object {
        private var libraryLoaded = false

        init {
            libraryLoaded =
                runCatching {
                    System.loadLibrary("nova_aec")
                    true
                }.getOrElse {
                    DebugVoiceLog.log("aec_backend=unavailable reason=native_load_failed")
                    false
                }
        }

        fun create(): WebRtcAcousticEcho {
            if (!libraryLoaded) return WebRtcAcousticEcho(0L)
            val handle = runCatching { nativeCreate() }.getOrElse {
                DebugVoiceLog.log("aec_backend=unavailable reason=native_create_failed")
                0L
            }
            if (handle != 0L) {
                DebugVoiceLog.log("aec_backend=webrtc")
            }
            return WebRtcAcousticEcho(handle)
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeRelease(handle: Long)
        @JvmStatic private external fun nativeSetStreamDelayMs(handle: Long, delayMs: Int)
        @JvmStatic private external fun nativeProcessRender(handle: Long, pcm16le: ByteArray, sampleRateHz: Int)
        @JvmStatic private external fun nativeProcessCapture(handle: Long, pcm16le: ByteArray): ByteArray
        @JvmStatic private external fun nativeGetStats(handle: Long): DoubleArray
    }
}

data class AecStats(
    val rawRms: Double = 0.0,
    val postAecRms: Double = 0.0,
    val renderRms: Double = 0.0,
    val renderFrames: Long = 0,
    val captureFrames: Long = 0,
    val renderLeftoverDrops: Long = 0,
)

/** Session-scoped AEC instance shared by playback and capture threads. */
object VoiceAec {
    @Volatile
    var instance: WebRtcAcousticEcho? = null
        private set

    val backendLabel: String
        get() = when {
            instance?.isAvailable == true -> "webrtc"
            else -> "unavailable"
        }

    fun open(): WebRtcAcousticEcho {
        release()
        val created = WebRtcAcousticEcho.create()
        instance = created
        VoiceAudioSession.aecBackend = backendLabel
        return created
    }

    fun release() {
        instance?.release()
        instance = null
        VoiceAudioSession.aecBackend = "unavailable"
        AecMetrics.reset()
    }
}

internal fun pcmRms(bytes: ByteArray): Double {
    if (bytes.size < 2) return 0.0
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < bytes.size) {
        val sample = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
        sum += sample * sample.toDouble()
        count++
        i += 2
    }
    if (count == 0) return 0.0
    return kotlin.math.sqrt(sum / count)
}
