package com.novadrive.app.voice

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.novadrive.ingress.realtime.AudioBufferGuard
import com.novadrive.ingress.realtime.BoundedThreadCleanup
import com.novadrive.ingress.realtime.MicrophonePort
import com.novadrive.ingress.realtime.mayStartAudioWorker
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 16 kHz PCM16 capture. Permission/start failures are reported via [onError].
 * Hardware AEC, Bluetooth SCO, and live RECORD_AUDIO behavior are manual-test-only.
 */
class PcmAudioCapture(
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val audioSessionId: Int = VoiceAudioSession.SESSION_ID_GENERATE,
) {
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var captureBufferDelayMs: Int = 0

    /** Frames read since the recorder started: the base of its timestamps. Capture thread only. */
    private var framesRead = 0L
    private val captureStamp = android.media.AudioTimestamp()

    fun start() = synchronized(lifecycleLock) {
        val previousWorker = worker
        if (!mayStartAudioWorker(previousWorker?.isAlive == true, running.get())) {
            onError("AUDIO_CAPTURE_FAILED")
            return@synchronized
        }
        if (previousWorker?.isAlive == true) {
            return@synchronized
        }
        if (previousWorker != null) {
            releaseCaptureResources()
            worker = null
            record = null
        }
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
                val builder =
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                VoiceAudioSession.applyToRecordBuilder(builder, audioSessionId)
                builder.build()
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
        captureBufferDelayMs = (recorder.bufferSizeInFrames * 1000) / SAMPLE_RATE / 2
        framesRead = 0L
        val useWebRtcAec = VoiceAec.instance?.isAvailable == true
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        echoCanceler =
            if (!useWebRtcAec && aecAvailable) {
                runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            } else {
                null
            }
        val nsAvailable = NoiseSuppressor.isAvailable()
        noiseSuppressor =
            if (!useWebRtcAec && nsAvailable) {
                runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            } else {
                null
            }
        VoiceAudioSession.recordCaptureSession(recorder.audioSessionId)
        VoiceAudioSession.aecEnabled = useWebRtcAec || echoCanceler?.enabled == true
        VoiceAudioSession.nsEnabled = noiseSuppressor?.enabled == true
        com.novadrive.app.DebugVoiceLog.log(
            "capture_start aec_backend=${VoiceAudioSession.aecBackend} aec_available=$aecAvailable " +
                "aec_enabled=${VoiceAudioSession.aecEnabled} ns_enabled=${VoiceAudioSession.nsEnabled} " +
                "captureSessionId=${recorder.audioSessionId} playbackSessionId=${VoiceAudioSession.playbackSessionId} " +
                "sessions_match=${VoiceAudioSession.sessionsMatch()}",
        )
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
                try {
                    while (running.get()) {
                        val read = recorder.read(buf, 0, buf.size)
                        if (read > 0) {
                            framesRead += read / 2
                            val raw = buf.copyOf(read)
                            val aec = VoiceAec.instance
                            val uploadFrame =
                                if (aec?.isAvailable != true) {
                                    raw
                                } else {
                                    refreshStreamDelay(aec, recorder)
                                    when (val result = aec.processCapture(raw)) {
                                        is AecCaptureResult.Processed -> result.pcm16le
                                        AecCaptureResult.Pending -> null
                                        AecCaptureResult.Failed -> {
                                            onError("AUDIO_CAPTURE_FAILED")
                                            running.set(false)
                                            null
                                        }
                                    }
                                }
                            if (uploadFrame != null) onFrame(uploadFrame)
                        } else if (read < 0 && running.get()) {
                            onError("AUDIO_CAPTURE_FAILED")
                            break
                        }
                    }
                } finally {
                    running.set(false)
                }
            }
    }

    fun stop() = synchronized(lifecycleLock) {
        running.set(false)
        val recorder = record
        recorder?.run {
            try {
                stop()
            } catch (_: Exception) {
            }
        }
        val toJoin = worker
        val joined = toJoin !== Thread.currentThread() && BoundedThreadCleanup.terminate(toJoin)
        if (joined) {
            releaseCaptureResources()
            record = null
            worker = null
        } else if (toJoin != null) {
            thread(name = "nova-pcm-capture-cleanup", isDaemon = true) {
                try {
                    toJoin.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                synchronized(lifecycleLock) {
                    if (worker === toJoin) {
                        releaseCaptureResources()
                        record = null
                        worker = null
                    }
                }
            }
        }
    }

    @Volatile private var lastDelayUnavailableLogMs: Long = 0L

    @Volatile private var lastDelayLogMs: Long = 0L

    /**
     * AEC3's stream delay from both audio clocks (see [EchoDelayEstimator]). Without an output
     * clock the previous delay stands: 0 ms is never reported as a measurement.
     */
    private fun refreshStreamDelay(aec: WebRtcAcousticEcho, recorder: AudioRecord) {
        val playout = VoicePlayoutDelay.snapshot()
        val render = playout?.renderClock
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (render == null) {
            if (nowMs - lastDelayUnavailableLogMs >= 5000L) {
                lastDelayUnavailableLogMs = nowMs
                com.novadrive.app.DebugVoiceLog.log("delay_unavailable")
            }
            return
        }
        val stamped = try {
            recorder.getTimestamp(captureStamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        } catch (_: Exception) {
            false
        }
        val capture = EchoDelayEstimator.CaptureClock(
            framesRead = framesRead,
            stampFrames = if (stamped) captureStamp.framePosition else null,
            stampNanos = if (stamped) captureStamp.nanoTime else null,
            sampleRateHz = SAMPLE_RATE,
            bufferFallbackMs = captureBufferDelayMs,
        )
        val estimate = EchoDelayEstimator.estimate(
            render, capture, VoicePlayoutDelay.resamplerDelayMs(playout.outputSampleRateHz), System.nanoTime(),
        )
        aec.streamDelayMs = estimate.streamDelayMs
        if (nowMs - lastDelayLogMs >= 5000L) {
            lastDelayLogMs = nowMs
            com.novadrive.app.DebugVoiceLog.log(
                "echo_delay ms=${estimate.streamDelayMs} renderMs=${estimate.renderMs} captureMs=${estimate.captureMs} " +
                    "render=${estimate.renderSource} capture=${estimate.captureSource}",
            )
        }
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

    private fun releaseCaptureResources() {
        releaseCaptureEffects()
        try {
            record?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_MS = com.novadrive.ingress.realtime.AudioFrameTiming.CAPTURE_FRAME_MS
        const val FRAME_BYTES = com.novadrive.ingress.realtime.AudioFrameTiming.CAPTURE_FRAME_BYTES_16K
    }
}

class AndroidMicrophonePort(
    private val onError: (String) -> Unit,
) : MicrophonePort {
    @Volatile private var audioSessionId: Int = VoiceAudioSession.SESSION_ID_GENERATE
    private var capture: PcmAudioCapture? = null

    fun configureAudioSession(sessionId: Int) {
        audioSessionId = sessionId
    }

    val uplinkGateOpen: Boolean get() = uplinkGate.isOpen

    /** Time-scoped post-AEC speech evidence; see [SpeechUplinkGate.hasRecentSpeech]. */
    val recentSpeech: Boolean get() = uplinkGate.hasRecentSpeech()

    val lastFrameRms: Int get() = uplinkGate.lastFrameRms
    override var muted: Boolean = false
    @Volatile var gated: Boolean = false

    /** Navigation guidance is being spoken (P3); see [GuidanceMicGate]. */
    @Volatile var guidanceGated: Boolean = false

    /**
     * Wall-clock ms until which post-speech cabin echo must not reach Baidu.
     * Set by [holdPostSpeechEcho] when the assistant finishes speaking.
     */
    @Volatile private var echoHoldUntilElapsedMs: Long = 0L

    /**
     * After 小诺 finishes a reply the mic reopens, but the room still holds her voice briefly.
     * That residual was measured (2026-09-20) to become a phantom 「没听清」. Drop frames and
     * reset the uplink onset for [durationMs] after reopen.
     */
    fun holdPostSpeechEcho(durationMs: Long) {
        if (durationMs <= 0L) return
        echoHoldUntilElapsedMs = android.os.SystemClock.elapsedRealtime() + durationMs
        uplinkGate.onCaptureInterrupted()
        interrupted = false
        com.novadrive.app.DebugVoiceLog.log("mic_echo_hold_ms=$durationMs")
    }

    private fun inPostSpeechEchoHold(): Boolean =
        android.os.SystemClock.elapsedRealtime() < echoHoldUntilElapsedMs

    /** Debug speech harness: drop live frames while synthetic speech is being injected. */
    @Volatile var suppressLive: Boolean = false

    /** Diagnostics only: frames seen and frames dropped, never audio content. */
    val capturedFrames = java.util.concurrent.atomic.AtomicLong()
    val droppedGated = java.util.concurrent.atomic.AtomicLong()
    val droppedMuted = java.util.concurrent.atomic.AtomicLong()
    val droppedGuidance = java.util.concurrent.atomic.AtomicLong()

    /** Loudest live sample since the last read (0 = the platform delivered pure silence). */
    val peakSinceLastRead = java.util.concurrent.atomic.AtomicInteger()

    fun takePeak(): Int = peakSinceLastRead.getAndSet(0)

    /** Lifts quiet speech above Baidu's VAD floor before it is sent (see [MicInputGain]). */
    private val inputGain = MicInputGain()

    /**
     * Holds brief impulse sounds back so the model never sees a turn for them. Classification runs
     * on the RAW frame: [MicInputGain] normalises peaks towards a target, which would erase the
     * loud/quiet distinction the gate reads.
     */
    private val uplinkGate = SpeechUplinkGate()

    /**
     * The shape of the turn currently being judged. Read at `response.created`, which lands before
     * the local hangover closes the segment, so this must report the utterance in progress.
     */
    fun measuredSegment(): SpeechUplinkGate.Segment? = uplinkGate.snapshot()

    val droppedUplinkGate = java.util.concurrent.atomic.AtomicLong()

    /** Debug A/B switch, mirroring [inputGainEnabled]; always on in normal use. */
    @Volatile var uplinkGateEnabled: Boolean = true

    /** The processing every outgoing microphone frame gets; the speech harness uses it too. */
    fun processForSend(frame: ByteArray): ByteArray =
        if (inputGainEnabled) inputGain.process(frame, PcmAudioCapture.FRAME_MS) else frame

    /**
     * The speech harness's frames, through the same uplink gate as the live microphone, so an
     * injected impulse is rejected exactly as a real tap would be and the gate can be measured on
     * the device. Returns the frames to send — empty while the gate is holding.
     */
    fun gateForInjection(frame: ByteArray): List<ByteArray> {
        if (!uplinkGateEnabled) return listOf(processForSend(frame))
        val decision = uplinkGate.offer(frame)
        decision.rejected?.let { rejection ->
            com.novadrive.app.DebugVoiceLog.log(
                "UPLINK_GATE_REJECT reason=impulse durationMs=${rejection.durationMs} peak=${rejection.peak} " +
                    "rms=${rms(frame)} threshold=${uplinkGate.voicedThreshold} bytes=${frame.size} source=injected",
            )
        }
        decision.finished?.let { segment ->
            com.novadrive.app.DebugVoiceLog.log(
                "UPLINK_GATE_SEGMENT durationMs=${segment.durationMs} voicedRatio=${"%.0f".format(segment.voicedRatio)} " +
                    "peak=${segment.peak} suspicious=${segment.isSuspicious()} source=injected",
            )
        }
        return decision.send.map { processForSend(it) }
    }

    /** Debug A/B switch for the speech harness; always on in normal use. */
    @Volatile var inputGainEnabled: Boolean = true

    val currentGain: Double get() = inputGain.gain

    override fun start(onFrame: (ByteArray) -> Unit) {
        stop()
        inputGain.reset()
        capture =
            PcmAudioCapture(
                audioSessionId = audioSessionId,
                onFrame = { bytes ->
                    capturedFrames.incrementAndGet()
                    val peak = peakAbs(bytes)
                    peakSinceLastRead.accumulateAndGet(peak) { a, b -> maxOf(a, b) }
                    when {
                        suppressLive -> Unit
                        muted -> droppedMuted.incrementAndGet()
                        gated -> {
                            droppedGated.incrementAndGet()
                            noteCaptureInterrupted()
                        }
                        guidanceGated -> {
                            droppedGuidance.incrementAndGet()
                            noteCaptureInterrupted()
                        }
                        inPostSpeechEchoHold() -> {
                            droppedGated.incrementAndGet()
                            noteCaptureInterrupted()
                        }
                        !uplinkGateEnabled -> onFrame(processForSend(bytes))
                        else -> {
                            interrupted = false
                            gateAndSend(bytes, onFrame)
                        }
                    }
                },
                onError = onError,
            )
        capture?.start()
    }

    /** True while capture is gated, so the reset happens once per gate — not once per frame. */
    private var interrupted = false

    /**
     * The assistant (or navigation guidance) started speaking. Reset the gate once, on the
     * transition: doing it per gated frame also wiped the onset of audio arriving on the harness's
     * injection path, so a barge-in could never accumulate its two frames (measured 2026-09-18).
     */
    private fun noteCaptureInterrupted() {
        if (interrupted) return
        interrupted = true
        uplinkGate.onCaptureInterrupted()
    }

    /**
     * One frame through the uplink gate. Frames the gate holds are simply not sent: the socket is
     * idle, exactly as it already is while the assistant speaks.
     */
    private fun gateAndSend(bytes: ByteArray, onFrame: (ByteArray) -> Unit) {
        val decision = uplinkGate.offer(bytes)
        if (decision.send.isEmpty()) droppedUplinkGate.incrementAndGet()
        decision.send.forEach { frame -> onFrame(processForSend(frame)) }
        decision.rejected?.let { rejection ->
            com.novadrive.app.DebugVoiceLog.log(
                "UPLINK_GATE_REJECT reason=impulse durationMs=${rejection.durationMs} peak=${rejection.peak} " +
                    "threshold=${uplinkGate.voicedThreshold}",
            )
        }
        decision.finished?.let { segment ->
            com.novadrive.app.DebugVoiceLog.log(
                "UPLINK_GATE_SEGMENT durationMs=${segment.durationMs} voicedRatio=${"%.0f".format(segment.voicedRatio)} " +
                    "peak=${segment.peak} suspicious=${segment.isSuspicious()}",
            )
        }
    }

    override fun stop() {
        capture?.stop()
        capture = null
        uplinkGate.reset()
    }
}

/** Largest absolute PCM16LE sample in [bytes]; diagnostics only. */
internal fun peakAbs(bytes: ByteArray): Int {
    var max = 0
    var i = 0
    while (i + 1 < bytes.size) {
        val sample = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
        val abs = kotlin.math.abs(sample.toShort().toInt())
        if (abs > max) max = abs
        i += 2
    }
    return max
}
