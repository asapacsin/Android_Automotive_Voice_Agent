package com.novadrive.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.app.nav.amap.EmulatorNaviSpeed

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
                "preflight" -> preflight(context, arg)
                "nav_desk_origin" -> navDeskOrigin(arg)
                "nav_start" -> navStart(arg)
                "nav_stop" -> navStop()
                // Simulated-car speed mid-drive (a voice-started emulator drive runs at 50 km/h).
                "nav_speed" -> {
                    val kmh = EmulatorNaviSpeed.clamp(arg.toIntOrNull() ?: EmulatorNaviSpeed.DEFAULT_KMH)
                    com.amap.api.navi.AMapNavi.getInstance(context.applicationContext)
                        .setEmulatorNaviSpeed(kmh)
                    "kmh=$kmh"
                }
                // The SDK speaks the current guidance now, through its own TTS path (onPlayStart/End):
                // lets the harness start a prompt at a chosen moment in a driver utterance (SPEC-012 R0).
                "nav_read" -> {
                    val ok = runCatching {
                        com.amap.api.navi.AMapNavi.getInstance(context.applicationContext).readNaviInfo()
                    }.getOrDefault(false)
                    "read=$ok"
                }
                "nav_overview" -> navOverview()
                "nav_lock" -> navLock()
                "nav_view_state" -> NavigationHostGateway.current()?.debugViewState() ?: "no live map host"
                "nav_clear_try" -> NavigationHostGateway.current()?.debugClearRoute(arg) ?: "no live map host"
                "amap_web_setup" -> applyAmapWebSetup(context)
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
                // Reads filesDir/qwen_setup.txt (workspace, then key). The broadcast arg is never the secret.
                "qwen_setup" -> applyQwenSetup(context)
                // Reads filesDir/gemini_setup.txt (the key). The broadcast arg is never the secret.
                "gemini_setup" -> applyGeminiSetup(context)
                // In-memory, debug-only provider override (2026-10-15): `gemini` | `clear`; next session.
                "provider" -> provider(arg)
                // P62: SPEC-018's developer toggle 「助手播报导航（实验）」, read when the map host is
                // created (restart the app after it): `on` | `on:<deadlineMs>` (measurement) | `off`.
                "guidance_relay" -> guidanceRelay(context, arg)
                // Piece check (owner rule 2026-10-16): the over-speed sentence through the real speak
                // path (relay or alert queue), without waiting for a route that passes a camera.
                "speak_alert" -> {
                    val limit = arg.toIntOrNull() ?: 60
                    com.novadrive.app.nav.amap.AmapGuidanceVoice.speakAlert(com.novadrive.app.nav.amap.OverspeedAlert.sentence(limit)) {
                        NavigationHostGateway.current()?.isNavigating == true
                    }
                    "alert_posted limitKmh=$limit"
                }
                // Host audio bridge (PC mic in, PC speakers out): arg `on:<port>` | `off` | `status`.
                "bridge" -> when {
                    arg.startsWith("on") -> {
                        val port = arg.substringAfter(':', "7790").toIntOrNull() ?: 7790
                        // The broadcast runs on the main thread; the connect is a network call.
                        Thread { runCatching { HostAudioBridge.start(port) }.onFailure {
                            Log.d("NovaVoice", "host_bridge failed=${it.javaClass.simpleName}")
                        } }.start()
                        "connecting port=$port"
                    }
                    arg == "off" -> HostAudioBridge.stop()
                    else -> HostAudioBridge.status
                }
                else -> "unknown tool"
            }
            val safeArg = safeDebugArg(tool, arg)
            Log.d("NovaVoice", "debug_tool tool=$tool arg=$safeArg result=$result")
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
        // `@lat,lon` (GCJ-02): route without the Web-key POI search, e.g. on a fresh emulator
        // whose keys have not been entered yet. Coordinates are never logged.
        if (target.startsWith("@")) {
            val parts = target.substring(1).split(',', limit = 2)
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return "usage: @lat,lon"
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return "usage: @lat,lon"
            host.calculateDriveRoute(lat, lon, "坐标目的地")
            return "routing to coordinates"
        }
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

    /**
     * Demo preflight (tools/demo/recorder/record_demo.py --preflight): what a take needs, checked before
     * any voice session opens, so a broken setup costs no Qwen quota. Answers the Qwen config code, the
     * Amap Web key and the GPS fix; then runs the app's own place search for [query] and routes to the
     * first result with the Navi SDK (nav_calc_success / nav_calc_failure). Logs counts and metres only.
     */
    private fun preflight(context: Context, query: String): String {
        val app = context.applicationContext
        val qwen = runCatching { QwenSettingsRepository(app).configProblem() ?: "ok" }.getOrElse { "error" }
        val webKey = if (AmapSettingsRepository(app).loadWebKey().isNullOrBlank()) "missing" else "set"
        val fix = if (runCatching { CoarseLocationProvider(app).lastKnown() }.getOrNull() != null) "set" else "none"
        val host = NavigationHostGateway.current()
        if (query.isNotBlank()) Thread {
            val source = com.novadrive.app.nav.LiveDestinationCandidateSource(app)
            fun timed(): Pair<List<com.novadrive.app.nav.DestinationCandidate>, Long> {
                val started = System.nanoTime()
                val result = runCatching { kotlinx.coroutines.runBlocking { source.resolve(query) } }.getOrDefault(emptyList())
                return result to (System.nanoTime() - started) / 1_000_000
            }
            val (found, ms) = timed()
            val first = found.firstOrNull()
            Log.d("NovaVoice", "preflight_search count=${found.size} first_m=${first?.distanceMeters ?: -1} ms=$ms")
            if (first != null && host != null) host.calculateDriveRoute(first.latitude, first.longitude, first.name)
            // P53: the same search again on the same source, to show the warm (reused-connection) cost.
            val (_, warmMs) = timed()
            Log.d("NovaVoice", "preflight_search_warm ms=$warmMs")
        }.start()
        return "qwen=$qwen web_key=$webKey fix=$fix map_host=${if (host != null) "live" else "none"}"
    }

    /**
     * Desk E2E only. Sets an explicit GCJ-02 start so Amap route calc can run when the SDK
     * has no supported origin (code 3). Arg: `lat,lon` or `clear`. Coordinates are never logged.
     */
    private fun navDeskOrigin(arg: String): String {
        val host = NavigationHostGateway.current()
            ?: return "no live map host (open MainActivity first)"
        if (arg.equals("clear", ignoreCase = true) || arg.isBlank()) {
            host.clearDeskOriginForTest()
            return "cleared"
        }
        val parts = arg.split(',', limit = 2)
        if (parts.size != 2) return "usage: lat,lon | clear"
        val lat = parts[0].trim().toDoubleOrNull() ?: return "bad lat"
        val lon = parts[1].trim().toDoubleOrNull() ?: return "bad lon"
        return if (host.setDeskOriginForTest(lat, lon)) "set=true" else "rejected"
    }

    /**
     * Stage 6. arg:
     *  - blank / `emulator` — indoor drive at urban default (50 km/h)
     *  - `emulator:80` — override fake-car speed (Amap clamps 40–120)
     *  - `gps` — real GNSS
     */
    private fun navStart(arg: String): String {
        val host = NavigationHostGateway.current() ?: return "no live map host"
        val request = EmulatorNaviSpeed.parseNavStart(arg)
        return "startNavi accepted=" + host.startNavigation(request.emulator, request.speedKmh)
    }

    /** Manual fallback. Must keep working even after automatic completion has stopped it. */
    private fun navStop(): String {
        val host = NavigationHostGateway.current() ?: return "no live map host"
        val stopped = host.stopNavigation("manual")
        return if (stopped) "stopped" else "already inactive"
    }

    private fun navOverview(): String {
        val host = NavigationHostGateway.current() ?: return "no live map host"
        return "overview=" + host.showOverview()
    }

    private fun navLock(): String {
        val host = NavigationHostGateway.current() ?: return "no live map host"
        return "lock=" + host.resumeTracking()
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
            liveInfo = LiveInfoTool.live(context),
            cabin = com.novadrive.app.vehicle.VehicleControlProvider.port,
            music = com.novadrive.app.media.AndroidMusicHandoffTool(context),
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
        val deferred = result.deferredOutput
        if (deferred != null) {
            // A readback takes seconds: off the main thread, and the real result is logged when
            // it arrives (code-only fields for play_music, I-8).
            Thread {
                val output = runCatching { kotlinx.coroutines.runBlocking { deferred() } }
                    .getOrElse { "failed=${it.javaClass.simpleName}" }
                Log.d("NovaVoice", "debug_tool tool=dispatch arg=${redactDispatchArg(arg)} deferred_result=${safeOutput(name, output)}")
            }.start()
            return "deferred"
        }
        return safeOutput(name, result.output ?: "blocked=${result.blockedReason ?: "-"}")
    }

    /**
     * Debug-only. A two-line file in the app's files dir: workspace id, then the API key.
     * The broadcast never carries either value, and neither is logged. The file is deleted.
     */
    /** Same pattern as qwen_setup: the Amap web-service key from a pushed file, which is deleted. */
    private fun applyAmapWebSetup(context: Context): String {
        val file = java.io.File(context.filesDir, "amap_web_setup.txt")
        if (!file.isFile) return "missing"
        return try {
            val key = file.readLines().firstOrNull()?.trim().orEmpty()
            if (key.isEmpty()) return "invalid"
            val repo = AmapSettingsRepository(context.applicationContext)
            repo.save(CredentialUpdate.Replace(key))
            if (repo.isConfigured()) "applied" else "rejected"
        } catch (_: Exception) {
            "failed"
        } finally {
            file.delete()
        }
    }

    private fun applyQwenSetup(context: Context): String {
        val file = java.io.File(context.filesDir, "qwen_setup.txt")
        if (!file.isFile) return "missing"
        return try {
            val lines = file.readLines()
            val workspace = lines.getOrNull(0)?.trim().orEmpty()
            val key = lines.getOrNull(1)?.trim().orEmpty()
            val repo = QwenSettingsRepository(context.applicationContext)
            repo.save(
                QwenAppSettings(consentAccepted = true, workspaceId = workspace),
                CredentialUpdate.Replace(key),
            )
            if (repo.configProblem() == null) "applied" else "rejected"
        } catch (e: IllegalArgumentException) {
            e.message?.takeIf { it.startsWith("QWEN_") } ?: "invalid"
        } catch (_: Exception) {
            "failed"
        } finally {
            file.delete()
        }
    }

    /**
     * Debug-only. A one-line file in the app's files dir: the Gemini API key. Stored with consent
     * through the Gemini settings repository; never in the broadcast, never logged; the file is deleted.
     */
    private fun applyGeminiSetup(context: Context): String {
        val file = java.io.File(context.filesDir, "gemini_setup.txt")
        if (!file.isFile) return "missing"
        return try {
            val key = file.readLines().firstOrNull()?.trim().orEmpty()
            val repo = GeminiSettingsRepository(context.applicationContext)
            repo.save(repo.loadSettings().copy(consentAccepted = true), CredentialUpdate.Replace(key))
            if (repo.debugOverrideProblem() == null) "applied" else "rejected"
        } catch (e: IllegalArgumentException) {
            e.message?.takeIf { it.startsWith("GEMINI_") } ?: "invalid"
        } catch (_: Exception) {
            "failed"
        } finally {
            file.delete()
        }
    }

    companion object {
        /** `provider` tool: sets or clears the in-memory override; anything else changes nothing. */
        internal fun provider(arg: String): String = when (arg.trim().lowercase()) {
            "gemini" -> { DebugProviderOverride.set(true); "override=gemini_live next_session" }
            "clear" -> { DebugProviderOverride.set(false); "override=none next_session" }
            else -> "usage: provider:gemini|clear"
        }

        /** Broadcast arguments that must never reach the log. */
        internal fun safeDebugArg(tool: String?, arg: String): String = when {
            tool == "qwen_setup" || tool == "gemini_setup" || tool == "amap_web_setup" -> "<redacted>"
            tool == "preflight" -> "<query>" // a destination is close to an address: never in the log
            tool == "nav_desk_origin" || arg.startsWith("@") -> "<redacted>"
            tool == "dispatch" -> redactDispatchArg(arg)
            tool == "turn" -> "<text>" // what the driver said: never in the log (AGENTS.md)
            else -> arg
        }

        /** `play_music:title=晴天,artist=周杰伦` -> `play_music:title,artist`. */
        internal fun redactDispatchArg(arg: String): String {
            val name = arg.substringBefore(':')
            val keys = arg.substringAfter(':', "").split(',').filter { it.contains('=') }
                .map { it.substringBefore('=').trim() }
            return if (keys.isEmpty()) name else "$name:${keys.joinToString(",")}"
        }

        /** play_music results name what is playing: only the codes are logged. */
        internal fun safeOutput(tool: String, output: String): String {
            if (tool != "play_music" && tool != "control_music") return output
            val json = runCatching { org.json.JSONObject(output) }.getOrNull() ?: return "unparsed"
            return listOf("ok", "status", "error", "matches_request")
                .filter { json.has(it) }
                .joinToString(" ") { "$it=${json.get(it)}" }
        }
    }

    private fun guidanceRelay(context: Context, arg: String): String {
        val on = arg.startsWith("on")
        val deadline = if (on) arg.substringAfter(':', "").toLongOrNull()?.coerceIn(500L, 8_000L) else null
        val prefs = context.getSharedPreferences(com.novadrive.app.nav.amap.AmapGuidanceVoice.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(com.novadrive.app.nav.amap.AmapGuidanceVoice.PREF_ASSISTANT_VOICE, on)
            .putLong(com.novadrive.app.nav.amap.AmapGuidanceVoice.PREF_DEBUG_DEADLINE_MS, deadline ?: 0L)
            .commit()
        return "relay=${if (on) "on" else "off"} deadline_ms=${deadline ?: "default"} next_map_host"
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
