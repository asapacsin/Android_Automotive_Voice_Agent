package com.novadrive.app.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.voice.PcmAudioCapture
import com.novadrive.app.voice.StartResult
import com.novadrive.app.voice.VoiceSessionGateway

/**
 * Process-lifetime owner of the MSC wake detector. [bind] is idempotent: one detector,
 * one [IflytekWakeWordDetector.onWake] listener, reused across wake cycles.
 *
 * It also owns the microphone while the assistant is idle. The engine is configured for app-fed
 * audio, so somebody has to feed it; before 2026-09-19 nobody did, and the engine's own recorder
 * lost the mic and reported it as a network error.
 */
object WakeWordController {
    private val lock = Any()
    private var detector: IflytekWakeWordDetector? = null
    private var idleCapture: PcmAudioCapture? = null
    private var reconcile: Runnable? = null

    /** While a harness clip is playing, live microphone frames are held back so the two do not interleave. */
    @Volatile
    private var injecting = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun bind(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val current = detector ?: IflytekWakeWordDetector(app).also { created ->
                created.onWake = { onDetected(app) }
                detector = created
            }
            syncLocked(app, current)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        WakeWordSettings.from(app).setEnabled(enabled)
        bind(app)
    }

    fun pause(context: Context) {
        val app = context.applicationContext
        WakeWordSettings.from(app).setEnabled(false)
        synchronized(lock) {
            stopListeningLocked()
            stopReconcileLocked()
        }
    }

    internal fun releaseForTeardown() {
        synchronized(lock) {
            stopCaptureLocked()
            stopReconcileLocked()
            detector?.release()
            detector = null
        }
    }

    private fun syncLocked(app: Context, current: IflytekWakeWordDetector) {
        val settings = WakeWordSettings.from(app)
        if (!settings.isEnabled()) {
            stopListeningLocked()
            stopReconcileLocked()
            return
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DebugVoiceLog.log("wake_permission_failure")
            return
        }
        // While a session runs the assistant is already listening, and its capture owns the
        // microphone. Two owners is the failure this class exists to avoid, so wake stands down
        // and the reconcile below re-arms it once the session is gone.
        if (VoiceSessionGateway.isActive) {
            stopListeningLocked()
            startReconcileLocked(app)
            return
        }
        val credentials = settings.loadCredentials()
        when (current.state) {
            WakeWordState.IDLE, WakeWordState.ERROR ->
                current.initialize(credentials, app.filesDir)
            WakeWordState.AUTHORISING, WakeWordState.READY, WakeWordState.LISTENING -> Unit
        }
        if (current.state != WakeWordState.LISTENING) {
            current.startListening()
        }
        startCaptureLocked(current)
        startReconcileLocked(app)
    }

    /**
     * Frames go straight to the engine. [IflytekWakeWordDetector.writeFrame] drops them unless the
     * session is listening, so capture may start before the engine has finished authorising.
     */
    private fun startCaptureLocked(current: IflytekWakeWordDetector) {
        if (idleCapture != null) return
        val capture = PcmAudioCapture(
            onFrame = { frame -> if (!injecting) current.writeFrame(frame, first = false, last = false) },
            onError = { code -> DebugVoiceLog.log("wake_capture_failure code=$code") },
        )
        idleCapture = capture
        capture.start()
        DebugVoiceLog.log("wake_capture_started")
    }

    private fun stopCaptureLocked() {
        val capture = idleCapture ?: return
        idleCapture = null
        capture.stop()
        DebugVoiceLog.log("wake_capture_stopped")
    }

    /**
     * Idempotent: the reconcile runs every 1.5 s, and an unconditional stop made the engine log
     * `send msg failed while status is exited` on every tick of every session.
     */
    private fun stopListeningLocked() {
        val wasCapturing = idleCapture != null
        stopCaptureLocked()
        val current = detector ?: return
        if (wasCapturing || current.state == WakeWordState.LISTENING) {
            current.stopListening()
        }
    }

    /**
     * A session can end without anyone telling us — inactivity, or a terminal error — so wake
     * re-arms by reconciling what it observes rather than by being notified. The alternative was a
     * callback on every path a session can die on, which is the kind of thing that silently grows
     * a missing case.
     */
    private fun startReconcileLocked(app: Context) {
        if (reconcile != null) return
        val task = object : Runnable {
            override fun run() {
                bind(app)
                synchronized(lock) {
                    if (reconcile === this) handler.postDelayed(this, RECONCILE_INTERVAL_MS)
                }
            }
        }
        reconcile = task
        handler.postDelayed(task, RECONCILE_INTERVAL_MS)
    }

    private fun stopReconcileLocked() {
        val task = reconcile ?: return
        reconcile = null
        handler.removeCallbacks(task)
    }

    private fun onDetected(app: Context) {
        // Hand the microphone over before the session opens its own capture.
        synchronized(lock) { stopListeningLocked() }
        com.novadrive.evaluation.Telemetry.record(com.novadrive.evaluation.EventType.WAKE_DETECTED)
        logForStartResult(VoiceSessionGateway.start("wake_word"))?.let(DebugVoiceLog::log)
        // Re-arm: the reconcile brings wake back as soon as the session is gone.
        synchronized(lock) { startReconcileLocked(app) }
    }

    /**
     * Debug speech harness: hand [pcm16le] (16 kHz mono PCM16) to the wake engine as if the
     * microphone had produced it. Paced in real time, because the engine's own endpointing reads
     * the arrival rate and a clip dumped at once is not the signal a spoken phrase is.
     *
     * This proves the model matches the phrase. It cannot prove the microphone path in a moving
     * cabin, which stays a human check.
     */
    internal fun injectForHarness(pcm16le: ByteArray): Int {
        val current = synchronized(lock) { detector } ?: return 0
        injecting = true
        try {
            var offset = 0
            while (offset < pcm16le.size) {
                val end = minOf(offset + FRAME_BYTES, pcm16le.size)
                current.writeFrame(pcm16le.copyOfRange(offset, end), first = offset == 0, last = end == pcm16le.size)
                offset = end
                Thread.sleep(FRAME_MS)
            }
        } finally {
            injecting = false
        }
        return pcm16le.size
    }

    private const val RECONCILE_INTERVAL_MS = 1_500L
    private const val FRAME_BYTES = PcmAudioCapture.FRAME_BYTES
    private const val FRAME_MS = 100L
}

internal fun logForStartResult(result: StartResult): String? =
    when (result) {
        StartResult.Started, StartResult.AlreadyActive -> null
        StartResult.MicPermissionMissing -> "wake_permission_failure"
        StartResult.NotAttached -> "wake_not_attached"
        is StartResult.ConfigInvalid -> "wake_config_invalid"
    }
