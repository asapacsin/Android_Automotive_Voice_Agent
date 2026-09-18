package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduCredentials
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phantom-turn gate over the real client and the real protocol: a scripted server plays the
 * exact event order Baidu produced on 2026-09-18 when a stray noise became a turn, and the test
 * asserts the driver hears nothing — while the same script with a tool call, or with a real
 * answer, is passed through untouched.
 */
class PhantomTurnSuppressionTest {
    private val server = MockWebServer()

    @AfterEach
    fun close() {
        server.close()
    }

    /** Plays a complete reply: audio, transcript, then response.done with [outputs]. */
    private fun scriptReply(replyText: String, withToolCall: Boolean): CountDownLatch {
        val done = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    webSocket.send("""{"type":"conversation.created","conversation":{"id":"conv_1"}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).optString("type") != "session.update") return
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    // The driver's turn, as the server reports it.
                    webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                    webSocket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    webSocket.send("""{"type":"response.created","response":{"id":"resp_1"}}""")
                    webSocket.send("""{"type":"response.audio.delta","delta":"AAAA"}""")
                    webSocket.send("""{"type":"response.audio.delta","delta":"AAAA"}""")
                    webSocket.send(
                        JSONObject()
                            .put("type", "response.audio_transcript.done")
                            .put("transcript", replyText)
                            .toString(),
                    )
                    webSocket.send("""{"type":"response.audio.done"}""")
                    if (withToolCall) {
                        // The real assembler path: the call is declared, its arguments stream, and
                        // only then does response.done list it.
                        webSocket.send(
                            """{"type":"response.output_item.added","item":{"type":"function_call","name":"control_music","call_id":"call_1","id":"item_1"}}""",
                        )
                        webSocket.send(
                            """{"type":"response.function_call_arguments.delta","call_id":"call_1","delta":"{\"action\":\"stop\"}"}""",
                        )
                        webSocket.send(
                            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_music","arguments":"{\"action\":\"stop\"}"}""",
                        )
                    }
                    val outputs = if (withToolCall) {
                        """[{"type":"function_call","name":"control_music","call_id":"call_1","arguments":"{\"action\":\"stop\"}"}]"""
                    } else {
                        """[{"type":"message"}]"""
                    }
                    webSocket.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed","output":$outputs}}""")
                    done.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        return done
    }

    private fun collectEvents(
        segment: SpeechUplinkGate.Segment?,
        replyText: String,
        withToolCall: Boolean = false,
        contextAwaiting: Boolean = false,
    ): List<DomainVoiceEvent> = runBlocking {
        val done = scriptReply(replyText, withToolCall)
        val client = BaiduFlexClient(
            http = OkHttpClient(),
            readyTimeoutMs = 3_000,
            requireTls = false,
            lastAudioSegment = { segment },
            contextAwaitingAnswer = { contextAwaiting },
        )
        val seen = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
        // Subscribe before connecting: the event flow has no replay, so a late collector sees
        // nothing and every assertion would pass or fail for the wrong reason.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { seen += it.payload } }
        client.connect(config())
        assertTrue(done.await(5, TimeUnit.SECONDS), "server script did not finish")
        // delay, not Thread.sleep: runBlocking is single-threaded, so sleeping here would starve
        // the collector and every assertion below would be about an empty list.
        kotlinx.coroutines.delay(500)
        job.cancel()
        client.disconnect()
        seen.toList()
    }

    /**
     * A real command produces two responses: the tool call, then the spoken result. Measured on
     * device 2026-09-18 — judging the second one on its own silenced 「音乐已开始播放。」, because it
     * carries no tool call of its own and the driver's transcript belonged to the first.
     */
    @Test
    fun theSpokenResultOfAnActionIsNotJudgedOnItsOwn() {
        val done = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    webSocket.send("""{"type":"conversation.created","conversation":{"id":"conv_1"}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).optString("type") != "session.update") return
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                    webSocket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    // Response 1: the action.
                    webSocket.send("""{"type":"response.created","response":{"id":"resp_1"}}""")
                    webSocket.send(
                        """{"type":"response.output_item.added","item":{"type":"function_call","name":"control_music","call_id":"call_1","id":"item_1"}}""",
                    )
                    webSocket.send(
                        """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_music","arguments":"{\"action\":\"play\"}"}""",
                    )
                    webSocket.send(
                        """{"type":"response.done","response":{"id":"resp_1","status":"completed","output":[{"type":"function_call"}]}}""",
                    )
                    // Response 2: the short spoken confirmation, with no tool call of its own.
                    webSocket.send("""{"type":"response.created","response":{"id":"resp_2"}}""")
                    webSocket.send("""{"type":"response.audio.delta","delta":"AAAA"}""")
                    webSocket.send(
                        JSONObject().put("type", "response.audio_transcript.done").put("transcript", "音乐已开始播放。").toString(),
                    )
                    webSocket.send("""{"type":"response.audio.done"}""")
                    webSocket.send(
                        """{"type":"response.done","response":{"id":"resp_2","status":"completed","output":[{"type":"message"}]}}""",
                    )
                    done.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        val events = runBlocking {
            val client = BaiduFlexClient(
                http = OkHttpClient(),
                readyTimeoutMs = 3_000,
                requireTls = false,
                // Short audio, as a brief command is.
                lastAudioSegment = { SpeechUplinkGate.Segment(600, 5, 12_000) },
                contextAwaitingAnswer = { false },
            )
            val seen = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
            val job = launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { seen += it.payload } }
            client.connect(config())
            assertTrue(done.await(5, TimeUnit.SECONDS), "server script did not finish")
            kotlinx.coroutines.delay(500)
            job.cancel()
            client.disconnect()
            seen.toList()
        }
        assertTrue(
            events.any { it is DomainVoiceEvent.AudioDelta },
            "the confirmation of a real action must be spoken, got $events",
        )
    }

    private val noiseSegment = SpeechUplinkGate.Segment(durationMs = 300, voicedFrames = 2, peak = 9_000)
    private val speechSegment = SpeechUplinkGate.Segment(durationMs = 1_900, voicedFrames = 16, peak = 6_000)

    @Test
    fun aRepairReplyToNoiseIsNeverPlayed() {
        val events = collectEvents(noiseSegment, "没听清，再说一遍。")
        // Non-vacuous: the turn really did happen, the audio simply never left the client.
        assertTrue(events.any { it is DomainVoiceEvent.SpeechStarted }, "the turn should have been seen, got $events")
        assertTrue(events.any { it is DomainVoiceEvent.ResponseDone }, "the reply should have completed, got $events")
        assertTrue(
            events.none { it is DomainVoiceEvent.AudioDelta },
            "the phantom reply must not reach playback, got $events",
        )
    }

    @Test
    fun theSameReplyIsPlayedWhenTheDriverClearlySpoke() {
        val events = collectEvents(speechSegment, "没听清，再说一遍。")
        assertTrue(
            events.any { it is DomainVoiceEvent.AudioDelta },
            "a repair after real speech is useful and must be spoken",
        )
    }

    @Test
    fun aTurnThatCallsAToolIsPlayedEvenAfterShortAudio() {
        // 「暂停」 is brief. It acts, so it is real.
        val events = collectEvents(noiseSegment, "音乐已关闭。", withToolCall = true)
        assertTrue(events.any { it is DomainVoiceEvent.AudioDelta }, "a tool turn must never be held back")
        assertTrue(events.any { it is DomainVoiceEvent.ToolCall }, "the action itself must still be dispatched")
    }

    @Test
    fun aRealAnswerAfterShortAudioIsPlayed() {
        val events = collectEvents(noiseSegment, "前面第二个路口右转就到了。")
        assertTrue(events.any { it is DomainVoiceEvent.AudioDelta }, "a substantive answer is not a phantom")
    }

    @Test
    fun aRepairIsPlayedWhileSomethingOnScreenIsWaiting() {
        val events = collectEvents(noiseSegment, "没听清，再说一遍。", contextAwaiting = true)
        assertTrue(events.any { it is DomainVoiceEvent.AudioDelta }, "the driver is mid-choice and must be prompted")
    }

    @Test
    fun withoutAnAudioMeasurementTheTurnIsPlayed() {
        val events = collectEvents(null, "没听清，再说一遍。")
        assertTrue(events.any { it is DomainVoiceEvent.AudioDelta }, "never suppress on a guess")
    }


    /**
     * Checklist row T07: 「把音量调大一点」 has no tool. The owner's requirement is one honest
     * sentence, with subtitle and speech identical — never a confident 「正在调整」 spoken first and
     * corrected afterwards.
     */
    private fun unsupportedRequestRun(replyText: String): List<DomainVoiceEvent> {
        val done = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    webSocket.send("""{"type":"conversation.created","conversation":{"id":"conv_1"}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).optString("type") != "session.update") return
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                    webSocket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    webSocket.send("""{"type":"response.created","response":{"id":"resp_1"}}""")
                    webSocket.send(
                        JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
                            .put("transcript", "把音量调大一点").toString(),
                    )
                    webSocket.send("""{"type":"response.audio.delta","delta":"AAAA"}""")
                    webSocket.send(
                        JSONObject().put("type", "response.audio_transcript.done").put("transcript", replyText).toString(),
                    )
                    webSocket.send("""{"type":"response.audio.done"}""")
                    webSocket.send(
                        """{"type":"response.done","response":{"id":"resp_1","status":"completed","output":[{"type":"message"}]}}""",
                    )
                    done.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        return runBlocking {
            val client = BaiduFlexClient(
                http = OkHttpClient(),
                readyTimeoutMs = 3_000,
                requireTls = false,
                lastAudioSegment = { SpeechUplinkGate.Segment(1_400, 13, 12_000) },
                contextAwaitingAnswer = { false },
            )
            val seen = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
            val job = launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { seen += it.payload } }
            client.connect(config())
            assertTrue(done.await(5, TimeUnit.SECONDS), "server script did not finish")
            kotlinx.coroutines.delay(500)
            job.cancel()
            client.disconnect()
            seen.toList()
        }
    }

    @Test
    fun aFalseClaimAboutAnUnsupportedRequestIsNeverSpokenOrShown() {
        val events = unsupportedRequestRun("音量调大设置中，正在调整。")
        assertTrue(events.none { it is DomainVoiceEvent.AudioDelta }, "the false claim must not be spoken")
        assertTrue(
            events.none { it is DomainVoiceEvent.AssistantTranscript },
            "and must not appear as a subtitle either, or subtitle and speech diverge",
        )
    }

    @Test
    fun anHonestRefusalOfAnUnsupportedRequestIsSpokenNormally() {
        val events = unsupportedRequestRun("抱歉，我无法调节音量。")
        assertTrue(events.any { it is DomainVoiceEvent.AudioDelta }, "the honest answer must be spoken")
        assertTrue(events.any { it is DomainVoiceEvent.AssistantTranscript }, "and shown")
    }

    private fun config() = BaiduApiConfig(
        BaiduAppSettings(
            authMode = BaiduAuthMode.BEARER_API_KEY,
            runtimeProvider = BaiduRuntimeProvider.FLEX,
            model = BaiduFlexProtocol.MODEL,
            endpoint = server.url("/ws/2.0/speech/v1/realtime").toString().replace("http://", "ws://"),
        ),
        BaiduCredentials("", "placeholder-flex-key", ""),
    )
}