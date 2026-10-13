package com.novadrive.app.voice

import com.novadrive.app.NavigationState
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.ingress.realtime.VoiceCatalog
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

/** SPEC-021 A3, A5, A6, A7 and the text refusal, on a mock socket in the Baidu Flex client-test shape. */
class QwenOmniClientTest {
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
        com.novadrive.app.SpeakingStyleState.restore(com.novadrive.app.SpeakingStyle.DEFAULT)
        server.close()
    }

    /** A server that answers session.update with session.updated and records what the client sends. */
    private fun enqueueServer(
        refuseInstructionsUpdate: Boolean = false,
        onClientMessage: (WebSocket, JSONObject) -> Unit = { _, _ -> },
    ) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                sockets += webSocket
                webSocket.send("""{"type":"session.created","session":{"model":"qwen3.8-omni-flash-realtime"}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                val json = JSONObject(text)
                val instructionsOnly = json.optString("type") == "session.update" && !json.getJSONObject("session").has("tools")
                if (refuseInstructionsUpdate && instructionsOnly) {
                    webSocket.send("""{"type":"error","error":{"type":"invalid_request_error","code":"InvalidParameter","param":"session.instructions","message":"not allowed"}}""")
                } else if (json.optString("type") == "session.update") {
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

    /** A driver's action request whose reply audio claims the action (event order as the docs list it). */
    private fun claimingReply() = send(
        """{"type":"input_audio_buffer.speech_started"}""",
        """{"type":"input_audio_buffer.speech_stopped"}""",
        """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"帮我打开空调"}""",
        """{"type":"response.created","response":{"id":"r1"}}""",
        """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
        """{"type":"response.audio.delta","delta":"AAE="}""",
        """{"type":"response.audio_transcript.done","transcript":"已为您打开空调"}""",
    )

    /** A plain chat turn whose response.done reports [inputTokens] of input (owner, 2026-10-11: cap the history). */
    private fun chatTurnCosting(inputTokens: Int) = send(
        """{"type":"input_audio_buffer.speech_started"}""",
        """{"type":"input_audio_buffer.speech_stopped"}""",
        """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"今天心情不错"}""",
        """{"type":"response.created","response":{"id":"r1"}}""",
        """{"type":"response.audio.delta","delta":"AAE="}""",
        """{"type":"response.audio_transcript.done","transcript":"那就好呀"}""",
        """{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"type":"message","role":"assistant",""" +
            """"content":[{"type":"audio","transcript":"那就好呀"}]}],"usage":{"total_tokens":${inputTokens + 40},""" +
            """"input_tokens":$inputTokens,"output_tokens":40,"input_tokens_details":{"text_tokens":$inputTokens,"audio_tokens":0},""" +
            """"output_tokens_details":{"text_tokens":8,"audio_tokens":32}}}}""",
    )

    @Test
    fun aConversationOverTheHistoryCapStartsAfreshAndOneUnderItDoesNot() = runBlocking {
        enqueueServer()
        enqueueServer()
        val client = client()
        collect(client)
        client.connect(config())
        chatTurnCosting(HistoryBudget.HISTORY_TOKEN_CAP - 1)
        Thread.sleep(500)
        assertEquals(1, sent("session.update").size, "under the cap: one conversation")
        chatTurnCosting(HistoryBudget.HISTORY_TOKEN_CAP + 500)
        awaitUntil({ "session.update sent ${sent("session.update").size}" }) { sent("session.update").size == 2 }
        assertEquals(2, sockets.size, "over the cap: a fresh conversation on a new socket")
        client.disconnect()
    }

    // ---- A3 ----

    @Test
    fun aMalformedServerEventIsLoggedByTypeAndCauseNeverByContent() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val previous = com.novadrive.app.DebugVoiceLog.sink
        com.novadrive.app.DebugVoiceLog.sink = { lines += it }
        try {
            client.connect(config())
            send("""{"secret_transcript_text" """) // not JSON: the parse fails
            awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.Error && it.code == "QWEN_PROTOCOL_ERROR" } }
        } finally {
            com.novadrive.app.DebugVoiceLog.sink = previous
        }
        val line = lines.single { it.startsWith("qwen_protocol_error ") }
        assertTrue(line.startsWith("qwen_protocol_error type=unparsed cause=JSONException at="), line)
        assertFalse(lines.any { "secret_transcript_text" in it }, "never the event's content (I-8)")
        client.disconnect()
    }

    /**
     * Take 9 (2026-10-12): the 3.5 Plus free tier was used up and the session failed 57 ms after
     * session.created as QWEN_PROTOCOL_ERROR, hiding the reason. Alibaba's error-code page
     * (alibabacloud.com/help/en/model-studio/error-code) documents 403 AllocationQuota.FreeTierOnly
     * ("the free tier of the model has been exhausted") and 400 Arrearage (overdue account).
     * Whatever shape the refusal of session.update takes, the driver-facing code says quota.
     */
    @Test
    fun aQuotaRefusalOfTheSessionIsReportedAsQuotaNeverAsAProtocolError() = runBlocking {
        val refusals = listOf(
            """{"type":"error","event_id":"e1","error":{"type":"invalid_request_error","code":"AllocationQuota.FreeTierOnly","message":"The free tier of the model has been exhausted."}}""",
            """{"type":"error","error":{"code":"Arrearage","message":"Access denied, please make sure your account is in good standing."}}""",
            """{"type":"error","error":"AllocationQuota.FreeTierOnly: The free tier of the model has been exhausted."}""",
            """{"type":"error","code":"AllocationQuota.FreeTierOnly","message":"The free tier of the model has been exhausted."}""",
            """{"code":"AllocationQuota.FreeTierOnly","message":"The free tier of the model has been exhausted.","request_id":"r1"}""",
        )
        for (refusal in refusals) {
            sockets.clear()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    sockets += webSocket
                    webSocket.send("""{"type":"session.created","session":{"model":"qwen3.5-omni-plus-realtime"}}""")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).optString("type") == "session.update") webSocket.send(refusal)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            }))
            val client = client()
            val seen = collect(client)
            val code = try {
                client.connect(config())
                awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.Error } }
                (seen.first { it is DomainVoiceEvent.Error } as DomainVoiceEvent.Error).code
            } catch (e: VoiceProviderException) {
                e.code
            }
            assertEquals("QWEN_QUOTA_EXHAUSTED", code, refusal)
            client.disconnect()
        }
    }

    @Test
    fun opensWithTheBearerKeyAndTheDocumentedSessionUpdate() = runBlocking {
        enqueueServer()
        val client = client()
        client.connect(config())
        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertEquals("qwen3.8-omni-flash-realtime", request.requestUrl?.queryParameter("model"))
        assertFalse(request.requestUrl.toString().contains(key))
        val update = sent("session.update").single().getJSONObject("session")
        assertEquals(QwenAppSettings.DEFAULT_CUSTOM_VOICES.getValue(VoiceCatalog.QWEN_OMNI_FLASH), update.getString("voice"))
        assertEquals("semantic_vad", update.getJSONObject("turn_detection").getString("type"))
        assertNull(NavigationState.onNavigatingChanged, "Qwen does not track navigation for VAD")
        client.disconnect()
    }

    // ---- A5: the claim gate holds the reply's audio ----

    @Test
    fun aClaimingReplysAudioIsHeldUntilOkTrue() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        claimingReply()
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.AudioDelta }, "nothing is heard before execution evidence (I-1)")
        client.sendFunctionResult("call_1", """{"ok":true,"tool":"control_climate"}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.AudioDelta } }
        assertEquals(DomainVoiceEvent.AudioDelta("AAE="), seen.first { it is DomainVoiceEvent.AudioDelta })
        client.disconnect()
    }

    @Test
    fun aClaimingReplysAudioIsDroppedOnARefusal() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        claimingReply()
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } }
        client.sendFunctionResult("call_1", """{"ok":false,"error":"VEHICLE_UNAVAILABLE"}""")
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1"},{"type":"message"}]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.AudioDelta }, "ok=false is not proof (I-1)")
        assertTrue(seen.none { it is DomainVoiceEvent.AssistantTranscript && it.text.contains("已为您") })
        client.disconnect()
    }

    // ---- P50: streamed words, stalls ----

    @Test
    fun aChatReplyIsHeardBeforeItEndsWhenItsWordsStream() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        val chunk = java.util.Base64.getEncoder().encodeToString(ByteArray(300 * 48))
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"今天过得怎么样"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.audio.delta","delta":"$chunk"}""",
            """{"type":"response.audio.delta","delta":"$chunk"}""",
            """{"type":"response.audio_transcript.delta","delta":"还不错呀，"}""",
            """{"type":"response.audio_transcript.delta","delta":"我们聊聊吧。"}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.count { it is DomainVoiceEvent.AudioDelta } == 2 }
        assertTrue(seen.none { it is DomainVoiceEvent.ResponseDone }, "released clause by clause, before response.done (SPEC-014)")
        client.disconnect()
    }

    @Test
    fun aStalledResponseIsRetriedOnceAndAStalledRetryIsGivenUp() = runBlocking {
        enqueueServer()
        val client = client(responseStallMs = 300)
        client.connect(config())
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.output_item.added","item":{"type":"message"}}""",
        )
        awaitUntil({ "sent: $received" }) { sent("response.cancel").size == 1 }
        val createsBefore = sent("response.create").size
        send("""{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"client_cancelled"},"output":[{"type":"message"}]}}""")
        awaitUntil({ "sent: $received" }) { sent("response.create").size == createsBefore + 1 }
        // The retry stalls too. Owner, 2026-10-11 (never silent): it is cancelled and given up, with an
        // honest line from the wait-cue owner - never asked for a third time, never waited for forever.
        var gaveUp = false
        client.onStallNotice = { final -> if (final) gaveUp = true }
        send("""{"type":"response.created","response":{"id":"r2"}}""")
        awaitUntil({ "sent: $received" }) { sent("response.cancel").size == 2 }
        send("""{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"client_cancelled"},"output":[]}}""")
        awaitUntil({ "gave up: $gaveUp" }) { gaveUp }
        Thread.sleep(500)
        assertEquals(createsBefore + 1, sent("response.create").size, "no third request")
        client.disconnect()
    }

    // ---- A6: tool round trip, no overlapping reply ----

    @Test
    fun aToolResultGoesBackAsFunctionCallOutputThenOneResponseCreateAfterTheRunningReply() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"i1","transcript":"打开空调"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall } }
        assertEquals(
            DomainVoiceEvent.ToolCall("call_1", "control_climate", mapOf("action" to "power_on")),
            seen.single { it is DomainVoiceEvent.ToolCall },
        )
        client.sendFunctionResult("call_1", """{"ok":true,"tool":"control_climate"}""")
        awaitUntil({ "sent: $received" }) { sent("conversation.item.create").isNotEmpty() }
        val output = sent("conversation.item.create").single().getJSONObject("item")
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(sent("response.create").isEmpty(), "no second reply while the first is running")
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_1"}]}}""")
        awaitUntil({ "sent: $received" }) { sent("response.create").isNotEmpty() }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.create").size)
        client.disconnect()
    }

    // ---- B10 / P55: a mid-session style change reaches the live conversation ----

    /** One tool turn: [name]'s call, the app's result after [style] was applied, then the response's end. */
    private fun toolTurn(client: QwenOmniClient, seen: List<DomainVoiceEvent>, callId: String, name: String, style: com.novadrive.app.SpeakingStyle?) {
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"response.created","response":{"id":"r_$callId"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"$callId","name":"$name","arguments":"{}"}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall && it.callId == callId } }
        style?.let { com.novadrive.app.SpeakingStyleState.restore(it) }
        val createsBefore = sent("response.create").size
        client.sendFunctionResult(callId, """{"ok":true,"tool":"$name"}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"$callId"}]}}""")
        awaitUntil({ "sent: $received" }) { sent("response.create").size > createsBefore }
        // The reply to the tool result ends, so the next turn starts idle.
        send("""{"type":"response.created","response":{"id":"r2_$callId"}}""", """{"type":"response.done","response":{"status":"completed","output":[]}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
    }

    private fun instructionsUpdates() = sent("session.update").filter { !it.getJSONObject("session").has("tools") }

    @Test
    fun aStyleChangeIsResentOnceAsAnInstructionsOnlyUpdateAtTheIdlePointBeforeTheReply() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        toolTurn(client, seen, "call_1", "control_climate", null)
        assertTrue(instructionsUpdates().isEmpty(), "unchanged style, other tool: nothing sent")
        val createsBefore = sent("response.create").size
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"response.created","response":{"id":"r3"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"call_2","name":"set_speaking_style","arguments":"{\"style\":\"sweet\"}"}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall && it.callId == "call_2" } }
        com.novadrive.app.SpeakingStyleState.restore(com.novadrive.app.SpeakingStyle.SWEET)
        client.sendFunctionResult("call_2", """{"ok":true,"tool":"set_speaking_style"}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(instructionsUpdates().isEmpty(), "never in the middle of a response")
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"call_2"}]}}""")
        awaitUntil({ "sent: $received" }) { sent("response.create").size > createsBefore }
        val update = instructionsUpdates().single()
        assertEquals(setOf("type", "session"), update.keySet())
        assertEquals(setOf("instructions"), update.getJSONObject("session").keySet())
        val instructions = update.getJSONObject("session").getString("instructions")
        assertTrue(com.novadrive.app.PersonaProfiles.SWEET_TONE in instructions)
        assertTrue(com.novadrive.app.PersonaProfiles.SWEET_VOICE_STYLE in instructions)
        assertFalse("不撒娇" in instructions)
        assertTrue(com.novadrive.app.PersonaProfiles.FLEX_TOOL_RULE in instructions, "the same text as at setup")
        val order = received.map(::JSONObject).map { it.getString("type") }
        val at = order.lastIndexOf("session.update")
        assertEquals("response.create", order[at + 1], "the update goes out before the tool reply")
        send("""{"type":"response.created","response":{"id":"r4"}}""", """{"type":"response.done","response":{"status":"completed","output":[]}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        toolTurn(client, seen, "call_3", "control_climate", null)
        assertEquals(1, instructionsUpdates().size, "sent once, not on every later tool turn")
        toolTurn(client, seen, "call_4", "set_speaking_style", com.novadrive.app.SpeakingStyle.DEFAULT)
        assertEquals(2, instructionsUpdates().size, "switching back re-sends once")
        assertFalse(com.novadrive.app.PersonaProfiles.SWEET_TONE in instructionsUpdates().last().getJSONObject("session").getString("instructions"))
        assertEquals(1, sockets.size, "no reconnect")
        client.disconnect()
    }

    /** P55 review: an update held behind a response and dropped by sleep goes out once, on a later tool turn. */
    @Test
    fun aStyleUpdateDroppedBySleepIsResentOnceOnTheNextToolTurn() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        send(
            """{"type":"input_audio_buffer.speech_started"}""",
            """{"type":"input_audio_buffer.speech_stopped"}""",
            """{"type":"response.created","response":{"id":"r1"}}""",
            """{"type":"response.function_call_arguments.done","call_id":"s1","name":"set_speaking_style","arguments":"{\"style\":\"sweet\"}"}""",
        )
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ToolCall && it.callId == "s1" } }
        com.novadrive.app.SpeakingStyleState.restore(com.novadrive.app.SpeakingStyle.SWEET)
        client.sendFunctionResult("s1", """{"ok":true,"tool":"set_speaking_style"}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        client.discardPendingAudio()
        send("""{"type":"response.done","response":{"status":"completed","output":[{"type":"function_call","call_id":"s1"}]}}""")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(instructionsUpdates().isEmpty(), "dropped with its turn")
        client.resumeListening()
        toolTurn(client, seen, "c2", "control_climate", null)
        assertEquals(1, instructionsUpdates().size, "re-sent once on the later tool turn")
        toolTurn(client, seen, "c3", "control_climate", null)
        assertEquals(1, instructionsUpdates().size)
        client.disconnect()
    }

    @Test
    fun aRefusedInstructionsUpdateIsNotSessionFatalAndLaterToolTurnsStillWork() = runBlocking {
        enqueueServer(refuseInstructionsUpdate = true)
        val client = client()
        val seen = collect(client)
        val lines = CopyOnWriteArrayList<String>()
        val previous = com.novadrive.app.DebugVoiceLog.sink
        com.novadrive.app.DebugVoiceLog.sink = { lines += it }
        try {
            client.connect(config())
            toolTurn(client, seen, "call_1", "set_speaking_style", com.novadrive.app.SpeakingStyle.SWEET)
            awaitUntil({ "lines: $lines" }) { lines.any { it.startsWith("qwen_instructions_update_refused code=InvalidParameter") } }
            val before = sent("conversation.item.create").size
            toolTurn(client, seen, "call_2", "control_climate", null)
            assertEquals(before + 1, sent("conversation.item.create").size, "a later tool turn still answers")
        } finally {
            com.novadrive.app.DebugVoiceLog.sink = previous
        }
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "the refusal is not session-fatal: $seen")
        val redelivered = lines.single { it.startsWith("style_redelivered ") }
        assertTrue(Regex("style_redelivered provider=qwen style=sweet chars=\\d+").matches(redelivered), redelivered)
        assertFalse(lines.any { com.novadrive.app.PersonaProfiles.SWEET_TONE in it }, "never the instructions text (I-8)")
        client.disconnect()
    }

    // ---- A7: the client's cancel (the session core's barge-in qualification is not exercised here) ----

    @Test
    fun clientCancelSendsOneResponseCancelAndARefusedCancelIsNotFatal() = runBlocking {
        enqueueServer()
        val client = client()
        val seen = collect(client)
        client.connect(config())
        // As in BaiduFlexClientTest: reply audio is playing (the turn gate is covered by the A5 tests).
        send("""{"type":"response.audio.delta","delta":"AAE="}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.AudioDelta } }
        client.cancelResponse()
        client.cancelResponse()
        awaitUntil({ "sent: $received" }) { sent("response.cancel").isNotEmpty() }
        send("""{"type":"error","error":{"code":"invalid_request_error","message":"Cancellation failed: no active response found"}}""")
        send("""{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"client_cancelled"}}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(1, sent("response.cancel").size)
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "a refused cancel is not session-fatal: $seen")
        assertTrue(seen.any { it is DomainVoiceEvent.Interrupted })
        client.disconnect()
    }

    // ---- SPEC-021 B7: a refused text item ----

    @Test
    fun afterTheServerRefusesATextItemTheNextCorrectionSendsNothing() = runBlocking {
        enqueueServer { ws, json ->
            if (json.optString("type") == "conversation.item.create" && json.getJSONObject("item").optString("type") == "message") {
                ws.send("""{"type":"error","error":{"code":"invalid_value","param":"item.type","message":"Invalid value: 'message'"}}""")
            }
        }
        val client = client()
        val seen = collect(client)
        client.connect(config())
        client.sendUserText("第一次纠正")
        awaitUntil({ "sent: $received" }) { sent("conversation.item.create").isNotEmpty() && sent("response.create").isNotEmpty() }
        send("""{"type":"response.created","response":{"id":"r1"}}""", """{"type":"response.done","response":{"status":"completed","output":[]}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseDone } }
        val itemsBefore = sent("conversation.item.create").size
        val createsBefore = sent("response.create").size
        client.sendUserText("第二次纠正")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(itemsBefore, sent("conversation.item.create").size, "no text item after a refusal")
        assertEquals(createsBefore, sent("response.create").size, "and no response.create either")
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "the refusal is not session-fatal: $seen")
        client.disconnect()
    }

    @Test
    fun aCorrectionDeferredBehindARunningReplyAndThenRefusedIsNotFatal() = runBlocking {
        enqueueServer { ws, json ->
            if (json.optString("type") == "conversation.item.create" && json.getJSONObject("item").optString("type") == "message") {
                ws.send("""{"type":"error","error":{"code":"invalid_value","param":"item.type","message":"Invalid value: 'message'"}}""")
            }
        }
        val client = client()
        val seen = collect(client)
        client.connect(config())
        // A reply is running, so the correction is deferred; other events arrive before it goes out.
        send("""{"type":"response.created","response":{"id":"r1"}}""")
        awaitUntil({ "seen: $seen" }) { seen.any { it is DomainVoiceEvent.ResponseStarted } }
        client.sendUserText("纠正")
        send(
            """{"type":"conversation.item.created","item":{"type":"message"}}""",
            """{"type":"response.done","response":{"status":"completed","output":[]}}""",
        )
        awaitUntil({ "sent: $received" }) { sent("conversation.item.create").isNotEmpty() }
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertTrue(seen.none { it is DomainVoiceEvent.Error }, "a late refusal is not session-fatal: $seen")
        val itemsBefore = sent("conversation.item.create").size
        val createsBefore = sent("response.create").size
        client.sendUserText("再纠正")
        Thread.sleep(NEGATIVE_WAIT_MS)
        assertEquals(itemsBefore, sent("conversation.item.create").size, "text is off after the refusal")
        assertEquals(createsBefore, sent("response.create").size)
        client.disconnect()
    }

    @Test
    fun aSessionThatNeverBecomesReadyFailsWithTheQwenCode() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) = Unit
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        val client = QwenOmniClient(
            OkHttpClient(), 300, requireTls = false,
            endpoint = { server.url("/api-ws/v1/realtime").toString().replace("http://", "ws://") },
        )
        val failure = runCatching { client.connect(config()) }.exceptionOrNull()
        client.close()
        kotlinx.coroutines.delay(200)
        assertEquals("QWEN_READY_TIMEOUT", (failure as VoiceProviderException).code)
        assertFalse(failure.safeMessage.contains(workspace))
    }

    private companion object {
        /** See BaiduFlexClientTest: readiness is not what these tests measure. */
        const val READY_TIMEOUT_MS = 30_000L
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val NEGATIVE_WAIT_MS = 300L
    }
}
