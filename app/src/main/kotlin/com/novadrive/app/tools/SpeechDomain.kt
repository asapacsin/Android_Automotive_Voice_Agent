package com.novadrive.app.tools

import com.novadrive.app.voice.RealtimeToolCatalog.END_CONVERSATION
import com.novadrive.app.voice.RealtimeToolCatalog.SET_SPEECH_OUTPUT
import com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** The `speech` car domain (SPEC-016 B, ADR-015): its tool declarations and argument rules. */
object SpeechDomain : ToolDomain {
    override val id = "speech"

    const val SET_SPEAKING_STYLE = "set_speaking_style"
    val STYLES = com.novadrive.app.SpeakingStyle.entries.map { it.wireName }

    override fun specs(): List<ToolSpec> {
        val endConversation = specNoArgs(
            name = END_CONVERSATION,
            description = "结束对话，小诺进入待命（「你好小诺」唤醒）。仅在用户明确不需要了时调用：「没事了，你休息吧」「不聊了」。调用后只说一句短道别。不用于关空调、音乐、导航、车窗，也不用于「算了」取消导航选择。",
        )
        val setSpeechOutput = spec(
            name = SET_SPEECH_OUTPUT,
            description = "「别说了」「安静」「停止说话」「停止說話」→mode=silent：立刻停说但继续听，下一句照常执行，无需唤醒，调用后不要再说话（不是休眠，休眠用 end_conversation）。「继续说」「可以说话了」→mode=spoken。不用于音乐、导航播报或音量。",
            properties = JSONObject().put(
                "mode",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("silent", "spoken"))),
            ),
            required = listOf("mode"),
        )
        val setSpeakingStyle = spec(
            name = SET_SPEAKING_STYLE,
            description = "切换小诺说话的语气（只改语气，不改声音），一直保持到再次更改。说的是她「说话」怎样（「说话能不能嗲一点」「说话再稍微嗲一点」），就是语气，不是空调、座椅或车窗的调节。「嗲一点」「甜一点」「可爱一点」「撒个娇」→sweet；「傲娇一点」「傲嬌一點」→tsundere；「温柔一点」→gentle；「元气一点」「活泼一点」→lively；「正常一点」「别嗲了」→default。不在其中的语气（如「霸道一点」）不要调用也不要替换，说只有甜、傲娇、温柔、元气和正常。调用后按返回的 instruction 用新语气简短回应。",
            properties = JSONObject().put(
                "style",
                JSONObject().put("type", "string").put("enum", JSONArray(STYLES)),
            ),
            required = listOf("style"),
        ).copy(repeatSensitive = true)
        return listOf(endConversation, setSpeechOutput, setSpeakingStyle)
    }

    override fun validate(name: String, args: JSONObject): String? {
        val json = args
        val keys = argumentKeys(json)
        return when (name) {
            END_CONVERSATION -> when {
                keys.isNotEmpty() -> "INVALID_FIELDS"
                else -> null
            }
            SET_SPEECH_OUTPUT -> when {
                keys != setOf("mode") -> "INVALID_FIELDS"
                json.opt("mode") !is String -> "INVALID_FIELD_TYPE"
                json.optString("mode") !in setOf("silent", "spoken") -> "MODE_NOT_ALLOWED"
                else -> null
            }
            SET_SPEAKING_STYLE -> when {
                keys != setOf("style") -> "INVALID_FIELDS"
                json.opt("style") !is String -> "INVALID_FIELD_TYPE"
                json.optString("style") !in STYLES -> "STYLE_NOT_ALLOWED"
                else -> null
            }
            else -> null
        }
    }
}
