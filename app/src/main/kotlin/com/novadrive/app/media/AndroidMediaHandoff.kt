package com.novadrive.app.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import com.novadrive.app.DebugVoiceLog

sealed interface HandoffResult {
    data class Sent(val pkg: String) : HandoffResult
    data object NoApp : HandoffResult
    data class Rejected(val code: String) : HandoffResult
}

/** Sends a search to an already active media session without opening another app (SPEC-017). */
class AndroidMediaHandoff(
    private val context: Context,
) {
    fun play(request: MusicRequest): HandoffResult {
        val spec = MediaSearchSpecs.build(request)
        val sessions = try {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, NovaMediaListener::class.java))
        } catch (_: SecurityException) {
            null
        }
        val eligible = sessions.orEmpty().firstOrNull { controller ->
            (controller.playbackState?.actions?.and(PlaybackState.ACTION_PLAY_FROM_SEARCH) ?: 0L) != 0L
        }
        val pkg = eligible?.packageName
        val result = when {
            sessions == null -> HandoffResult.Rejected("listener_access")
            eligible == null -> HandoffResult.NoApp
            else -> try {
                val extras = Bundle().apply { spec.extras.forEach { (key, value) -> putString(key, value) } }
                eligible.transportControls.playFromSearch(spec.query, extras)
                HandoffResult.Sent(eligible.packageName)
            } catch (_: SecurityException) {
                HandoffResult.Rejected("security")
            } catch (_: RuntimeException) {
                HandoffResult.Rejected("session_error")
            }
        }
        val code = when (result) {
            is HandoffResult.Sent -> "sent"
            HandoffResult.NoApp -> "no_app"
            is HandoffResult.Rejected -> result.code
        }
        DebugVoiceLog.log("music_handoff pkg=${pkg ?: "-"} fields=${request.fieldFlags()} result=$code")
        return result
    }
}
