package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONArray
import org.json.JSONObject

object QwenProtocol {
    fun sessionUpdate(): String {
        val parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("temperature_c", JSONObject().put("type", "string"))
                        .put("zone", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("temperature_c"))
        val tool =
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "set_temperature")
                        .put("description", "Set cabin temperature in Celsius after safety verification")
                        .put("parameters", parameters),
                )
        val session =
            JSONObject()
                .put("modalities", JSONArray().put("text").put("audio"))
                .put("voice", "longanqian")
                .put("input_audio_format", "pcm")
                .put("output_audio_format", "pcm")
                .put(
                    "turn_detection",
                    JSONObject()
                        .put("type", "server_vad")
                        .put("threshold", 0.5)
                        .put("silence_duration_ms", 800),
                )
                .put("tools", JSONArray().put(tool))
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun audioAppend(audioBase64: String): String =
        JSONObject().put("type", "input_audio_buffer.append").put("audio", audioBase64).toString()

    fun audioCommit(): String = JSONObject().put("type", "input_audio_buffer.commit").toString()

    fun responseCancel(): String = JSONObject().put("type", "response.cancel").toString()

    fun functionCallOutput(callId: String, output: String): String =
        JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", callId)
                    .put("output", output),
            ).toString()

    fun responseCreate(): String = JSONObject().put("type", "response.create").toString()

    fun parseServerEvent(text: String): List<DomainVoiceEvent> {
        val raw = JSONObject(text)
        return when (val type = raw.optString("type")) {
            "session.created", "session.updated" -> {
                val session = raw.optJSONObject("session") ?: JSONObject()
                listOf(DomainVoiceEvent.SessionReady(session.optString("model"), true))
            }
            "input_audio_buffer.speech_started" -> listOf(DomainVoiceEvent.SpeechStarted)
            "input_audio_buffer.speech_stopped" -> listOf(DomainVoiceEvent.SpeechStopped)
            "conversation.item.input_audio_transcription.delta" ->
                listOf(DomainVoiceEvent.UserTranscript(raw.optString("delta"), false))
            "conversation.item.input_audio_transcription.completed" ->
                listOf(DomainVoiceEvent.UserTranscript(raw.optString("transcript"), true))
            "response.audio.delta" -> listOf(DomainVoiceEvent.AudioDelta(raw.optString("delta")))
            "response.audio.done" -> listOf(DomainVoiceEvent.AudioDone)
            "response.audio_transcript.delta" ->
                listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("delta"), false))
            "response.audio_transcript.done" ->
                listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("transcript"), true))
            "response.function_call_arguments.done" -> listOf(parseToolCall(raw))
            "response.done" -> parseResponseDone(raw)
            "error" -> {
                val error = raw.optJSONObject("error") ?: raw
                val providerCode = error.optString("code")
                val message = sanitizeProviderMessage(error.optString("message"))
                listOf(DomainVoiceEvent.Error(classifyProviderError(providerCode, message), diagnostic(providerCode, message)))
            }
            else -> emptyList()
        }
    }

    fun classifyProviderError(providerCode: String?, message: String?): String {
        val blob = "${providerCode.orEmpty()} ${message.orEmpty()}".lowercase()
        return when {
            "401" in blob || "403" in blob || "unauthorized" in blob ||
                ("invalid" in blob && ("key" in blob || "credential" in blob)) -> "QWEN_AUTH_FAILED"
            "429" in blob || "rate" in blob || "quota" in blob || "qps" in blob -> "QWEN_RATE_LIMITED"
            "model" in blob && ("not found" in blob || "invalid" in blob || "unavailable" in blob) -> "QWEN_MODEL_NOT_FOUND"
            "timeout" in blob -> "QWEN_TIMEOUT"
            "session" in blob -> "QWEN_SESSION_FAILED"
            else -> "QWEN_PROTOCOL_ERROR"
        }
    }

    fun sanitizeProviderMessage(message: String?): String {
        val value = message?.trim().orEmpty()
        if (value.isBlank()) return "provider rejected the request"
        val lower = value.lowercase()
        if (listOf("api key", "authorization", "bearer", "secret", "access_token").any { it in lower }) {
            return "provider rejected the request"
        }
        return value.replace('\n', ' ').replace('\r', ' ').take(240)
    }

    private fun parseToolCall(raw: JSONObject): DomainVoiceEvent.ToolCall {
        val argsRaw = raw.opt("arguments")
        val args =
            when (argsRaw) {
                is JSONObject -> argsRaw
                is String -> runCatching { JSONObject(argsRaw) }.getOrDefault(JSONObject())
                else -> JSONObject()
            }
        val values = buildMap {
            val keys = args.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, args.opt(key)?.toString().orEmpty())
            }
        }
        return DomainVoiceEvent.ToolCall(raw.optString("call_id"), raw.optString("name"), values)
    }

    private fun parseResponseDone(raw: JSONObject): List<DomainVoiceEvent> {
        val response = raw.optJSONObject("response") ?: raw
        val status = response.optString("status", "completed")
        val details = response.optJSONObject("status_details") ?: JSONObject()
        val reason = details.optString("reason").ifBlank { raw.optString("reason").ifBlank { null } }
        return buildList {
            if (status == "cancelled") add(DomainVoiceEvent.Interrupted(reason ?: "cancelled"))
            add(DomainVoiceEvent.ResponseDone(status, reason))
            if (status == "failed") {
                val error = details.optJSONObject("error") ?: JSONObject()
                val providerCode = error.optString("code")
                val message = sanitizeProviderMessage(error.optString("message"))
                add(DomainVoiceEvent.Error(classifyProviderError(providerCode, message), diagnostic(providerCode, message)))
            }
        }
    }

    private fun diagnostic(providerCode: String?, message: String): String =
        if (providerCode.isNullOrBlank()) message else "provider=$providerCode: $message"
}
