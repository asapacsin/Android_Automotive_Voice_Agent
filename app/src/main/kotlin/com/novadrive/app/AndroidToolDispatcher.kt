package com.novadrive.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationChoice
import com.novadrive.app.nav.NavigationPickerIntercept
import com.novadrive.app.nav.NavigationBackends
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.app.tools.AppsServer
import com.novadrive.app.tools.BodyServer
import com.novadrive.app.tools.ClimateServer
import com.novadrive.app.tools.ComfortServer
import com.novadrive.app.tools.LiveInfoServer
import com.novadrive.app.tools.MediaServer
import com.novadrive.app.tools.NavigationServer
import com.novadrive.app.tools.PhoneServer
import com.novadrive.app.tools.SpeechServer
import com.novadrive.app.tools.ToolCallEnv
import com.novadrive.app.tools.ToolRegistry
import com.novadrive.app.tools.ToolServer
import com.novadrive.app.tools.VisionServer
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.DriverContext
import com.novadrive.app.vision.CameraQuestionHandler
import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

enum class AllowedApp { MAPS, SETTINGS }

sealed interface AndroidActionResult {
    data class Accepted(val status: String = "accepted") : AndroidActionResult
    /** [details] reach the model next to the error: facts it needs to say what did not happen. */
    data class Rejected(val code: String, val details: Map<String, Any> = emptyMap()) : AndroidActionResult
}

interface AndroidActionExecutor {
    fun navigate(destination: String): AndroidActionResult
    fun openApp(app: AllowedApp): AndroidActionResult
    fun playMusic(): AndroidActionResult
    fun stopMusic(): AndroidActionResult
    fun exitNavigationMode(): AndroidActionResult

    /** 「第二个」「选最快的」: picks from the on-screen destination or route list. */
    fun chooseNavigationOption(choice: NavigationChoice): AndroidActionResult =
        AndroidActionResult.Rejected("NAVIGATION_CHOICE_UNAVAILABLE")

    /**
     * When a picker is on screen, a spoken name that matches exactly one row, or null when the
     * utterance should go to the model / a new search.
     */
    fun matchPickerName(utterance: String): NavigationChoice? = null

    /** true: stop talking and wait for the next command (SILENT_WAIT); false: talk normally again. */
    fun setSpeechSilent(silent: Boolean): AndroidActionResult = AndroidActionResult.Rejected("SPEECH_OUTPUT_UNAVAILABLE")

    /** GO_TO_SLEEP chosen by the model: stop listening once the goodbye has played. */
    fun endConversation(): AndroidActionResult = AndroidActionResult.Rejected("LISTENING_CONTROL_UNAVAILABLE")

    /** What the navigation screen shows once the latest request has settled (bounded wait). */
    suspend fun awaitNavigationOptions(): EmbeddedNavigationController.OptionsSnapshot? = null
}

