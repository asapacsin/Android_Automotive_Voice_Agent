package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaiduProtocolTest {
    @Test
    fun sessionUpdateContainsOnlyDocumentedConfiguration() {
        val root = JSONObject(BaiduProtocol.sessionUpdate())
        val session = root.getJSONObject("session")
        assertEquals("session.update", root.getString("type"))
        assertEquals("pcm16", session.getString("input_audio_format"))
        assertEquals("pcm16", session.getString("output_audio_format"))
        assertTrue(session.getJSONObject("turn_detection").getBoolean("create_response"))
        assertTrue(session.getJSONObject("turn_detection").getBoolean("interrupt_response"))
        assertFalse(session.has("tools"))
        assertFalse(session.has("instructions"))
        assertFalse(session.has("voice"))
        assertFalse(session.has("speed"))
        assertFalse(root.toString().contains("response.cancel"))
        assertFalse(root.toString().contains("function_call"))
    }

    @Test
    fun sessionUpdateIncludesInstructionsWhenProvided() {
        val without = JSONObject(BaiduProtocol.sessionUpdate()).getJSONObject("session")
        assertFalse(without.has("instructions"))
        val with = JSONObject(BaiduProtocol.sessionUpdate("x")).getJSONObject("session")
        assertEquals("x", with.getString("instructions"))
    }

    @Test
    fun sessionUpdateIncludesVoiceAndSpeedOnlyWhenProvided() {
        val without = JSONObject(BaiduProtocol.sessionUpdate()).getJSONObject("session")
        assertFalse(without.has("voice"))
        assertFalse(without.has("speed"))
        val with = JSONObject(BaiduProtocol.sessionUpdate("x", "4157", 1.1)).getJSONObject("session")
        assertEquals("4157", with.getString("voice"))
        assertEquals(1.1, with.getDouble("speed"))
    }

    @Test
    fun translatesReadinessAudioTranscriptsAndBargeIn() {
        val ready = BaiduProtocol.parseServerEvent("""{"type":"session.updated","session":{"model":"audio-mini-realtime-near"}}""")
        assertTrue(ready.single() is DomainVoiceEvent.SessionReady)
        val audio = BaiduProtocol.parseServerEvent("""{"type":"response.audio.delta","delta":"AAE="}""")
        assertEquals(DomainVoiceEvent.AudioDelta("AAE="), audio.single())
        val bargeIn = BaiduProtocol.parseServerEvent("""{"type":"input_audio_buffer.speech_started"}""", speaking = true)
        assertEquals(listOf(DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.Interrupted("turn_detected")), bargeIn)
    }

    @Test
    fun doesNotInterpretUndocumentedItemsAsCommands() {
        val events = BaiduProtocol.parseServerEvent("""{"type":"conversation.item.created","item":{"type":"function_call"}}""")
        assertTrue(events.single() is DomainVoiceEvent.ToolUnsupported)
    }
}
