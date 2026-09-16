package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONObject

/** Exact Pro/Lite E2E messages documented by Baidu; no vendor JSON escapes this adapter. */
object BaiduProtocol {
    fun sessionUpdate(instructions: String? = null, voice: String? = null, speed: Double? = null): String {
        val session = JSONObject()
            .put("input_audio_format", "pcm16")
            .put("input_audio_transcription", JSONObject().put("model", "default"))
            .put("output_audio_format", "pcm16")
            .put(
                "turn_detection",
                JSONObject()
                    .put("type", "server_vad")
                    .put("create_response", true)
                    .put("interrupt_response", true),
            )
        if (!instructions.isNullOrBlank()) {
            session.put("instructions", instructions)
        }
        if (voice != null) {
            session.put("voice", voice)
        }
        if (speed != null) {
            session.put("speed", speed)
        }
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun audioAppend(audioBase64: String): String =
        JSONObject().put("type", "input_audio_buffer.append").put("audio", audioBase64).toString()

    fun eventType(text: String): String = JSONObject(text).optString("type")

    fun parseServerEvent(text: String, speaking: Boolean = false): List<DomainVoiceEvent> {
        val raw = JSONObject(text)
        return when (val type = raw.optString("type")) {
            "session.updated" -> {
                val session = raw.optJSONObject("session") ?: JSONObject()
                val turn = session.optJSONObject("turn_detection") ?: JSONObject()
                listOf(DomainVoiceEvent.SessionReady(session.optString("model"), turn.optBoolean("interrupt_response", true)))
            }
            "input_audio_buffer.speech_started" -> buildList {
                add(DomainVoiceEvent.SpeechStarted)
                if (speaking) add(DomainVoiceEvent.Interrupted("turn_detected"))
            }
            "input_audio_buffer.speech_stopped" -> listOf(DomainVoiceEvent.SpeechStopped)
            "conversation.item.input_audio_transcription.delta" ->
                listOf(DomainVoiceEvent.UserTranscript(raw.optString("delta"), false))
            "conversation.item.input_audio_transcription.completed" ->
                listOf(DomainVoiceEvent.UserTranscript(raw.optString("transcript"), true))
            "conversation.item.input_audio_transcription.failed", "error" -> listOf(parseError(raw))
            "response.created" -> emptyList()
            "response.audio.delta" -> listOf(DomainVoiceEvent.AudioDelta(raw.optString("delta").ifBlank { raw.optString("audio") }))
            "response.audio.done" -> listOf(DomainVoiceEvent.AudioDone)
            "response.audio_transcript.delta" ->
                listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("delta"), false))
            "response.audio_transcript.done" ->
                listOf(DomainVoiceEvent.AssistantTranscript(raw.optString("transcript"), true))
            "response.done" -> parseResponseDone(raw)
            "conversation.item.created" -> {
                val itemType = raw.optJSONObject("item")?.optString("type").orEmpty()
                if (itemType.isNotBlank() && itemType != "message") {
                    listOf(DomainVoiceEvent.ToolUnsupported("BLOCKED_BAIDU_FUNCTION_CALLING:$itemType"))
                } else emptyList()
            }
            "session.created", "conversation.created", "input_audio_buffer.committed",
            "response.output_item.added", "response.output_item.done",
            "response.content_part.added", "response.content_part.done" -> emptyList()
            else -> if (type.isBlank()) throw IllegalArgumentException("missing Baidu event type") else emptyList()
        }
    }

    fun classifyError(providerCode: String?, message: String?): String {
        val blob = "${providerCode.orEmpty()} ${message.orEmpty()}".lowercase()
        return when {
            listOf("quota", "qps", "rate limit", "limit exceeded", "配额", "余额不足").any { it in blob } -> "BAIDU_QUOTA_EXHAUSTED"
            listOf("invalid_client", "unauthorized", "invalid_api_key", "authentication").any { it in blob } -> "BAIDU_AUTH_FAILED"
            "model" in blob && ("invalid" in blob || "not found" in blob || "unavailable" in blob) -> "BAIDU_INVALID_MODEL"
            "timeout" in blob -> "BAIDU_TIMEOUT"
            else -> "BAIDU_API_REJECTED"
        }
    }

    fun sanitize(message: String?): String {
        val value = message?.trim().orEmpty()
        if (value.isBlank()) return "provider rejected the request"
        val lower = value.lowercase()
        if (listOf("secret", "api key", "authorization", "bearer", "access_token").any { it in lower }) {
            return "provider rejected the request"
        }
        return value.replace('\n', ' ').replace('\r', ' ').take(240)
    }

    private fun parseError(raw: JSONObject): DomainVoiceEvent.Error {
        val error = raw.optJSONObject("error") ?: raw
        val providerCode = error.optString("code")
        val message = sanitize(error.optString("message"))
        return DomainVoiceEvent.Error(classifyError(providerCode, message), diagnostic(providerCode, message))
    }

    private fun parseResponseDone(raw: JSONObject): List<DomainVoiceEvent> {
        val response = raw.optJSONObject("response") ?: raw
        val status = response.optString("status", "completed")
        val details = response.optJSONObject("status_details") ?: JSONObject()
        val reason = details.optString("reason").ifBlank { null }
        return buildList {
            if (status == "cancelled" && reason in setOf("turn_detected", "client_cancelled")) {
                add(DomainVoiceEvent.Interrupted(reason.orEmpty()))
            }
            add(DomainVoiceEvent.ResponseDone(status, reason))
            if (status == "failed") add(parseError(details.optJSONObject("error") ?: details))
        }
    }

    private fun diagnostic(code: String?, message: String): String =
        if (code.isNullOrBlank()) message else "provider=$code: $message"
}
