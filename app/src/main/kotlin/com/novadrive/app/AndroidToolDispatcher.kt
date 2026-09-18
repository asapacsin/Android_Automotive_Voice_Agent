package com.novadrive.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationChoice
import com.novadrive.app.nav.NavigationVoiceOutput
import com.novadrive.app.nav.NavigationBackends
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.ContextResolver
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
    data class Rejected(val code: String) : AndroidActionResult
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
        val result = rejectIfRepeat(call) ?: dispatchUnrecorded(call)
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

    private fun dispatchUnrecorded(call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
        call.arguments["_validation_error"]?.let { return failed(call, it) }
        return when (call.name) {
            CameraQuestionHandler.TOOL -> {
                val question = call.arguments["question"]
                // A vision request takes seconds: hand it to the session as async work instead of
                // blocking the event loop. The answer is spoken when it arrives.
                ToolDispatchResult(
                    null,
                    null,
                    successChip = "📷 正在看",
                    deferredOutput = {
                        val outcome = camera.ask(question)
                        NavigationState.allowConfirmation()
                        outcome.output
                    },
                )
            }
            ClimateToolHandler.TOOL -> {
                refuseAmbiguousAdjustment(call)?.let { return it }
                val outcome = runBlocking { climate.handle(call.arguments) }
                driverContext()?.let { context ->
                    context.onClimateResult(
                        action = call.arguments["action"].orEmpty(),
                        value = call.arguments["value"]?.toDoubleOrNull(),
                        output = outcome.output,
                        epoch = context.currentEpoch(),
                    )
                }
                // Failures are worth hearing too, even while navigating: the driver must not
                // assume the climate changed when it did not.
                NavigationState.allowConfirmation()
                ToolDispatchResult(
                    null,
                    null,
                    blockedReason = outcome.errorCode,
                    successChip = outcome.chip,
                    output = outcome.output,
                )
            }
            "navigate_to" -> {
                val destination = call.arguments["destination"]?.trim().orEmpty()
                if (destination.isBlank()) return failed(call, "BLANK_DESTINATION")
                if (destination.length > 120) return failed(call, "DESTINATION_TOO_LONG")
                // The executor sets the navigation speech mute before the search starts, so a
                // search that fails at once cannot leave it on.
                val action = executor.navigate(destination)
                if (action !is AndroidActionResult.Accepted) return result(call, action)
                navigationResult(call, action)
            }
            CHOOSE_NAVIGATION_OPTION -> {
                val choice = parseChoice(call.arguments) ?: return failed(call, "INVALID_CHOICE")
                val action = executor.chooseNavigationOption(choice)
                if (action !is AndroidActionResult.Accepted) return result(call, action)
                navigationResult(call, action)
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
                    // One bundled track, no library: a request that named a song, an artist or a
                    // style cannot be satisfied, and starting the bundled track would make ok=true
                    // mean "you got what you asked for". Refused here because this is the only
                    // bridge to a device action.
                    "play" ->
                        if (ActionClaimGuard.isSpecificMediaRequest(
                                driverContext()?.currentRequestText().orEmpty(),
                            )
                        ) {
                            failed(call, "MEDIA_LIBRARY_UNSUPPORTED")
                        } else {
                            result(call, executor.playMusic())
                        }
                    "stop" -> result(call, executor.stopMusic())
                    else -> failed(call, "ACTION_NOT_ALLOWED")
                }
            }
            "exit_navigation_mode" -> result(call, executor.exitNavigationMode())
            com.novadrive.app.voice.BaiduFlexProtocol.END_CONVERSATION -> result(call, executor.endConversation())
            com.novadrive.app.voice.BaiduFlexProtocol.SET_SPEECH_OUTPUT -> when (call.arguments["mode"]) {
                "silent" -> result(call, executor.setSpeechSilent(true))
                "spoken" -> result(call, executor.setSpeechSilent(false))
                else -> failed(call, "MODE_NOT_ALLOWED")
            }
            else -> failed(call, "UNKNOWN_TOOL")
        }
    }

    /** Waits for the list to load, so the model can read the options out. */
    private fun navigationResult(call: DomainVoiceEvent.ToolCall, action: AndroidActionResult.Accepted) =
        ToolDispatchResult(
            null,
            null,
            successChip = "✓ ${call.name}",
            deferredOutput = {
                val snapshot = executor.awaitNavigationOptions()
                NavigationState.allowConfirmation()
                NavigationVoiceOutput.build(call.name, action.status, snapshot)
            },
        )

    private fun parseChoice(args: Map<String, String>): NavigationChoice? {
        // Numbers arrive stringified by the assembler: "2", or "2.0" from some JSON encoders.
        args["index"]?.let { raw -> return raw.trim().toDoubleOrNull()?.toInt()?.let { NavigationChoice.Index(it) } }
        args["preference"]?.let { raw -> return NavigationChoice.Kind.fromWire(raw)?.let { NavigationChoice.Preference(it) } }
        args["name"]?.let { raw -> return raw.trim().takeIf { it.isNotEmpty() }?.let { NavigationChoice.Name(it) } }
        return null
    }

    companion object {
        const val CHOOSE_NAVIGATION_OPTION = com.novadrive.app.voice.BaiduFlexProtocol.CHOOSE_NAVIGATION_OPTION

        /**
         * Tools whose effect accumulates, so running one twice is not the same as running it once.
         * A navigation search or a camera question can be repeated harmlessly; a relative climate
         * change cannot.
         */
        private val REPEAT_SENSITIVE = setOf(ClimateToolHandler.TOOL, "control_music")
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

    /**
     * The same call, with the same arguments, twice in one driver turn. For `adjust_temperature`
     * that is the difference between −2 °C and −4 °C, so it is refused rather than repeated. A
     * driver who really does ask twice speaks twice, which is two turns and two epochs.
     */
    private fun rejectIfRepeat(call: DomainVoiceEvent.ToolCall): ToolDispatchResult? {
        if (call.name !in REPEAT_SENSITIVE) return null
        val context = driverContext() ?: return null
        // No transcribed utterance means no turn to be "within". Two identical calls are then two
        // separate requests, not a repeat of one — refusing the second would drop a real action,
        // which is the more expensive mistake of the two.
        val epoch = context.currentEpoch()
        if (epoch <= 0) return null
        val arguments = call.arguments.filterKeys { it != "_validation_error" }
        return if (context.claimDispatch(epoch, call.name, arguments)) {
            null
        } else {
            failed(call, "DUPLICATE_IN_TURN")
        }
    }

    /**
     * Refuses a relative climate change when the driver's words do not say *what* to change and the
     * history cannot say either — 「再低一点」 after both the temperature and the fan were adjusted,
     * or after nothing was.
     *
     * The hint asks the model to ask. This makes it hold: a prompt rule is not an enforcement
     * mechanism ([I-11](../../../../../../docs/INVARIANTS.md)), and guessing right is still wrong
     * — it is the same coin toss the next time. Recording the clarification here is what lets the
     * driver's one-word answer resolve on the following turn.
     */
    private fun refuseAmbiguousAdjustment(call: DomainVoiceEvent.ToolCall): ToolDispatchResult? {
        val action = call.arguments["action"] ?: return null
        if (action != ClimateToolActions.ADJUST_TEMPERATURE && action != ClimateToolActions.ADJUST_FAN) return null
        val context = driverContext() ?: return null
        val epoch = context.currentEpoch()
        if (epoch <= 0) return null
        val resolution = ContextResolver.resolve(context.currentRequestText(), context, epoch)
        if (resolution !is ContextResolver.Resolution.Clarify) return null
        if (resolution.reason == ContextResolver.REASON_NOTHING_TO_REVERSE) return null
        context.recordClarification(resolution.options, resolution.delta, epoch)
        return failed(call, "AMBIGUOUS_REFERENT")
    }

    private fun failed(call: DomainVoiceEvent.ToolCall, code: String) = ToolDispatchResult(
        null, null, blockedReason = code,
        output = JSONObject().put("ok", false).put("tool", call.name).put("error", code)
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
object ToolFailureAdvice {
    private val ADVICE = mapOf(
        "NO_MATCH" to "屏幕上的候选里没有这个名字。请如实说没有这个选项，并请用户说第几个。",
        "AMBIGUOUS" to "有多个候选都符合这个名字。请如实说有多个，并请用户说第几个。",
        "OUT_OF_RANGE" to "屏幕上没有这一项。请如实说没有这一项，并说明一共有几个。",
        "NO_OPTIONS_ON_SCREEN" to "现在屏幕上没有候选列表。请如实说明，不要假装已经选择。",
        "OPTIONS_NOT_READY" to "候选还在计算中。请让用户稍等，不要假装已经选择。",
        "DISTANCE_UNKNOWN" to "这些候选没有距离信息，无法判断最近的。请用户说第几个。",
        "PREFERENCE_NOT_FOR_DESTINATIONS" to "这个偏好只能用于路线，不能用于地点。请用户说第几个。",
        "PREFERENCE_NOT_FOR_ROUTES" to "这个偏好只能用于地点，不能用于路线。请用户说第几条。",
        "NO_OPTIONS" to "现在没有可选的内容。请如实说明。",
        "AMBIGUOUS_REFERENT" to
            "用户这句话没有说明要调的是温度还是风量，之前的记录也无法确定，所以没有执行。" +
            "请只用一句话反问用户是温度还是风量，不要再调用任何工具，也不要说已经调好了。",
        "DUPLICATE_IN_TURN" to
            "这个操作在本轮已经执行过一次，没有重复执行。请根据上一次的结果回答，不要说又调了一次。",
        "MEDIA_LIBRARY_UNSUPPORTED" to
            "车上只有一首内置曲目，没有音乐库，无法搜索或指定歌曲。" +
            "请用一句话如实告诉用户放不了他要的那首歌，不要谎称已经播放，也不要改放其它曲子。",
    )

    fun forCode(code: String): String? = ADVICE[code]
}

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
     * Sets the [NavigationState] speech mute before the search starts; the controller clears it
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

    override fun chooseNavigationOption(choice: NavigationChoice): AndroidActionResult =
        when (val outcome = navigationFlow.chooseByVoice(choice)) {
            is EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen ->
                AndroidActionResult.Accepted("destination_selected")
            is EmbeddedNavigationController.VoiceChoiceResult.RouteChosen ->
                AndroidActionResult.Accepted("navigation_started")
            is EmbeddedNavigationController.VoiceChoiceResult.Rejected ->
                AndroidActionResult.Rejected(outcome.code)
        }

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
