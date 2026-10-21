package com.novadrive.app.media

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
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
            if (Build.VERSION.SDK_INT >= 34) {
                // The music hand-off moved our task to the background. Android 14 blocks a delayed
                // startActivity from that state unless the app explicitly opts in through a
                // PendingIntent created for this user-requested return-to-map transition.
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                PendingIntent.getActivity(
                    context,
                    RETURN_TO_APP_REQUEST,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    options.toBundle(),
                ).send()
            } else {
                context.startActivity(launch)
            }
            DebugVoiceLog.log("music_handoff return_to_front=sent")
        } catch (_: PendingIntent.CanceledException) {
            DebugVoiceLog.log("music_handoff return_to_front=failed")
        } catch (_: RuntimeException) {
            DebugVoiceLog.log("music_handoff return_to_front=failed")
        }
    }

    private companion object {
        const val RETURN_TO_APP_REQUEST = 17017
    }
}
