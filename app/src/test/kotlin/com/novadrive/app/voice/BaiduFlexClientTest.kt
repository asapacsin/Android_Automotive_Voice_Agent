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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BaiduFlexClientTest {
    private val server = MockWebServer()
    @AfterEach
    fun close() {
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
        server.close()
    }

    @Test
    fun authenticatesWaitsForCreatedThenUpdatedAndSendsFunctionResultLoop() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<String>())
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
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
        client.connect(config())
        client.sendFunctionResult("call_9", "{\"ok\":true}")
        assertTrue(resultMessages.await(3, TimeUnit.SECONDS))
        client.disconnect()
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer placeholder-flex-key", request.getHeader("Authorization"))
        assertEquals(BaiduFlexProtocol.MODEL, request.requestUrl?.queryParameter("model"))
        assertEquals("session.update", JSONObject(received[0]).getString("type"))
        assertEquals(6, JSONObject(received[0]).getJSONObject("session").getJSONArray("tools").length())
        val output = received.map(::JSONObject).single { it.getString("type") == "conversation.item.create" }
        assertEquals("call_9", output.getJSONObject("item").getString("call_id"))
        assertTrue(received.map(::JSONObject).any { it.getString("type") == "response.create" })
    }

    @Test
    fun rejectedVoiceFallsBackToDefaultAndStillReachesSessionUpdated() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<String>())
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
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
        client.connect(config(voice = "4157"))
        client.disconnect()
        val updates = received.map(::JSONObject).filter { it.getString("type") == "session.update" }
        assertEquals(2, updates.size)
        assertEquals("default", updates[1].getJSONObject("session").getString("voice"))
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
        val received = Collections.synchronizedList(mutableListOf<String>())
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
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
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
        val received = Collections.synchronizedList(mutableListOf<String>())
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
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
        client.connect(config())
        assertTrue(first.await(3, TimeUnit.SECONDS))
        val payload = received.map(::JSONObject).first { it.getString("type") == "session.update" }
        assertEquals(0.75, payload.getJSONObject("session").getJSONObject("turn_detection").getDouble("threshold"))
        client.disconnect()
    }

    @Test
    fun emptyCompletedResponseAfterUtteranceRequestsExactlyOneReply() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<String>())
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
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
        client.connect(config())
        Thread.sleep(800)
        val creates = received.map(::JSONObject).count { it.getString("type") == "response.create" }
        assertEquals(1, creates)
        client.disconnect()
    }

    @Test
    fun sendAudioOnClosedSocketEmitsErrorWithoutThrowing() = runBlocking {
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
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
        val received = Collections.synchronizedList(mutableListOf<String>())
        val sockets = Collections.synchronizedList(mutableListOf<WebSocket>())
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
        val client = BaiduFlexClient(OkHttpClient(), 3_000, requireTls = false)
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
}
