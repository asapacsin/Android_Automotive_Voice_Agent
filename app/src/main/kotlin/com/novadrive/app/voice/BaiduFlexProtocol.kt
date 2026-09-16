package com.novadrive.app.voice

import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.PersonaProfiles
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONArray
import org.json.JSONObject

object BaiduFlexProtocol {
    const val MODEL = "qianfan-realtime-flex-v1"
    const val MAX_ARGUMENT_BYTES = 4096
    const val DEFAULT_VAD_THRESHOLD = 0.62
    const val NAVIGATION_VAD_THRESHOLD = 0.75

    fun sessionUpdate(
        instructions: String,
        voice: String = BaiduAppSettings.DEFAULT_VOICE,
        speed: Double = BaiduAppSettings.DEFAULT_SPEED,
        vadThreshold: Double = DEFAULT_VAD_THRESHOLD,
    ): String {
        val navigate = functionTool(
            name = "navigate_to",
            description = "导航到指定地点。用户说「导航到X」「带我去X」「去X」时调用。Open navigation to a named destination.",
            properties = JSONObject().put(
                "destination",
                JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 120),
            ),
            required = "destination",
        )
        val openApp = functionTool(
            name = "open_app",
            description = "打开受支持的应用：maps=地图，settings=设置。音乐请改用 control_music。Open a supported app (maps or settings). Do not use this for music; use control_music.",
            properties = JSONObject().put(
                "app",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("maps", "settings"))),
            ),
            required = "app",
        )
        val controlMusic = functionTool(
            name = "control_music",
            description = "控制内置音乐播放器。用户说「播放音乐」「放首歌」「来点音乐」时 action=play；用户说「关闭音乐」「关掉音乐」「停止音乐」「别放了」「不要音乐了」时 action=stop。Play or stop the built-in music player.",
            properties = JSONObject().put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("play", "stop")))
                    .put("description", "play=开始播放音乐；stop=停止播放音乐"),
            ),
            required = "action",
        )
        val exitNavigationMode = functionToolNoArgs(
            name = "exit_navigation_mode",
            description = "退出小诺的导航模式，让小诺恢复正常说话。用户说「结束导航」「导航结束了」「退出导航」「不用导航了」时调用。注意：这只会让小诺恢复说话，并不会关闭高德地图的导航，高德需要用户自己退出。Exit the assistant's navigation quiet mode; this does NOT stop the Amap app's navigation.",
        )
        val session = JSONObject()
            .put("model", MODEL)
            .put("modalities", JSONArray(listOf("text", "audio")))
            .put("instructions", instructions.trim() + "\n" + PersonaProfiles.FLEX_TOOL_RULE)
            .put("voice", voice)
            .put("speed", speed)
            .put("input_audio_format", "pcm16")
            .put("output_audio_format", "pcm16")
            .put("input_audio_transcription", JSONObject().put("model", "default").put("language", "zh"))
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    // Raised from 0.5 so our own media playback is less likely to trip server VAD.
                    .put("threshold", vadThreshold)
                    .put("prefix_padding_ms", 300)
                    .put("silence_duration_ms", 200)
                    .put("create_response", true)
                    .put("interrupt_response", true),
            )
            .put("tools", JSONArray().put(navigate).put(openApp).put(controlMusic).put(exitNavigationMode))
            .put("tool_choice", "auto")
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun audioAppend(base64Audio: String): String =
        JSONObject().put("type", "input_audio_buffer.append").put("audio", base64Audio).toString()

    fun responseCancel(): String = JSONObject().put("type", "response.cancel").toString()

    fun functionCallOutput(callId: String, output: String): String {
        require(validId(callId)) { "FLEX_CALL_ID_INVALID" }
        require(output.length <= MAX_ARGUMENT_BYTES) { "FLEX_TOOL_OUTPUT_TOO_LARGE" }
        return JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", output),
            ).toString()
    }

    fun responseCreate(): String = JSONObject().put("type", "response.create").toString()

    fun parseCommonEvent(text: String, speaking: Boolean): List<DomainVoiceEvent> {
        val raw = JSONObject(text)
        return when (raw.optString("type")) {
            "response.text.delta" -> listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("delta"), false))
            "response.text.done" -> listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("text"), true))
            "response.function_call_arguments.delta", "response.function_call_arguments.done" -> emptyList()
            "response.output_item.added", "response.output_item.done", "conversation.item.created" -> emptyList()
            "error" -> {
                val error = raw.optJSONObject("error") ?: raw
                val providerCode = error.optString("code")
                val message = BaiduProtocol.sanitize(error.optString("message"))
                val blob = "$providerCode $message".lowercase()
                // A refused cancel is benign: nothing was playing, so emit nothing instead of Error.
                if (listOf("no active response", "cancellation failed", "没有可取消", "无可取消").any { it in blob }) {
                    return emptyList()
                }
                val code = if (listOf("permission", "forbidden", "access denied", "not entitled", "public beta", "无权限").any { it in blob }) {
                    "BAIDU_FLEX_ACCESS_DENIED"
                } else BaiduProtocol.classifyError(providerCode, message).replace("BAIDU_", "BAIDU_FLEX_")
                listOf(DomainVoiceEvent.Error(code, if (code == "BAIDU_FLEX_ACCESS_DENIED") {
                    "Baidu Flex public-beta/model access was denied"
                } else message))
            }
            else -> BaiduProtocol.parseServerEvent(text, speaking)
        }
    }

    private fun functionTool(name: String, description: String, properties: JSONObject, required: String): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray().put(required))
                    .put("additionalProperties", false),
            )

    private fun functionToolNoArgs(name: String, description: String): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject())
                    .put("additionalProperties", false),
            )

    internal fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 128 && value.all {
        it.isLetterOrDigit() || it == '_' || it == '-'
    }
}