/** Validates untrusted model output before calling the narrow Android executor. */
class AndroidToolDispatcher(
    private val executor: AndroidActionExecutor,
    private val climate: ClimateToolHandler,
    private val camera: CameraQuestionHandler,
    /**
     * The app's cross-turn record for the turn this call belongs to, or null when there is none.
     *
     * A tool call alone cannot show that the *wrong capability* is about to run:
     * `control_music{play}` is identical whether the driver said 「放首歌」 or named a song this
     * product cannot play. It also cannot show that the same call already ran this turn, which for
     * a relative adjustment means applying it twice.
     */
    /**
     * What this driver has told us 「家」 and 「公司」 mean, and how to change it. Injected rather
     * than read from a store here so the guard is testable without Android, and so a dispatcher
     * built for a simulation never reads the real device's saved places.
     */
    private val places: SavedPlaceTool = SavedPlaceTool.none(),
    /** Calling someone is the one action here that reaches a person; see [PhoneCallTool]. */
    private val phone: PhoneCallTool = PhoneCallTool.none(),
    /** `query_live_info` (SPEC-011): weather, route traffic, along-route POIs, place details. */
    private val liveInfo: LiveInfoTool = LiveInfoTool.none(),
    /** Windows and seat (SPEC-015 body domain); null answers every body call VEHICLE_UNAVAILABLE. */
    private val cabin: com.novadrive.vehicle.VehicleControlPort? = null,
    /** `play_music` (SPEC-017); null answers every call MUSIC_HANDOFF_UNAVAILABLE. */
    private val music: com.novadrive.app.media.MusicHandoffTool? = null,
    /**
     * Last so the common test call site can pass it as a trailing lambda. Everything above has a
     * default; this one is what nearly every dispatcher test overrides.
     */
    private val driverContext: () -> DriverContext? = { DriverContext.currentOrNull() },
) {
    /**
     * Records the call, runs it and records the outcome (including deferred work). Arguments are
     * only stored by telemetry during benchmark runs.
     */
    fun dispatch(call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
        Telemetry.record(EventType.TOOL_CALL_RECEIVED, toolType = call.name, detail = call.callId) {
            com.novadrive.evaluation.Json.write(call.arguments.filterKeys { it != "_validation_error" })
        }
        Telemetry.record(EventType.TOOL_EXECUTION_START, toolType = call.name, detail = call.callId)
        val result = guard(call) ?: dispatchUnrecorded(call)
        val deferred = result.deferredOutput
        if (deferred == null) {
            recordEnd(call, result.output, result.blockedReason)
            return result
        }
        return result.copy(deferredOutput = {
            val output = deferred()
            recordEnd(call, output, null)
            output
        })
    }

    private fun recordEnd(call: DomainVoiceEvent.ToolCall, output: String?, blocked: String?) {
        val failed = blocked != null || output?.contains("\"ok\":false") == true
        val code = blocked ?: output?.let { Regex("\"error\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        Telemetry.record(EventType.TOOL_EXECUTION_END, toolType = call.name, success = !failed, errorCode = if (failed) code else null, detail = call.callId)
    }

    /** One server per car domain (ADR-015), indexed by domain id. */
    private val servers: Map<String, ToolServer> = serverIndex(
        ToolRegistry.PRODUCT,
        listOf(
            NavigationServer(executor, places),
            AppsServer(executor),
            MediaServer(executor, music),
            ClimateServer(climate),
            BodyServer(cabin),
            // Scenario steps take the same server path as a direct call. Not the repeat or referent guards
            // (fixed steps are neither driver repeats nor referents), but a named song still is not ours.
            ComfortServer(
                route = { step -> ToolCallGuards.unsupportedMedia(step, driverContext())?.let { failed(step, it) } ?: dispatchUnrecorded(step) },
                cabinState = { cabin?.cabinState?.value },
            ),
            VisionServer(camera),
            PhoneServer(phone),
            LiveInfoServer(liveInfo),
            SpeechServer(executor),
        ),
    )

    private val env = ToolCallEnv(driverContext, ::failed, ::result)

    private fun dispatchUnrecorded(call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
        call.arguments["_validation_error"]?.let { return failed(call, it) }
        val domain = ToolRegistry.PRODUCT.domainOf(call.name) ?: return failed(call, "UNKNOWN_TOOL")
        return servers.getValue(domain.id).call(call, env)
    }

    companion object {
        const val CHOOSE_NAVIGATION_OPTION = com.novadrive.app.voice.BaiduFlexProtocol.CHOOSE_NAVIGATION_OPTION

        /** Call ids of the app's own local picks (MainActivity's onLocalNavigationPick). */
        const val LOCAL_PICK_CALL_PREFIX = "local_nav_"

        /** A spoken name matched no row but sounds like one: ask, do not select. */
        const val CONFIRM_CANDIDATE = "CONFIRM_CANDIDATE"

        /**
         * Servers by domain id. Fails fast on a duplicate domain id, a server whose domain is not
         * exactly one of the registry's domains, or a registry domain with no server.
         */
        internal fun serverIndex(registry: ToolRegistry, servers: List<ToolServer>): Map<String, ToolServer> {
            val index = LinkedHashMap<String, ToolServer>()
            servers.forEach { server ->
                require(index.put(server.domain.id, server) == null) { "two ToolServers for domain ${server.domain.id}" }
            }
            val registered = registry.tools().mapNotNull { registry.domainOf(it.name) }.distinct()
            servers.forEach { server ->
                require(registered.any { it === server.domain }) { "domain ${server.domain.id} is not in the registry" }
            }
            registered.forEach { domain ->
                require(domain.id in index) { "domain ${domain.id} has no ToolServer" }
            }
            return index
        }

    }

    private fun result(call: DomainVoiceEvent.ToolCall, action: AndroidActionResult): ToolDispatchResult =
        when (action) {
            is AndroidActionResult.Accepted -> {
                com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
                ToolDispatchResult(
                    null, null, successChip = "✓ ${call.name}",
                    output = JSONObject().put("ok", true).put("tool", call.name).put("status", action.status).toString(),
                )
            }
            is AndroidActionResult.Rejected -> failed(call, action.code, action.details)
        }

    /**
     * Everything that can stop a validated call before it executes, in one place so the order is
     * visible: is this the wrong capability, a repeat of one already run, or an adjustment whose
     * target nobody has stated? See [ToolCallGuards].
     */
    private fun guard(call: DomainVoiceEvent.ToolCall): ToolDispatchResult? {
        if (call.arguments.containsKey("_validation_error")) return null
        val context = driverContext()
        val code = ToolCallGuards.unsupportedMedia(call, context)
            ?: ToolCallGuards.ambiguousReferent(call, context)
            ?: ToolCallGuards.repeatedInTurn(call, context)
        return code?.let { failed(call, it, ToolCallGuards.refusalDetails(it, context)) }
    }

    private fun failed(
        call: DomainVoiceEvent.ToolCall,
        code: String,
        details: Map<String, Any> = emptyMap(),
    ) = ToolDispatchResult(
        null, null, blockedReason = code,
        output = JSONObject().put("ok", false).put("tool", call.name).put("error", code)
            .apply { details.forEach { (key, value) -> put(key, value) } }
            .apply { ToolFailureAdvice.forCode(code)?.let { put("next", it) } }
            .toString(),
    )
}

/**
 * What the assistant should say when a tool refuses.
 *
 * Carried in the tool result rather than left to the tool description: a conversation reset drops
 * the description's fine print, and the model then improvises. Measured on device 2026-09-18 — a
 * name that matched nothing on screen returned `AMBIGUOUS` and the driver was told 「没听清，再说一遍」,
 * which is not what happened and gives them nothing to do.
 */

/**
 * The tool actions without Android: navigation flow, music backend and listening control are
 * injected. The app wraps it in [SafeAndroidActionExecutor]; the JVM simulation benchmark uses it
 * directly, so both run the same code.
 */
open class CoreActionExecutor(
    private val navigationFlow: EmbeddedNavigationController,
    private val music: () -> MusicBackend,
    /** Why navigation cannot run right now (no map host, no web key), or null. */
    private val navigationBlocker: () -> String? = { null },
    private val endConversationRequest: () -> Boolean = { false },
    private val speechSilent: (Boolean) -> Unit = {},
) : AndroidActionExecutor {
    /**
     * Voice path into the EMBEDDED navigation. Resolves candidates and waits for the
     * driver to pick a destination and a route — it does not jump straight to startNavi.
     *
     * Marks navigating (the arbiter's P1 input) before the search starts; the controller clears it
     * whenever the flow ends. The authoritative outcome arrives asynchronously
     * (`nav_resolve_candidates` / `nav_route_candidates` / `nav_flow_error`).
     */
    override fun navigate(destination: String): AndroidActionResult {
        navigationBlocker()?.let { return AndroidActionResult.Rejected(it) }

        NavigationState.begin()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        lastRequest = done
        Thread {
            try {
                runBlocking { navigationFlow.requestDestination(destination) }
            } finally {
                done.complete(Unit)
            }
        }.start()

        // The driver must still pick a destination (if ambiguous) and always a route on screen,
        // so the model must not announce that navigation has started.
        return AndroidActionResult.Accepted("awaiting_route_selection_on_screen")
    }

    /** The latest destination request; the options report must not read the previous list. */
    @Volatile
    private var lastRequest: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun setSpeechSilent(silent: Boolean): AndroidActionResult {
        speechSilent(silent)
        return AndroidActionResult.Accepted(if (silent) "stopped_talking_still_listening" else "talking_normally")
    }

    override fun endConversation(): AndroidActionResult =
        if (endConversationRequest()) {
            AndroidActionResult.Accepted("listening_will_stop_after_this_reply")
        } else {
            AndroidActionResult.Rejected("NO_ACTIVE_SESSION")
        }

    override fun matchPickerName(utterance: String): NavigationChoice? =
        NavigationPickerIntercept.resolve(
            utterance,
            navigationFlow.state().value,
            navigationFlow.destinationCandidates.value,
            navigationFlow.routeCandidates.value,
        )

    override fun chooseNavigationOption(choice: NavigationChoice): AndroidActionResult =
        navigationChoiceAction(navigationFlow, navigationFlow.chooseByVoice(choice))

    override suspend fun awaitNavigationOptions(): EmbeddedNavigationController.OptionsSnapshot {
        lastRequest?.let { kotlinx.coroutines.withTimeoutOrNull(REQUEST_WAIT_MS) { it.await() } }
        return navigationFlow.awaitOptions(OPTIONS_WAIT_MS)
    }

    private companion object {
        const val REQUEST_WAIT_MS = 10_000L
        const val OPTIONS_WAIT_MS = 8_000L
    }

    override fun openApp(app: AllowedApp): AndroidActionResult = AndroidActionResult.Rejected("APP_UNAVAILABLE")

    override fun playMusic(): AndroidActionResult =
        if (music().play()) AndroidActionResult.Accepted("music_playing")
        else AndroidActionResult.Rejected("MUSIC_UNAVAILABLE")

    override fun stopMusic(): AndroidActionResult {
        music().stop()
        return AndroidActionResult.Accepted("music_stopped")
    }

    /**
     * Ends navigation for real: stops embedded guidance or closes the picker (the tool predates
     * the embedded SDK, when it could only un-mute the assistant). Also clears the speech mute.
     */
    override fun exitNavigationMode(): AndroidActionResult {
        val outcome = runBlocking { navigationFlow.endByVoice() }
        NavigationState.reset()
        return AndroidActionResult.Accepted(
            when (outcome) {
                EmbeddedNavigationController.VoiceEndResult.STOPPED_NAVIGATION -> "navigation_stopped"
                EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION -> "navigation_selection_cancelled"
                EmbeddedNavigationController.VoiceEndResult.NOTHING_ACTIVE -> "no_navigation_active"
            },
        )
    }
}

class SafeAndroidActionExecutor(
    context: Context,
    navigationFlow: EmbeddedNavigationController = EmbeddedNavigation.shared(context),
) : CoreActionExecutor(
    navigationFlow = navigationFlow,
    music = { MusicBackends.current(context.applicationContext) },
    navigationBlocker = { liveNavigationBlocker(context.applicationContext) },
    endConversationRequest = { com.novadrive.app.voice.VoiceSessionGateway.sleepAfterReply("end_conversation") },
    speechSilent = { silent ->
        if (silent) com.novadrive.app.voice.VoiceSessionGateway.shutUp("tool") else com.novadrive.app.voice.VoiceSessionGateway.start("tool")
    },
) {
    private val appContext = context.applicationContext

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

    private fun tryStart(intent: Intent): Boolean =
        try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }

    private companion object {
        /** A simulated navigation world needs neither the map view nor the Amap web key. */
        fun liveNavigationBlocker(context: Context): String? {
            if (NavigationBackends.simulated != null) return null
            if (NavigationHostGateway.current() == null) return "NAVIGATION_HOST_UNAVAILABLE"
            val key = AmapSettingsRepository(context).loadWebKey()
            return if (key.isNullOrBlank()) "AMAP_WEB_KEY_MISSING" else null
        }
    }
}
