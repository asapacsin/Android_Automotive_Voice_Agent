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

/** SPEC-020 provider-spoken wait cue and the P50 stall watchdog, on the [QwenOmniClientTest] mock socket. */
class QwenCueCaptureClientTest {
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

    private fun client(responseStallMs: Long = ResponseStallWatchdog.STALL_MS) = QwenOmniClient(
        OkHttpClient(),
        READY_TIMEOUT_MS,
        requireTls = false,
        endpoint = { server.url("/api-ws/v1/realtime?model=${it.model}").toString().replace("http://", "ws://") },
        contextHint = { null },
        lastAudioSegment = { goodAudio },
        contextAwaitingAnswer = { false },
        responseStallMs = responseStallMs,
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


    private val cueText = "稍等，我查一下。"
    private val pcmA = byteArrayOf(1, 2, 3, 4)
    private val pcmB = byteArrayOf(5, 6)
    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private suspend fun connected(stallMs: Long = ResponseStallWatchdog.STALL_MS): Pair<QwenOmniClient, MutableList<DomainVoiceEvent>> {
        enqueueServer()
        val client = client(stallMs)
        val seen = collect(client)
        client.connect(config())
        return client to seen
    }

    private fun requestCue(client: QwenOmniClient) {
        assertTrue(client.speakFixedCue(cueText))
        awaitUntil({ "sent: $received" }) { sent("response.create").size == 1 }
    }

    private fun cueDeltas(vararg parts: String) = parts.map { """{"type":"response.audio_transcript.delta","delta":"$it"}""" }.toTypedArray()

    @Test
    fun aServerReplyWithACallTakenAsTheCueIsDispatchedAndNotPlayed() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_1","name":"control_climate"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1"}]}}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } && seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals("call_1", (seen.single { it is DomainVoiceEvent.ToolCall } as DomainVoiceEvent.ToolCall).callId)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio })
        client.disconnect()
    }

    @Test
    fun aDivergingTranscriptIsDemotedAndItsEventsTakeTheNormalPathInOrder() = runBlocking {
        val (client, seen) = connected()
        // The driver's turn, as in QwenOmniClientTest; the cue may be asked for once the gate's after-speech window passed.
        send("""{"type":"input_audio_buffer.speech_started"}""", """{"type":"input_audio_buffer.speech_stopped"}""")
        Thread.sleep(1_800)
        requestCue(client)
        val first = java.util.Base64.getEncoder().encodeToString(ByteArray(300 * 48) { 1 })
        val second = java.util.Base64.getEncoder().encodeToString(ByteArray(300 * 48) { 2 })
        send(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"今天过得怎么样"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"$first"}""",
            """{"type":"response.audio.delta","delta":"$second"}""",
            *cueDeltas("还不错呀，", "我们聊聊吧。"),
        )
        awaitUntil({ "seen: $seen" }) { seen.count { it is DomainVoiceEvent.AudioDelta } == 2 }
        val audio = seen.filterIsInstance<DomainVoiceEvent.AudioDelta>().map { it.pcm16leBase64 }
        assertEquals(listOf(first, second), audio, "the reply's audio reaches the normal gate, in order")
        assertTrue(seen.indexOfFirst { it is DomainVoiceEvent.ResponseStarted } < seen.indexOfFirst { it is DomainVoiceEvent.AudioDelta })
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio })
        client.disconnect()
    }

    @Test
    fun aResponseThatSaysExactlyTheCueIsPlayedOnceWithItsPcm() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"${b64(pcmA)}"}""",
            *cueDeltas("稍等", "我查一下"),
            """{"type":"response.audio.delta","delta":"${b64(pcmB)}"}""",
            """{"type":"response.audio_transcript.done","transcript":"稍等 我查一下！"}""",
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.WaitCueAudio } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        val cue = seen.filterIsInstance<DomainVoiceEvent.WaitCueAudio>().single()
        assertEquals(b64(pcmA + pcmB), cue.pcm16leBase64)
        assertTrue(seen.none { it is DomainVoiceEvent.AudioDelta || it is DomainVoiceEvent.AssistantTranscript })
        client.disconnect()
    }

    @Test
    fun aTranscriptThatEndsShortOfTheCueIsNotPlayed() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"${b64(pcmA)}"}""",
            """{"type":"response.audio_transcript.done","transcript":"稍等"}""",
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""",
        )
        assertNothingPlayedAfterCue(client, seen)
    }

    @Test
    fun aNonCompletedCueIsNotPlayed() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"${b64(pcmA)}"}""",
            """{"type":"response.audio_transcript.done","transcript":"$cueText"}""",
            """{"type":"response.done","response":{"status":"incomplete","output":[{"type":"message"}]}}""",
        )
        assertNothingPlayedAfterCue(client, seen)
    }

    /** The cue's done released the turn gate: a text turn goes out at once. */
    private fun assertNothingPlayedAfterCue(client: QwenOmniClient, seen: List<DomainVoiceEvent>) {
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio || it is DomainVoiceEvent.AudioDelta }, "seen: $seen")
        client.sendUserText("你好")
        awaitUntil({ "sent: $received" }) { sent("response.create").size == 2 }
        client.disconnect()
    }

    @Test
    fun driverSpeechDuringACueCancelsItAndReachesTheCore() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"${b64(pcmA)}"}""",
            """{"type":"input_audio_buffer.speech_started"}""",
        )
        awaitUntil({ "sent: $received" }) { sent("response.cancel").size == 1 }
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.SpeechStarted } }
        send(
            """{"type":"response.audio_transcript.done","transcript":"$cueText"}""",
            """{"type":"response.done","response":{"status":"cancelled","output":[{"type":"message"}]}}""",
        )
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio || it is DomainVoiceEvent.AudioDelta }, "seen: $seen")
        assertEquals(1, sent("response.cancel").size)
        client.disconnect()
    }

    @Test
    fun aClientCancelDuringACueCancelsItOnceAndPlaysNothing() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send("""{"type":"response.created","response":{"id":"r1"}}""", """{"type":"response.audio.delta","delta":"${b64(pcmA)}"}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        client.cancelResponse()
        client.cancelActiveResponse()
        awaitUntil({ "sent: $received" }) { sent("response.cancel").size == 1 }
        send(
            """{"type":"response.audio_transcript.done","transcript":"$cueText"}""",
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""",
        )
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.cancel").size)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio || it is DomainVoiceEvent.AudioDelta }, "seen: $seen")
        client.disconnect()
    }

    @Test
    fun anUnrelatedErrorDuringACueTakesTheNormalErrorPath() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send(
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"error","error":{"code":"internal_error","message":"server exploded"}}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.Error } }
        send("""{"type":"response.done","response":{"status":"failed","output":[]}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio })
        client.disconnect()
    }

    @Test
    fun aRefusedCueRequestIsConsumedQuietly() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send("""{"type":"error","error":{"code":"invalid_request_error","message":"Conversation already has an active response in progress"}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "seen: $seen")
        client.disconnect()
    }

    private val busy = """{"type":"error","error":{"code":"invalid_request_error","message":"Conversation already has an active response in progress"}}"""
    private val call = arrayOf(
        """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_1","name":"control_climate"}}""",
        """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
    )

    @Test
    fun aBusyRefusalDuringACaptureDemotesTheReplyAndQueuesNoExtraResponse() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send("""{"type":"response.created","response":{"id":"r1"}}""", busy, *call,
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1"}]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } && seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(2_000)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio || it is DomainVoiceEvent.Error }, "seen: $seen")
        assertEquals(1, sent("response.create").size, "only the cue request: the refusal queued no retry")
        client.disconnect()
    }

    @Test
    fun anOtherErrorDuringACaptureDoesNotLoseTheCall() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        send("""{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"error","error":{"code":"internal_error","message":"x"}}""", *call,
            """{"type":"response.done","response":{"status":"failed","output":[{"type":"function_call","call_id":"call_1"}]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } && seen.any { it is DomainVoiceEvent.ResponseDone } }
        assertTrue(seen.any { it is DomainVoiceEvent.Error })
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio })
        client.disconnect()
    }

    @Test
    fun aClientCancelBeforeTheCueResponseExistsCancelsItOnceItDoes() = runBlocking {
        val (client, seen) = connected()
        requestCue(client)
        client.cancelResponse()
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(0, sent("response.cancel").size)
        send("""{"type":"response.created","response":{"id":"r1"}}""", """{"type":"response.audio.delta","delta":"${b64(pcmA)}"}""")
        awaitUntil({ "sent: $received" }) { sent("response.cancel").size == 1 }
        send("""{"type":"response.audio_transcript.done","transcript":"$cueText"}""",
            """{"type":"response.done","response":{"status":"cancelled","output":[{"type":"message"}]}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.cancel").size)
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio || it is DomainVoiceEvent.AudioDelta }, "seen: $seen")
        client.disconnect()
    }

    @Test
    fun aLostCueDoneDoesNotPoisonTheNextResponse() = runBlocking {
        val (client, seen) = connected()
        send("""{"type":"input_audio_buffer.speech_started"}""", """{"type":"input_audio_buffer.speech_stopped"}""")
        Thread.sleep(1_800)
        requestCue(client)
        send("""{"type":"response.created","response":{"id":"r1"}}""") // its done never comes
        val chunk = java.util.Base64.getEncoder().encodeToString(ByteArray(300 * 48) { 3 })
        send(
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"今天过得怎么样"}""",
            """{"type":"response.created","response":{"id":"r2"}}""",
            """{"type":"response.audio.delta","delta":"$chunk"}""",
            *cueDeltas("还不错呀，", "我们聊聊吧。"),
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } && seen.any { it is DomainVoiceEvent.AudioDelta } }
        assertTrue(seen.none { it is DomainVoiceEvent.WaitCueAudio })
        Thread.sleep(1_800)
        assertTrue(client.speakFixedCue(cueText), "a later cue can be asked for again")
        client.disconnect()
    }

    // ---- P50 stall watchdog ----

    @Test
    fun aStallRetryWithADeferredTurnQueuedSendsExactlyOneResponseCreate() = runBlocking {
        val (client, _) = connected(stallMs = 300)
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.output_item.added","item":{"type":"message"}}""",
        )
        awaitUntil({ "sent: $received" }) { sent("response.cancel").size == 1 }
        client.sendUserText("再说一遍")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(0, sent("response.create").size, "the text turn waits behind the running reply")
        send("""{"type":"response.done","response":{"status":"cancelled","output":[{"type":"message"}]}}""")
        awaitUntil({ "sent: $received" }) { sent("response.create").size == 1 }
        Thread.sleep(1_000)
        assertEquals(1, sent("response.create").size, "the deferred turn and the stall retry never both go out on one done")
        // The text turn's reply finishes: a stall retry must not have waited in the queue behind it.
        send(
            """{"type":"response.created","response":{"id":"r2"}}""",
            """{"type":"response.output_item.added","item":{"type":"message"}}""",
            """{"type":"response.done","response":{"status":"completed","output":[{"type":"message"}]}}""",
        )
        Thread.sleep(1_000)
        assertEquals(1, sent("response.create").size, "no unrequested second reply")
        client.disconnect()
    }

    @Test
    fun aCueCandidateIsNeverStallRetried() = runBlocking {
        val (client, _) = connected(stallMs = 300)
        requestCue(client)
        send("""{"type":"response.created","response":{"id":"r1"}}""")
        Thread.sleep(1_200)
        assertEquals(0, sent("response.cancel").size)
        send("""{"type":"response.done","response":{"status":"completed","output":[]}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.create").size, "only the cue request itself")
        client.disconnect()
    }

    @Test
    fun aStallCancelThatRacesADoneDoesNotRetryTheNextResponse() {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        var retries = 0
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val watchdog = ResponseStallWatchdog(scope, 200, "t", cancel = { cancelled.countDown() }, retry = { retries++ })
        val alive = { true }
        watchdog.onResponseCreated(alive)
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        // The cancel raced: response 1's done had already been dealt with by a reset elsewhere, and
        // a done for a fresh, unwatched response comes in. It is the cancelled response's done here.
        watchdog.onResponseDone(alive) // response 1: retried once
        watchdog.onDriverTurn()
        watchdog.onResponseCreated(alive)
        watchdog.onResponseDone(alive) // response 2: never cancelled
        Thread.sleep(400)
        assertEquals(1, retries)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun aDoneThatPassedBeforeTheStallCheckLeavesNoRetryForTheNextResponse() {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        var retries = 0
        var cancels = 0
        val watchdog = ResponseStallWatchdog(scope, 200, "t", cancel = { cancels++ }, retry = { retries++ })
        val alive = { true }
        watchdog.onResponseCreated(alive)
        watchdog.onResponseDone(alive) // done before any stall
        watchdog.onResponseDone(alive) // a stray done (e.g. a cue's) must not retry
        Thread.sleep(500)
        assertEquals(0, cancels)
        assertEquals(0, retries)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private companion object {
        const val READY_TIMEOUT_MS = 30_000L
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val NEGATIVE_WAIT_MS = 300L
    }
}
