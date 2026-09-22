package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackScopedVadTest {
    @Test
    fun playbackActiveRaisesVadThresholdOnConnectedSession() {
        val server = MockWebServer()
        server.start()
        val wsUrl = server.url("/v1/realtime").toString().replace("http://", "ws://")
        val config =
            BaiduApiConfig(
                settings = BaiduAppSettings(endpoint = wsUrl, model = BaiduFlexProtocol.MODEL),
                credentials = BaiduCredentials(apiKey = "test-key"),
            )
        val received = mutableListOf<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val type = JSONObject(text).optString("type")
                if (type == "session.update") {
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
            }
        }))
        val client = BaiduFlexClient(http = OkHttpClient(), requireTls = false)
        runBlocking { client.connect(config) }
        client.onPlaybackActiveChanged(true)
        val updates = received.map(::JSONObject).filter { it.getString("type") == "session.update" }
        assertTrue(updates.size >= 2)
        val playbackUpdate = updates.last().getJSONObject("session").getJSONObject("turn_detection")
        assertEquals(BaiduFlexProtocol.PLAYBACK_VAD_THRESHOLD, playbackUpdate.getDouble("threshold"), 0.001)
        client.onPlaybackActiveChanged(false)
        val restored = received.map(::JSONObject).filter { it.getString("type") == "session.update" }.last()
        assertEquals(
            BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD,
            restored.getJSONObject("session").getJSONObject("turn_detection").getDouble("threshold"),
            0.001,
        )
        server.shutdown()
    }
}
