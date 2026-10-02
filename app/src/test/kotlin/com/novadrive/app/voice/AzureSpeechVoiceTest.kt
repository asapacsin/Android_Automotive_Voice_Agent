package com.novadrive.app.voice

import com.novadrive.app.SpeakingStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

class AzureSpeechVoiceTest {
    private val server = MockWebServer()
    private val key = "secret-key-123"
    private val config = AzureSpeechConfig(key = key, region = "eastasia")

    @AfterEach
    fun close() = server.close()

    private fun voice() = AzureSpeechVoice(config, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun pcm(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    private fun collect(text: String = "你好", style: SpeakingStyle = SpeakingStyle.DEFAULT): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { voice().synthesize(text, style) { out.write(it) } }
        return out.toByteArray()
    }

    @Test
    fun requestShape() {
        server.enqueue(MockResponse().setBody(Buffer().write(pcm(10))))
        collect()
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/cognitiveservices/v1", r.path)
        assertEquals(key, r.getHeader("Ocp-Apim-Subscription-Key"))
        assertEquals("application/ssml+xml", r.getHeader("Content-Type"), "no charset parameter appended")
        assertEquals("raw-24khz-16bit-mono-pcm", r.getHeader("X-Microsoft-OutputFormat"))
        assertEquals("NovaDrive", r.getHeader("User-Agent"))
        val body = r.body.readUtf8()
        assertEquals(azureSsml("你好", "zh-CN-XiaoyiNeural", SpeakingStyle.DEFAULT), body)
        assertTrue(body.contains("<voice name=\"zh-CN-XiaoyiNeural\">"))
    }

    @Test
    fun ssmlPerStyle() {
        assertFalse(azureSsml("a", "v", SpeakingStyle.DEFAULT).contains("express-as"))
        val expected = mapOf(
            SpeakingStyle.SWEET to ("affectionate" to "1.2"),
            SpeakingStyle.TSUNDERE to ("disgruntled" to "0.6"),
            SpeakingStyle.GENTLE to ("gentle" to "1.0"),
            SpeakingStyle.LIVELY to ("cheerful" to "1.2"),
        )
        for ((style, pair) in expected) {
            assertEquals(pair, azureStyleFor(style))
            assertTrue(
                azureSsml("a", "v", style).contains(
                    "<voice name=\"v\"><mstts:express-as style=\"${pair.first}\" styledegree=\"${pair.second}\">a</mstts:express-as></voice>",
                ),
            )
        }
        assertEquals(null, azureStyleFor(SpeakingStyle.DEFAULT))
        assertEquals(
            "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"https://www.w3.org/2001/mstts\" xml:lang=\"zh-CN\"><voice name=\"v\">a</voice></speak>",
            azureSsml("a", "v", SpeakingStyle.DEFAULT),
        )
    }

    @Test
    fun ssmlEscapes() {
        val s = azureSsml("<&>\"'", "v<\"", SpeakingStyle.DEFAULT)
        assertTrue(s.contains("&lt;&amp;&gt;&quot;&apos;"))
        assertTrue(s.contains("name=\"v&lt;&quot;\""))
    }

    @Test
    fun streamsEvenLengthByteExact() {
        val data = pcm(20_000)
        server.enqueue(MockResponse().setBody(Buffer().write(data)).throttleBody(3001, 0, java.util.concurrent.TimeUnit.MILLISECONDS))
        val chunks = mutableListOf<ByteArray>()
        runBlocking { voice().synthesize("x", SpeakingStyle.DEFAULT) { chunks.add(it) } }
        assertTrue(chunks.size > 1)
        chunks.forEach { assertEquals(0, it.size % 2) }
        assertArrayEquals(data, chunks.fold(ByteArray(0)) { a, b -> a + b })
    }

    /** Odd total: every whole sample is delivered in order; the final odd byte is dropped. */
    @Test
    fun oddLengthCarriesAcrossChunksAndDropsLastOddByte() {
        val data = pcm(10_001)
        server.enqueue(MockResponse().setBody(Buffer().write(data)).throttleBody(1001, 0, java.util.concurrent.TimeUnit.MILLISECONDS))
        val chunks = mutableListOf<ByteArray>()
        runBlocking { voice().synthesize("x", SpeakingStyle.DEFAULT) { chunks.add(it) } }
        chunks.forEach { assertEquals(0, it.size % 2) }
        assertArrayEquals(data.copyOf(10_000), chunks.fold(ByteArray(0)) { a, b -> a + b })
    }

    private fun errorCode(response: MockResponse): AssistantVoiceException {
        server.enqueue(response)
        val e = assertThrows<AssistantVoiceException> { collect(text = "秘密文本") }
        assertFalse(e.message!!.contains(key))
        assertFalse(e.message!!.contains("秘密文本"))
        return e
    }

    @Test
    fun errorMapping() {
        assertEquals("AZURE_TTS_AUTH", errorCode(MockResponse().setResponseCode(401)).code)
        assertEquals("AZURE_TTS_AUTH", errorCode(MockResponse().setResponseCode(403)).code)
        assertEquals("AZURE_TTS_THROTTLED", errorCode(MockResponse().setResponseCode(429)).code)
        assertEquals("AZURE_TTS_BAD_REQUEST", errorCode(MockResponse().setResponseCode(400)).code)
        assertEquals("AZURE_TTS_HTTP_500", errorCode(MockResponse().setResponseCode(500)).code)
        assertEquals("AZURE_TTS_EMPTY", errorCode(MockResponse().setResponseCode(200)).code)
        assertEquals(
            "AZURE_TTS_NETWORK",
            errorCode(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)).code,
        )
    }

    @Test
    fun configToStringHidesKey() {
        assertFalse(config.toString().contains(key))
    }

    @Test
    fun blankTextMakesNoRequest() {
        assertEquals(0, collect(text = "  ").size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellingMidStreamStopsTheReadPromptly() = runBlocking {
        // 48 000 bytes trickled at 4 800 bytes per second: a full read would take ~10 s.
        server.enqueue(MockResponse().setBody(Buffer().write(pcm(48_000))).throttleBody(4_800, 1, java.util.concurrent.TimeUnit.SECONDS))
        val firstChunk = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            voice().synthesize("很长的一句话", SpeakingStyle.DEFAULT) { firstChunk.complete(Unit) }
        }
        kotlinx.coroutines.withTimeout(5_000) { firstChunk.await() }
        val started = System.nanoTime()
        job.cancel()
        kotlinx.coroutines.withTimeout(2_000) { job.join() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "cancellation must not wait for the read timeout")
        assertTrue(job.isCancelled)
    }
}

