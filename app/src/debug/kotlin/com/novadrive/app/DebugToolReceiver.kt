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
                "climate" -> climate(arg)
                "wake" -> wake(context, arg)
                // Cuts the realtime socket as a lost signal would, so recovery can be a scenario
                // rather than a claim. NetworkFaults is production code with a test-only caller.
                "net" -> when (arg.lowercase()) {
                    "drop" -> "dropped=" + com.novadrive.app.voice.NetworkFaults.dropConnectionNow()
                    else -> "usage: net:drop"
                }
                "turn" -> beginTurn(arg)
                "dispatch" -> dispatch(context, arg)
                "voice" -> voice(context, arg)
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

    /**
     * LLM-bypassed climate check through the SAME handler and port the voice tool uses.
     * arg: "power_on" | "set_temperature:22" | "adjust_fan:1" | "get_state" ...
     * "fail:<KIND>" is not offered: failure injection is covered by unit tests, and the
     * production provider exposes only the port interface.
     */
    private fun climate(arg: String): String {
        val action = arg.substringBefore(':')
        val value = arg.substringAfter(':', "").takeIf { it.isNotBlank() }
        val args = buildMap {
            put("action", action)
            if (value != null) put("value", value)
        }
        val outcome = kotlinx.coroutines.runBlocking {
            com.novadrive.app.vehicle.ClimateToolHandler(com.novadrive.app.vehicle.VehicleControlProvider.port).handle(args)
        }
        return outcome.output
    }

    /**
     * Enables or disables the wake word through the **production** controller.
     *
     * 开发者设置 is not exported, so ADB cannot open it, and driving the toggle through
     * uiautomator would prove the button works rather than that the engine initialises. This calls
     * the same `WakeWordController.setEnabled` the button calls.
     *
     * arg: `on` | `off` | `status`
     */
    private fun wake(context: Context, arg: String): String {
        val settings = com.novadrive.app.wake.WakeWordSettings.from(context)
        return when (arg.lowercase()) {
            "on" -> {
                com.novadrive.app.wake.WakeWordController.setEnabled(context, true)
                "wake enabled=${settings.isEnabled()}"
            }
            "off" -> {
                com.novadrive.app.wake.WakeWordController.setEnabled(context, false)
                "wake enabled=${settings.isEnabled()}"
            }
            // Credential *presence*, never the value: a blank appId is the one failure mode that
            // looks identical to a broken engine in the logs.
            "status" -> "wake enabled=${settings.isEnabled()} credentials_complete=" +
                settings.loadCredentials().isComplete()
            // Feeds <externalFilesDir>/wake.pcm (16 kHz mono PCM16) to the wake engine, so the
            // model's match can be proven without a person in the car saying the phrase.
            "inject" -> {
                val clip = java.io.File(context.getExternalFilesDir(null), "wake.pcm")
                if (!clip.isFile) {
                    "no clip at ${clip.absolutePath}"
                } else {
                    val bytes = clip.readBytes()
                    Thread { com.novadrive.app.wake.WakeWordController.injectForHarness(bytes) }.start()
                    "injecting ${bytes.size} bytes"
                }
            }
            else -> "usage: wake:on|off|status"
        }
    }

    /**
     * Starts a driver turn with [arg] as the transcript, without a model or a network.
     *
     * The guards that decide whether a call may run read the driver's words and the turn epoch
     * from `DriverContext`, so proving them on the device needs a way to set those two facts that
     * does not depend on Baidu being reachable.
     */
    private fun beginTurn(arg: String): String {
        val context = com.novadrive.app.voice.DriverContext.currentOrNull()
            ?: com.novadrive.app.voice.DriverContext().also { com.novadrive.app.voice.DriverContext.install(it) }
        context.onDriverUtterance(arg, context.currentEpoch() + 1)
        return "turn epoch=${context.currentEpoch()}"
    }

    /**
     * Runs a tool call through the **real** [AndroidToolDispatcher] — the same bridge the model's
     * output crosses, with the same validation and the same guards.
     *
     * `climate` above goes straight to the handler, which is deliberate for checking the port, but
     * it therefore proves nothing about the dispatcher. A debug path that skips the layer under
     * test reads like evidence and is not.
     *
     * arg: `control_music:action=play` / `control_climate:action=adjust_temperature,value=-1`.
     * No spaces — `am broadcast --es` is re-parsed by the device shell, which splits on them.
     */
    private fun dispatch(context: Context, arg: String): String {
        val name = arg.substringBefore(':')
        val arguments = arg.substringAfter(':', "")
            .split(',')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val dispatcher = AndroidToolDispatcher(
            SafeAndroidActionExecutor(context),
            com.novadrive.app.vehicle.ClimateToolHandler(com.novadrive.app.vehicle.VehicleControlProvider.port),
            com.novadrive.app.vision.VisionProvider.handler(context),
            phone = PhoneCallTool(com.novadrive.app.phone.PhoneProvider.port(context)),
            places = SavedPlaceTool(
                read = com.novadrive.app.nav.SavedPlaceStore(context)::get,
                write = com.novadrive.app.nav.SavedPlaceStore(context)::set,
                resolve = { address ->
                    kotlinx.coroutines.runBlocking {
                        com.novadrive.app.nav.LiveDestinationCandidateSource(context).resolve(address)
                    }.firstOrNull()
                },
            ),
        )
        val result = dispatcher.dispatch(
            com.novadrive.ingress.realtime.DomainVoiceEvent.ToolCall("debug", name, arguments),
        )
        return result.output ?: "blocked=${result.blockedReason ?: "-"}"
    }

    /**
     * Speech harness. arg:
     *  - "start" / "stop"             : voice session via the same gateway as the wake word
     *  - "say:<name>"                 : inject files/test_speech/<name>.pcm (16 kHz mono PCM16)
     *  - "speak:<text>"               : a text turn, as the camera auto-look sends
     *  - "camera"                     : open the camera through the real UI path is not reachable
     *                                   from here; use a tap on 📷 instead
     */
    private fun voice(context: Context, arg: String): String {
        val gateway = com.novadrive.app.voice.VoiceSessionGateway
        return when {
            arg == "start" -> gateway.start().toString()
            arg == "stop" -> { gateway.stop(); "stopped" }
            arg == "activate" -> gateway.start("debug").toString()
            // Same path as a wake-word detection (the MSC engine cannot be fed audio from here).
            arg == "wake" -> gateway.start("wake_word").toString()
            arg == "sleep" -> { gateway.sleep("debug"); "sleep" }
            arg == "shutup" -> gateway.shutUp("debug").toString()
            arg == "state" -> gateway.listeningState.toString()
            arg.startsWith("say:") -> {
                val name = arg.removePrefix("say:").filter { it.isLetterOrDigit() || it == '_' }
                val file = java.io.File(context.getExternalFilesDir(null), "test_speech/$name.pcm")
                if (!file.isFile) return "missing ${file.name}"
                "injected=" + gateway.injectTestSpeech(file.readBytes())
            }
            arg.startsWith("speak:") -> gateway.speak(arg.removePrefix("speak:")).toString()
            arg == "gain:on" || arg == "gain:off" -> {
                gateway.setInputGainEnabled(arg == "gain:on")
                arg
            }
            else -> "unknown voice arg"
        }
    }

    private fun format(action: AndroidActionResult): String = when (action) {
        is AndroidActionResult.Accepted -> "Accepted"
        is AndroidActionResult.Rejected -> "Rejected(${action.code})"
    }
}
