package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini Live (BidiGenerateContent) wire format: client messages and parsed server messages
 * (ADR-010, docs/GEMINI_LIVE_ARCHITECTURE.md §3). Pure; never logs. Vendor JSON stops here and in
 * [GeminiLiveClient].
 */
object GeminiLiveProtocol {
    const val API_KEY_HEADER = "x-goog-api-key"
    const val INPUT_AUDIO_MIME = "audio/pcm;rate=16000"
    const val LANGUAGE = "zh-CN"
    /**
     * Provider-specific tone line appended after the persona (and context hint). Measured in
     * docs/reports/2026-09-29-gemini-live-probe.md F20: without it the first probe saw a filler
     * first and the call 7.7–31 s after the end of speech; with it, spoken commands in the app
     * client still got a filler turn and the call 5–9 s after the end of speech (text runs: 3/3
     * navigation calls before speaking, at 8.5–27 s). Shapes tone only (I-11); the deferred
     * correction in [GeminiLiveClient] and the pipeline's gate are the enforcement.
     */
    const val CALL_FIRST_HINT = "用户要求执行操作（空调、导航、音乐等）时，先调用对应工具，拿到结果之后再说话；调用之前不要先说“马上”“这就”之类的话。"
    const val DUPLICATE_CALL_INSTRUCTION = "同一个操作刚才已经执行过一次，这次没有重复执行。"

