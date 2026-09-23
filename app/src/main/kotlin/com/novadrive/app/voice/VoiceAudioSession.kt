package com.novadrive.app.voice

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import com.novadrive.app.DebugVoiceLog

/**
 * One shared audio session id for capture and playback so platform [android.media.audiofx.AcousticEchoCanceler]
 * can subtract far-end assistant audio from the uplink.
 */
object VoiceAudioSession {
    /** Platform sentinel: let [AudioRecord]/[AudioTrack] allocate, or bind an explicit id. */
    const val SESSION_ID_GENERATE = 0

    @Volatile
    var sharedSessionId: Int = SESSION_ID_GENERATE
        private set

    @Volatile
    var captureSessionId: Int = SESSION_ID_GENERATE
        private set

    @Volatile
    var playbackSessionId: Int = SESSION_ID_GENERATE
        private set

    @Volatile
    var aecEnabled: Boolean = false

    @Volatile
    var aecBackend: String = "unavailable"

    @Volatile
    var nsEnabled: Boolean = false

    fun allocate(): Int {
        val id =
            runCatching {
                AudioManager::class.java.getMethod("generateAudioSessionId").invoke(null) as Int
            }.getOrElse { 1 }
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
        captureSessionId != SESSION_ID_GENERATE &&
            playbackSessionId != SESSION_ID_GENERATE &&
            captureSessionId == playbackSessionId

    fun applyToRecordBuilder(builder: AudioRecord.Builder, sessionId: Int): Boolean =
        applySessionId(builder, sessionId, "AudioRecord.Builder")

    fun applyToTrackBuilder(builder: AudioTrack.Builder, sessionId: Int): Boolean =
        applySessionId(builder, sessionId, "AudioTrack.Builder")

    private fun applySessionId(builder: Any, sessionId: Int, label: String): Boolean {
        if (sessionId == SESSION_ID_GENERATE) return false
        if (invokeBuilderSessionId(builder, "setAudioSessionId", sessionId)) return true
        if (invokeBuilderSessionId(builder, "setSessionId", sessionId)) return true
        DebugVoiceLog.log("audio_session bind_failed target=$label")
        return false
    }

    private fun invokeBuilderSessionId(builder: Any, method: String, sessionId: Int): Boolean =
        runCatching {
            builder.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(builder, sessionId)
            true
        }.getOrDefault(false)

    private fun logSessionBinding() {
        if (!DebugVoiceLog.isEnabled) return
        if (captureSessionId == SESSION_ID_GENERATE && playbackSessionId == SESSION_ID_GENERATE) return
        DebugVoiceLog.log(
            "audio_session capture=$captureSessionId playback=$playbackSessionId " +
                "shared=$sharedSessionId sessions_match=${sessionsMatch()}",
        )
    }
}
