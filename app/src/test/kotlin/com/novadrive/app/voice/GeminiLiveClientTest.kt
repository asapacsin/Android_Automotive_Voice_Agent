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
        fun messages(key: String) = synchronized(received) { received.toList() }.map(::JSONObject).filter { it.has(key) }
    }

    private fun fake(ready: Boolean = true) = FakeGemini(ready).also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }

    private fun config() = GeminiApiConfig(
        settings = GeminiAppSettings(consentAccepted = true, endpoint = server.url("/ws").toString().replaceFirst("http://", "ws://")),
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
    fun transcriptChunksDoNotCountTowardTheHoldBudget() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        // 60 audio chunks + 60 transcript chunks: 120 messages, but only the 60 audio chunks are
        // held, so the 120-event hold budget is not exceeded and nothing is released before the end.
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
        val events = collect(client)
        first.send("""{"sessionResumptionUpdate":{"newHandle":"resume-h1","resumable":true}}""")
        first.send(input("你好"))
        first.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.UserTranscript } }
        // The core's reconnect: the server closes, then connect() again without disconnect().
        first.socket!!.close(1011, "internal")
        waitUntil { events.snapshot().contains(DomainVoiceEvent.Closed) }
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


    // ---- P4: settle the claim gate at generationComplete ----------------------------------

    private val generationComplete = content(""""generationComplete":true""")

    @Test
    fun cleanReplyIsReleasedAtGenerationCompleteBeforeTurnComplete() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.send(audio("Q0xFQU4="))
        fake.send(output("好的，请问还需要什么"))
        fake.send(generationComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.AudioDone } }
        val before = events.snapshot()
        assertTrue(before.contains(DomainVoiceEvent.AudioDelta("Q0xFQU4=")))
        assertTrue(before.any { it is DomainVoiceEvent.AssistantTranscript })
        assertTrue(before.none { it is DomainVoiceEvent.ResponseDone }, "turnComplete not yet delivered")
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val after = events.snapshot()
        assertEquals(1, after.count { it is DomainVoiceEvent.AudioDelta })
        assertEquals(1, after.count { it is DomainVoiceEvent.AudioDone })
        assertEquals(1, after.count { it is DomainVoiceEvent.AssistantTranscript })
        assertEquals(DomainVoiceEvent.ResponseDone("completed"), after.single { it is DomainVoiceEvent.ResponseDone })
    }

    @Test
    fun heldClaimIsStillDroppedAtGenerationComplete() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(audio())
        fake.send(output("已为您打开空调"))
        fake.send(generationComplete)
        Thread.sleep(250)
        assertTrue(events.snapshot().none { it is DomainVoiceEvent.AudioDelta || it is DomainVoiceEvent.AssistantTranscript })
        assertTrue(fake.messages("clientContent").isEmpty(), "no correction before turnComplete")
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        // The 300 ms grace is measured from turnComplete, not from the early settle.
        Thread.sleep(150)
        assertTrue(fake.messages("clientContent").isEmpty(), "grace runs from turnComplete")
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        Thread.sleep(400)
        assertEquals(1, fake.messages("clientContent").size)
        assertTrue(events.snapshot().none { it is DomainVoiceEvent.AudioDelta })
    }

    @Test
    fun anUnverifiedPlayMusicPlayingClaimIsNeverHeard() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("放梶浦由记的歌"))
        fake.send(toolCall("m1", "play_music", """{"artist":"梶浦由記"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        client.sendToolResult("m1", """{"ok":true,"tool":"play_music","status":"requested_unverified"}""")
        waitUntil { fake.messages("toolResponse").isNotEmpty() }
        fake.send(audio("TVVTSUM="))
        fake.send(output("正在放《X》"))
        fake.send(generationComplete)
        fake.send(turnComplete)
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        Thread.sleep(300)
        assertEquals(1, fake.messages("clientContent").size, "exactly one correction")
        val snapshot = events.snapshot()
        assertTrue(snapshot.none { it == DomainVoiceEvent.AudioDelta("TVVTSUM=") })
        assertTrue(snapshot.none { it is DomainVoiceEvent.AssistantTranscript })
    }

    @Test
    fun droppedClaimAfterADispatchedCallIsCorrectedAtTurnCompleteNotBefore() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(toolCall("f1", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        client.sendToolResult("f1", result(ok = false))
        waitUntil { fake.messages("toolResponse").isNotEmpty() }
        fake.send(audio("RkFJTA=="))
        fake.send(output("已为您打开空调"))
        fake.send(generationComplete)
        Thread.sleep(250)
        assertTrue(fake.messages("clientContent").isEmpty(), "no correction between generationComplete and turnComplete")
        fake.send(turnComplete)
        // A call was dispatched this driver turn: the correction goes out at close, not after 60 s.
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        Thread.sleep(300)
        assertEquals(1, fake.messages("clientContent").size)
        assertTrue(events.snapshot().none { it == DomainVoiceEvent.AudioDelta("RkFJTA==") })
    }

    @Test
    fun aClaimTranscribedAfterSettleIsStillCorrectedAtTurnComplete() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 200)
        collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(audio("TEFURTI="))
        fake.send(generationComplete)
        fake.send(output("已为您打开空调"))
        Thread.sleep(150)
        assertTrue(fake.messages("clientContent").isEmpty(), "no correction before turnComplete")
        fake.send(turnComplete)
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        Thread.sleep(300)
        assertEquals(1, fake.messages("clientContent").size)
    }

    @Test
    fun audioAfterASettledCleanTurnIsDropped() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.send(audio("Q0xFQU4y"))
        fake.send(output("好的"))
        fake.send(generationComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.AudioDone } }
        fake.send(audio("TEFURQ=="))
        fake.send(output("还有"))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertTrue(snapshot.contains(DomainVoiceEvent.AudioDelta("Q0xFQU4y")))
        assertTrue(snapshot.none { it == DomainVoiceEvent.AudioDelta("TEFURQ==") })
    }

    @Test
    fun audioAfterASupersededSettleIsDropped() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(audio("SE9MRA=="))
        fake.send(output("已为您打开空调"))
        Thread.sleep(200)
        client.onLocalSpeechActivity(true)  // new onset while the held reply is open: superseded
        fake.send(generationComplete)
        fake.send(audio("TEFURTI="))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(100)
        val snapshot = events.snapshot()
        assertTrue(snapshot.none { it == DomainVoiceEvent.AudioDelta("TEFURTI=") })
        assertTrue(snapshot.none { it == DomainVoiceEvent.AudioDelta("SE9MRA==") })
    }

    @Test
    fun withoutGenerationCompleteTheCleanReplySettlesOnceAtTurnComplete() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.send(audio("Tk9HQw=="))
        fake.send(output("好的"))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertEquals(1, snapshot.count { it == DomainVoiceEvent.AudioDelta("Tk9HQw==") })
        assertEquals(1, snapshot.count { it is DomainVoiceEvent.AudioDone })
        assertEquals(1, snapshot.count { it is DomainVoiceEvent.AssistantTranscript })
    }

    @Test
    fun lateToolCallAfterGenerationCompleteDispatchesOnceAndCancelsTheCorrection() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(audio())
        fake.send(output("已为您打开空调"))
        fake.send(generationComplete)
        fake.send(toolCall("late1", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(700)
        assertEquals(1, events.snapshot().count { it is DomainVoiceEvent.ToolCall && it.callId == "late1" })
        assertTrue(fake.messages("clientContent").isEmpty(), "late call must cancel the correction")
        // A claim response created before the result is judged at its end and dropped, never emitted ...
        fake.send(audio("RUFSTFk="))
        fake.send(output("空调已打开"))
        fake.send(generationComplete)
        fake.send(turnComplete)
        waitUntil { events.snapshot().count { it is DomainVoiceEvent.ResponseDone } == 2 }
        assertTrue(events.snapshot().none { it == DomainVoiceEvent.AudioDelta("RUFSTFk=") })
        // ... and the response answering the ok result is released.
        client.sendToolResult("late1", result(ok = true))
        waitUntil { fake.messages("toolResponse").isNotEmpty() }
        fake.send(audio("UkVTVUxU"))
        fake.send(output("空调已打开"))
        fake.send(generationComplete)
        waitUntil { events.snapshot().contains(DomainVoiceEvent.AudioDelta("UkVTVUxU")) }
        fake.send(turnComplete)
        waitUntil { events.snapshot().count { it is DomainVoiceEvent.ResponseDone } == 3 }
        assertTrue(events.snapshot().none { it == DomainVoiceEvent.AudioDelta("RUFSTFk=") })
    }

    @Test
    fun interruptedAfterGenerationCompleteEndsOnceAsCancelled() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.send(audio("SU5UUg=="))
        fake.send(output("好的"))
        fake.send(generationComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.AudioDone } }
        fake.send(content(""""interrupted":true"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        fake.send(turnComplete)
        Thread.sleep(200)
        val snapshot = events.snapshot()
        assertEquals(DomainVoiceEvent.ResponseDone("cancelled"), snapshot.single { it is DomainVoiceEvent.ResponseDone })
        assertEquals(1, snapshot.count { it is DomainVoiceEvent.AudioDone })
        assertEquals(1, snapshot.count { it == DomainVoiceEvent.AudioDelta("SU5UUg==") })
    }

    // ---- G2.2 corrections ------------------------------------------------------------------

    private fun result(ok: Boolean, tool: String = "control_climate") =
        if (ok) """{"ok":true,"tool":"$tool"}""" else """{"ok":false,"tool":"$tool","error":"CLIMATE_UNAVAILABLE"}"""

    private fun provenTurn(fake: FakeGemini, client: GeminiLiveClient, events: List<DomainVoiceEvent>, id: String = "p1") {
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(toolCall(id, "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall && it.callId == id } }
        client.sendToolResult(id, result(ok = true))
        fake.send(audio("UFJPVkVO"))
        fake.send(output("空调已打开"))
        fake.send(turnComplete)
        waitUntil { events.snapshot().count { it is DomainVoiceEvent.ResponseDone } == 1 }
    }

    @Test
    fun strayTranscriptAfterTurnCompleteDoesNotLetTheNextClaimBypassTheGate() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        provenTurn(fake, client, events)
        assertTrue(events.snapshot().contains(DomainVoiceEvent.AudioDelta("UFJPVkVO")))
        fake.send(output("。"))
        Thread.sleep(300)
        client.onLocalSpeechActivity(true)
        fake.send(audio("Q0xBSU0="))
        fake.send(output("已为您把空调调到二十度"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().count { it is DomainVoiceEvent.ResponseDone } == 2 }
        Thread.sleep(300)
        assertFalse(events.snapshot().contains(DomainVoiceEvent.AudioDelta("Q0xBSU0=")), "unproven claim must not be heard")
    }

    @Test
    fun strayTranscriptWhileThePreviousTurnHoldsDoesNotSwallowTheNextChatReply() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(toolCall("h1", "control_climate", """{"action":"power_on"}"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        // The result is still outstanding: the previous driver turn holds whatever comes next.
        fake.send(output("我正在"))
        Thread.sleep(300)
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        fake.send(audio("Q0hBVA=="))
        fake.send(output("好的，请问还需要什么"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().contains(DomainVoiceEvent.AudioDelta("Q0hBVA==")) }
    }

    @Test
    fun newOnsetClosesAnEmptyOpenTurnWithoutSwallowingTheNextReply() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        // An invalid call opens a turn but dispatches nothing: the turn has no audio and no call.
        fake.send("""{"toolCall":{"functionCalls":[{"id":"bad id!","name":"x","args":{}}]}}""")
        waitUntil { events.snapshot().contains(DomainVoiceEvent.ResponseStarted) }
        client.onLocalSpeechActivity(true)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        fake.send(input("你好"))
        fake.send(audio("TkVYVA=="))
        fake.send(output("好的，请问还需要什么"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().contains(DomainVoiceEvent.AudioDelta("TkVYVA==")) }
    }

    @Test
    fun releaseBurstIsNotDroppedForASlowCollector() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
        jobs += CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            client.events().collect { kotlinx.coroutines.delay(3); events += it.payload }
        }
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("你好"))
        val parts = (1..130).joinToString(",") { """{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"QUJD$it"}}""" }
        fake.send(content(""""modelTurn":{"parts":[$parts]}"""))
        fake.send(output("好的，请问还需要什么"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil(5_000) { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val snapshot = events.snapshot()
        assertEquals(130, snapshot.count { it is DomainVoiceEvent.AudioDelta })
        assertTrue(snapshot.contains(DomainVoiceEvent.AudioDone))
    }

    @Test
    fun fillerClaimThenCallInTheNextTurnWithOkResultReleasesTheResultAudio() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        fake.send(toolCall("n1", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        client.sendToolResult("n1", result(ok = true))
        fake.send(audio("UkVTVUxU"))
        fake.send(output("空调已打开"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().contains(DomainVoiceEvent.AudioDelta("UkVTVUxU")) }
        assertFalse(events.snapshot().contains(DomainVoiceEvent.AudioDelta("QUJD")), "the filler claim stays dropped")
        assertTrue(fake.messages("clientContent").isEmpty(), "no correction once the call arrived")
    }

    @Test
    fun fillerClaimThenCallWithFailedResultKeepsTheClaimSilentAndCorrectsAtOnce() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        fake.send(toolCall("f1", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        client.sendToolResult("f1", result(ok = false))
        fake.send(audio("RkFJTA=="))
        fake.send(output("已为您打开空调"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().count { it is DomainVoiceEvent.ResponseDone } == 2 }
        // A call was dispatched in this driver turn: the correction cannot race one, so it is not
        // deferred by the 60 s grace.
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        assertFalse(events.snapshot().contains(DomainVoiceEvent.AudioDelta("RkFJTA==")), "a failed action's claim is not heard")
    }

    @Test
    fun correctionWithNoCallDispatchedIsDeferred() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 1_500)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(700)
        assertTrue(fake.messages("clientContent").isEmpty(), "deferred while a call may still come")
        waitUntil { fake.messages("clientContent").isNotEmpty() }
    }

    @Test
    fun pendingCorrectionIsNotSentAfterDisconnect() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        client.disconnect()
        Thread.sleep(1_200)
        assertTrue(fake.messages("clientContent").isEmpty())
    }

    @Test
    fun pendingCorrectionIsNotSentWhileListeningIsSuspended() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 300)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        client.discardPendingAudio()
        Thread.sleep(1_200)
        assertTrue(fake.messages("clientContent").isEmpty())
    }

    @Test
    fun expiredResumptionHandleIsClearedAndTheFailureIsRetryable() = runBlocking {
        val first = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        first.send("""{"sessionResumptionUpdate":{"newHandle":"resume-old","resumable":true}}""")
        first.send(input("你好"))
        first.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.UserTranscript } }
        first.socket!!.close(1011, "internal")
        waitUntil { events.snapshot().contains(DomainVoiceEvent.Closed) }
        val rejected = object : WebSocketListener() {
            val setups = Collections.synchronizedList(mutableListOf<String>())
            override fun onMessage(webSocket: WebSocket, text: String) { setups += text; webSocket.close(1007, "invalid handle") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(rejected))
        val failure = assertThrows<VoiceProviderException> { runBlocking { client.connect(config()) } }
        assertEquals(ErrorClass.RETRYABLE, classifyVoiceError(failure.code))
        assertTrue(JSONObject(rejected.setups.single()).getJSONObject("setup").getJSONObject("sessionResumption").has("handle"))
        val third = fake()
        client.connect(config())
        waitUntil { third.received.isNotEmpty() }
        assertFalse(JSONObject(third.received[0]).getJSONObject("setup").getJSONObject("sessionResumption").has("handle"))
    }

    @Test
    fun malformedSetupWithoutAHandleStaysMalformed() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.close(1007, "Unknown name") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val failure = assertThrows<VoiceProviderException> { runBlocking { client().connect(config()) } }
        assertEquals(ErrorClass.MALFORMED, classifyVoiceError(failure.code))
    }

    @Test
    fun disconnectForgetsTheResumptionHandle() = runBlocking {
        val first = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        first.send("""{"sessionResumptionUpdate":{"newHandle":"resume-h2","resumable":true}}""")
        first.send(input("你好"))
        first.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.UserTranscript } }
        client.disconnect()
        val second = fake()
        client.connect(config())
        waitUntil { second.received.isNotEmpty() }
        assertFalse(JSONObject(second.received[0]).getJSONObject("setup").getJSONObject("sessionResumption").has("handle"))
    }

    private fun voiceActivity(type: String) = """{"voiceActivity":{"type":"$type","audioOffset":"0.360s"}}"""

    @Test
    fun activityStartWithoutLocalOnsetOpensTheDriverTurnBeforeTheReply() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        provenTurn(fake, client, events)
        // The uplink gate missed the onset; the server's ACTIVITY_START opens the driver turn, so
        // the earlier proof does not release this turn's claim.
        fake.send(voiceActivity("ACTIVITY_START"))
        fake.send(voiceActivity("ACTIVITY_END"))
        fake.send(audio("Q0xBSU0="))
        fake.send(output("已为您把空调调到二十度"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().count { it is DomainVoiceEvent.ResponseDone } == 2 }
        Thread.sleep(300)
        val snapshot = events.snapshot()
        assertFalse(snapshot.contains(DomainVoiceEvent.AudioDelta("Q0xBSU0=")))
        assertTrue(snapshot.none { it is DomainVoiceEvent.SpeechStarted || it is DomainVoiceEvent.SpeechStopped })
    }

    @Test
    fun activityStartAfterLocalOnsetDoesNotOpenASecondDriverTurn() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 60_000)
        val events = collect(client)
        client.connect(config())
        client.onLocalSpeechActivity(true)
        fake.send(input("打开空调"))
        fake.send(toolCall("v1", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        // A second driver turn here would cancel the first and make its proof useless.
        fake.send(voiceActivity("ACTIVITY_START"))
        Thread.sleep(200)
        client.sendToolResult("v1", result(ok = true))
        fake.send(audio("RE9ORQ=="))
        fake.send(output("空调已打开"))
        fake.send(content(""""generationComplete":true"""))
        fake.send(turnComplete)
        waitUntil { events.snapshot().contains(DomainVoiceEvent.AudioDelta("RE9ORQ==")) }
        assertTrue(events.snapshot().none { it is DomainVoiceEvent.SpeechStarted })
    }

    private fun assertNoKey(failure: Throwable) {
        generateSequence(failure) { it.cause }.forEach {
            assertFalse(it.message.orEmpty().contains(KEY), "key leaked in ${it::class.simpleName}")
            assertFalse(it.toString().contains(KEY))
        }
    }

    // ---- SPEC-018 correlation contract -----------------------------------------------------

    private fun promptEvents(events: List<DomainVoiceEvent>) =
        events.snapshot().filterIsInstance<DomainVoiceEvent.AppPromptTurn>()

    private fun suspended(client: GeminiLiveClient): Boolean =
        GeminiLiveClient::class.java.getDeclaredField("listeningSuspended").let { it.isAccessible = true; it.getBoolean(client) }

    @Test
    fun sendPromptFailsWhenNotConnected() {
        val client = client()
        assertFalse(client.sendPrompt("前方左转", "p1"))
    }

    @Test
    fun sendPromptSendsATextTurnNowAndLeavesListeningSuspended() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        client.discardPendingAudio()
        assertTrue(client.sendPrompt("前方左转", "p1"))
        waitUntil { fake.messages("clientContent").isNotEmpty() }
        val turn = fake.messages("clientContent").single().getJSONObject("clientContent")
        assertTrue(turn.getBoolean("turnComplete"))
        assertTrue(turn.toString().contains("前方左转"))
        assertTrue(suspended(client), "sendPrompt must not clear listeningSuspended")
    }

    @Test
    fun secondPromptWhileOneIsArmedOrOpenIsRefused() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        assertFalse(client.sendPrompt("前方右转", "p2"), "armed")
        fake.send(audio())
        waitUntil { promptEvents(events).isNotEmpty() }
        assertFalse(client.sendPrompt("前方右转", "p2"), "open")
        fake.send(turnComplete)
        waitUntil { promptEvents(events).size == 2 }
        assertTrue(client.sendPrompt("前方右转", "p3"), "free again after COMPLETED")
    }

    @Test
    fun openedPrecedesTheFirstAudioAndCompletedMarksTurnEnd() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        fake.send(audio())
        fake.send(output("前方左转"))
        fake.send(audio())
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        val seq = events.snapshot().filter {
            it is DomainVoiceEvent.AppPromptTurn || it is DomainVoiceEvent.AudioDelta || it is DomainVoiceEvent.ResponseDone
        }
        assertEquals(DomainVoiceEvent.AppPromptTurn("p1", DomainVoiceEvent.AppPromptTurn.Phase.OPENED), seq[0])
        assertTrue(seq[1] is DomainVoiceEvent.AudioDelta && seq[2] is DomainVoiceEvent.AudioDelta)
        assertEquals(DomainVoiceEvent.AppPromptTurn("p1", DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED), seq[3])
        assertEquals(DomainVoiceEvent.ResponseDone("completed"), seq[4])
    }

    @Test
    fun interruptedGuidanceTurnIsVoided() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        fake.send(audio())
        fake.send(content(""""interrupted":true"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        assertEquals(
            listOf(DomainVoiceEvent.AppPromptTurn.Phase.OPENED, DomainVoiceEvent.AppPromptTurn.Phase.VOIDED),
            promptEvents(events).map { it.phase },
        )
    }

    @Test
    fun closeVoidsAnArmedPrompt() = runBlocking {
        fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        client.disconnect()
        waitUntil { promptEvents(events).isNotEmpty() }
        assertEquals(listOf(DomainVoiceEvent.AppPromptTurn("p1", DomainVoiceEvent.AppPromptTurn.Phase.VOIDED)), promptEvents(events))
    }

    @Test
    fun serverCloseVoidsAnOpenGuidanceTurn() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        fake.send(audio())
        waitUntil { promptEvents(events).isNotEmpty() }
        fake.socket!!.close(1011, "internal")
        waitUntil { promptEvents(events).size == 2 }
        assertEquals(DomainVoiceEvent.AppPromptTurn.Phase.VOIDED, promptEvents(events)[1].phase)
    }

    @Test
    fun driverOnsetBeforeTheResponseOpensVoidsThePromptAndTheReplyIsADriverTurn() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        client.onLocalSpeechActivity(true)
        waitUntil { promptEvents(events).isNotEmpty() }
        assertEquals(listOf(DomainVoiceEvent.AppPromptTurn("p1", DomainVoiceEvent.AppPromptTurn.Phase.VOIDED)), promptEvents(events))
        // The next reply is the driver's, judged as usual: an unproven claim is held.
        claimTurn(fake)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        assertEquals(1, promptEvents(events).size)
        assertTrue(events.snapshot().none { it is DomainVoiceEvent.AudioDelta })
    }

    @Test
    fun toolCallInsideAGuidanceTurnIsRejectedAsNotADriverTurn() = runBlocking {
        val fake = fake()
        val client = client()
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("前方左转", "p1"))
        fake.send(audio())
        fake.send(toolCall("g1", "control_climate", """{"action":"power_on"}"""))
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ToolCall } }
        val call = events.snapshot().filterIsInstance<DomainVoiceEvent.ToolCall>().single()
        assertEquals("g1", call.callId)
        assertEquals(mapOf("_validation_error" to GeminiPromptTurn.NOT_A_DRIVER_TURN), call.arguments)
        client.sendToolResult("g1", """{"ok":false,"error":"NOT_A_DRIVER_TURN"}""")
        waitUntil { fake.messages("toolResponse").isNotEmpty() }
        val response = fake.messages("toolResponse").single().getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("control_climate", response.getString("name"))
    }

    @Test
    fun guidanceReplyIsNotJudgedAsADriverClaim() = runBlocking {
        val fake = fake()
        val client = client(graceMs = 100)
        val events = collect(client)
        client.connect(config())
        assertTrue(client.sendPrompt("已为您打开空调", "p1"))
        fake.send(audio())
        fake.send(output("已为您打开空调"))
        fake.send(turnComplete)
        waitUntil { events.snapshot().any { it is DomainVoiceEvent.ResponseDone } }
        // Not held (audio emitted), and no claim correction follows.
        assertTrue(events.snapshot().any { it is DomainVoiceEvent.AudioDelta })
        assertTrue(events.snapshot().any { it is DomainVoiceEvent.AssistantTranscript })
        Thread.sleep(400)
        assertEquals(1, fake.messages("clientContent").size, "only the prompt itself")
    }

    private companion object {
        const val KEY = "test-gemini-key-7f3a"
    }
}