    /**
     * The first client message. Never `includeThoughts`, an explicit `behavior` (F18), `googleSearch`
     * or `parameters` (F17: rejects `additionalProperties`; `parametersJsonSchema` accepts it).
     * [thinkingLevel] null omits `thinkingConfig` entirely (a model without the trait rejects it, F27).
     */
    fun setup(
        model: String,
        voice: String,
        thinkingLevel: String?,
        instructions: String,
        silenceDurationMs: Int?,
        resumptionHandle: String?,
        tools: List<RealtimeToolCatalog.ToolSpec> = RealtimeToolCatalog.tools(),
    ): String {
        val declarations = JSONArray()
        tools.forEach { spec ->
            declarations.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parametersJsonSchema", spec.parameters),
            )
        }
        val activity = JSONObject()
        if (silenceDurationMs != null) activity.put("silenceDurationMs", silenceDurationMs)
        val resumption = JSONObject()
        if (!resumptionHandle.isNullOrEmpty()) resumption.put("handle", resumptionHandle)
        val generation = JSONObject()
            .put("responseModalities", JSONArray(listOf("AUDIO")))
            .put(
                "speechConfig",
                JSONObject()
                    .put("languageCode", LANGUAGE)
                    .put(
                        "voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)),
                    ),
            )
        if (thinkingLevel != null) {
            generation.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        }
        val setup = JSONObject()
            .put("model", modelName(model))
            .put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions.trimEnd() + "\n" + CALL_FIRST_HINT))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", activity))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", resumption)
        return JSONObject().put("setup", setup).toString()
    }

    fun modelName(model: String): String = if (model.startsWith("models/")) model else "models/$model"

    fun audio(base64Pcm: String): String =
        JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", JSONObject().put("data", base64Pcm).put("mimeType", INPUT_AUDIO_MIME)),
        ).toString()

    fun textTurn(text: String): String {
        require(text.length <= RealtimeToolCatalog.MAX_ARGUMENT_BYTES) { "GEMINI_LIVE_TEXT_TOO_LARGE" }
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
        return JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true),
        ).toString()
    }

    /** [output] is parsed as a JSON object; anything else is wrapped as `{"output": output}`. */
    fun toolResponse(callId: String, name: String, output: String): String {
        require(RealtimeToolCatalog.validId(callId)) { "GEMINI_LIVE_CALL_ID_INVALID" }
        require(output.length <= RealtimeToolCatalog.MAX_ARGUMENT_BYTES) { "GEMINI_LIVE_TOOL_OUTPUT_TOO_LARGE" }
        val response = runCatching { JSONObject(output) }.getOrNull() ?: JSONObject().put("output", output)
        val item = JSONObject().put("id", callId).put("name", name).put("response", response)
        return JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(item)),
        ).toString()
    }

    fun duplicateCallOutput(name: String): String =
        JSONObject().put("ok", true).put("tool", name).put("status", "duplicate_call_ignored")
            .put("instruction", DUPLICATE_CALL_INSTRUCTION).toString()

    /** One raw function call as it arrived; [args] is null when it was not a JSON object. */
    data class RawCall(val id: String, val name: String, val args: JSONObject?, val argsChars: Int)

    /** Everything one server message carries. Unknown keys are ignored. */
    data class ServerMessage(
        val setupComplete: Boolean = false,
        val resumptionHandle: String? = null,
        val resumable: Boolean = false,
        val audio: List<String> = emptyList(),
        val thoughtParts: Int = 0,
        val inputTranscription: String? = null,
        val outputTranscription: String? = null,
        val interrupted: Boolean = false,
        val generationComplete: Boolean = false,
        val turnComplete: Boolean = false,
        /** Null when absent; true for IN_PROGRESS, false for IDLE or any other value. */
        val workPending: Boolean? = null,
        val toolCalls: List<RawCall> = emptyList(),
        val cancelledCallIds: List<String> = emptyList(),
        val goAway: Boolean = false,
        /** [ACTIVITY_START] / [ACTIVITY_END] from `voiceActivity` (F19), else null. */
        val voiceActivity: String? = null,
    )

    /** Throws on a message that is not a JSON object. */
    fun parse(text: String): ServerMessage {
        val raw = JSONObject(text)
        val activity = (raw.optJSONObject("voiceActivity") ?: raw.optJSONObject("serverContent")?.optJSONObject("voiceActivity"))
            ?.optString("type")?.let { type -> listOf(ACTIVITY_START, ACTIVITY_END).firstOrNull { type.uppercase().endsWith(it) } }
        var message = ServerMessage(setupComplete = raw.has("setupComplete"), goAway = raw.has("goAway"), voiceActivity = activity)
        raw.optJSONObject("sessionResumptionUpdate")?.let { update ->
            message = message.copy(
                resumptionHandle = update.optString("newHandle").ifEmpty { null },
                resumable = update.optBoolean("resumable", false),
            )
        }
        raw.optJSONObject("serverContent")?.let { content ->
            val audio = mutableListOf<String>()
            var thoughts = 0
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) { thoughts++; continue }
                    val inline = part.optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isNotEmpty() && inline.optString("mimeType").startsWith("audio/pcm")) audio += data
                }
            }
            message = message.copy(
                audio = audio,
                thoughtParts = thoughts,
                inputTranscription = content.optJSONObject("inputTranscription")?.optString("text")?.ifEmpty { null },
                outputTranscription = content.optJSONObject("outputTranscription")?.optString("text")?.ifEmpty { null },
                interrupted = content.optBoolean("interrupted", false),
                generationComplete = content.optBoolean("generationComplete", false),
                turnComplete = content.optBoolean("turnComplete", false),
                workPending = content.opt("interactionStatus")?.toString()?.let { isInProgress(it) },
            )
        }
        raw.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val list = mutableListOf<RawCall>()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val args = call.opt("args")
                val parsed = when (args) {
                    null, JSONObject.NULL -> JSONObject()
                    is JSONObject -> args
                    else -> null
                }
                list += RawCall(call.optString("id"), call.optString("name"), parsed, args?.toString()?.length ?: 0)
            }
            message = message.copy(toolCalls = list)
        }
        raw.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { ids ->
            message = message.copy(cancelledCallIds = (0 until ids.length()).map { ids.optString(it) }.filter { it.isNotEmpty() })
        }
        return message
    }

    /** IN_PROGRESS in any spelling (in_progress, InProgress, INTERACTION_STATUS_IN_PROGRESS). */
    internal fun isInProgress(status: String): Boolean =
        status.uppercase().filter { it in 'A'..'Z' }.endsWith("INPROGRESS")

    /** The neutral call, or null when its id is unusable (the caller logs and drops it). */
    fun toolCall(raw: RawCall): DomainVoiceEvent.ToolCall? {
        if (!RealtimeToolCatalog.validId(raw.id)) return null
        if (!RealtimeToolCatalog.validId(raw.name)) return RealtimeToolCatalog.rejectedCall(raw.id, raw.name, "MISSING_TOOL_METADATA")
        if (raw.argsChars > RealtimeToolCatalog.MAX_ARGUMENT_BYTES) {
            return RealtimeToolCatalog.rejectedCall(raw.id, raw.name, "ARGUMENTS_TOO_LARGE")
        }
        val args = raw.args ?: return RealtimeToolCatalog.rejectedCall(raw.id, raw.name, "MALFORMED_JSON")
        return RealtimeToolCatalog.toolCall(raw.id, raw.name, args)
    }

    /** Close code (and reason, read for the word "quota" only) to a stable error code. */
    fun closeCode(code: Int, reason: String?): String = when (code) {
        1007 -> MALFORMED_SETUP
        1008 -> AUTH_FAILED
        1011 -> if (reason.orEmpty().contains("quota", ignoreCase = true)) QUOTA_EXCEEDED else UNAVAILABLE
        else -> CONNECTION_CLOSED
    }

    /** Generic, never the server's reason text (it can quote the request). */
    fun closeFailure(code: Int, reason: String?, resumeRejected: Boolean = false): VoiceProviderException {
        // A resumed setup rejected with 1007 is an expired handle, not a malformed setup: retry fresh.
        val mapped = if (resumeRejected && code == 1007) RESUME_UNAVAILABLE else closeCode(code, reason)
        return VoiceProviderException(mapped, "Gemini Live connection closed (status=$code)")
    }

    fun socketFailure(httpCode: Int?, cause: Throwable?): VoiceProviderException =
        if (httpCode == 401 || httpCode == 403) {
            VoiceProviderException(AUTH_FAILED, "Gemini Live rejected the API key (HTTP $httpCode)", cause)
        } else {
            VoiceProviderException(CONNECTION_FAILED, "Gemini Live connection failed", cause)
        }

    const val MALFORMED_SETUP = "GEMINI_LIVE_MALFORMED_SETUP"
    const val AUTH_FAILED = "GEMINI_LIVE_AUTH_FAILED"
    const val QUOTA_EXCEEDED = "GEMINI_LIVE_QUOTA_EXCEEDED"
    const val UNAVAILABLE = "GEMINI_LIVE_UNAVAILABLE"
    const val CONNECTION_CLOSED = "GEMINI_LIVE_CONNECTION_CLOSED"
    const val TIMEOUT = "GEMINI_LIVE_TIMEOUT"
    const val CONNECTION_FAILED = "GEMINI_LIVE_CONNECTION_FAILED"
    const val PROTOCOL_ERROR = "GEMINI_LIVE_PROTOCOL_ERROR"
    const val RESUME_UNAVAILABLE = "GEMINI_LIVE_RESUME_UNAVAILABLE"
    const val ACTIVITY_START = "ACTIVITY_START"
    const val ACTIVITY_END = "ACTIVITY_END"
}
