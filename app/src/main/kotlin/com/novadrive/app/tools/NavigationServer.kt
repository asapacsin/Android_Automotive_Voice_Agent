package com.novadrive.app.tools

import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher.Companion.CHOOSE_NAVIGATION_OPTION
import com.novadrive.app.AndroidToolDispatcher.Companion.LOCAL_PICK_CALL_PREFIX
import com.novadrive.app.SavedPlaceTool
import com.novadrive.app.ToolCallGuards
import com.novadrive.app.nav.NavigationChoice
import com.novadrive.app.nav.NavigationLocalPickGuard
import com.novadrive.app.nav.NavigationVoiceOutput
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult

/** Executes [NavigationDomain]: search, pick, exit, and the driver's saved places. */
class NavigationServer(
    private val executor: AndroidActionExecutor,
    private val places: SavedPlaceTool,
) : ToolServer {
    override val domain: ToolDomain = NavigationDomain

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "navigate_to" -> navigateTo(call, env)
            CHOOSE_NAVIGATION_OPTION -> chooseOption(call, env)
            "exit_navigation_mode" -> env.result(call, executor.exitNavigationMode())
            "save_place" -> places.save(call) { c, code -> env.failed(c, code) }
            else -> env.failed(call, "UNKNOWN_TOOL")
        }

    private fun navigateTo(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult {
        val destination = call.arguments["destination"]?.trim().orEmpty()
        if (destination.isBlank()) return env.failed(call, "BLANK_DESTINATION")
        if (destination.length > 120) return env.failed(call, "DESTINATION_TOO_LONG")
        val authority = NavigationLocalPickGuard.current()
        if (authority != null &&
            NavigationLocalPickGuard.consumeNavigateToSuppression(authority.listKey, authority.turnKey)
        ) {
            com.novadrive.app.DebugVoiceLog.log("nav_voice_suppress_navigate_to")
            // The local pick already selected the destination; report exactly what that pick
            // did, through the same output a spoken choice gets. A bare ok here let the model
            // invent 「导航已开始」 while the route list waited (OPEN_PROBLEMS 2026-09-27).
            return navigationResult(call, AndroidActionResult.Accepted("destination_selected"))
        }
        executor.matchPickerName(destination)?.let { choice ->
            val action = executor.chooseNavigationOption(choice)
            if (action !is AndroidActionResult.Accepted) return env.result(call, action)
            val args = when (choice) {
                is NavigationChoice.Name -> mapOf("name" to choice.text)
                is NavigationChoice.Index -> mapOf("index" to choice.position.toString())
                is NavigationChoice.Preference -> mapOf("preference" to choice.kind.wire)
            }
            return navigationResult(call.copy(name = CHOOSE_NAVIGATION_OPTION, arguments = args), action)
        }
        // A saved place the driver has not given us is a question, not a search. Letting
        // it through would send 「回家」 to a POI search, which on 2026-09-19 returned
        // nothing - and on a different day could return a stranger's address.
        ToolCallGuards.savedPlaceMissing(destination, places::get)?.let { code ->
            return env.failed(call, code)
        }
        // The executor sets the navigation speech mute before the search starts, so a
        // search that fails at once cannot leave it on.
        val action = executor.navigate(destination)
        if (action !is AndroidActionResult.Accepted) return env.result(call, action)
        return navigationResult(call, action)
    }

    private fun chooseOption(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult {
        // The app already picked for this utterance (「第二个」, an exact name): the model's
        // own call is a duplicate, whatever its arguments say. It gets that pick's result.
        if (!call.callId.startsWith(LOCAL_PICK_CALL_PREFIX)) {
            NavigationLocalPickGuard.consumeChoiceSuppression()?.let { status ->
                com.novadrive.app.DebugVoiceLog.log("nav_voice_suppress_choose_option")
                return navigationResult(call, AndroidActionResult.Accepted(status))
            }
        }
        val choice = parseChoice(call.arguments) ?: return env.failed(call, "INVALID_CHOICE")
        val action = executor.chooseNavigationOption(choice)
        if (action !is AndroidActionResult.Accepted) return env.result(call, action)
        return navigationResult(call, action)
    }

    /** Waits for the list to load, so the model can read the options out. */
    private fun navigationResult(call: DomainVoiceEvent.ToolCall, action: AndroidActionResult.Accepted) =
        ToolDispatchResult(
            null,
            null,
            successChip = "✓ ${call.name}",
            deferredOutput = {
                val snapshot = executor.awaitNavigationOptions()
                com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
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
}
