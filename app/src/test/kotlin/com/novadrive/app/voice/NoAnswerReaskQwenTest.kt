package com.novadrive.app.voice

import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * P45 Unit 2 through the real OpenAI-realtime client (Qwen dialect) on a scripted socket: a driver
 * turn that gets no answer is re-asked once with one `response.create`, and given up once.
 */
class NoAnswerReaskQwenTest {
    private val server = MockWebServer()
    private val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val received = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private val previousSink = com.novadrive.app.DebugVoiceLog.sink
    @Volatile private var segment = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 60, peak = 9_000)

    init { com.novadrive.app.DebugVoiceLog.sink = { lines += it } }

    @AfterEach
    fun close() {
        com.novadrive.app.DebugVoiceLog.sink = previousSink
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        server.close()
    }

    private fun enqueueServer() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                sockets += webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qwen3.5-omni-plus-realtime"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qwen3.5-omni-plus-realtime","voice":"Maia"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
    }

    private fun client(deadlineMs: Long = 300, giveUpMs: Long = 60_000) = QwenOmniClient(
        OkHttpClient(), 30_000, requireTls = false,
        endpoint = { server.url("/api-ws/v1/realtime?model=${it.model}").toString().replace("http://", "ws://") },
        contextHint = { null }, lastAudioSegment = { segment }, contextAwaitingAnswer = { false },
    ).also { it.emptyRetry.deadlineMs = deadlineMs; it.emptyRetry.giveUpMs = giveUpMs }

    private fun config() = QwenApiConfig(QwenAppSettings(consentAccepted = true, workspaceId = "ws-test"), "placeholder-key", "PERSONA")

    private fun collect(client: QwenOmniClient): MutableList<DomainVoiceEvent> {
        val seen = CopyOnWriteArrayList<DomainVoiceEvent>()
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { seen += it.payload } }
        return seen
    }

    private fun creates() = received.map(::JSONObject).count { it.getString("type") == "response.create" }
    private fun log(prefix: String) = synchronized(lines) { lines.filter { it.startsWith(prefix) } }

    private fun awaitUntil(state: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5_000)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail<Unit>("condition not reached; ${state()}")
            Thread.sleep(10)
        }
    }

    private fun send(vararg events: String) = events.forEach { sockets.single().send(it) }
    private val started = """{"type":"input_audio_buffer.speech_started"}"""
    private val stopped = """{"type":"input_audio_buffer.speech_stopped"}"""
    private fun transcript(text: String) = """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"$text"}"""
    private val emptyDone = """{"type":"response.done","response":{"status":"completed","output":[]}}"""

    @Test
    fun theDefaultDeadlineIsThreeSecondsAndTheGiveUpFive() {
        val policy = QwenOmniClient(contextHint = { null }).emptyRetry
        assertEquals(3_000, policy.deadlineMs)
        assertEquals(5_000, policy.giveUpMs)
    }

    @Test
    fun aSilentTurnAfterEndOfSpeechGetsExactlyOneResponseCreate() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "sent: $received" }) { creates() == 1 }
        Thread.sleep(600)
        assertEquals(1, creates(), "once per utterance")
        assertEquals(1, log("qwen_reask reason=deadline").size, "$lines")
        client.disconnect()
    }

    @Test
    fun anEmptyCloseOfATranscribedTurnIsReaskedAtOnceAndOnlyOnce() = runBlocking {
        enqueueServer()
        val client = client(deadlineMs = 60_000)
        client.connect(config())
        send(started, stopped, transcript("调到二十四度"), """{"type":"response.created","response":{"id":"r1"}}""", emptyDone)
        awaitUntil({ "sent: $received" }) { creates() == 1 }
        // The re-ask also comes back empty: no second one.
        send("""{"type":"response.created","response":{"id":"r2"}}""", emptyDone)
        Thread.sleep(400)
        assertEquals(1, creates())
        assertEquals(1, log("qwen_reask reason=empty_close").size, "$lines")
        assertTrue(synchronized(lines) { lines.none { "二十四" in it } }, "no transcript text in a log (I-8)")
        client.disconnect()
    }

    @Test
    fun noReaskWhenTheDriverSpeaksAgain() = runBlocking {
        enqueueServer()
        val client = client(deadlineMs = 400)
        client.connect(config())
        send(started, stopped)
        Thread.sleep(150)
        send(started)
        Thread.sleep(700)
        assertEquals(0, creates())
        client.disconnect()
    }

    @Test
    fun noReaskAfterAnAppHandledUtterance() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "$lines" }) { log("qwen_event type=input_audio_buffer.speech_stopped").isNotEmpty() }
        client.cancelActiveResponse()
        Thread.sleep(700)
        assertEquals(0, creates())
        client.disconnect()
    }

    @Test
    fun noReaskWhileListeningIsSuspended() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        send(started, stopped)
        client.discardPendingAudio()
        awaitUntil({ "$lines" }) { log("qwen_reask_dropped reason=suspended").isNotEmpty() }
        assertEquals(0, creates())
        client.disconnect()
    }

    @Test
    fun noReaskAfterASuspiciousSegment() = runBlocking {
        enqueueServer()
        segment = SpeechUplinkGate.Segment(durationMs = 200, voicedFrames = 1, peak = 900)
        val client = client()
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "$lines" }) { log("qwen_reask_dropped reason=suspicious").isNotEmpty() }
        assertEquals(0, creates())
        client.disconnect()
    }

    @Test
    fun noReaskWhenTheSocketIsClosed() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "$lines" }) { log("qwen_event type=input_audio_buffer.speech_stopped").isNotEmpty() }
        client.disconnect()
        Thread.sleep(700)
        assertEquals(0, creates())
    }

    @Test
    fun noAnswerAfterTheReaskGivesUpOnceAndALateCallIsNotExecuted() = runBlocking {
        enqueueServer()
        val client = client(deadlineMs = 200, giveUpMs = 300)
        val seen = collect(client)
        var gaveUp = 0
        client.onStallNotice = { final -> if (final) gaveUp++ }
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "gave up $gaveUp; $lines" }) { gaveUp == 1 }
        Thread.sleep(500)
        assertEquals(1, gaveUp)
        assertEquals(1, log("qwen_reask_gave_up").size)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
        )
        awaitUntil({ "$lines" }) { log("qwen_call_dropped reason=reask_gave_up").isNotEmpty() }
        assertTrue(seen.none { it is DomainVoiceEvent.ToolCall }, "a given-up turn's late call never runs")
        client.disconnect()
    }

    @Test
    fun aBusyErrorAfterTheReaskDropsItWithoutRequeueing() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "sent: $received" }) { creates() == 1 }
        send("""{"type":"error","error":{"type":"invalid_request_error","code":"InvalidRequest","message":"Conversation already has an active response"}}""")
        awaitUntil({ "$lines" }) { log("qwen_reask_dropped reason=busy").isNotEmpty() }
        send("""{"type":"response.created","response":{"id":"r9"}}""", """{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
        Thread.sleep(400)
        assertEquals(1, creates(), "dropped, not re-queued")
        client.disconnect()
    }

    @Test
    fun anAnswerToTheReaskIsStillJudgedForClaims() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        send(started, stopped)
        awaitUntil({ "sent: $received" }) { creates() == 1 }
        send(
            transcript("打开空调"),
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"AAE="}""",
            """{"type":"response.audio_transcript.done","transcript":"已为您打开空调"}""",
            """{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"type":"message","role":"assistant"}]}}""",
        )
        // The unproven claim draws the claim gate's correction, as any reply would (I-1).
        awaitUntil({ "sent: $received; $lines" }) { received.map(::JSONObject).any { it.getString("type") == "conversation.item.create" } }
        client.disconnect()
    }
}
