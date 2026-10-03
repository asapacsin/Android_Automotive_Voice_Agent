package com.novadrive.app.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.novadrive.app.DebugVoiceLog

sealed interface HandoffResult {
    data class Sent(val pkg: String) : HandoffResult
    data object NoApp : HandoffResult
    data class Rejected(val code: String) : HandoffResult
}

/** Hands a search to the driver's own music app via MEDIA_PLAY_FROM_SEARCH (SPEC-017). */
class AndroidMediaHandoff(
    private val context: Context,
    private val preference: List<String> = MediaAppPreference.DEFAULT,
    private val returnDelayMs: Long = 1500,
) {
    fun play(request: MusicRequest): HandoffResult {
        val spec = MediaSearchSpecs.build(request)
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
        spec.extras.forEach { (k, v) -> intent.putExtra(k, v) }
        val resolvers = context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
        val pkg = MediaAppPreference.pick(resolvers, preference)
        val result = if (pkg == null) {
            HandoffResult.NoApp
        } else {
            intent.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                HandoffResult.Sent(pkg)
            } catch (_: ActivityNotFoundException) {
                HandoffResult.Rejected("activity_not_found")
            } catch (_: SecurityException) {
                HandoffResult.Rejected("security")
            }
        }
        val code = when (result) {
            is HandoffResult.Sent -> "sent"
            HandoffResult.NoApp -> "no_app"
            is HandoffResult.Rejected -> result.code
        }
        DebugVoiceLog.log("music_handoff pkg=${pkg ?: "-"} fields=${request.fieldFlags()} result=$code")
        if (result is HandoffResult.Sent) {
            Handler(Looper.getMainLooper()).postDelayed({ bringAppToFront() }, returnDelayMs)
        }
        return result
    }

    /** Keep the map as the driver's screen (SPEC-017 B5). */
    fun bringAppToFront() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launch)
        } catch (_: RuntimeException) {
            DebugVoiceLog.log("music_handoff return_to_front=failed")
        }
    }
}
