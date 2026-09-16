package com.novadrive.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

enum class AllowedApp { MAPS, SETTINGS }

sealed interface AndroidActionResult {
    data class Accepted(val status: String = "accepted") : AndroidActionResult
    data class Rejected(val code: String) : AndroidActionResult
}

interface AndroidActionExecutor {
    fun navigate(destination: String): AndroidActionResult
    fun openApp(app: AllowedApp): AndroidActionResult
    fun playMusic(): AndroidActionResult
    fun stopMusic(): AndroidActionResult
    fun exitNavigationMode(): AndroidActionResult
}

/** Validates untrusted model output before calling the narrow Android executor. */
class AndroidToolDispatcher(private val executor: AndroidActionExecutor) {
    fun dispatch(call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
        call.arguments["_validation_error"]?.let { return failed(call, it) }
        return when (call.name) {
            "navigate_to" -> {
                val destination = call.arguments["destination"]?.trim().orEmpty()
                if (destination.isBlank()) return failed(call, "BLANK_DESTINATION")
                if (destination.length > 120) return failed(call, "DESTINATION_TOO_LONG")
                val action = executor.navigate(destination)
                if (action is AndroidActionResult.Accepted) NavigationState.begin()
                result(call, action)
            }
            "open_app" -> {
                val app = when (call.arguments["app"]) {
                    "maps" -> AllowedApp.MAPS
                    "settings" -> AllowedApp.SETTINGS
                    else -> return failed(call, "APP_NOT_ALLOWED")
                }
                result(call, executor.openApp(app))
            }
            "control_music" -> {
                when (call.arguments["action"]) {
                    "play" -> result(call, executor.playMusic())
                    "stop" -> result(call, executor.stopMusic())
                    else -> failed(call, "ACTION_NOT_ALLOWED")
                }
            }
            "exit_navigation_mode" -> result(call, executor.exitNavigationMode())
            else -> failed(call, "UNKNOWN_TOOL")
        }
    }

    private fun result(call: DomainVoiceEvent.ToolCall, action: AndroidActionResult): ToolDispatchResult =
        when (action) {
            is AndroidActionResult.Accepted -> {
                NavigationState.allowConfirmation()
                ToolDispatchResult(
                    null, null, successChip = "✓ ${call.name}",
                    output = JSONObject().put("ok", true).put("tool", call.name).put("status", action.status).toString(),
                )
            }
            is AndroidActionResult.Rejected -> failed(call, action.code)
        }

    private fun failed(call: DomainVoiceEvent.ToolCall, code: String) = ToolDispatchResult(
        null, null, blockedReason = code,
        output = JSONObject().put("ok", false).put("tool", call.name).put("error", code).toString(),
    )
}

class SafeAndroidActionExecutor(
    context: Context,
    private val navigationFlow: EmbeddedNavigationController = EmbeddedNavigation.shared(context),
) : AndroidActionExecutor {
    private val appContext = context.applicationContext

    /**
     * Voice path into the EMBEDDED navigation. Resolves candidates and waits for the
     * driver to pick a destination and a route — it does not jump straight to startNavi.
     *
     * Returns `Accepted` so [AndroidToolDispatcher]'s [NavigationState.begin] speech-mute
     * behaviour is unchanged. The authoritative outcome arrives asynchronously
     * (`nav_resolve_candidates` / `nav_route_candidates` / `nav_flow_error`).
     */
    override fun navigate(destination: String): AndroidActionResult {
        if (NavigationHostGateway.current() == null) {
            return AndroidActionResult.Rejected("NAVIGATION_HOST_UNAVAILABLE")
        }
        val key = AmapSettingsRepository(appContext).loadWebKey()
        if (key.isNullOrBlank()) return AndroidActionResult.Rejected("AMAP_WEB_KEY_MISSING")

        Thread {
            runBlocking { navigationFlow.requestDestination(destination) }
        }.start()

        return AndroidActionResult.Accepted("navigation_starting")
    }

    override fun openApp(app: AllowedApp): AndroidActionResult {
        val candidates = when (app) {
            AllowedApp.MAPS -> listOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=")))
            AllowedApp.SETTINGS -> listOf(Intent(Settings.ACTION_SETTINGS))
        }
        for (candidate in candidates) {
            if (tryStart(candidate)) return AndroidActionResult.Accepted()
        }
        return AndroidActionResult.Rejected("APP_UNAVAILABLE")
    }

    override fun playMusic(): AndroidActionResult =
        if (BundledMusicPlayer.play(appContext)) AndroidActionResult.Accepted("music_playing")
        else AndroidActionResult.Rejected("MUSIC_UNAVAILABLE")

    override fun stopMusic(): AndroidActionResult {
        BundledMusicPlayer.stop()
        return AndroidActionResult.Accepted("music_stopped")
    }

    override fun exitNavigationMode(): AndroidActionResult {
        NavigationState.reset()
        return AndroidActionResult.Accepted("navigation_mode_exited")
    }

    private fun tryStart(intent: Intent): Boolean =
        try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
}
