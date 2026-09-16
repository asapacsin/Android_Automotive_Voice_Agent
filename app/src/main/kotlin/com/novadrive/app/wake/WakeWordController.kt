package com.novadrive.app.wake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.voice.StartResult
import com.novadrive.app.voice.VoiceSessionGateway

/**
 * Process-lifetime owner of the MSC wake detector. [bind] is idempotent: one detector,
 * one [IflytekWakeWordDetector.onWake] listener, reused across wake cycles.
 */
object WakeWordController {
    private val lock = Any()
    private var detector: IflytekWakeWordDetector? = null

    fun bind(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val current = detector ?: IflytekWakeWordDetector(app).also { created ->
                created.onWake = { onDetected() }
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
            detector?.stopListening()
        }
    }

    internal fun releaseForTeardown() {
        synchronized(lock) {
            detector?.release()
            detector = null
        }
    }

    private fun syncLocked(app: Context, current: IflytekWakeWordDetector) {
        val settings = WakeWordSettings.from(app)
        if (!settings.isEnabled()) {
            current.stopListening()
            return
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            DebugVoiceLog.log("wake_permission_failure")
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
    }

    private fun onDetected() {
        logForStartResult(VoiceSessionGateway.start())?.let(DebugVoiceLog::log)
    }
}

internal fun logForStartResult(result: StartResult): String? =
    when (result) {
        StartResult.Started, StartResult.AlreadyActive -> null
        StartResult.MicPermissionMissing -> "wake_permission_failure"
        StartResult.NotAttached -> "wake_not_attached"
        is StartResult.ConfigInvalid -> "wake_config_invalid"
    }
