package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.PersonaProfiles
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.app.QwenSettings
import com.novadrive.app.QwenSettingsValidator
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.UnknownHostException

/**
 * Qwen-Omni Realtime on [OpenAiRealtimeClient] (SPEC-021 step 2): the workspace endpoint, Bearer
 * auth, the documented session.update, `QWEN_*` error codes and the text-item refusal. Logs carry
 * codes only: never the key, the workspace id, a transcript or the instructions (I-8).
 */
class QwenOmniDialect(
    private val requireTls: Boolean = true,
    /** The WebSocket URL for the settings; tests point it at a local server. */
    private val endpoint: (QwenAppSettings) -> String = QwenSettings::endpointUrl,
) : RealtimeDialect<QwenApiConfig> {
    override val logPrefix = "qwen"

    /** Unused: semantic_vad has no threshold. */
    override val defaultVadThreshold = 0.0

    /** Mid-session VAD changes are not made, so navigation is not tracked. */
    override val tracksNavigationVad = false

    @Volatile private var settings = QwenAppSettings()

    /** The server refused a user text item in this session; text turns are not sent again. */
    @Volatile private var textRefused = false

    /** A user text item was sent and the server has not yet accepted it (item created / response). */
    @Volatile private var textOutstanding = false

    override suspend fun buildRequest(config: QwenApiConfig): Request {
        QwenSettingsValidator.validate(config.settings, config.apiKey)?.let {
            throw VoiceProviderException(it, QwenSettingsValidator.message(it) ?: it)
        }
        val url = endpoint(config.settings)
        if (requireTls && !url.startsWith("wss://")) {
            throw VoiceProviderException("QWEN_ENDPOINT_INVALID", "Qwen endpoint must use wss://")
        }
        // The URL holds the workspace id in its host: its parse error must never carry it.
        val builder = try {
            Request.Builder().url(url)
        } catch (_: IllegalArgumentException) {
            throw VoiceProviderException("QWEN_WORKSPACE_INVALID", QwenSettingsValidator.message("QWEN_WORKSPACE_INVALID")!!)
        }
        return builder.header("Authorization", "Bearer ${config.apiKey}").build()
    }

    override fun onSessionOpening(config: QwenApiConfig) {
        settings = config.settings
        textRefused = false
        textOutstanding = false
    }

    override fun instructions(config: QwenApiConfig): String? = config.instructions

    override fun vadThreshold(navigating: Boolean): Double = defaultVadThreshold

    override fun sessionUpdate(instructions: String, vadThreshold: Double): String = sessionUpdate(instructions, settings)

    override fun recoverSessionError(instructions: () -> String, vadThreshold: Double): String? = null

    override fun onSessionUpdated(text: String) {
        DebugVoiceLog.log("qwen_session voice=${settings.voice} vad=${settings.vadType} model=${settings.model}")
    }

    override fun newCallAssembler(): RealtimeCallAssembler = FlexFunctionCallAssembler(
        onMalformed = { DebugVoiceLog.log(it.replaceFirst("flex_", "qwen_")) },
        nameFromDoneEvent = true,
    )

    override fun audioAppend(base64Audio: String) = BaiduFlexProtocol.audioAppend(base64Audio)
    override fun isAudioAppend(message: String) = message.contains("\"input_audio_buffer.append\"")
    override fun responseCancel() = BaiduFlexProtocol.responseCancel()
    override fun responseCreate() = BaiduFlexProtocol.responseCreate()

    override fun functionCallOutput(callId: String, output: String): String {
        require(BaiduFlexProtocol.validId(callId)) { "QWEN_CALL_ID_INVALID" }
        require(output.length <= BaiduFlexProtocol.MAX_ARGUMENT_BYTES) { "QWEN_TOOL_OUTPUT_TOO_LARGE" }
        return JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output))
            .toString()
    }

    /** The OpenAI `message`/`input_text` item; null once the server has refused one (SPEC-021 B7). */
    override fun userTextMessage(text: String): String? {
        textOutstanding = false
        if (textRefused) return null
        require(text.length <= BaiduFlexProtocol.MAX_ARGUMENT_BYTES) { "QWEN_TEXT_TOO_LARGE" }
        return JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text))),
            ).toString().also { textOutstanding = true }
    }

    override fun parseCommonEvent(text: String, speaking: Boolean): List<DomainVoiceEvent> {
        val raw = JSONObject(text)
        val type = raw.optString("type")
        if (type == "conversation.item.created" || type == "response.created") textOutstanding = false
        return when (type) {
            "response.text.delta" -> listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("delta"), false))
            "response.text.done" -> listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("text"), true))
            "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.output_item.added", "response.output_item.done", "conversation.item.created",
            // A failed transcription loses one utterance's text, not the session.
            "conversation.item.input_audio_transcription.failed",
            -> emptyList()
            "error" -> parseError(raw.optJSONObject("error") ?: raw)
            // The remaining documented events are plain OpenAI-Realtime; only an error inside a
            // failed response.done needs the Qwen code instead of the shared parser's.
            else -> BaiduProtocol.parseServerEvent(text, speaking).map {
                if (it is DomainVoiceEvent.Error) DomainVoiceEvent.Error(classify(it.message), redact(it.message)) else it
            }
        }
    }

    private fun parseError(error: JSONObject): List<DomainVoiceEvent> {
        val blob = blob(error)
        if (isTextItemRefusal(error)) {
            if (!textRefused) DebugVoiceLog.log("qwen_text_unsupported")
            textRefused = true
            return emptyList()
        }
        // A refused cancel (nothing playing) and an overlapping reply are not session-fatal.
        if (CANCEL_REFUSED_PHRASES.any { it in blob } || ACTIVE_RESPONSE_PHRASE in blob) return emptyList()
        val code = classify(blob)
        return listOf(DomainVoiceEvent.Error(code, redact(BaiduProtocol.sanitize(error.optString("message")))))
    }

    override fun errorCode(text: String): String = runCatching {
        val raw = JSONObject(text)
        val error = raw.optJSONObject("error") ?: raw
        val code = error.optString("code").filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }.take(64)
        val blob = blob(error)
        val kind = when {
            isTextItemRefusal(error) -> "text_refused"
            CANCEL_REFUSED_PHRASES.any { it in blob } -> "cancel_refused"
            ACTIVE_RESPONSE_PHRASE in blob -> "response_busy"
            else -> classify(blob).removePrefix("QWEN_").lowercase()
        }
        "${code.ifEmpty { "none" }} kind=$kind"
    }.getOrDefault("unparsed kind=other")

    override fun isResponseAlreadyActive(text: String): Boolean = runCatching {
        val raw = JSONObject(text)
        ACTIVE_RESPONSE_PHRASE in blob(raw.optJSONObject("error") ?: raw)
    }.getOrDefault(false)

    override fun readyTimeout() = VoiceProviderException("QWEN_READY_TIMEOUT", "Qwen session readiness timed out")
    override fun notConnected() = VoiceProviderException("QWEN_CONNECTION_CLOSED", "Qwen WebSocket is not connected")
    override fun connectionClosed(status: Int) =
        VoiceProviderException("QWEN_CONNECTION_CLOSED", "Qwen WebSocket closed (status=$status)")
    override fun sessionFailed() = VoiceProviderException("QWEN_SESSION_FAILED", "failed to configure the Qwen session")
    override fun protocolError(cause: Throwable) =
        VoiceProviderException("QWEN_PROTOCOL_ERROR", "invalid Qwen realtime event", cause)
    override fun mapFailure(failure: Throwable, response: Response?) = Companion.mapFailure(failure, response)

    /** The provider message with the workspace id removed; it is part of the host. */
    private fun redact(message: String): String {
        val workspace = settings.workspaceId.trim()
        return if (workspace.isEmpty()) message else message.replace(workspace, "<workspace>", ignoreCase = true)
    }

    companion object {
        const val TRANSCRIPTION_MODEL = "qwen3-asr-flash-realtime"
        private const val ACTIVE_RESPONSE_PHRASE = "already has an active response"
        private val CANCEL_REFUSED_PHRASES = listOf("no active response", "cancellation failed", "没有可取消", "无可取消")
        private val AUTH_MARKERS = listOf("invalidapikey", "invalid_api_key", "accessdenied", "access denied", "unauthorized")
        private val QUOTA_MARKERS = listOf("throttling", "quota", "allocationquota")
        private val AUTH_STATUS = Regex("\\b(401|403)\\b")
        private val QUOTA_STATUS = Regex("\\b429\\b")

        /** A fault of the service, not of this client (the Baidu P38 lesson): reconnect, not fail. */
        private val SERVER_MARKERS = listOf(
            "internal", "serviceunavailable", "service unavailable", "server_error", "server error", "server busy", "serverbusy",
        )

        /** `param` values that belong to a function_call_output item, never to a text item. */
        private val CALL_OUTPUT_PARAMS = setOf("item.call_id", "item.output")

        /** The documented session.update (SPEC-021 B3). */
        fun sessionUpdate(instructions: String, settings: QwenAppSettings): String {
            val session = JSONObject()
                .put("modalities", JSONArray(listOf("text", "audio")))
                .put("voice", settings.voice)
                .put("audio", JSONObject().put("output", JSONObject().put("voice", settings.voice)))
                .put("instructions", instructions.trim() + "\n" + PersonaProfiles.FLEX_TOOL_RULE)
                .put("input_audio_format", "pcm")
                .put("output_audio_format", "pcm")
                .put("input_audio_transcription", JSONObject().put("model", TRANSCRIPTION_MODEL))
                .put(
                    "turn_detection",
                    JSONObject().put("type", settings.vadType).put("silence_duration_ms", settings.silenceDurationMs),
                )
                .put("tools", JSONArray().apply { RealtimeToolCatalog.tools().forEach { put(functionTool(it)) } })
            return JSONObject().put("type", "session.update").put("session", session).toString()
        }

        fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException {
            // No cause: an UnknownHostException's message is the host, which holds the workspace id.
            // Retryable: offline Android raises it too, and ReconnectPolicy caps the attempts.
            if (failure is UnknownHostException) return VoiceProviderException(
                "QWEN_DNS_FAILED",
                "Qwen endpoint not found: check the workspace ID and region in developer settings",
            )
            return when (response?.code) {
                401, 403 -> VoiceProviderException("QWEN_AUTH_FAILED", "Qwen rejected the API key (HTTP ${response.code})")
                429 -> VoiceProviderException("QWEN_QUOTA_EXHAUSTED", "Qwen quota exhausted or throttled (HTTP 429)")
                else -> VoiceProviderException("QWEN_CONNECTION_FAILED", "Qwen connection failed")
            }
        }

        private fun classify(blob: String): String {
            val lower = blob.lowercase()
            return when {
                AUTH_MARKERS.any { it in lower } || AUTH_STATUS.containsMatchIn(lower) -> "QWEN_AUTH_FAILED"
                QUOTA_MARKERS.any { it in lower } || QUOTA_STATUS.containsMatchIn(lower) -> "QWEN_QUOTA_EXHAUSTED"
                SERVER_MARKERS.any { it in lower } -> "QWEN_SERVER_UNAVAILABLE"
                else -> "QWEN_PROVIDER_ERROR"
            }
        }

        private fun blob(error: JSONObject) = "${error.optString("code")} ${error.optString("message")}".lowercase()

        private fun isItemRefusal(error: JSONObject, param: String): Boolean {
            if (param.startsWith("item")) return true
            val message = error.optString("message").lowercase()
            return error.optString("code").equals("invalid_value", ignoreCase = true) &&
                ("item.type" in message || "item type" in message)
        }

        /** The OpenAI Realtime function wrapper the Qwen docs list: `{type, function: {...}}`. */
        private fun functionTool(spec: RealtimeToolCatalog.ToolSpec): JSONObject =
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", spec.parameters),
                )
    }

    /**
     * The server refused our user text item: one is outstanding, and `param` names the item (not a
     * function_call_output field) or the error is an invalid item type.
     */
    private fun isTextItemRefusal(error: JSONObject): Boolean {
        if (!textOutstanding) return false
        val param = error.optString("param")
        if (param in CALL_OUTPUT_PARAMS) return false
        return isItemRefusal(error, param)
    }
}
