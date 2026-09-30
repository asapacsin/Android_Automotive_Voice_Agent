package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `media` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object MediaDomain : ToolDomain {
    override val id = "media"

    override fun specs(): List<ToolSpec> {
        val controlMusic = spec(
            name = "control_music",
            description = "控制内置音乐播放器。用户说「播放音乐」「放首歌」「来点音乐」时 action=play；用户说「关闭音乐」「关掉音乐」「停止音乐」「别放了」「不要音乐了」时 action=stop。Play or stop the built-in music player.",
            properties = JSONObject().put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("play", "stop")))
                    .put("description", "play=开始播放音乐；stop=停止播放音乐"),
            ),
            required = listOf("action"),
        )
        return listOf(controlMusic.copy(repeatSensitive = true))
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            "control_music" -> when {
                keys != setOf("action") -> "INVALID_FIELDS"
                json.opt("action") !is String -> "INVALID_FIELD_TYPE"
                json.optString("action") !in setOf("play", "stop") -> "ACTION_NOT_ALLOWED"
                else -> null
            }
            else -> null
        }
    }
}
