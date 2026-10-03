package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `apps` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object AppsDomain : ToolDomain {
    override val id = "apps"

    override fun specs(): List<ToolSpec> {
        val openApp = spec(
            name = "open_app",
            description = "打开受支持的应用：maps=地图，settings=设置。音乐请改用 control_music。Open a supported app (maps or settings). Do not use this for music; use control_music.",
            properties = JSONObject().put(
                "app",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("maps", "settings"))),
            ),
            required = listOf("app"),
        )
        return listOf(openApp.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            "open_app" -> when {
                keys != setOf("app") -> "INVALID_FIELDS"
                json.opt("app") !is String -> "INVALID_FIELD_TYPE"
                json.optString("app") !in setOf("maps", "settings") -> "APP_NOT_ALLOWED"
                else -> null
            }
            else -> null
        }
    }
}
