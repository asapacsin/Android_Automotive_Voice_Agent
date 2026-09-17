package com.novadrive.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationChoice
import com.novadrive.app.nav.NavigationVoiceOutput
import com.novadrive.app.nav.NavigationHostGateway
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vision.CameraQuestionHandler
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

    /** TERMINATE_LISTENING chosen by the model: stop listening once the goodbye has played. */
    fun endConversation(): AndroidActionResult = AndroidActionResult.Rejected("LISTENING_CONTROL_UNAVAILABLE")

    /** What the navigation screen shows once the latest request has settled (bounded wait). */
    suspend fun awaitNavigationOptions(): EmbeddedNavigationController.OptionsSnapshot? = null
}

/** Validates untrusted model output before calling the narrow Android executor. */
class AndroidToolDispatcher(
    private val executor: AndroidActionExecutor,
    private val climate: ClimateToolHandler,
    private val camera: CameraQuestionHandler,
) {
    fun dispatch(call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
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
                val outcome = runBlocking { climate.handle(call.arguments) }
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
                val action = executor.navigate(destination)
                if (action !is AndroidActionResult.Accepted) return result(call, action)
                NavigationState.begin()
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
                    "play" -> result(call, executor.playMusic())
                    "stop" -> result(call, executor.stopMusic())
                    else -> failed(call, "ACTION_NOT_ALLOWED")
                }
            }
            "exit_navigation_mode" -> result(call, executor.exitNavigationMode())
            com.novadrive.app.voice.BaiduFlexProtocol.END_CONVERSATION -> result(call, executor.endConversation())
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

    override fun endConversation(): AndroidActionResult =
        if (com.novadrive.app.voice.VoiceSessionGateway.standbyAfterReply("end_conversation")) {
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

    private fun tryStart(intent: Intent): Boolean =
        try {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
}