/** Stateful, bounded assembler. It emits nothing until the authoritative done event. */
class FlexFunctionCallAssembler {
    private data class Pending(val name: String, val itemId: String, val delta: StringBuilder = StringBuilder())
    private val pending = mutableMapOf<String, Pending>()
    private val completed = mutableSetOf<String>()

    fun consume(text: String): List<DomainVoiceEvent> {
        val raw = JSONObject(text)
        return when (raw.optString("type")) {
            "response.output_item.added", "response.output_item.done" -> {
                val item = raw.optJSONObject("item") ?: return emptyList()
                if (item.optString("type") != "function_call") return emptyList()
                val callId = item.optString("call_id")
                val name = item.optString("name")
                val itemId = item.optString("id")
                if (BaiduFlexProtocol.validId(callId) && BaiduFlexProtocol.validId(name)) {
                    pending.putIfAbsent(callId, Pending(name, itemId))
                }
                emptyList()
            }
            "response.function_call_arguments.delta" -> {
                val callId = raw.optString("call_id")
                val current = pending[callId] ?: return emptyList()
                val delta = raw.optString("delta")
                if (current.delta.length + delta.length > BaiduFlexProtocol.MAX_ARGUMENT_BYTES) {
                    pending.remove(callId)
                    completed.add(callId)
                    return listOf(rejected(callId, current.name, "ARGUMENTS_TOO_LARGE"))
                }
                current.delta.append(delta)
                emptyList()
            }
            "response.function_call_arguments.done" -> finish(raw)
            else -> emptyList()
        }
    }

    fun clear() {
        pending.clear()
        completed.clear()
    }

    private fun finish(raw: JSONObject): List<DomainVoiceEvent> {
        val callId = raw.optString("call_id")
        if (!BaiduFlexProtocol.validId(callId) || !completed.add(callId)) return emptyList()
        val current = pending.remove(callId)
        val name = current?.name.orEmpty()
        if (name.isBlank()) return listOf(rejected(callId, "", "MISSING_TOOL_METADATA"))
        val arguments = raw.optString("arguments")
        if (arguments.length > BaiduFlexProtocol.MAX_ARGUMENT_BYTES) return listOf(rejected(callId, name, "ARGUMENTS_TOO_LARGE"))
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
            ?: return listOf(rejected(callId, name, "MALFORMED_JSON"))
        val validation = validate(name, parsed)
        if (validation != null) return listOf(rejected(callId, name, validation))
        val args = buildMap {
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, parsed.getString(key))
            }
        }
        return listOf(DomainVoiceEvent.ToolCall(callId, name, args))
    }

    private fun validate(name: String, json: JSONObject): String? {
        val keys = buildSet { val iterator = json.keys(); while (iterator.hasNext()) add(iterator.next()) }
        return when (name) {
            "navigate_to" -> when {
                keys != setOf("destination") -> "INVALID_FIELDS"
                json.opt("destination") !is String -> "INVALID_FIELD_TYPE"
                json.optString("destination").trim().isEmpty() -> "BLANK_DESTINATION"
                json.optString("destination").trim().length > 120 -> "DESTINATION_TOO_LONG"
                else -> null
            }
            "open_app" -> when {
                keys != setOf("app") -> "INVALID_FIELDS"
                json.opt("app") !is String -> "INVALID_FIELD_TYPE"
                json.optString("app") !in setOf("maps", "settings") -> "APP_NOT_ALLOWED"
                else -> null
            }
            "control_music" -> when {
                keys != setOf("action") -> "INVALID_FIELDS"
                json.opt("action") !is String -> "INVALID_FIELD_TYPE"
                json.optString("action") !in setOf("play", "stop") -> "ACTION_NOT_ALLOWED"
                else -> null
            }
            "exit_navigation_mode" -> when {
                keys.isNotEmpty() -> "INVALID_FIELDS"
                else -> null
            }
            else -> null // The Android dispatcher returns UNKNOWN_TOOL without executing.
        }
    }

    private fun rejected(callId: String, name: String, reason: String) =
        DomainVoiceEvent.ToolCall(callId, name, mapOf("_validation_error" to reason))
}
