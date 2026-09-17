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
            description = "导航到指定地点。用户说「导航到X」「带我去X」「去X」时调用。调用成功后屏幕会列出候选地点或路线，列表已显示在屏幕上，不要念出列表，按返回的 next 只说一句（找到几个，请说第几个或点选），选择用 choose_navigation_option；选好路线前导航没有开始，不要说已经开始导航。用户换目的地或说「不是这个」「换成X」时，必须用新的目的地再次调用本工具，屏幕上的候选会被替换。Shows destination and route choices on screen; navigation starts only after the driver picks a route, so never claim it has started. Call again with the new destination whenever the driver changes it; the on-screen list is replaced.",
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
            description = "结束或取消导航。用户说「结束导航」「停止导航」「取消导航」「退出导航」「不去了」，或在导航、选择地点/路线时说「算了」「不用了」「返回」时调用；只口头答应而不调用本工具，屏幕上的导航或列表不会有任何变化。" +
                "会真正停止屏幕上的导航，或关闭候选列表；根据返回的 status 如实回答：navigation_stopped=导航已结束，" +
                "navigation_selection_cancelled=已取消选择，no_navigation_active=当前没有导航。" +
                "Ends the on-screen navigation or closes the picker; answer from the returned status.",
        )
        val chooseNavigationOption = JSONObject()
            .put("type", "function")
            .put("name", CHOOSE_NAVIGATION_OPTION)
            .put(
                "description",
                "在屏幕上的导航候选地点或候选路线中做选择（与点选效果相同）。屏幕显示候选列表时，用户说「第二个」「第一条」→ index；" +
                    "「选最快的」「时间最短」→ preference=fastest；「最短的」「距离最近的路线」→ shortest；「推荐的」→ recommended；" +
                    "「不要收费」「免费的」→ no_toll；路线列表显示时说「开始导航」「好的」「就这条」→ recommended；「红绿灯少的」→ fewest_lights；「最近的那个地点」→ nearest；「就去拱北口岸」「选万达广场那个」→ name。" +
                    "三个参数只填一个。根据返回结果如实回答：destination_selected 时不要逐条念路线，按返回的 next 只说一句并请用户选路线；" +
                    "navigation_started 时说导航已开始；ok=false 时按 error 说明（OUT_OF_RANGE=没有这一项，NO_OPTIONS_ON_SCREEN=现在没有可选的列表，" +
                    "OPTIONS_NOT_READY=还在计算，AMBIGUOUS=有多个匹配请说第几个）。" +
                    "Pick from the on-screen destination or route list, exactly like a tap. Fill exactly one field.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("index", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_CHOICE_INDEX))
                            .put(
                                "preference",
                                JSONObject().put("type", "string").put("enum", JSONArray(CHOICE_PREFERENCES.toList())),
                            )
                            .put("name", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 40)),
                    )
                    .put("additionalProperties", false),
            )
        val endConversation = functionToolNoArgs(
            name = END_CONVERSATION,
            description = "结束这次对话，小诺停止聆听（进入待命，说「你好小诺」可再唤醒）。只在用户明确表示不需要小诺了时调用，" +
                "例如「没事了，你休息吧」「先这样吧，不聊了」「that's all」。调用后只说一句很短的道别，例如「好的，有需要再叫我」。" +
                "不要用于关闭空调、音乐、导航或车窗等设备，也不要用于「算了」取消导航选择。" +
                "Ends the conversation: the assistant stops listening until woken again. Not for turning devices off.",
        )
        val setSpeechOutput = functionTool(
            name = SET_SPEECH_OUTPUT,
            description = "切换小诺是否用语音回答。用户让小诺别出声、别吵、保持安静时用 mode=silent（之后只显示文字，仍然听指令、执行操作）；" +
                "用户说可以说话了、恢复语音时用 mode=spoken。不要用于音乐、导航播报或车辆音量。" +
                "Turns the assistant's spoken replies off (silent: text only, still listening and acting) or back on.",
            properties = JSONObject().put(
                "mode",
                JSONObject().put("type", "string").put("enum", JSONArray(listOf("silent", "spoken"))),
            ),
            required = "mode",
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
            .put("tools", JSONArray().put(navigate).put(openApp).put(controlMusic).put(controlClimate).put(describeCamera).put(exitNavigationMode).put(chooseNavigationOption).put(endConversation).put(setSpeechOutput))
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

    const val CHOOSE_NAVIGATION_OPTION = "choose_navigation_option"
    const val END_CONVERSATION = "end_conversation"
    const val SET_SPEECH_OUTPUT = "set_speech_output"
    const val MAX_CHOICE_INDEX = 10
    val CHOICE_PREFERENCES = setOf("fastest", "shortest", "recommended", "no_toll", "fewest_lights", "nearest")

    private const val ACTIVE_RESPONSE_PHRASE = "already has an active response"

    fun isResponseAlreadyActive(text: String): Boolean = runCatching {
        val raw = JSONObject(text)
        val error = raw.optJSONObject("error") ?: raw
        ACTIVE_RESPONSE_PHRASE in "${error.optString("code")} ${error.optString("message")}".lowercase()
    }.getOrDefault(false)

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
                // Two replies overlapped. Not session-fatal: the running reply continues and the
                // client asks again when it ends (see ResponseTurnGate). Measured 2026-09-17 —
                // this refusal put the session into ERROR mid camera question.
                if (ACTIVE_RESPONSE_PHRASE in blob) {
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
            "exit_navigation_mode", BaiduFlexProtocol.END_CONVERSATION -> when {
                keys.isNotEmpty() -> "INVALID_FIELDS"
                else -> null
            }
            BaiduFlexProtocol.SET_SPEECH_OUTPUT -> when {
                keys != setOf("mode") -> "INVALID_FIELDS"
                json.opt("mode") !is String -> "INVALID_FIELD_TYPE"
                json.optString("mode") !in setOf("silent", "spoken") -> "MODE_NOT_ALLOWED"
                else -> null
            }
            BaiduFlexProtocol.CHOOSE_NAVIGATION_OPTION -> when {
                keys.size != 1 || !setOf("index", "preference", "name").containsAll(keys) -> "INVALID_FIELDS"
                "index" in keys -> {
                    val index = when (val raw = json.opt("index")) {
                        is Number -> raw.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
                        is String -> raw.trim().toIntOrNull()
                        else -> null
                    }
                    if (index == null || index !in 1..BaiduFlexProtocol.MAX_CHOICE_INDEX) "INVALID_INDEX" else null
                }
                "preference" in keys ->
                    if ((json.opt("preference") as? String) !in BaiduFlexProtocol.CHOICE_PREFERENCES) "PREFERENCE_NOT_ALLOWED" else null
                else -> {
                    val name = json.opt("name") as? String
                    if (name == null || name.isBlank() || name.length > 40) "INVALID_NAME" else null
                }
            }
            else -> null // The Android dispatcher returns UNKNOWN_TOOL without executing.
        }
    }

    private fun rejected(callId: String, name: String, reason: String) =
        DomainVoiceEvent.ToolCall(callId, name, mapOf("_validation_error" to reason))
}
