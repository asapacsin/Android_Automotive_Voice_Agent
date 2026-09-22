package com.novadrive.app.voice

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Build
import com.novadrive.app.DebugVoiceLog

/**
 * One shared audio session id for capture and playback so platform [android.media.audiofx.AcousticEchoCanceler]
 * can subtract far-end assistant audio from the uplink.
 */
object VoiceAudioSession {
    @Volatile
    var sharedSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
        private set

    @Volatile
    var captureSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
        private set

    @Volatile
    var playbackSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
        private set

    @Volatile
    var aecEnabled: Boolean = false

    @Volatile
    var nsEnabled: Boolean = false

    fun allocate(): Int {
        val id = AudioManager.generateAudioSessionId()
        sharedSessionId = id
        return id
    }

    fun recordCaptureSession(id: Int) {
        captureSessionId = id
        logSessionBinding()
    }

    fun recordPlaybackSession(id: Int) {
        playbackSessionId = id
        logSessionBinding()
    }

    fun sessionsMatch(): Boolean =
        captureSessionId != AudioManager.AUDIO_SESSION_ID_GENERATE &&
            playbackSessionId != AudioManager.AUDIO_SESSION_ID_GENERATE &&
            captureSessionId == playbackSessionId

    fun applyToRecordBuilder(builder: AudioRecord.Builder, sessionId: Int): Boolean =
        applySessionId(builder, sessionId, "AudioRecord.Builder")

    fun applyToTrackBuilder(builder: AudioTrack.Builder, sessionId: Int): Boolean =
        applySessionId(builder, sessionId, "AudioTrack.Builder")

    private fun applySessionId(builder: Any, sessionId: Int, label: String): Boolean {
        if (sessionId == AudioManager.AUDIO_SESSION_ID_GENERATE) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (builder) {
                is AudioRecord.Builder -> {
                    builder.setAudioSessionId(sessionId)
                    true
                }
                is AudioTrack.Builder -> {
                    builder.setAudioSessionId(sessionId)
                    true
                }
                else -> false
            }
        }
        return try {
            val method = builder.javaClass.getMethod("setSessionId", Int::class.javaPrimitiveType)
            method.invoke(builder, sessionId)
            true
        } catch (_: Exception) {
            DebugVoiceLog.log("audio_session bind_failed target=$label")
            false
        }
    }

    private fun logSessionBinding() {
        if (!DebugVoiceLog.isEnabled) return
        if (captureSessionId == AudioManager.AUDIO_SESSION_ID_GENERATE &&
            playbackSessionId == AudioManager.AUDIO_SESSION_ID_GENERATE
        ) {
            return
        }
        DebugVoiceLog.log(
            "audio_session capture=$captureSessionId playback=$playbackSessionId " +
                "shared=$sharedSessionId sessions_match=${sessionsMatch()}",
        )
    }
}
