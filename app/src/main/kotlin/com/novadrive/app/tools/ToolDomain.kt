package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * One car domain (MCP-shaped, ADR-015): the tools it declares and their argument rules. Runtime
 * execution is bound later (ToolServer). Never logs arguments (I-8).
 */
interface ToolDomain {
    /** "navigation", "apps", "media", "climate", "vision", "phone", "live_info", "speech". */
    val id: String

    /** This domain's tool declarations; fresh [JSONObject] instances on every call. */
    fun specs(): List<ToolSpec>

    /** Rule-violation code for a tool this domain declares, or null when valid. */
    fun validate(name: String, args: JSONObject): String?
}

internal fun argumentKeys(json: JSONObject): Set<String> =
    buildSet { val iterator = json.keys(); while (iterator.hasNext()) add(iterator.next()) }

internal fun spec(name: String, description: String, properties: JSONObject, required: List<String>) =
    ToolSpec(
        name,
        description,
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false),
    )

internal fun specNoArgs(name: String, description: String) =
    ToolSpec(
        name,
        description,
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false),
    )
