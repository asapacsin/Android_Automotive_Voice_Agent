package com.novadrive.app.vision

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Proves request shape and response mapping against a local server. Not Baidu connectivity. */
class QianfanVisionClientTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client(bearer: String? = "placeholder-vision-key") =
        QianfanVisionClient(
            auth = { bearer },
            model = { "test-vl-model" },
            endpoint = server.url("/v2/chat/completions").toString(),
            requireTls = false,
        )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    @Test
    fun sendsModelQuestionAndImageAndParsesAnswer() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":" 前方有一辆白色汽车。 "}}]}"""))
        val result = client().ask("前面有什么", jpeg)
        assertEquals(VisionResult.Answer("前方有一辆白色汽车。"), result)

        val request = server.takeRequest()
        assertEquals("Bearer placeholder-vision-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-vl-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("前面有什么", parts.getJSONObject(0).getString("text"))
        val url = parts.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg), url)
    }

    @Test
    fun missingCredentialSendsNothing() = runBlocking {
        val result = client(bearer = null).ask("前面有什么", jpeg)
        assertTrue(result is VisionResult.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun authFailuresAreDistinguished() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_iam_token","message":"IAM authentication failed"}}"""))
        assertTrue(client().ask("q", jpeg) is VisionResult.AuthFailed)
        server.enqueue(MockResponse().setBody("""{"error_code":110,"error_msg":"Access token invalid or no longer valid"}"""))
        assertTrue(client().ask("q", jpeg) is VisionResult.AuthFailed)
    }

    @Test
    fun otherFailuresAreReportedNotAnswered() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"server_error","message":"busy"}}"""))
        assertTrue(client().ask("q", jpeg) is VisionResult.Failed)
        server.enqueue(MockResponse().setBody("""{"error":{"code":"model_not_found","message":"no such model"}}"""))
        assertTrue(client().ask("q", jpeg) is VisionResult.Failed)
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"   "}}]}"""))
        assertTrue(client().ask("q", jpeg) is VisionResult.Failed)
        server.enqueue(MockResponse().setBody("not json"))
        assertTrue(client().ask("q", jpeg) is VisionResult.Failed)
    }

    @Test
    fun longAnswersAreCapped() = runBlocking {
        val long = "车".repeat(1000)
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", long)))).toString()))
        val result = client().ask("q", jpeg) as VisionResult.Answer
        assertEquals(QianfanVisionClient.MAX_ANSWER_CHARS, result.text.length)
    }

    @Test
    fun plainHttpIsRefusedWhenTlsRequired() = runBlocking {
        val strict = QianfanVisionClient(auth = { "k" }, model = { "m" }, endpoint = server.url("/v2").toString())
        assertTrue(strict.ask("q", jpeg) is VisionResult.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun defaultEndpointIsHttpsQianfanV2() {
        assertTrue(QianfanVisionClient.DEFAULT_ENDPOINT.startsWith("https://qianfan.baidubce.com/v2/"))
    }
}
