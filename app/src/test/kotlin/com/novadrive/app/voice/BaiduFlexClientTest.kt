package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduCredentials
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.NavigationState
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BaiduFlexClientTest {
    private val server = MockWebServer()
    @AfterEach
    fun close() {
        collectorScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
        server.close()
    }

    @Test
    fun authenticatesWaitsForCreatedThenUpdatedAndSendsFunctionResultLoop() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        val resultMessages = CountDownLatch(3)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
                webSocket.send("""{"type":"conversation.created","conversation":{"id":"conv_1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text; resultMessages.countDown()
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1","turn_detection":{"interrupt_response":true}}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config())
        client.sendFunctionResult("call_9", "{\"ok\":true}")
        assertTrue(resultMessages.await(3, TimeUnit.SECONDS))
        client.disconnect()
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer placeholder-flex-key", request.getHeader("Authorization"))
        assertEquals(BaiduFlexProtocol.MODEL, request.requestUrl?.queryParameter("model"))
        assertEquals("session.update", JSONObject(received[0]).getString("type"))
        assertEquals(17, JSONObject(received[0]).getJSONObject("session").getJSONArray("tools").length())
        val output = received.map(::JSONObject).single { it.getString("type") == "conversation.item.create" }
        assertEquals("call_9", output.getJSONObject("item").getString("call_id"))
        assertTrue(received.map(::JSONObject).any { it.getString("type") == "response.create" })
    }

    @Test
    fun rejectedVoiceFallsBackToDefaultAndStillReachesSessionUpdated() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") != "session.update") return
                val voice = JSONObject(text).getJSONObject("session").optString("voice")
                if (voice != "default") {
                    webSocket.send("""{"type":"error","error":{"code":"invalid_voice","message":"unsupported voice"}}""")
                } else {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1","voice":"default"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config(voice = "4157"))
        assertEquals("default", client.confirmedVoice)
        assertEquals("4157", client.requestedVoice)
        client.disconnect()
        val updates = received.map(::JSONObject).filter { it.getString("type") == "session.update" }
        assertEquals(2, updates.size)
        assertEquals("default", updates[1].getJSONObject("session").getString("voice"))
    }

    @Test
    fun sessionUpdatedRecordsConfirmedVoiceMatchingRequest() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") != "session.update") return
                val voice = JSONObject(text).getJSONObject("session").getString("voice")
                webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1","voice":"$voice"}}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config(voice = "4196"))
        assertEquals("4196", client.requestedVoice)
        assertEquals("4196", client.confirmedVoice)
        assertTrue(client.voiceConfirmedAsRequested)
        client.disconnect()
    }

    @Test
    fun releasingFirstOwnerDoesNotClearSecondOwner() {
        val a: (Boolean) -> Unit = {}
        val b: (Boolean) -> Unit = {}
        NavigationState.onNavigatingChanged = a
        NavigationState.onNavigatingChanged = b
        releaseNavigatingListenerIfOwned(a)
        assertTrue(NavigationState.onNavigatingChanged === b)
        releaseNavigatingListenerIfOwned(b)
        assertEquals(null, NavigationState.onNavigatingChanged)
    }

    @Test
    fun navigationFlipDoesNotResendSessionUpdateMidSession() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    first.countDown()
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config())
        assertTrue(first.await(3, TimeUnit.SECONDS))
        NavigationState.begin()
        Thread.sleep(500)
        val payloads = received.map(::JSONObject).filter { it.getString("type") == "session.update" }
        // Baidu rejects a mid-session threshold change while audio flows; only the connect-time update is sent.
        assertEquals(1, payloads.size)
        assertEquals(0.62, payloads[0].getJSONObject("session").getJSONObject("turn_detection").getDouble("threshold"))
        client.disconnect()
    }

    @Test
    fun connectWhileNavigatingUsesRaisedVadThreshold() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    first.countDown()
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        NavigationState.begin()
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config())
        assertTrue(first.await(3, TimeUnit.SECONDS))
        val payload = received.map(::JSONObject).first { it.getString("type") == "session.update" }
        assertEquals(0.75, payload.getJSONObject("session").getJSONObject("turn_detection").getDouble("threshold"))
        client.disconnect()
    }

    @Test
    fun emptyCompletedResponseAfterUtteranceRequestsExactlyOneReply() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    // Measured on device: utterance transcribed, then a completed response with no output — twice.
                    webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                    webSocket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"温度调高一点。"}""")
                    webSocket.send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
                    webSocket.send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        client.connect(config())
        awaitUntil({ "response.done events: $seen" }) { seen.count { it is DomainVoiceEvent.ResponseDone } >= 2 }
        fun creates() = received.map(::JSONObject).count { it.getString("type") == "response.create" }
        awaitUntil({ "sent: $received" }) { creates() >= 1 }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, creates())
        client.disconnect()
    }

    @Test
    fun appReplyRequestedWhileTheDriverSpeaksWaitsAndAnOverlapIsNotFatal() = runBlocking {
        // Measured 2026-09-17: the camera's read-aloud turn was sent mid-question, Baidu refused
        // the driver's turn ("already has an active response") and the session went to ERROR.
        val received = CopyOnWriteArrayList<String>()
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        fun errors() = seen.filterIsInstance<DomainVoiceEvent.Error>()
        client.connect(config())
        fun creates() = received.map(::JSONObject).count { it.getString("type") == "response.create" }
        fun textItems() = received.map(::JSONObject).count { it.getString("type") == "conversation.item.create" }
        val socket = serverSocket!!

        socket.send("""{"type":"input_audio_buffer.speech_started"}""")
        awaitUntil({ "events: $seen" }) { DomainVoiceEvent.SpeechStarted in seen }
        client.sendUserText("请把刚才的画面描述读出来")
        socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
        socket.send("""{"type":"response.created","response":{"id":"r1"}}""")
        socket.send("""{"type":"error","error":{"code":"invalid_request","message":"Conversation already has an active response in progress: r1. Wait until the response is finished before creating a new one."}}""")
        awaitUntil({ "events: $seen" }) { DomainVoiceEvent.ResponseStarted in seen }
        // Negative wait: longer than the client's own deferred-turn flush after speech_stopped.
        Thread.sleep(2_000)
        assertEquals(0, creates(), "nothing may be requested while the driver's reply runs")
        assertEquals(0, textItems())

        socket.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
        awaitUntil({ "sent: $received" }) { creates() >= 1 }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, creates(), "the refused reply is retried first, once")
        socket.send("""{"type":"response.created","response":{"id":"r2"}}""")
        socket.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
        awaitUntil({ "sent: $received" }) { textItems() >= 1 && creates() >= 2 }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, textItems(), "then the app's own turn goes out")
        assertEquals(2, creates())
        assertTrue(errors().isEmpty(), "an overlap must not surface as a session error: ${errors()}")
        client.disconnect()
    }

    @Test
    fun aClaimedActionWithoutAToolCallGetsOneCorrectiveTurn() = runBlocking {
        // Event order and wording as measured on device 2026-09-17.
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") != "session.update") return
                webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                webSocket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                webSocket.send("""{"type":"response.created","response":{"id":"r1"}}""")
                webSocket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"温度调高一点。"}""")
                webSocket.send("""{"type":"response.audio_transcript.done","transcript":"调高温度了。"}""")
                webSocket.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config())
        awaitUntil({ "sent: $received" }) {
            received.map(::JSONObject).let { sent ->
                sent.any { it.getString("type") == "conversation.item.create" } && sent.any { it.getString("type") == "response.create" }
            }
        }
        Thread.sleep(NEGATIVE_WAIT_MS)
        val sent = received.map(::JSONObject)
        val followUp = sent.filter { it.getString("type") == "conversation.item.create" }
        assertEquals(1, followUp.size)
        assertTrue(followUp.single().toString().contains("温度调高一点"))
        assertEquals(1, sent.count { it.getString("type") == "response.create" })
        client.disconnect()
    }

    /**
     * Owner demo 2026-09-28 08:33:12: the driver kept talking over a held reply. The turn was
     * dropped (`cancelled_superseded replyChars=0`), and then the rest of that reply -
     * 「我没听清，再说一遍。」 - was still emitted, and the cancelled response triggered a context
     * reset while the driver was mid-sentence.
     */
    @Test
    fun aSupersededReplyIsNeitherShownNorCountedAsATurn() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") != "session.update") return
                webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                // Three chat turns so a fourth completed reply would reset the conversation.
                repeat(2) { i ->
                    webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                    webSocket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                    webSocket.send("""{"type":"response.created","response":{"id":"c$i"}}""")
                    webSocket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u$i","transcript":"今天心情不错啊。"}""")
                    webSocket.send("""{"type":"response.audio_transcript.done","transcript":"那真好，继续加油。"}""")
                    webSocket.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
                }
                webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                webSocket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
                webSocket.send("""{"type":"response.created","response":{"id":"r1"}}""")
                webSocket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"我在办公室讲话，会不会吵到别人。"}""")
                // The driver goes on talking: the held reply is superseded...
                webSocket.send("""{"type":"input_audio_buffer.speech_started"}""")
                // ...and Baidu still streams the end of it before cancelling.
                webSocket.send("""{"type":"response.audio.delta","delta":"AAE="}""")
                webSocket.send("""{"type":"response.audio_transcript.done","transcript":"我没听清，再说一遍。"}""")
                webSocket.send("""{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"turn_detected"},"output":[{"type":"message"}]}}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        client.connect(config())
        awaitUntil({ "events: $seen" }) { seen.count { it is DomainVoiceEvent.ResponseDone } >= 3 }
        Thread.sleep(NEGATIVE_WAIT_MS)
        val transcripts = seen.filterIsInstance<DomainVoiceEvent.AssistantTranscript>().map { it.text }
        assertEquals(listOf("那真好，继续加油。", "那真好，继续加油。"), transcripts, "the superseded reply is never shown")
        assertEquals(0, seen.count { it is DomainVoiceEvent.AudioDelta }, "nor heard")
        assertEquals(1, server.requestCount, "a reply the driver talked over does not reset the conversation mid-utterance")
        assertEquals(0, received.count { JSONObject(it).optString("type") == "conversation.item.create" }, "and is not corrected")
        client.disconnect()
    }

    @Test
    fun aServerCloseIsReportedSoTheSessionCanRecover() = runBlocking {
        // Without answering the close frame OkHttp never reported it: the session died silently.
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.withTimeout(3_000) {
                client.events().first { (it.payload as? DomainVoiceEvent.Error)?.code == "BAIDU_FLEX_CONNECTION_CLOSED" }
            }
        }
        client.connect(config())
        serverSocket!!.close(1011, "server going away")
        assertEquals("BAIDU_FLEX_CONNECTION_CLOSED", (closed.await().payload as DomainVoiceEvent.Error).code)
        client.disconnect()
    }

    @Test
    fun afterStandbyTheClientSendsNoTurnsOfItsOwn() = runBlocking {
        // 「关闭小诺」: the reply to it is cancelled and must not trigger a false-claim follow-up.
        val received = CopyOnWriteArrayList<String>()
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        client.connect(config())
        val socket = serverSocket!!
        socket.send("""{"type":"response.created","response":{"id":"r1"}}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"空调关闭小诺"}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.UserTranscript && it.final } }
        client.discardPendingAudio()
        client.cancelActiveResponse()
        socket.send("""{"type":"response.audio_transcript.done","transcript":"好的，已关闭。"}""")
        socket.send("""{"type":"response.done","response":{"status":"cancelled","output":[{"type":"message"}]}}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        val types = received.map { JSONObject(it).getString("type") }
        assertTrue("response.cancel" in types, "the reply in progress is cancelled before any audio: $types")
        assertEquals(0, types.count { it == "conversation.item.create" }, "no follow-up turn in standby")
        assertEquals(0, types.count { it == "response.create" })
        client.disconnect()
    }

    @Test
    fun aReplyCancelledForALocalPickIsNotCorrected() = runBlocking {
        // P40, emulator replay 2026-09-28: 「开始导航」 during guidance was answered by the app; Baidu
        // still completed the cancelled reply 「导航已开始」, and the correction for that unproven
        // claim made the model call navigate_to with no destination.
        val received = CopyOnWriteArrayList<String>()
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        client.connect(config())
        val socket = serverSocket!!
        socket.send("""{"type":"input_audio_buffer.speech_started"}""")
        socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
        socket.send("""{"type":"response.created","response":{"id":"r1"}}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"开始导航。"}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.UserTranscript && it.final } }
        client.cancelActiveResponse()
        socket.send("""{"type":"response.audio_transcript.done","transcript":"导航已开始。"}""")
        socket.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        val types = received.map { JSONObject(it).getString("type") }
        assertTrue("response.cancel" in types, "the reply was cancelled: $types")
        assertEquals(0, types.count { it == "conversation.item.create" }, "no correction turn: $types")
        client.disconnect()
    }

    @Test
    fun aReplyTheClientCancelledIsNotShown() = runBlocking {
        // P40, replay 16:04:52: the cancelled reply to 「开始导航」 was released as a subtitle.
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        client.connect(config())
        val socket = serverSocket!!
        socket.send("""{"type":"input_audio_buffer.speech_started"}""")
        socket.send("""{"type":"input_audio_buffer.speech_stopped"}""")
        socket.send("""{"type":"response.created","response":{"id":"r1"}}""")
        socket.send("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"开始导航。"}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.UserTranscript && it.final } }
        client.cancelActiveResponse()
        socket.send("""{"type":"response.audio_transcript.done","transcript":"导航启动中，请说目的地。"}""")
        socket.send("""{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"client_cancelled"},"output":[{"type":"message"}]}}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(
            seen.none { it is DomainVoiceEvent.AssistantTranscript || it is DomainVoiceEvent.AudioDelta },
            "a cancelled reply is neither heard nor shown: $seen",
        )
        client.disconnect()
    }

    @Test
    fun completedToolTurnStartsAFreshConversationAndHeldAudioReachesIt() = runBlocking {
        val secondUpdateSeen = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val audioOnSecond = CountDownLatch(1)
        val firstSocket = arrayOfNulls<WebSocket>(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                firstSocket[0] = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (JSONObject(text).optString("type")) {
                    "session.update" ->
                        webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    "response.create" ->
                        // The spoken reply after the tool result: this completes the tool turn.
                        webSocket.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (JSONObject(text).optString("type")) {
                    "session.update" -> {
                        secondUpdateSeen.countDown()
                        releaseSecond.await(3, TimeUnit.SECONDS)
                        webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                    }
                    "input_audio_buffer.append" -> audioOnSecond.countDown()
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        client.connect(config())

        // A tool turn: the model calls a tool, the result goes back, the model replies.
        val first = requireNotNull(firstSocket[0])
        first.send("""{"type":"response.output_item.added","item":{"id":"i1","type":"function_call","call_id":"call_1","name":"control_climate"}}""")
        first.send("""{"type":"response.function_call_arguments.done","call_id":"call_1","arguments":"{\"action\":\"power_on\"}"}""")
        first.send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call"}]}}""")
        awaitUntil({ "events: $seen" }) {
            seen.any { it is DomainVoiceEvent.ToolCall } && seen.any { it is DomainVoiceEvent.ResponseDone }
        }
        client.sendFunctionResult("call_1", "{\"ok\":true}")

        // The reset has opened a second conversation and is waiting for it to be ready.
        assertTrue(secondUpdateSeen.await(3, TimeUnit.SECONDS), "a fresh conversation was opened")
        client.sendAudio(ByteArray(PcmAudioCapture.FRAME_BYTES)) // spoken during the reset: must be held, not dropped
        releaseSecond.countDown()
        assertTrue(audioOnSecond.await(3, TimeUnit.SECONDS), "held audio reached the new conversation")
        client.disconnect()
    }

    @Test
    fun sendAudioOnClosedSocketEmitsErrorWithoutThrowing() = runBlocking {
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { client.events().first() }
        client.sendAudio(ByteArray(320))
        val payload = pending.await().payload
        assertTrue(payload is DomainVoiceEvent.Error)
        assertEquals("BAIDU_FLEX_CONNECTION_CLOSED", (payload as DomainVoiceEvent.Error).code)
        client.cancelResponse()
        val thrown = assertThrows<VoiceProviderException> { client.sendFunctionResult("call_1", "{}") }
        assertEquals("BAIDU_FLEX_CONNECTION_CLOSED", thrown.code)
        client.disconnect()
    }

    @Test
    fun cancelResponseSendsOnlyWhenAssistantIsSpeaking() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val audioAppended = CountDownLatch(1)
        val cancelSent = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                sockets += webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val type = JSONObject(text).optString("type")
                if (type == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
                if (type == "input_audio_buffer.append") audioAppended.countDown()
                if (type == "response.cancel") cancelSent.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        client.connect(config())
        client.cancelResponse()
        client.sendAudio(ByteArray(320))
        assertTrue(audioAppended.await(3, TimeUnit.SECONDS))
        assertTrue(received.none { JSONObject(it).optString("type") == "response.cancel" })

        val pendingAudio = async(start = CoroutineStart.UNDISPATCHED) {
            client.events().first { it.payload is DomainVoiceEvent.AudioDelta }
        }
        sockets.single().send("""{"type":"response.audio.delta","delta":"AAE="}""")
        pendingAudio.await()
        client.cancelResponse()
        assertTrue(cancelSent.await(3, TimeUnit.SECONDS))
        assertEquals(1, received.count { JSONObject(it).optString("type") == "response.cancel" })
        client.disconnect()
    }

    /**
     * OPEN_PROBLEMS 2026-09-28, reproduced from the emulator log: 「播放音乐」 ran the on-screen play
     * control and cancelled the model's reply; Baidu then finished that reply's control_music call
     * with cut-off arguments (MALFORMED_JSON), the failure went back to the model and 小诺 said
     * 没成功 while the music played. Then 「闭嘴」 silenced twice (local router + set_speech_output),
     * the second response.cancel was refused with an error event.
     */
    @Test
    fun aCallFromACancelledResponseIsNotRunAndTheResponseIsCancelledOnce() = runBlocking {
        val received = CopyOnWriteArrayList<String>()
        var serverSocket: WebSocket? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (JSONObject(text).optString("type") == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), READY_TIMEOUT_MS, requireTls = false)
        val seen = collectEvents(client)
        fun calls() = seen.filterIsInstance<DomainVoiceEvent.ToolCall>()
        client.connect(config())
        val socket = serverSocket!!
        socket.send("""{"type":"response.created","response":{"id":"r1"}}""")
        socket.send("""{"type":"response.output_item.added","item":{"id":"i1","type":"function_call","call_id":"call_music","name":"control_music"}}""")
        socket.send("""{"type":"response.function_call_arguments.delta","call_id":"call_music","delta":"{\"action\":\"pl"}""")
        awaitUntil({ "events: $seen" }) { DomainVoiceEvent.ResponseStarted in seen }
        // The on-screen path took the utterance; both 「闭嘴」 paths also ask for a cancel.
        client.cancelActiveResponse()
        client.cancelActiveResponse()
        awaitUntil({ "sent: $received" }) { received.any { JSONObject(it).optString("type") == "response.cancel" } }
        socket.send("""{"type":"response.function_call_arguments.done","call_id":"call_music","arguments":"{\"action\":\"pl"}""")
        socket.send("""{"type":"response.done","response":{"status":"cancelled","output":[{"type":"function_call"}]}}""")
        awaitUntil({ "events: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        val types = received.map { JSONObject(it).getString("type") }
        assertEquals(1, types.count { it == "response.cancel" }, "one cancel per response: $types")
        assertTrue(calls().isEmpty(), "the cancelled reply's call is neither run nor failed: ${calls()}")
        assertEquals(0, types.count { it == "conversation.item.create" }, "no failure result goes back")
        assertEquals(0, types.count { it == "response.create" }, "no reply is asked for")

        // The next response is a fresh one: its calls run, and it may be cancelled again.
        socket.send("""{"type":"response.created","response":{"id":"r2"}}""")
        socket.send("""{"type":"response.output_item.added","item":{"id":"i2","type":"function_call","call_id":"call_stop","name":"control_music"}}""")
        socket.send("""{"type":"response.function_call_arguments.done","call_id":"call_stop","arguments":"{\"action\":\"stop\"}"}""")
        awaitUntil({ "events: $seen" }) { calls().isNotEmpty() }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(listOf("call_stop"), calls().map { it.callId })
        client.cancelActiveResponse()
        awaitUntil({ "sent: $received" }) { received.count { JSONObject(it).optString("type") == "response.cancel" } >= 2 }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(2, received.count { JSONObject(it).optString("type") == "response.cancel" })
        client.disconnect()
    }

    /**
     * Records every event the client emits. Subscribed before this returns (UNDISPATCHED), so no
     * event emitted after it can be missed; cancelled after each test.
     */
    private fun collectEvents(client: BaiduFlexClient): MutableList<DomainVoiceEvent> {
        val seen = CopyOnWriteArrayList<DomainVoiceEvent>()
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { seen += it.payload } }
        return seen
    }

    private val collectorScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Waits for a condition the client reaches asynchronously instead of sleeping a fixed time
     * (D-FLAKE-BAIDU): fixed sleeps failed when the machine was loaded.
     */
    private fun awaitUntil(state: () -> String, timeoutMs: Long = AWAIT_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail<Unit>("condition not reached within ${timeoutMs}ms; ${state()}")
            Thread.sleep(10)
        }
    }

    private fun config(voice: String = BaiduAppSettings.DEFAULT_VOICE) = BaiduApiConfig(
        BaiduAppSettings(
            authMode = BaiduAuthMode.BEARER_API_KEY,
            runtimeProvider = BaiduRuntimeProvider.FLEX,
            model = BaiduFlexProtocol.MODEL,
            endpoint = server.url("/ws/2.0/speech/v1/realtime").toString().replace("http://", "ws://"),
            voice = voice,
        ),
        BaiduCredentials("", "placeholder-flex-key", ""),
    )

    /**
     * The readiness timeout does fire - proven here, with a short one, so no other test has to
     * depend on it.
     */
    @Test
    fun aSessionThatNeverBecomesReadyFails() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                // Upgrades and then says nothing: no session.created, ever.
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = Unit

                // Without this the half-open socket outlives the test and MockWebServer's
                // shutdown fails with "Gave up waiting for queue to shut down".
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }),
        )
        val client = BaiduFlexClient(OkHttpClient(), 300, requireTls = false)
        val failure = runCatching { client.connect(config()) }.exceptionOrNull()
        client.close()
        kotlinx.coroutines.delay(200)
        assertTrue(failure is VoiceProviderException, "a session that never opens must fail, not hang")
        assertEquals("BAIDU_FLEX_TIMEOUT", (failure as VoiceProviderException).code)
    }

    private companion object {
        /**
         * Deliberately far longer than any of these tests needs.
         *
         * At 3 s the suite failed with `Baidu Flex session readiness timed out` under a full
         * parallel Gradle run on 2026-09-19 and passed on its own seconds later (B-013). None of
         * these tests is about how long readiness may take - they are about what happens once it
         * arrives - so the wait must not encode how busy the machine is. The timeout itself is
         * covered by [aSessionThatNeverBecomesReadyFails], with its own short value.
         */
        const val READY_TIMEOUT_MS = 30_000L

        /** Deadline for an awaited positive condition; generous so load cannot fail a test. */
        const val AWAIT_TIMEOUT_MS = 5_000L

        /**
         * Bounded wait used only to show that something does NOT happen, always taken after the
         * preceding positive event has been awaited. Load can only make it more lenient, never fail.
         */
        const val NEGATIVE_WAIT_MS = 300L
    }
}
