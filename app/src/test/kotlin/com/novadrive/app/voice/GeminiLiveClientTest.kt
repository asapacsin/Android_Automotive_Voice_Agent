package com.novadrive.app.voice

import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.NavigationState
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ErrorClass
import com.novadrive.ingress.realtime.VoiceProviderException
import com.novadrive.ingress.realtime.classifyVoiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

class GeminiLiveClientTest {
    private val server = MockWebServer()
    private val jobs = mutableListOf<Job>()
    private val clients = mutableListOf<GeminiLiveClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.disconnect() }
        jobs.forEach { it.cancel() }
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
        server.close()
    }

    /** The server side of one socket: records client messages, answers setup with setupComplete. */
    private class FakeGemini(private val ready: Boolean = true) : WebSocketListener() {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            if (ready && JSONObject(text).has("setup")) webSocket.send("""{"setupComplete":{}}""")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        fun send(json: String) = socket!!.send(json)
        fun messages(key: String) = received.map(::JSONObject).filter { it.has(key) }
    }

    private fun fake(ready: Boolean = true) = FakeGemini(ready).also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }

    private fun config() = GeminiApiConfig(
        settings = GeminiAppSettings(endpoint = server.url("/ws").toString().replaceFirst("http://", "ws://")),
        apiKey = KEY,
        instructions = "你是小诺。",
    )

    private fun client(graceMs: Long = 300) = GeminiLiveClient(
        http = OkHttpClient(),
        readyTimeoutMs = 3_000,
        requireTls = false,
        contextHint = { null },
        contextAwaitingAnswer = { false },
        correctionGraceMs = graceMs,
    ).also { clients += it }

    private fun collect(client: GeminiLiveClient): MutableList<DomainVoiceEvent> {
        val events = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
        jobs += CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            client.events().collect { events += it.payload }
        }
        return events
    }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition not met in ${timeoutMs}ms")
    }

    private fun content(body: String) = """{"serverContent":{$body}}"""
    private fun audio(data: String = "QUJD") =
        content(""""modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}}]}""")
    private fun input(text: String) = content(""""inputTranscription":{"text":"$text"}""")
    private fun output(text: String) = content(""""outputTranscription":{"text":"$text"}""")
    private val turnComplete = content(""""turnComplete":true""")
    private fun toolCall(id: String, name: String, args: String) =
        """{"toolCall":{"functionCalls":[{"id":"$id","name":"$name","args":$args}]}}"""

    private fun List<DomainVoiceEvent>.snapshot() = synchronized(this) { toList() }

    @Test
    fun handshakeSendsKeyInHeaderOnlyAndSetupFirstAndReportsReady() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals(KEY, request.getHeader("x-goog-api-key"))
        assertFalse(request.requestUrl.toString().contains(KEY))
        assertFalse(request.path!!.contains(KEY))
        waitUntil { fake.received.isNotEmpty() }
        assertTrue(JSONObject(fake.received[0]).has("setup"))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.SessionReady } }
        val ready = events.snapshot().filterIsInstance<DomainVoiceEvent.SessionReady>().single()
        assertTrue(ready.interruptResponse)
    }

    @Test
    fun audioFrameIsSentAsRealtimeInput() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        client.sendAudio(byteArrayOf(1, 2, 3, 4))
        waitUntil { fake.messages("realtimeInput").isNotEmpty() }
        val audio = fake.messages("realtimeInput").single().getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)), audio.getString("data"))
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
    }

    @Test
    fun toolRoundTripValidationAndDuplicate() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(toolCall("c1", "open_app", """{"app":"maps"}"""))
        fake.send(toolCall("c1b", "open_app", """{"app":"maps"}"""))
        fake.send(toolCall("c2", "open_app", """{"app":"nope"}"""))
        waitUntil { events.snapshot().filterIsInstance<DomainVoiceEvent.ToolCall>().size == 2 }
        waitUntil { fake.messages("toolResponse").size == 1 }
        val calls = events.snapshot().filterIsInstance<DomainVoiceEvent.ToolCall>()
        assertEquals(DomainVoiceEvent.ToolCall("c1", "open_app", mapOf("app" to "maps")), calls[0])
        assertEquals("APP_NOT_ALLOWED", calls[1].arguments["_validation_error"])
        val dup = fake.messages("toolResponse").single().getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1b", dup.getString("id"))
        assertEquals("duplicate_call_ignored", dup.getJSONObject("response").getString("status"))

        client.sendToolResult("c1", """{"ok":true,"tool":"open_app"}""")
        waitUntil { fake.messages("toolResponse").size == 2 }
        val result = fake.messages("toolResponse")[1].getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", result.getString("id"))
        assertEquals("open_app", result.getString("name"))
        assertTrue(result.getJSONObject("response").getBoolean("ok"))
    }

    private fun claimTurn(fake: FakeGemini) {
        fake.send(input("打开空调"))
        fake.send(audio())
        fake.send(output("已为您打开空调"))
        fake.send(turnComplete)
    }

    @Test
    fun falseClaimIsHeldAndItsCorrectionIsCancelledByALateToolCall() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        fake.send(toolCall("c9", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        Thread.sleep(700)
        assertTrue(events.snapshot().none { it is DomainVoiceEvent.AudioDelta })
        assertTrue(fake.messages("clientContent").isEmpty(), "correction must not be sent")
        assertEquals(1, events.snapshot().count { it is DomainVoiceEvent.ToolCall })
    }

    @Test
    fun longActionClaimIsNotReleasedByTheHoldBudgetBeforeTurnEnd() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        repeat(60) {
            fake.send(audio())
            fake.send(output("已为您打开空调。"))
        }
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertTrue(snapshot.none { it is DomainVoiceEvent.AudioDelta })
        assertTrue(snapshot.none { it is DomainVoiceEvent.AssistantTranscript })
    }

    @Test
    fun falseClaimWithoutToolCallSendsTheCorrectionAfterTheGrace() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(100)
        assertTrue(fake.messages("clientContent").isEmpty(), "correction is deferred, not immediate")
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        assertTrue(events.snapshot().none { it is DomainVoiceEvent.AudioDelta })
        val turn = fake.messages("clientContent").single().getJSONObject("clientContent")
        assertTrue(turn.getBoolean("turnComplete"))
    }

    @Test
    fun newDriverTurnCancelsTheDeferredCorrection() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        client.onLocalSpeechActivity(true)
        Thread.sleep(700)
        assertTrue(fake.messages("clientContent").isEmpty())
    }

    @Test
    fun replyThatClaimsNothingIsReleased() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.send(audio())
        fake.send(output("好的，请问还需要什么"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertTrue(snapshot.any { it is DomainVoiceEvent.AudioDelta })
        assertTrue(snapshot.any { it is DomainVoiceEvent.AssistantTranscript })
        assertTrue(snapshot.any { it is DomainVoiceEvent.AudioDone })
        assertEquals(DomainVoiceEvent.ResponseDone("completed"), snapshot.last { it is DomainVoiceEvent.ResponseDone })
        assertTrue(snapshot.none { it is DomainVoiceEvent.SpeechStarted || it is DomainVoiceEvent.SpeechStopped })
    }

    @Test
    fun interruptedEndsTheTurnAsCancelled() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(audio())
        fake.send(content(""""interrupted":true"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertTrue(snapshot.contains(DomainVoiceEvent.Interrupted("server_vad")))
        assertEquals(DomainVoiceEvent.ResponseDone("cancelled"), snapshot.single { it is DomainVoiceEvent.ResponseDone })
    }

    @Test
    fun thoughtPartsAreNeverEmittedAndBinaryFramesAreParsed() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.socket!!.send(content(""""modelTurn":{"parts":[{"text":"SECRET_THOUGHT","thought":true}]}""").encodeUtf8())
        fake.socket!!.send(audio("WFla").encodeUtf8())
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertTrue(snapshot.none { it.toString().contains("SECRET_THOUGHT") })
        assertTrue(snapshot.contains(DomainVoiceEvent.AudioDelta("WFla")))
    }

    @Test
    fun quotaCloseIsRateLimitAndPolicyCloseIsAuth() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        fake.socket!!.close(1011, "You exceeded your current quota")
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.Error } }
        val error = events.snapshot().filterIsInstance<DomainVoiceEvent.Error>().first()
        assertEquals(ErrorClass.RATE_LIMIT, classifyVoiceError(error.code))
        assertFalse(error.message.contains("quota"))
        waitUntil { events.snapshot().contains(DomainVoiceEvent.Closed) }

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.close(1008, "API key not valid") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val failure = assertThrows<VoiceProviderException> { runBlocking { client.connect(config()) } }
        assertEquals(ErrorClass.AUTH, classifyVoiceError(failure.code))
        assertNoKey(failure)
    }

    @Test
    fun resumptionHandleIsSentInTheNextSetup() = runBlocking {
        val first = fake()
        val client = client()
        client.connect(config())
        assertFalse(JSONObject(first.received[0]).getJSONObject("setup").getJSONObject("sessionResumption").has("handle"))
        first.send("""{"sessionResumptionUpdate":{"newHandle":"resume-h1","resumable":true}}""")
        Thread.sleep(200)
        client.disconnect()
        val second = fake()
        client.connect(config())
        waitUntil { second.received.isNotEmpty() }
        assertEquals("resume-h1", JSONObject(second.received[0]).getJSONObject("setup").getJSONObject("sessionResumption").getString("handle"))
    }

    @Test
    fun userTranscriptPrecedesResponseStarted() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        fake.send(input("你好"))
        fake.send(audio())
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        val transcript = snapshot.indexOf(DomainVoiceEvent.UserTranscript("你好", final = true))
        val started = snapshot.indexOf(DomainVoiceEvent.ResponseStarted)
        assertTrue(transcript >= 0 && started > transcript, "transcript=$transcript started=$started")
    }

    @Test
    fun keyNeverAppearsInFailures() = runBlocking {
        fake(ready = false)
        val slow = GeminiLiveClient(OkHttpClient(), readyTimeoutMs = 300, requireTls = false, contextHint = { null }).also { clients += it }
        val timeout = assertThrows<VoiceProviderException> { runBlocking { slow.connect(config()) } }
        assertEquals(ErrorClass.RETRYABLE, classifyVoiceError(timeout.code))
        assertNoKey(timeout)

        server.enqueue(MockResponse().setResponseCode(401))
        val auth = assertThrows<VoiceProviderException> { runBlocking { client().connect(config()) } }
        assertEquals(ErrorClass.AUTH, classifyVoiceError(auth.code))
        assertNoKey(auth)

        val unconnected = client()
        val closed = assertThrows<VoiceProviderException> { unconnected.sendUserText("x") }
        assertNoKey(closed)
        assertNotNull(GeminiApiConfig(GeminiAppSettings(), KEY, "p").toString().takeIf { !it.contains(KEY) })
    }

    private fun assertNoKey(failure: Throwable) {
        generateSequence(failure) { it.cause }.forEach {
            assertFalse(it.message.orEmpty().contains(KEY), "key leaked in ${it::class.simpleName}")
            assertFalse(it.toString().contains(KEY))
        }
    }

    private companion object {
        const val KEY = "test-gemini-key-7f3a"
    }
}
