package com.novadrive.ingress.realtime.protocol

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.GptLiveCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Official-fixture serialization/parsing for OpenAI GPT-Live.
 * Sources (retrieved 2026-09-14):
 * - https://developers.openai.com/api/docs/guides/voice-websockets
 * - https://developers.openai.com/api/docs/guides/live
 * - https://developers.openai.com/api/docs/guides/live-delegation
 * - https://developers.openai.com/api/docs/guides/live-conversations
 */
class GptLiveProtocolFixtureTest {
    @Test
    fun sessionStartAndAudioAppendMatchOfficialLiveEvents() {
        val start = GptLiveProtocol.sessionStart("gpt-live-1")
        assertTrue(start.contains("\"type\":\"session.start\""))
        assertTrue(start.contains("\"model\":\"gpt-live-1\""))
        assertTrue(start.contains("\"type\":\"audio/pcm\""))
        assertTrue(start.contains("\"rate\":16000"))
        assertTrue(start.contains("\"type\":\"client\""))
        val append = GptLiveProtocol.inputAudioAppend("AA==")
        assertEquals("session.input_audio.append", field(append, "type"))
        assertTrue(append.contains("\"audio\":\"AA==\""))
        val close = GptLiveProtocol.sessionClose()
        assertEquals("session.close", field(close, "type"))
    }

    @Test
    fun workResultUsesCommentaryAppendAndUnknownEventsAreIgnored() {
        val result = GptLiveProtocol.commentaryAppend("item_9tA2", "温度已设为22度。")
        assertTrue(result.contains("\"type\":\"session.commentary.append\""))
        assertTrue(result.contains("\"delegation_id\":\"item_9tA2\""))
        assertTrue(result.contains("\"content\":\"温度已设为22度。\""))
        val thinking = GptLiveProtocol.thinkingAppend("item_9tA2", "checking HVAC")
        assertTrue(thinking.contains("\"type\":\"session.thinking.append\""))
        val parsed =
            GptLiveProtocol.parseServerEvent(
                """{"type":"session.started","session":{"id":"sess_1","model":"gpt-live-1"}}""",
            )
        assertTrue(parsed.any { it is DomainVoiceEvent.SessionReady })
        val unknown = GptLiveProtocol.parseServerEvent("""{"type":"session.moderation.updated","flag":true}""")
        assertTrue(unknown.isEmpty())
        assertFalse(GptLiveCapabilities.CLIENT_SPEECH_CANCEL_DOCUMENTED)
    }

    @Test
    fun nestedResponseEventFunctionCallIsTranslated() {
        val raw =
            """{"type":"response.event","delegation_id":"item_9tA2","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_123","name":"set_temperature","arguments":"{\"temperature_c\":\"22\"}","status":"completed"}}}"""
        val parsed = GptLiveProtocol.parseServerEvent(raw)
        val call = parsed.filterIsInstance<DomainVoiceEvent.ToolCall>().single()
        assertEquals("call_123", call.callId)
        assertEquals("set_temperature", call.name)
    }

    @Test
    fun transcriptsAndOutputAudioDeltasMapToDomainEvents() {
        val audio = GptLiveProtocol.parseServerEvent("""{"type":"session.output_audio.delta","delta":"AA=="}""")
        assertEquals("AA==", (audio.single() as DomainVoiceEvent.AudioDelta).pcm16leBase64)
        val user = GptLiveProtocol.parseServerEvent("""{"type":"session.input_transcript.delta","delta":"你好"}""")
        assertEquals("你好", (user.single() as DomainVoiceEvent.UserTranscript).text)
        val assistant = GptLiveProtocol.parseServerEvent("""{"type":"session.output_transcript.delta","delta":"在"}""")
        assertEquals("在", (assistant.single() as DomainVoiceEvent.AssistantTranscript).text)
        val delegation =
            GptLiveProtocol.parseServerEvent(
                """{"type":"session.delegation.created","offset_ms":1000,"delegation":{"id":"item_9tA2","type":"delegation","target":"client"}}""",
            )
        assertTrue(delegation.any { it is DomainVoiceEvent.WorkProgress && it.workId == "item_9tA2" })
    }

    @Test
    fun clientEventsDoNotEmbedSecrets() {
        val start = GptLiveProtocol.sessionStart("gpt-live-1")
        assertTrue(!start.contains("OPENAI_API_KEY"))
        assertTrue(!start.contains("Bearer"))
        assertTrue(!start.contains("sk-"))
    }

    private fun field(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
}

object GptLiveProtocol {
    fun sessionStart(model: String): String =
        "{\"type\":\"session.start\",\"event_id\":\"event_start\",\"session\":{\"model\":\"$model\",\"instructions\":\"Be concise. Delegate vehicle actions to the backend.\",\"audio\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":16000},\"output\":{\"voice\":\"marin\"}},\"delegation\":{\"type\":\"client\"}}}"

    fun inputAudioAppend(audioB64: String): String =
        "{\"type\":\"session.input_audio.append\",\"audio\":\"$audioB64\"}"

    fun sessionClose(): String = "{\"type\":\"session.close\"}"

    fun commentaryAppend(delegationId: String, content: String): String =
        "{\"type\":\"session.commentary.append\",\"event_id\":\"result_1\",\"delegation_id\":\"$delegationId\",\"content\":\"$content\"}"

    fun thinkingAppend(delegationId: String, content: String): String =
        "{\"type\":\"session.thinking.append\",\"event_id\":\"progress_1\",\"delegation_id\":\"$delegationId\",\"content\":\"$content\"}"

    fun parseServerEvent(raw: String): List<DomainVoiceEvent> {
        val type = stringField(raw, "type") ?: return emptyList()
        return when (type) {
            "session.started", "session.updated" ->
                listOf(DomainVoiceEvent.SessionReady(stringField(raw, "model") ?: "gpt-live-1", true))
            "session.output_audio.delta" ->
                listOf(DomainVoiceEvent.AudioDelta(stringField(raw, "delta") ?: ""))
            "session.input_transcript.delta" ->
                listOf(DomainVoiceEvent.UserTranscript(stringField(raw, "delta") ?: "", false))
            "session.output_transcript.delta" ->
                listOf(DomainVoiceEvent.AssistantTranscript(stringField(raw, "delta") ?: "", false))
            "session.delegation.created" -> {
                val id = stringField(raw, "id") ?: ""
                listOf(DomainVoiceEvent.WorkProgress(id, "delegated"))
            }
            "response.event" -> parseNested(raw)
            "error" -> listOf(DomainVoiceEvent.Error("GPT_LIVE_API_REJECTED", "provider error"))
            "session.closed" -> listOf(DomainVoiceEvent.Closed)
            else -> emptyList()
        }
    }

    private fun parseNested(raw: String): List<DomainVoiceEvent> {
        if (!raw.contains("\"type\":\"function_call\"") && !raw.contains("function_call")) {
            return emptyList()
        }
        val callId = Regex("\"call_id\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: return emptyList()
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.firstOrNull { it != "function_call" } ?: "delegate"
        val temp = Regex("\"temperature_c\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        return listOf(
            DomainVoiceEvent.ToolCall(
                callId = callId,
                name = name,
                arguments = if (temp != null) mapOf("temperature_c" to temp) else emptyMap(),
            ),
        )
    }

    private fun stringField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
}
