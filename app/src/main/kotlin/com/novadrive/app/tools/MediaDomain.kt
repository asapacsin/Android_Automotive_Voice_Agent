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
            description = "内置音乐播放器：「播放音乐」「放首歌」→play；「关闭音乐」「别放了」→stop。",
            properties = JSONObject().put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("play", "stop")))
                    .put("description", "play=播放；stop=停止")
                    ,
            ),
            required = listOf("action"),
        )
        val properties = JSONObject()
        PLAY_MUSIC_FIELDS.forEach { (key, meaning) ->
            properties.put(key, JSONObject().put("type", "string").put("maxLength", MAX_FIELD).put("description", meaning))
        }
        val playMusic = spec(
            name = PLAY_MUSIC,
            description = PLAY_MUSIC_DESCRIPTION,
            properties = properties,
            required = emptyList(),
        ).also { it.parameters.remove("required") }
        return listOf(controlMusic.copy(repeatSensitive = true), playMusic.copy(repeatSensitive = true))
    }

    const val PLAY_MUSIC = "play_music"
    const val MAX_FIELD = 80

    /** Fields of `play_music`; at least one of [DESCRIBING_FIELDS] must be non-blank. */
    private val PLAY_MUSIC_FIELDS = linkedMapOf(
        "title" to "你确定的歌名",
        "artist" to "歌手或作曲者",
        "album_or_work" to "专辑或作品（动画、电影、游戏）",
        "mood" to "情绪，如燃、提神",
        "query" to "用户原话",
        "exclude_title" to "「不是这首」时刚才那首歌名",
    )
    private val DESCRIBING_FIELDS = setOf("title", "artist", "album_or_work", "query", "mood")

    private const val PLAY_MUSIC_DESCRIPTION =
        "按歌名、歌手、作品或模糊描述放歌（用手机音乐 app），如「梶浦由记的、空之境界里很燃的那首片头曲」「来点开车提神的」。先用你的知识确定是哪首，填 title/artist/album_or_work；确定不了只填 query 和 mood。只按返回的 now_playing 说在放什么；status=requested_unverified 只说「已经让音乐 app 去找了」；ok=false 如实说没放成。「不是这首」时再调用并填 exclude_title。无描述的「放首歌」用 control_music。"

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
            PLAY_MUSIC -> when {
                keys.isEmpty() || !PLAY_MUSIC_FIELDS.keys.containsAll(keys) -> "INVALID_FIELDS"
                keys.any { json.opt(it) !is String } -> "INVALID_FIELD_TYPE"
                keys.any { json.optString(it).length > MAX_FIELD } -> "INVALID_FIELD_VALUE"
                DESCRIBING_FIELDS.none { json.optString(it).isNotBlank() } -> "MISSING_VALUE"
                else -> null
            }
            else -> null
        }
    }
}
