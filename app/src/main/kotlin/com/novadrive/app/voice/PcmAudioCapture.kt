package com.novadrive.app.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.novadrive.ingress.realtime.AudioBufferGuard
import com.novadrive.ingress.realtime.BoundedThreadCleanup
import com.novadrive.ingress.realtime.MicrophonePort
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 16 kHz PCM16 capture. Permission/start failures are reported via [onError].
 * Hardware AEC, Bluetooth SCO, and live RECORD_AUDIO behavior are manual-test-only.
 */
class PcmAudioCapture(
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuf =
            try {
                AudioBufferGuard.requireValidMinBuffer(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    "AUDIO_CAPTURE_FAILED",
                )
            } catch (_: IllegalArgumentException) {
                running.set(false)
                onError("AUDIO_CAPTURE_FAILED")
                return
            }
        val recorder =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (security: SecurityException) {
                running.set(false)
                onError("MIC_PERMISSION_DENIED")
                return
            } catch (_: Exception) {
                running.set(false)
                onError("AUDIO_CAPTURE_FAILED")
                return
            }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            onError("AUDIO_CAPTURE_FAILED")
            return
        }
        echoCanceler =
            if (AcousticEchoCanceler.isAvailable()) {
                runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            } else {
                null
            }
        noiseSuppressor =
            if (NoiseSuppressor.isAvailable()) {
                runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            } else {
                null
            }
        try {
            recorder.startRecording()
        } catch (_: Exception) {
            releaseCaptureEffects()
            recorder.release()
            running.set(false)
            onError("AUDIO_CAPTURE_FAILED")
            return
        }
        record = recorder
        worker =
            thread(name = "nova-pcm-capture", isDaemon = true) {
                val buf = ByteArray(FRAME_BYTES)
                while (running.get()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        onFrame(buf.copyOf(read))
                    } else if (read < 0) {
                        onError("AUDIO_CAPTURE_FAILED")
                        break
                    }
                }
            }
    }

    fun stop() {
        running.set(false)
        releaseCaptureEffects()
        record?.run {
            try {
                stop()
            } catch (_: Exception) {
            }
            try {
                release()
            } catch (_: Exception) {
            }
        }
        record = null
        val toJoin = worker
        worker = null
        BoundedThreadCleanup.terminate(toJoin)
    }

    private fun releaseCaptureEffects() {
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_BYTES = 3200
    }
}

class AndroidMicrophonePort(
    private val onError: (String) -> Unit,
) : MicrophonePort {
    private var capture: PcmAudioCapture? = null
    override var muted: Boolean = false
    @Volatile var gated: Boolean = false

    override fun start(onFrame: (ByteArray) -> Unit) {
        stop()
        capture =
            PcmAudioCapture(
                onFrame = { bytes -> if (!muted && !gated) onFrame(bytes) },
                onError = onError,
            )
        capture?.start()
    }

    override fun stop() {
        capture?.stop()
        capture = null
    }
}
