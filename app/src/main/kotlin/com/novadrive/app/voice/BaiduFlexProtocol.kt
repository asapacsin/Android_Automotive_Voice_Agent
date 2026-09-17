package com.novadrive.app.voice

import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.PersonaProfiles
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONArray
import org.json.JSONObject

/** Single source of truth for the climate tool's action enum: the handler defines it. */
internal val CLIMATE_ACTIONS: List<String> = ClimateToolHandler.ACTIONS

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
            description = "导航到指定地点。用户说「导航到X」「带我去X」「去X」时调用。调用成功后屏幕会列出候选地点和路线，由用户点选后才开始导航：只回答「请在屏幕上选择路线」，不要说已经开始导航。用户换目的地或说「不是这个」「换成X」时，必须用新的目的地再次调用本工具，屏幕上的候选会被替换。Shows destination and route choices on screen; navigation starts only after the driver picks a route, so never claim it has started. Call again with the new destination whenever the driver changes it; the on-screen list is replaced.",
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
        val controlClimate = functionTool(
            name = "control_climate",
            description = "控制车内空调（当前为模拟空调）。「打开空调」action=power_on；「关闭空调」action=power_off；" +
                "「空调调到22度」action=set_temperature,value=22；「温度调高一点」action=adjust_temperature,value=1；" +
                "「温度调低一点」action=adjust_temperature,value=-1；「风量调到3档」action=set_fan,value=3；" +
                "「风量调大」action=adjust_fan,value=1；「风量调小」action=adjust_fan,value=-1；「现在空调多少度」action=get_state。" +
                "相对调节必须用 adjust_*，不要自己推算原来的温度。只根据工具返回的 temperature_c/fan_level/power_on 确认结果；" +
                "ok=false 时必须如实说没有成功。Control the cabin climate (simulated backend).",
            properties = JSONObject()
                .put(
                    "action",
                    JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray(CLIMATE_ACTIONS)),
                )
                .put(
                    "value",
                    JSONObject()
                        .put("type", "number")
                        .put("description", "set_temperature: 16-32 摄氏度；set_fan: 0-7 档；adjust_*: 变化量，默认 1"),
                ),
            required = "action",
        )
        val describeCamera = functionTool(
            name = "describe_camera_view",
            description = "看摄像头画面并回答问题。用户说「看看前面有什么」「摄像头里是什么」「画面里有几个人」「这是什么东西」「这是什么」「这个是什么」「看一下这个」等询问眼前或镜头画面的问题时调用（没有其他上下文时，「这是什么」一律当作问镜头画面），" +
                "question 填用户的原问题。会自动打开摄像头并把当前画面发给视觉模型，需要几秒钟。" +
                "只根据返回的 answer 回答；ok=false 时只转述 message，绝对不要猜测画面内容。" +
                "Look at the camera image and answer the driver's question about it.",
            properties = JSONObject().put(
                "question",
                JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200),
            ),
            required = "question",
        )
        val exitNavigationMode = functionToolNoArgs(
            name = "exit_navigation_mode",
            description = "结束或取消导航。用户说「结束导航」「停止导航」「取消导航」「退出导航」「不去了」，或在导航、选择地点/路线时说「算了」时调用。" +
                "会真正停止屏幕上的导航，或关闭候选列表；根据返回的 status 如实回答：navigation_stopped=导航已结束，" +
                "navigation_selection_cancelled=已取消选择，no_navigation_active=当前没有导航。" +
                "Ends the on-screen navigation or closes the picker; answer from the returned status.",
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
            .put("tools", JSONArray().put(navigate).put(openApp).put(controlMusic).put(controlClimate).put(describeCamera).put(exitNavigationMode))
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

    /** A user text turn (OpenAI-realtime-compatible `conversation.item.create` message). */
    fun userTextMessage(text: String): String {
        require(text.length <= MAX_ARGUMENT_BYTES) { "FLEX_TEXT_TOO_LARGE" }
        return JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text))),
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
                // A refused session.update is not session-fatal either: the session keeps its
                // previous settings and the conversation can continue. Measured 2026-09-16 —
                // treating "Cannot update a session's turn detection threshold while input audio
                // is in progress" as an Error put the live session into ERROR.
                if ("cannot update a session" in blob) {
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
                // get().toString(), not getString(): numeric tool arguments (control_climate value)
                // must stringify identically on Android's org.json and the JVM test artifact,
                // which throws from getString() on a number.
                put(key, parsed.get(key).toString())
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
            "control_climate" -> when {
                !keys.contains("action") -> "INVALID_FIELDS"
                !setOf("action", "value").containsAll(keys) -> "INVALID_FIELDS"
                json.opt("action") !is String -> "INVALID_FIELD_TYPE"
                json.optString("action") !in CLIMATE_ACTIONS -> "ACTION_NOT_ALLOWED"
                keys.contains("value") && json.opt("value") !is Number -> "INVALID_FIELD_TYPE"
                json.optString("action") in setOf("set_temperature", "set_fan") && !keys.contains("value") -> "MISSING_VALUE"
                else -> null
            }
            "describe_camera_view" -> when {
                keys != setOf("question") -> "INVALID_FIELDS"
                json.opt("question") !is String -> "INVALID_FIELD_TYPE"
                json.optString("question").isBlank() -> "BLANK_QUESTION"
                json.optString("question").length > 200 -> "QUESTION_TOO_LONG"
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
