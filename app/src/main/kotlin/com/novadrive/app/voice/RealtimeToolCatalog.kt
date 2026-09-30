package com.novadrive.app.voice

import com.novadrive.app.tools.ToolRegistry
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONObject

/**
 * Provider-neutral realtime tool catalogue: every tool's name, description and JSON-Schema
 * parameters, plus the argument rules a call must satisfy. Shared by every realtime adapter
 * (ADR-010); each adapter only wraps [ToolSpec] in its own wire format. Never logs arguments (I-8).
 */
object RealtimeToolCatalog {
    const val CHOOSE_NAVIGATION_OPTION = "choose_navigation_option"
    const val END_CONVERSATION = "end_conversation"
    const val SET_SPEECH_OUTPUT = "set_speech_output"
    const val QUERY_LIVE_INFO = "query_live_info"
    val LIVE_INFO_KINDS = listOf("weather", "route_traffic", "along_route", "place_details")
    val ALONG_ROUTE_CATEGORIES = listOf("fuel", "charging", "service_area", "toilet")
    const val MAX_CHOICE_INDEX = 10
    val CHOICE_PREFERENCES = setOf("fastest", "shortest", "recommended", "no_toll", "fewest_lights", "nearest")

    /** [parameters] is the JSON Schema object; a fresh instance on every [tools] call. [repeatSensitive]: an identical second call in one driver turn is dropped (ToolCallGuards). */
    data class ToolSpec(val name: String, val description: String, val parameters: JSONObject, val repeatSensitive: Boolean = false)

    /** All tools, assembled from the car domains by [ToolRegistry.PRODUCT]. */
    fun tools(): List<ToolSpec> = ToolRegistry.PRODUCT.tools()

    /** Rule violation code for a call, or null when valid or the tool is unknown (dispatcher answers UNKNOWN_TOOL). */
    fun validate(name: String, json: JSONObject): String? = ToolRegistry.PRODUCT.validate(name, json)

    /** Upper bound on a call's serialised arguments (same value as the Baidu adapter's). */
    const val MAX_ARGUMENT_BYTES = 4096

    /** A call or tool id an adapter may accept from the wire. */
    fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 128 && value.all {
        it.isLetterOrDigit() || it == '_' || it == '-'
    }

    /**
     * Parsed arguments turned into the neutral, validated [DomainVoiceEvent.ToolCall]; a rule
     * violation becomes `{"_validation_error": CODE}`. Values are `get(key).toString()` so numbers
     * stringify identically on Android's org.json and the JVM test artifact.
     */
    fun toolCall(callId: String, name: String, args: JSONObject): DomainVoiceEvent.ToolCall {
        validate(name, args)?.let { return rejectedCall(callId, name, it) }
        val map = buildMap {
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, args.get(key).toString())
            }
        }
        return DomainVoiceEvent.ToolCall(callId, name, map)
    }

    fun rejectedCall(callId: String, name: String, reason: String) =
        DomainVoiceEvent.ToolCall(callId, name, mapOf("_validation_error" to reason))

}
