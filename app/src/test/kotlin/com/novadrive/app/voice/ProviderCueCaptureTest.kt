package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-020 provider-spoken cue: positive identification and demotion (INVARIANT I-1). */
class ProviderCueCaptureTest {
    private val capture = ProviderCueCapture()
    private fun step(text: String, active: Boolean = false) = capture.onMessage(signal(text), text) { active }

    /** The adapter's translation, as OpenAiRealtimeClient does it for these messages. */
    private fun signal(text: String): ProviderCueCapture.Signal {
        val json = org.json.JSONObject(text)
        val response = json.optJSONObject("response")
        return when (json.getString("type")) {
            "response.created" -> ProviderCueCapture.Signal.Started
            "response.done" -> ProviderCueCapture.Signal.Finished(
                response?.optString("status") == "completed",
                response?.optJSONArray("output")?.optJSONObject(0)?.optString("type") == "function_call",
            )
            "input_audio_buffer.speech_started" -> ProviderCueCapture.Signal.DriverSpeech
            "error" -> ProviderCueCapture.Signal.Error
            "response.output_item.added" -> ProviderCueCapture.Signal.Call
            "response.audio_transcript.delta" -> ProviderCueCapture.Signal.Words(json.getString("delta"))
            "response.audio_transcript.done" -> ProviderCueCapture.Signal.AllWords(json.getString("transcript"))
            "response.audio.delta" -> ProviderCueCapture.Signal.Audio(json.getString("delta"))
            else -> ProviderCueCapture.Signal.Unrelated
        }
    }

    private val created = """{"type":"response.created","response":{"id":"r1"}}"""
    private val audio = """{"type":"response.audio.delta","delta":"AQI="}"""
    private fun done(status: String = "completed", type: String = "message") =
        """{"type":"response.done","response":{"status":"$status","output":[{"type":"$type"}]}}"""

    @Test
    fun nothingIsCapturedWithoutARequest() {
        assertSame(ProviderCueCapture.Step.Pass, step(created))
    }

    @Test
    fun aFunctionCallDemotesWithEveryBufferedMessageInOrder() {
        capture.request("稍等。")
        assertSame(ProviderCueCapture.Step.Started, step(created))
        assertSame(ProviderCueCapture.Step.Buffered, step(audio))
        val call = """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"c"}}"""
        val demoted = step(call) as ProviderCueCapture.Step.Demote
        assertEquals(listOf(created, audio, call), demoted.messages)
        assertTrue(capture.idle)
    }

    @Test
    fun aDivergingDeltaDemotes() {
        capture.request("稍等，我查一下。")
        step(created)
        assertSame(ProviderCueCapture.Step.Buffered, step("""{"type":"response.audio_transcript.delta","delta":"稍等，"}"""))
        assertTrue(step("""{"type":"response.audio_transcript.delta","delta":"好的"}""") is ProviderCueCapture.Step.Demote)
    }

    @Test
    fun anExactTranscriptPlaysItsPcmAndAShortOneDoesNot() {
        capture.request("稍等，我查一下。")
        step(created); step(audio)
        step("""{"type":"response.audio_transcript.done","transcript":"稍等 我查一下!"}""")
        assertArrayEquals(byteArrayOf(1, 2), (step(done()) as ProviderCueCapture.Step.Finish).pcm)

        capture.request("稍等，我查一下。")
        step(created); step(audio)
        step("""{"type":"response.audio_transcript.done","transcript":"稍等"}""")
        assertNull((step(done()) as ProviderCueCapture.Step.Finish).pcm)
    }

    @Test
    fun driverInputAndSessionEventsPassThroughACapture() {
        capture.request("稍等")
        step(created)
        assertSame(ProviderCueCapture.Step.CancelAndPass, step("""{"type":"input_audio_buffer.speech_started"}"""))
        assertSame(ProviderCueCapture.Step.Pass, step("""{"type":"input_audio_buffer.speech_stopped"}"""))
        assertSame(ProviderCueCapture.Step.Pass, step("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"x"}"""))
        step("""{"type":"response.audio_transcript.done","transcript":"稍等"}""")
        assertNull((step(done()) as ProviderCueCapture.Step.Finish).pcm, "speech since the request: never played")
    }

    @Test
    fun onlyAResponseActiveRefusalIsTheCuesOwnError() {
        capture.request("稍等")
        assertSame(ProviderCueCapture.Step.Skipped, step("""{"type":"error"}""", active = true))
        capture.request("稍等")
        assertSame(ProviderCueCapture.Step.Pass, step("""{"type":"error"}"""))
        assertTrue(capture.idle)
    }
}
