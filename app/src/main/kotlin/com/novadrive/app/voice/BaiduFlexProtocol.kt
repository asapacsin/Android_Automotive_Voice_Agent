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
    /** Raised while assistant audio is still playing locally, to resist echo-only speech_started. */
    const val PLAYBACK_VAD_THRESHOLD = 0.75

    internal fun playbackScopedVadThreshold(playbackActive: Boolean, navigating: Boolean): Double =
        when {
            navigating -> NAVIGATION_VAD_THRESHOLD
            playbackActive -> PLAYBACK_VAD_THRESHOLD
            else -> DEFAULT_VAD_THRESHOLD
        }

    fun sessionUpdate(
        instructions: String,
        voice: String = BaiduAppSettings.DEFAULT_VOICE,
        speed: Double = BaiduAppSettings.DEFAULT_SPEED,
        vadThreshold: Double = DEFAULT_VAD_THRESHOLD,
    ): String {
        val session = JSONObject()
            .put("model", MODEL)
            .put("modalities", JSONArray(listOf("text", "audio")))
            .put("instructions", instructions.trim() + "\n" + PersonaProfiles.FLEX_TOOL_RULE)
            .put("voice", voice)
            .put("speed", speed)
            .put("input_audio_format", "pcm16")
            .put("output_audio_format", "pcm16")
            .put(
                "input_audio_transcription",
                // Measured 2026-09-19: the server rejects anything else outright -
                // `Invalid value: 'yue'. Value must be null or 'zh'.` There is no dialect
                // hint to give this model, so Cantonese input is transcribed as whatever
                // Mandarin it sounds like (B-015).
                JSONObject().put("model", "default").put("language", "zh"),
            )
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
            .put("tools", JSONArray().apply { RealtimeToolCatalog.tools().forEach { put(functionTool(it)) } })
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

    const val CHOOSE_NAVIGATION_OPTION = RealtimeToolCatalog.CHOOSE_NAVIGATION_OPTION
    const val END_CONVERSATION = RealtimeToolCatalog.END_CONVERSATION
    const val SET_SPEECH_OUTPUT = RealtimeToolCatalog.SET_SPEECH_OUTPUT
    const val QUERY_LIVE_INFO = RealtimeToolCatalog.QUERY_LIVE_INFO
    val LIVE_INFO_KINDS = RealtimeToolCatalog.LIVE_INFO_KINDS
    val ALONG_ROUTE_CATEGORIES = RealtimeToolCatalog.ALONG_ROUTE_CATEGORIES
    const val MAX_CHOICE_INDEX = RealtimeToolCatalog.MAX_CHOICE_INDEX
    val CHOICE_PREFERENCES = RealtimeToolCatalog.CHOICE_PREFERENCES

    private const val ACTIVE_RESPONSE_PHRASE = "already has an active response"

    fun isResponseAlreadyActive(text: String): Boolean = runCatching {
        val raw = JSONObject(text)
        val error = raw.optJSONObject("error") ?: raw
        ACTIVE_RESPONSE_PHRASE in "${error.optString("code")} ${error.optString("message")}".lowercase()
    }.getOrDefault(false)

    fun responseCreate(): String = JSONObject().put("type", "response.create").toString()

    /**
     * An `error` event reduced to what may be logged: the provider code (identifier characters
     * only) and a kind derived from the message. The message itself is never returned — it can
     * quote the driver.
     */
    fun errorCode(text: String): String = runCatching {
        val raw = JSONObject(text)
        val error = raw.optJSONObject("error") ?: raw
        val code = error.optString("code").filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }.take(64)
        val blob = "${error.optString("code")} ${error.optString("message")}".lowercase()
        val kind = when {
            CANCEL_REFUSED_PHRASES.any { it in blob } -> "cancel_refused"
            ACTIVE_RESPONSE_PHRASE in blob -> "response_busy"
            "cannot update a session" in blob -> "session_update_refused"
            else -> "other"
        }
        "${code.ifEmpty { "none" }} kind=$kind"
    }.getOrDefault("unparsed kind=other")

    private val CANCEL_REFUSED_PHRASES = listOf("no active response", "cancellation failed", "没有可取消", "无可取消")

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
                if (CANCEL_REFUSED_PHRASES.any { it in blob }) {
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

    /** Baidu's wire wrapper around a provider-neutral tool spec; key order is part of the golden. */
    private fun functionTool(spec: RealtimeToolCatalog.ToolSpec): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", spec.name)
            .put("description", spec.description)
            .put("parameters", spec.parameters)

    internal fun validId(value: String): Boolean = value.isNotBlank() && value.length <= 128 && value.all {
        it.isLetterOrDigit() || it == '_' || it == '-'
    }
}

/** Stateful, bounded assembler. It emits nothing until the authoritative done event. */
class FlexFunctionCallAssembler(
    private val onMalformed: (String) -> Unit = { com.novadrive.app.DebugVoiceLog.log(it) },
) : RealtimeCallAssembler {
    private data class Pending(val name: String, val itemId: String, val delta: StringBuilder = StringBuilder())
    private val pending = mutableMapOf<String, Pending>()
    private val completed = mutableSetOf<String>()

    override fun consume(text: String): List<DomainVoiceEvent> {
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

    override fun clear() {
        pending.clear()
        completed.clear()
    }

    private fun finish(raw: JSONObject): List<DomainVoiceEvent> {
        val callId = raw.optString("call_id")
        if (!BaiduFlexProtocol.validId(callId) || !completed.add(callId)) return emptyList()
        val current = pending.remove(callId)
        val name = current?.name.orEmpty()
        if (name.isBlank()) return listOf(rejected(callId, "", "MISSING_TOOL_METADATA"))
        // The done event is authoritative; the streamed deltas are the fallback when it carries none.
        val streamed = current?.delta?.toString().orEmpty()
        val arguments = raw.optString("arguments").ifEmpty { streamed }
        if (arguments.length > BaiduFlexProtocol.MAX_ARGUMENT_BYTES) return listOf(rejected(callId, name, "ARGUMENTS_TOO_LARGE"))
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        if (parsed == null) {
            // Shape only, never content: the arguments can hold a destination or a question.
            onMalformed("flex_call_args_malformed tool=$name ${argumentShape(raw.optString("arguments"), streamed)}")
            return listOf(rejected(callId, name, "MALFORMED_JSON"))
        }
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

    private fun validate(name: String, json: JSONObject): String? = RealtimeToolCatalog.validate(name, json)

    private fun argumentShape(done: String, streamed: String): String {
        val trimmed = done.trim()
        val shape = when {
            trimmed.isEmpty() -> "empty"
            !trimmed.startsWith("{") -> "not_object"
            !trimmed.endsWith("}") -> "truncated_object"
            else -> "invalid_object"
        }
        return "shape=$shape done_chars=${done.length} delta_chars=${streamed.length}"
    }

    private fun rejected(callId: String, name: String, reason: String) =
        DomainVoiceEvent.ToolCall(callId, name, mapOf("_validation_error" to reason))
}
