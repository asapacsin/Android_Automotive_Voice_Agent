package com.novadrive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.novadrive.app.nav.NavigationHostGateway

class DebugToolReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val tool = intent.getStringExtra("tool")
            val arg = intent.getStringExtra("arg").orEmpty()
            val result = when (tool) {
                "navigate" -> format(SafeAndroidActionExecutor(context).navigate(arg))
                "open_app" -> openApp(context, arg)
                "control_music" -> controlMusic(context, arg)
                "exit_navigation_mode" -> format(SafeAndroidActionExecutor(context).exitNavigationMode())
                "probe_playback" -> probePlayback(context, arg)
                "nav_route" -> navRoute(context, arg)
                "nav_start" -> navStart(arg)
                "nav_stop" -> navStop()
                else -> "unknown tool"
            }
            Log.d("NovaVoice", "debug_tool tool=$tool arg=$arg result=$result")
        } catch (e: Exception) {
            Log.d("NovaVoice", "debug_tool failed: ${e.message}")
        }
    }

    private fun openApp(context: Context, arg: String): String {
        val app = when (arg.lowercase()) {
            "maps" -> AllowedApp.MAPS
            "settings" -> AllowedApp.SETTINGS
            else -> return "unknown app"
        }
        return format(SafeAndroidActionExecutor(context).openApp(app))
    }

    private fun controlMusic(context: Context, arg: String): String {
        val executor = SafeAndroidActionExecutor(context)
        return when (arg.lowercase()) {
            "play" -> format(executor.playMusic())
            "stop" -> format(executor.stopMusic())
            else -> "unknown action"
        }
    }

    private fun probePlayback(context: Context, arg: String): String {
        return when (arg.lowercase()) {
            "start" -> {
                AudioPlaybackProbe.start(context)
                "started"
            }
            "stop" -> {
                AudioPlaybackProbe.stop(context)
                "stopped"
            }
            else -> "unknown action"
        }
    }

    /**
     * Stages 3-4, LLM bypassed. Resolves [keyword] to real coordinates via the same
     * AmapPoiClient the product uses, then asks the LIVE map host to calculate a route.
     *
     * Runs on a worker thread: a BroadcastReceiver runs on the main thread and
     * AmapPoiClient blocks on OkHttp, so calling it inline would throw
     * NetworkOnMainThreadException and be misread as a routing failure.
     */
    private fun navRoute(context: Context, keyword: String): String {
        // Nearby default: 珠海站 is across the water from Hengqin and produced ~16 min
        // emulator runs per test cycle. 横琴口岸 exercises the same path far faster.
        val target = keyword.ifBlank { "横琴口岸" }
        val host = NavigationHostGateway.current()
            ?: return "no live map host (open MainActivity first)"
        Thread {
            val key = AmapSettingsRepository(context.applicationContext).loadWebKey()
            if (key.isNullOrBlank()) {
                Log.d("NovaVoice", "nav_resolve failed=no_web_key")
                return@Thread
            }
            val poi = AmapPoiClient().resolve(target, key)
            if (poi == null) {
                Log.d("NovaVoice", "nav_resolve failed=no_result")
                return@Thread
            }
            // Name only. Coordinates are location data and are never logged.
            Log.d("NovaVoice", "nav_resolve ok name=${poi.name}")
            host.calculateDriveRoute(poi.latitude, poi.longitude, poi.name)
        }.start()
        return "resolving $target"
    }

    /** Stage 6. arg "emulator" simulates driving so rendering can be proven indoors. */
    private fun navStart(arg: String): String {
        val host = NavigationHostGateway.current() ?: return "no live map host"
        val emulator = arg.isBlank() || arg.equals("emulator", ignoreCase = true)
        return "startNavi accepted=" + host.startNavigation(emulator)
    }

    /** Manual fallback. Must keep working even after automatic completion has stopped it. */
    private fun navStop(): String {
        val host = NavigationHostGateway.current() ?: return "no live map host"
        val stopped = host.stopNavigation("manual")
        return if (stopped) "stopped" else "already inactive"
    }

    private fun format(action: AndroidActionResult): String = when (action) {
        is AndroidActionResult.Accepted -> "Accepted"
        is AndroidActionResult.Rejected -> "Rejected(${action.code})"
    }
}
