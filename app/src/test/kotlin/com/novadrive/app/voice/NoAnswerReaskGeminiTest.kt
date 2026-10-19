package com.novadrive.app.voice

import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.ingress.realtime.DomainVoiceEvent
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
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * P45 Unit 2 through the real [GeminiLiveClient] on a scripted socket: a driver turn that gets no
 * answer is re-asked once with a silent clientContent turn (turnComplete:true), and given up once.
 */
class NoAnswerReaskGeminiTest {
    private val server = MockWebServer()
    private val jobs = mutableListOf<Job>()
    private val clients = mutableListOf<GeminiLiveClient>()
    private val lines = Collections.synchronizedList(mutableListOf<String>())
    private val previousSink = com.novadrive.app.DebugVoiceLog.sink
    @Volatile private var segment: SpeechUplinkGate.Segment? = null

    init { com.novadrive.app.DebugVoiceLog.sink = { lines += it } }

    @AfterEach
    fun tearDown() {
        clients.forEach { it.disconnect() }
        jobs.forEach { it.cancel() }
        com.novadrive.app.DebugVoiceLog.sink = previousSink
        server.close()
    }

    private class FakeGemini : WebSocketListener() {
        val received: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var socket: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { socket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            if (JSONObject(text).has("setup")) webSocket.send("""{"setupComplete":{}}""")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        fun send(json: String) = socket!!.send(json)
        fun reasks() = synchronized(received) { received.toList() }.map(::JSONObject).filter { it.has("clientContent") }
            .map { it.getJSONObject("clientContent") }
            .filter { it.getJSONArray("turns").getJSONObject(0).getJSONArray("parts").getJSONObject(0).optString("text") == REASK }
    }

    private fun fake() = FakeGemini().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }

    private fun config() = GeminiApiConfig(
        settings = GeminiAppSettings(consentAccepted = true, endpoint = server.url("/ws").toString().replaceFirst("http://", "ws://")),
        apiKey = "placeholder-gemini-key",
        instructions = "你是小诺。",
    )

    private fun client(deadlineMs: Long = 300, giveUpMs: Long = 60_000, speechText: Boolean = false) = GeminiLiveClient(
        http = OkHttpClient(), readyTimeoutMs = 3_000, requireTls = false, contextHint = { null },
        lastAudioSegment = { segment }, contextAwaitingAnswer = { false }, correctionGraceMs = 300, speechTextEvents = speechText,
    ).also { it.noAnswer.deadlineMs = deadlineMs; it.noAnswer.giveUpMs = giveUpMs; clients += it }

