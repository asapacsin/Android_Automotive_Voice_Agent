package com.novadrive.app.voice

import com.novadrive.app.NavigationState
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** SPEC-021 A3, A5, A6, A7 and the text refusal, on a mock socket in the Baidu Flex client-test shape. */
class QwenOmniClientTest {
    private val server = MockWebServer()
    private val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val received = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val goodAudio = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 14, peak = 9_000)
    private val workspace = "ws-secret-7f3a"
    private val key = "placeholder-qwen-key"

    @AfterEach
    fun close() {
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
        server.close()
    }

    /** A server that answers session.update with session.updated and records what the client sends. */
    private fun enqueueServer(onClientMessage: (WebSocket, JSONObject) -> Unit = { _, _ -> }) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                sockets += webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qwen3.8-omni-flash-realtime"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val json = JSONObject(text)
                if (json.optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qwen3.8-omni-flash-realtime","voice":"Maia"}}""")
                }
                onClientMessage(webSocket, json)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
    }

    private fun client() = QwenOmniClient(
        OkHttpClient(),
        READY_TIMEOUT_MS,
        requireTls = false,
        endpoint = { server.url("/api-ws/v1/realtime?model=${it.model}").toString().replace("http://", "ws://") },
        contextHint = { null },
        lastAudioSegment = { goodAudio },
        contextAwaitingAnswer = { false },
    )

    private fun config() = QwenApiConfig(QwenAppSettings(consentAccepted = true, workspaceId = workspace), key, "PERSONA")

    private fun collect(client: QwenOmniClient): MutableList<DomainVoiceEvent> {
        val seen = CopyOnWriteArrayList<DomainVoiceEvent>()
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { seen += it.payload } }
        return seen
    }

    private fun sent(type: String) = received.map(::JSONObject).filter { it.getString("type") == type }

    private fun awaitUntil(state: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail<Unit>("condition not reached within ${AWAIT_TIMEOUT_MS}ms; ${state()}")
            Thread.sleep(10)
        }
    }

    private fun send(vararg events: String) = events.forEach { sockets.single().send(it) }

    /** A driver's action request whose reply audio claims the action (event order as the docs list it). */
    private fun claimingReply() = send(
        """{"type":"input_audio_buffer.speech_started"}""",
        """{"type":"input_audio_buffer.speech_stopped"}""",
        """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"帮我打开空调"}""",
        """{"type":"response.created","response":{"id":"r1"}}""",
        """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
        """{"type":"response.audio.delta","delta":"AAE="}""",
        """{"type":"response.audio_transcript.done","transcript":"已为您打开空调"}""",
    )

    // ---- A3 ----

    @Test
    fun opensWithTheBearerKeyAndTheDocumentedSessionUpdate() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertEquals("qwen3.8-omni-flash-realtime", request.requestUrl?.queryParameter("model"))
        assertFalse(request.requestUrl.toString().contains(key))
        val update = sent("session.update").single().getJSONObject("session")
        assertEquals("Maia", update.getString("voice"))
        assertEquals("semantic_vad", update.getJSONObject("turn_detection").getString("type"))
        assertNull(NavigationState.onNavigatingChanged, "Qwen does not track navigation for VAD")
        client.disconnect()
    }

    // ---- A5: the claim gate holds the reply's audio ----

    @Test
    fun aClaimingReplysAudioIsHeldUntilOkTrue() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        claimingReply()
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.AudioDelta }, "nothing is heard before execution evidence (I-1)")
        client.sendFunctionResult("call_1", """{"ok":true,"tool":"control_climate"}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.AudioDelta } }
        assertEquals(DomainVoiceEvent.AudioDelta("AAE="), seen.first { it is DomainVoiceEvent.AudioDelta })
        client.disconnect()
    }

    @Test
    fun aClaimingReplysAudioIsDroppedOnARefusal() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        claimingReply()
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } }
        client.sendFunctionResult("call_1", """{"ok":false,"error":"VEHICLE_UNAVAILABLE"}""")
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1"},{"type":"message"}]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.AudioDelta }, "ok=false is not proof (I-1)")
        assertTrue(seen.none { it is DomainVoiceEvent.AssistantTranscript && it.text.contains("已为您") })
        client.disconnect()
    }

    // ---- A6: tool round trip, no overlapping reply ----

    @Test
    fun aToolResultGoesBackAsFunctionCallOutputThenOneResponseCreateAfterTheRunningReply() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"打开空调"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } }
        assertEquals(
            DomainVoiceEvent.ToolCall("call_1", "control_climate", mapOf("action" to "power_on")),
            seen.single { it is DomainVoiceEvent.ToolCall },
        )
        client.sendFunctionResult("call_1", """{"ok":true,"tool":"control_climate"}""")
        awaitUntil({ "sent: $received" }) { sent("conversation.item.create").isNotEmpty() }
        val output = sent("conversation.item.create").single().getJSONObject("item")
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(sent("response.create").isEmpty(), "no second reply while the first is running")
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1"}]}}""")
        awaitUntil({ "sent: $received" }) { sent("response.create").isNotEmpty() }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.create").size)
        client.disconnect()
    }

    // ---- A7: barge-in ----

    @Test
    fun bargeInSendsOneResponseCancelAndARefusedCancelIsNotFatal() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        // As in BaiduFlexClientTest: reply audio is playing (the turn gate is covered by the A5 tests).
        send("""{"type":"response.audio.delta","delta":"AAE="}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.AudioDelta } }
        client.cancelResponse()
        client.cancelResponse()
        awaitUntil({ "sent: $received" }) { sent("response.cancel").isNotEmpty() }
        send("""{"type":"error","error":{"code":"invalid_request_error","message":"Cancellation failed: no active response found"}}""")
        send("""{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"client_cancelled"}}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.cancel").size)
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "a refused cancel is not session-fatal: $seen")
        assertTrue(seen.any { it is DomainVoiceEvent.Interrupted })
        client.disconnect()
    }

    // ---- SPEC-021 B7: a refused text item ----

    @Test
    fun afterTheServerRefusesATextItemTheNextCorrectionSendsNothing() = runBlocking {
        enqueueServer { ws, json ->
            if (json.optString("type") == "conversation.item.create" && json.getJSONObject("item").optString("type") == "message") {
                ws.send("""{"type":"error","error":{"code":"invalid_value","param":"item.type","message":"Invalid value: 'message'"}}""")
            }
        }
        val client = client()
        val seen = collect(client)
        client.connect(config())
        client.sendUserText("第一次纠正")
        awaitUntil({ "sent: $received" }) { sent("conversation.item.create").isNotEmpty() && sent("response.create").isNotEmpty() }
        send("""{"type":"response.created","response":{"id":"r1"}}""", """{"type":"response.done","response":{"status":"completed","output":[]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        val itemsBefore = sent("conversation.item.create").size
        val createsBefore = sent("response.create").size
        client.sendUserText("第二次纠正")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(itemsBefore, sent("conversation.item.create").size, "no text item after a refusal")
        assertEquals(createsBefore, sent("response.create").size, "and no response.create either")
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "the refusal is not session-fatal: $seen")
        client.disconnect()
    }

    @Test
    fun aSessionThatNeverBecomesReadyFailsWithTheQwenCode() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = Unit
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = QwenOmniClient(
            OkHttpClient(), 300, requireTls = false,
            endpoint = { server.url("/api-ws/v1/realtime").toString().replace("http://", "ws://") },
        )
        val failure = runCatching { client.connect(config()) }.exceptionOrNull()
        client.close()
        kotlinx.coroutines.delay(200)
        assertEquals("QWEN_READY_TIMEOUT", (failure as VoiceProviderException).code)
        assertFalse(failure.safeMessage.contains(workspace))
    }

    private companion object {
        /** See BaiduFlexClientTest: readiness is not what these tests measure. */
        const val READY_TIMEOUT_MS = 30_000L
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val NEGATIVE_WAIT_MS = 300L
    }
}
