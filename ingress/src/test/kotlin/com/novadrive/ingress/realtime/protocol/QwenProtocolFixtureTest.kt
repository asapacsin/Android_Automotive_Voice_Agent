package com.novadrive.ingress.realtime.protocol

import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Official-fixture serialization/parsing for Qwen Audio Realtime.
 * Sources (retrieved 2026-09-14):
 * - https://docs.qwencloud.com/api-reference/qwen-audio-realtime/websocket-api
 * - https://docs.qwencloud.com/api-reference/qwen-audio-realtime/client-events
 * - https://docs.qwencloud.com/api-reference/qwen-audio-realtime/server-events
 */
class QwenProtocolFixtureTest {
    @Test
    fun sessionUpdateAndAudioAppendMatchOfficialClientEvents() {
        val update = QwenProtocol.sessionUpdate(modelVoice = "longanqian", tools = true)
        assertTrue(update.contains("\"type\":\"session.update\""))
        assertTrue(update.contains("\"input_audio_format\":\"pcm\""))
        assertTrue(update.contains("\"output_audio_format\":\"pcm\""))
        assertTrue(update.contains("\"type\":\"server_vad\""))
        assertTrue(update.contains("\"name\":\"set_temperature\""))
        val append = QwenProtocol.inputAudioAppend("AA==")
        assertEquals("input_audio_buffer.append", field(append, "type"))
        assertTrue(append.contains("\"audio\":\"AA==\""))
        val cancel = QwenProtocol.responseCancel()
        assertEquals("{\"type\":\"response.cancel\"}", cancel)
        val commit = QwenProtocol.inputAudioCommit()
        assertEquals("{\"type\":\"input_audio_buffer.commit\"}", commit)
    }

    @Test
    fun toolResultFlowUsesFunctionCallOutputThenResponseCreate() {
        val tool = QwenProtocol.functionCallOutput("call_xxx", "{\"ok\":true}")
        assertTrue(tool.contains("\"type\":\"conversation.item.create\""))
        assertTrue(tool.contains("\"type\":\"function_call_output\""))
        assertTrue(tool.contains("\"call_id\":\"call_xxx\""))
        assertEquals("response.create", field(QwenProtocol.responseCreate(), "type"))
        val parsed =
            QwenProtocol.parseServerEvent(
                """{"event_id":"event_xxx","type":"response.function_call_arguments.done","call_id":"call_xxx","name":"set_temperature","arguments":"{\"temperature_c\":\"22\"}"}""",
            )
        val call = parsed.filterIsInstance<DomainVoiceEvent.ToolCall>().single()
        assertEquals("call_xxx", call.callId)
        assertEquals("set_temperature", call.name)
        assertEquals("22", call.arguments["temperature_c"])
    }

    @Test
    fun cancellationAndUnknownEventsAreTolerated() {
        val cancelled =
            QwenProtocol.parseServerEvent(
                """{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"client_cancelled"}}}""",
            )
        assertTrue(cancelled.any { it is DomainVoiceEvent.Interrupted && it.reason == "client_cancelled" })
        val unknown = QwenProtocol.parseServerEvent("""{"type":"voiceprint_audio_list.completed","item_id":"item_1"}""")
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun secretsAreNotSerializedInClientEvents() {
        val update = QwenProtocol.sessionUpdate("longanqian", tools = false)
        assertTrue(!update.contains("DASHSCOPE"))
        assertTrue(!update.contains("api_key"))
        assertTrue(!update.contains("Bearer"))
    }

    private fun field(json: String, key: String): String? {
        val match = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)
        return match?.groupValues?.get(1)
    }
}

object QwenProtocol {
    fun sessionUpdate(modelVoice: String, tools: Boolean): String {
        val toolJson =
            if (tools) {
                ",\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"set_temperature\",\"description\":\"Set cabin temperature\",\"parameters\":{\"type\":\"object\",\"properties\":{\"temperature_c\":{\"type\":\"string\"},\"zone\":{\"type\":\"string\"}},\"required\":[\"temperature_c\"]}}}]"
            } else {
                ""
            }
        return "{\"type\":\"session.update\",\"session\":{\"modalities\":[\"text\",\"audio\"],\"voice\":\"$modelVoice\",\"input_audio_format\":\"pcm\",\"output_audio_format\":\"pcm\",\"turn_detection\":{\"type\":\"server_vad\",\"threshold\":0.5,\"silence_duration_ms\":800}$toolJson}}"
    }

    fun inputAudioAppend(audioB64: String): String =
        "{\"type\":\"input_audio_buffer.append\",\"audio\":\"$audioB64\"}"

    fun inputAudioCommit(): String = "{\"type\":\"input_audio_buffer.commit\"}"

    fun responseCancel(): String = "{\"type\":\"response.cancel\"}"

    fun responseCreate(): String = "{\"type\":\"response.create\"}"

    fun functionCallOutput(callId: String, output: String): String =
        "{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"function_call_output\",\"call_id\":\"$callId\",\"output\":${encode(output)}}}"

    fun parseServerEvent(raw: String): List<DomainVoiceEvent> {
        val type = stringField(raw, "type") ?: return emptyList()
        return when (type) {
            "session.created", "session.updated" ->
                listOf(DomainVoiceEvent.SessionReady(stringField(raw, "model") ?: "", true))
            "input_audio_buffer.speech_started" -> listOf(DomainVoiceEvent.SpeechStarted)
            "input_audio_buffer.speech_stopped" -> listOf(DomainVoiceEvent.SpeechStopped)
            "response.audio.delta" -> listOf(DomainVoiceEvent.AudioDelta(stringField(raw, "delta") ?: ""))
            "response.audio_transcript.delta", "response.audio_transcript.done" ->
                listOf(DomainVoiceEvent.AssistantTranscript(stringField(raw, "delta") ?: "", type.endsWith("done")))
            "conversation.item.input_audio_transcription.delta",
            "conversation.item.input_audio_transcription.completed",
            ->
                listOf(
                    DomainVoiceEvent.UserTranscript(
                        stringField(raw, "delta") ?: stringField(raw, "transcript") ?: "",
                        type.endsWith("completed"),
                    ),
                )
            "response.done" -> {
                val status = stringField(raw, "status") ?: "completed"
                val reason = stringField(raw, "reason")
                buildList {
                    if (status == "cancelled") add(DomainVoiceEvent.Interrupted(reason ?: "cancelled"))
                    add(DomainVoiceEvent.ResponseDone(status, reason))
                }
            }
            "response.function_call_arguments.done" -> {
                val args = escapedStringField(raw, "arguments") ?: "{}"
                val unescaped = args.replace("\\\"", "\"")
                val temp = Regex("\"temperature_c\"\\s*:\\s*\"([^\"]+)\"").find(unescaped)?.groupValues?.get(1)
                val zone = Regex("\"zone\"\\s*:\\s*\"([^\"]+)\"").find(unescaped)?.groupValues?.get(1)
                listOf(
                    DomainVoiceEvent.ToolCall(
                        callId = stringField(raw, "call_id") ?: "",
                        name = stringField(raw, "name") ?: "",
                        arguments = buildMap {
                            if (temp != null) put("temperature_c", temp)
                            if (zone != null) put("zone", zone)
                        },
                    ),
                )
            }
            "error" -> listOf(DomainVoiceEvent.Error("QWEN_API_REJECTED", "provider error"))
            else -> emptyList()
        }
    }

    private fun stringField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun escapedStringField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.groupValues?.get(1)

    private fun encode(value: String): String = "\"${value.replace("\"", "\\\"")}\""
}
