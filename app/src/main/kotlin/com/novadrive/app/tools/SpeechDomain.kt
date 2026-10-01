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
            description = "结束这次对话，小诺停止聆听（进入待命，说「你好小诺」可再唤醒）。只在用户明确表示不需要小诺了时调用，" +
                "例如「没事了，你休息吧」「先这样吧，不聊了」「that's all」。调用后只说一句很短的道别，例如「好的，有需要再叫我」。" +
                "不要用于关闭空调、音乐、导航或车窗等设备，也不要用于「算了」取消导航选择。" +
                "Ends the conversation: the assistant stops listening until woken again. Not for turning devices off.",
        )
        val setSpeechOutput = spec(
            name = SET_SPEECH_OUTPUT,
            description = "用户让小诺别说了、别吵、保持安静、停止说话、停止說話时用 mode=silent：小诺立刻停止说话，但继续听，下一句指令照常执行，不需要再唤醒；" +
                "调用后不要再说任何话（不要「好的」之类确认）。这不是休眠（休眠用 end_conversation）。用户说「继续说」「可以说话了」「你说吧」时用 mode=spoken 恢复正常对话。" +
                "不要用于音乐、导航播报或车辆音量。silent: stop talking now, no acknowledgement, wait for the next command (not sleep); spoken: talk normally.",
            properties = JSONObject().put(
                "mode",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("silent", "spoken"))),
            ),
            required = listOf("mode"),
        )
        val setSpeakingStyle = spec(
            name = SET_SPEAKING_STYLE,
            description = "切换小诺说话的语气（只改语气，不改声音）。用户说「说话能不能嗲一点」「撒个娇」「可爱一点」「甜一点」时 style=sweet；" +
                "「傲娇一点」「傲嬌一點」「用傲娇的语气」「嘴硬一点」时 style=tsundere；" +
                "「温柔一点」「温柔些」「说话轻一点」时 style=gentle；「元气一点」「活泼一点」「有活力一点」「开心一点地说」时 style=lively；" +
                "说「正常一点」「恢复」「别嗲了」「别傲娇了」「正常说话」时 style=default。设置会一直保持，直到用户再次要求更改。" +
                "用户要的语气不在这几种里（例如「霸道一点」「凶一点」）时不要调用，也不要换成别的语气，用一句话说暂时只有甜、傲娇、温柔、元气和正常这几种。" +
                "调用后按返回的 instruction 用新的语气简短回应一句。" +
                "Switches the assistant's speaking tone only (the voice never changes); the setting stays until the driver asks again.",
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
