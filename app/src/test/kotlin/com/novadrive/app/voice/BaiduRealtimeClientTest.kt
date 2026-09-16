package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduCredentials
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BaiduRealtimeClientTest {
    private val server = MockWebServer()

    @AfterEach fun closeServer() = server.close()

    @Test
    fun bearerHandshakeWaitsForUpdatedAndStreamsOnlyAppend() = runBlocking {
        val messages = mutableListOf<String>()
        val latch = CountDownLatch(2)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages += text; latch.countDown()
                if (text.contains("session.update")) {
                    webSocket.send("""{"type":"session.updated","session":{"model":"audio-mini-realtime-near"}}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        val client = client()
        client.connect(config())
        client.sendAudio(byteArrayOf(0, 1))
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        client.disconnect()
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer placeholder-api-key", request.getHeader("Authorization"))
        assertEquals("audio-mini-realtime-near", request.requestUrl?.queryParameter("model"))
        assertTrue(messages.any { it.contains("input_audio_buffer.append") })
        assertTrue(messages.none { it.contains("function_call") || it.contains("response.cancel") })
    }

    @Test
    fun authFailureIsSafeAndSpecific() {
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = assertThrows<VoiceProviderException> { runBlocking { client().connect(config()) } }
        assertEquals("BAIDU_AUTH_FAILED", failure.code)
        assertTrue(failure.safeMessage.contains("HTTP 401"))
    }

    private fun client() = BaiduRealtimeClient(OkHttpClient(), 3_000, requireTls = false)
    private fun config() = BaiduApiConfig(
        BaiduAppSettings(
            authMode = BaiduAuthMode.BEARER_API_KEY,
            runtimeProvider = BaiduRuntimeProvider.LITE,
            model = VoiceCatalog.BAIDU_LITE_NEAR,
            endpoint = server.url("/ws/2.0/speech/v1/realtime").toString().replace("http://", "ws://"),
        ),
        BaiduCredentials("", "placeholder-api-key", ""),
    )
}