    private fun collect(client: GeminiLiveClient): MutableList<DomainVoiceEvent> {
        val events = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
        jobs += CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) { client.events().collect { events += it.payload } }
        return events
    }

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(10) }
        assertTrue(condition(), "condition not met in ${timeoutMs}ms; $lines")
    }

    private fun log(prefix: String) = synchronized(lines) { lines.filter { it.startsWith(prefix) } }
    private fun content(body: String) = """{"serverContent":{$body}}"""
    private fun audio() = content(""""modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"QUJD"}}]}""")
    private fun input(text: String) = content(""""inputTranscription":{"text":"$text"}""")
    private fun output(text: String) = content(""""outputTranscription":{"text":"$text"}""")
    private val turnComplete = content(""""turnComplete":true""")
    private val generationComplete = content(""""generationComplete":true""")
    private val activityEnd = """{"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"0.360s"}}"""

    /** The driver's onset (local) and the server's end of speech, then nothing. */
    private fun driverTurn(fake: FakeGemini, client: GeminiLiveClient) {
        client.onLocalSpeechActivity(true)
        fake.send(activityEnd)
    }

    @Test
    fun theDefaultDeadlineIsThreeSecondsAndTheGiveUpFive() {
        val policy = GeminiLiveClient(contextHint = { null }).noAnswer
        assertEquals(3_000, policy.deadlineMs)
        assertEquals(5_000, policy.giveUpMs)
    }

    @Test
    fun activityEndThenSilenceGetsExactlyOneSilentReask() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { fake.reasks().size == 1 }
        Thread.sleep(600)
        assertEquals(1, fake.reasks().size, "once per utterance")
        assertTrue(fake.reasks().single().getBoolean("turnComplete"))
        assertEquals(1, log("gemini_reask reason=deadline").size)
    }

    @Test
    fun anEmptyCloseOfATranscribedTurnIsReaskedAtOnceAndOnlyOnce() = runBlocking {
        val fake = fake()
        val client = client(deadlineMs = 60_000)
        client.connect(config())
        driverTurn(fake, client)
        fake.send(input("调到二十四度"))
        fake.send(turnComplete)
        waitUntil { fake.reasks().size == 1 }
        // The re-ask also closes empty: no second one.
        fake.send(input("调到二十四度"))
        fake.send(turnComplete)
        Thread.sleep(400)
        assertEquals(1, fake.reasks().size)
        assertEquals(1, log("gemini_reask reason=empty_close").size)
        assertTrue(synchronized(lines) { lines.none { "二十四" in it } }, "no transcript text in a log (I-8)")
    }

    @Test
    fun noReaskWhenTheDriverSpeaksAgain() = runBlocking {
        val fake = fake()
        val client = client(deadlineMs = 400)
        client.connect(config())
        driverTurn(fake, client)
        Thread.sleep(150)
        client.onLocalSpeechActivity(true)
        Thread.sleep(700)
        assertEquals(0, fake.reasks().size)
    }

    @Test
    fun noReaskAfterAnAppHandledUtterance() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { log("gemini_voice_activity type=ACTIVITY_END").isNotEmpty() }
        client.markClientCancelled()
        Thread.sleep(700)
        assertEquals(0, fake.reasks().size)
    }

    @Test
    fun noReaskWhileListeningIsSuspended() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        driverTurn(fake, client)
        client.discardPendingAudio()
        waitUntil { log("gemini_reask_dropped reason=suspended").isNotEmpty() }
        assertEquals(0, fake.reasks().size)
    }

    @Test
    fun noReaskDuringAGuidancePrompt() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        assertTrue(client.sendPrompt("前方路口右转", "g1"))
        fake.send(activityEnd)
        waitUntil { log("gemini_reask_dropped reason=guidance").isNotEmpty() }
        assertEquals(0, fake.reasks().size)
    }

    @Test
    fun noReaskAfterASuspiciousSegment() = runBlocking {
        segment = SpeechUplinkGate.Segment(durationMs = 200, voicedFrames = 1, peak = 900)
        val fake = fake()
        val client = client()
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { log("gemini_reask_dropped reason=suspicious").isNotEmpty() }
        assertEquals(0, fake.reasks().size)
    }

    @Test
    fun noReaskWhenTheSocketIsClosed() = runBlocking {
        val fake = fake()
        val client = client()
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { log("gemini_voice_activity type=ACTIVITY_END").isNotEmpty() }
        fake.socket!!.close(1000, "bye")
        Thread.sleep(700)
        assertEquals(0, fake.reasks().size)
        assertTrue(log("gemini_reask ").isEmpty())
    }

    @Test
    fun noAnswerAfterTheReaskGivesUpOnceAndALateCallIsNotExecuted() = runBlocking {
        val fake = fake()
        val client = client(deadlineMs = 200, giveUpMs = 300)
        val events = collect(client)
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { log("gemini_reask_gave_up").isNotEmpty() }
        Thread.sleep(500)
        assertEquals(1, log("gemini_reask_gave_up").size)
        fake.send("""{"toolCall":{"functionCalls":[{"id":"c1","name":"control_climate","args":{"action":"power_on"}}]}}""")
        waitUntil { events.toList().any { it is DomainVoiceEvent.ToolCall } }
        val call = events.toList().filterIsInstance<DomainVoiceEvent.ToolCall>().single()
        assertEquals("REASK_GAVE_UP", call.arguments["_validation_error"], "answered as not executed")
    }

    @Test
    fun quietListeningBlocksTheDeadlineReask() = runBlocking {
        val fake = fake()
        val client = client()
        client.noAnswer.repliesSpoken = { false }
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { log("gemini_reask_dropped reason=quiet").isNotEmpty() }
        assertEquals(0, fake.reasks().size)
    }

    @Test
    fun quietListeningBlocksTheEmptyCloseReask() = runBlocking {
        val fake = fake()
        val client = client(deadlineMs = 60_000)
        client.noAnswer.repliesSpoken = { false }
        client.connect(config())
        driverTurn(fake, client)
        fake.send(input("调到二十四度"))
        fake.send(turnComplete)
        waitUntil { log("gemini_reask_dropped reason=quiet trigger=empty_close").isNotEmpty() }
        assertEquals(0, fake.reasks().size)
    }

    /** F19: the x86 emulator bypasses the uplink gate, so only the server reports the onsets. */
    private val activityStart = """{"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"0.100s"}}"""

    @Test
    fun aServerOnlyOnsetStartsANewUtteranceAfterAGiveUp() = runBlocking {
        val fake = fake()
        val client = client(deadlineMs = 200, giveUpMs = 300)
        val events = collect(client)
        client.connect(config())
        fake.send(activityStart); fake.send(activityEnd)
        waitUntil { log("gemini_reask_gave_up").isNotEmpty() }
        fake.send(activityStart); fake.send(activityEnd)
        fake.send("""{"toolCall":{"functionCalls":[{"id":"c2","name":"control_climate","args":{"action":"power_on"}}]}}""")
        waitUntil { events.toList().any { it is DomainVoiceEvent.ToolCall } }
        val call = events.toList().filterIsInstance<DomainVoiceEvent.ToolCall>().single()
        assertEquals(null, call.arguments["_validation_error"], "the new utterance's call runs")
    }

    @Test
    fun anUnansweredServerOnlySecondUtteranceGetsItsOwnReask() = runBlocking {
        val fake = fake()
        val client = client(deadlineMs = 200)
        client.connect(config())
        fake.send(activityStart); fake.send(activityEnd)
        waitUntil { fake.reasks().size == 1 }
        fake.send(activityStart); fake.send(activityEnd)
        waitUntil { fake.reasks().size == 2 }
    }

    @Test
    fun aNonClaimAnswerToTheReaskIsSpoken() = runBlocking {
        val fake = fake()
        val client = client(speechText = true)
        val events = collect(client)
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { fake.reasks().size == 1 }
        fake.send(audio())
        fake.send(output("你好呀，我在听。"))
        fake.send(generationComplete)
        fake.send(turnComplete)
        waitUntil { events.toList().any { it is DomainVoiceEvent.SpeechText } }
    }

    @Test
    fun anAnswerToTheReaskIsStillJudgedForClaims() = runBlocking {
        val fake = fake()
        val client = client(speechText = true)
        val events = collect(client)
        client.connect(config())
        driverTurn(fake, client)
        waitUntil { fake.reasks().size == 1 }
        fake.send(audio())
        fake.send(output("已为您打开空调"))
        fake.send(generationComplete)
        fake.send(turnComplete)
        waitUntil { events.toList().any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(150)
        assertTrue(events.toList().none { it is DomainVoiceEvent.SpeechText }, "an unproven claim is never spoken (I-1)")
    }

    private companion object {
        const val REASK = "请回应我刚才说的话。"
    }
}
