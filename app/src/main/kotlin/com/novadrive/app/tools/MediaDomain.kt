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
        "title" to "歌名（你确定的具体歌曲）",
        "artist" to "歌手或作曲者",
        "album_or_work" to "专辑或作品（动画、电影、游戏）",
        "mood" to "情绪或风格，例如燃、安静、提神",
        "query" to "用户的原话描述",
        "exclude_title" to "用户说「不是这首」时，刚才那首的歌名",
    )
    private val DESCRIBING_FIELDS = setOf("title", "artist", "album_or_work", "query", "mood")

    private const val PLAY_MUSIC_DESCRIPTION =
        "按描述播放音乐（交给手机上的音乐 app 播放）：用户说出歌名、歌手、作品（动画、电影、游戏）或模糊描述" +
            "（例如「梶浦由记的、空之境界里很燃的那首片头曲」「来点开车提神的」）时调用。" +
            "先用你的知识确定具体是哪一首：歌名填 title，歌手填 artist，作品填 album_or_work，情绪填 mood，用户原话填 query；" +
            "确定不了具体歌曲时只填 query 和 mood。只根据返回的 now_playing 说正在放什么；" +
            "status=requested_unverified 时只能说「已经让音乐 app 去找了」，不能说正在放哪首；ok=false 时如实说没放成。" +
            "用户说「不是这首」时再调用一次，把刚才那首填到 exclude_title。" +
            "只说「放首歌」「播放音乐」而没有任何描述时用 control_music。" +
            " Play a song the driver named or described through the phone's music app; report only what now_playing says."

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
