package com.novadrive.app.voice

import com.novadrive.app.PersonaProfiles
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.app.QwenSettingsValidator
import com.novadrive.ingress.realtime.ErrorClass
import com.novadrive.ingress.realtime.classifyVoiceError
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.UnknownHostException

/** SPEC-021 step 2: the Qwen-Omni wire dialect (A2, A3, A4). */
class QwenOmniDialectTest {
    private val workspace = "ws-secret-7f3a"
    private val key = "sk-placeholder-" + "q".repeat(24)
    private val settings = QwenAppSettings(consentAccepted = true, workspaceId = workspace)
    private val config = QwenApiConfig(settings, key, "PERSONA")

    private fun opened() = QwenOmniDialect().also { it.onSessionOpening(config) }

    private fun golden(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("golden/$name")) { "missing golden $name" }.readText()

    // ---- A2: session.update ----

    @Test
    fun sessionUpdateMatchesTheGolden() {
        assertEquals(JSONObject(golden("qwen_session_update.json")).toString(), JSONObject(opened().sessionUpdate("PERSONA", 0.9)).toString())
    }

    @Test
    fun sessionUpdateHasTheDocumentedShapeAndNothingElse() {
        val root = JSONObject(opened().sessionUpdate("PERSONA", 0.9))
        assertEquals("session.update", root.getString("type"))
        val s = root.getJSONObject("session")
        assertEquals(
            setOf("modalities", "voice", "audio", "instructions", "input_audio_format", "output_audio_format",
                "input_audio_transcription", "turn_detection", "tools"),
            s.keySet(),
        )
        assertEquals(JSONArray(listOf("text", "audio")).toString(), s.getJSONArray("modalities").toString())
        assertEquals("Maia", s.getString("voice"))
        assertEquals("Maia", s.getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertEquals("PERSONA\n" + PersonaProfiles.FLEX_TOOL_RULE, s.getString("instructions"))
        assertEquals("pcm", s.getString("input_audio_format"))
        assertEquals("pcm", s.getString("output_audio_format"))
        assertEquals("qwen3-asr-flash-realtime", s.getJSONObject("input_audio_transcription").getString("model"))
        val turn = s.getJSONObject("turn_detection")
        assertEquals("semantic_vad", turn.getString("type"))
        assertEquals(800, turn.getInt("silence_duration_ms"))
        assertEquals(setOf("type", "silence_duration_ms"), turn.keySet(), "the VAD threshold is ignored")
        val tools = s.getJSONArray("tools")
        assertEquals(RealtimeToolCatalog.tools().size, tools.length())
        (0 until tools.length()).map { tools.getJSONObject(it) }.forEach {
            assertEquals("function", it.getString("type"))
            assertEquals(setOf("name", "description", "parameters"), it.getJSONObject("function").keySet())
        }
        listOf("enable_search", "model", "tool_choice").forEach { assertFalse(s.has(it), it) }
    }

    @Test
    fun voiceVadAndSilenceComeFromTheSettings() {
        val d = QwenOmniDialect()
        d.onSessionOpening(config.copy(settings = settings.copy(voice = "Cherry", vadType = "server_vad", silenceDurationMs = 500)))
        val s = JSONObject(d.sessionUpdate("P", 0.0)).getJSONObject("session")
        assertEquals("Cherry", s.getString("voice"))
        assertEquals("server_vad", s.getJSONObject("turn_detection").getString("type"))
        assertEquals(500, s.getJSONObject("turn_detection").getInt("silence_duration_ms"))
    }

    @Test
    fun noRecoveryAndNoNavigationTracking() {
        val d = opened()
        assertNull(d.recoverSessionError({ "P" }, 0.5))
        assertFalse(d.tracksNavigationVad)
        assertEquals("qwen", d.logPrefix)
    }

    // ---- A3: endpoint, auth and secrets ----

    @Test
    fun requestUsesTheWorkspaceEndpointAndBearerKeyOnlyInTheHeader() = runBlocking {
        val request: Request = QwenOmniDialect().buildRequest(config)
        assertEquals("$workspace.ap-southeast-1.maas.aliyuncs.com", request.url.host)
        assertEquals("/api-ws/v1/realtime", request.url.encodedPath)
        assertEquals("qwen3.8-omni-flash-realtime", request.url.queryParameter("model"))
        assertTrue(request.url.isHttps, "wss only")
        assertEquals("Bearer $key", request.header("Authorization"))
        assertFalse(request.url.toString().contains(key))
        assertFalse(config.toString().contains(key))
        assertFalse(config.toString().contains(workspace))
    }

    @Test
    fun missingConfigFailsWithItsCodeAndAnHonestMessage() {
        listOf(
            config.copy(apiKey = "") to "QWEN_API_KEY_MISSING",
            config.copy(settings = settings.copy(workspaceId = "")) to "QWEN_WORKSPACE_MISSING",
            config.copy(settings = settings.copy(consentAccepted = false)) to "QWEN_CONSENT_MISSING",
        ).forEach { (bad, code) ->
            val failure = assertThrows<VoiceProviderException> { runBlocking { QwenOmniDialect().buildRequest(bad) } }
            assertEquals(code, failure.code)
            assertFalse(failure.safeMessage.contains(key))
        }
    }

    @Test
    fun aPlainWebSocketEndpointIsRefusedWhenTlsIsRequired() {
        val d = QwenOmniDialect(requireTls = true, endpoint = { "ws://127.0.0.1:1/realtime" })
        assertEquals("QWEN_ENDPOINT_INVALID", assertThrows<VoiceProviderException> { runBlocking { d.buildRequest(config) } }.code)
    }

    @Test
    fun anUnknownHostMapsToEndpointUnreachableWithoutTheHost() {
        val host = "$workspace.ap-southeast-1.maas.aliyuncs.com"
        val failure = QwenOmniDialect.mapFailure(UnknownHostException("Unable to resolve host \"$host\""), null)
        assertEquals("QWEN_DNS_FAILED", failure.code)
        listOf(failure.safeMessage, failure.message.orEmpty(), failure.toString()).forEach {
            assertFalse(it.contains(workspace), it)
            assertFalse(it.contains(host), it)
        }
        assertNull(failure.cause, "the cause carries the host")
        assertTrue(failure.safeMessage.contains("workspace ID"))
    }

    @Test
    fun handshakeStatusesMapToQwenCodes() {
        fun status(code: Int) = QwenOmniDialect.mapFailure(java.io.IOException("x"), response(code)).code
        assertEquals("QWEN_AUTH_FAILED", status(401))
        assertEquals("QWEN_AUTH_FAILED", status(403))
        assertEquals("QWEN_QUOTA_EXHAUSTED", status(429))
        assertEquals("QWEN_CONNECTION_FAILED", status(500))
        assertEquals("QWEN_CONNECTION_FAILED", QwenOmniDialect.mapFailure(java.io.IOException("reset"), null).code)
    }

    @Test
    fun errorMessagesAndCodesNeverCarryTheWorkspaceOrTheKey() {
        val d = opened()
        val text = """{"type":"error","error":{"code":"InternalError","message":"host $workspace failed"}}"""
        val event = d.parseCommonEvent(text, false).single() as DomainVoiceEvent.Error
        assertFalse(event.message.contains(workspace))
        assertFalse(d.errorCode(text).contains(workspace))
        val keyed = """{"type":"error","error":{"code":"InvalidApiKey","message":"Bearer $key is invalid"}}"""
        val keyedEvent = d.parseCommonEvent(keyed, false).single() as DomainVoiceEvent.Error
        assertFalse(keyedEvent.message.contains(key))
        assertFalse(d.errorCode(keyed).contains(key))
    }

    // ---- A4: events ----

    @Test
    fun documentedServerEventsMapToNeutralEvents() {
        val d = opened()
        fun one(json: String, speaking: Boolean = false) = d.parseCommonEvent(json, speaking)
        assertTrue(one("""{"type":"session.updated","session":{"model":"m"}}""").single() is DomainVoiceEvent.SessionReady)
        assertEquals(listOf(DomainVoiceEvent.SpeechStarted), one("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(DomainVoiceEvent.Interrupted("turn_detected"), one("""{"type":"input_audio_buffer.speech_started"}""", true)[1])
        assertEquals(listOf(DomainVoiceEvent.SpeechStopped), one("""{"type":"input_audio_buffer.speech_stopped"}"""))
        assertEquals(
            listOf(DomainVoiceEvent.UserTranscript("你好", true)),
            one("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"你好"}"""),
        )
        assertEquals(listOf(DomainVoiceEvent.ResponseStarted), one("""{"type":"response.created","response":{"id":"r"}}"""))
        assertEquals(listOf(DomainVoiceEvent.AudioDelta("AAE=")), one("""{"type":"response.audio.delta","delta":"AAE="}"""))
        assertEquals(listOf(DomainVoiceEvent.AudioDone), one("""{"type":"response.audio.done"}"""))
        assertEquals(
            listOf(DomainVoiceEvent.AssistantTranscript("好", false)),
            one("""{"type":"response.audio_transcript.delta","delta":"好"}"""),
        )
        assertEquals(
            listOf(DomainVoiceEvent.AssistantTranscript("好的", true)),
            one("""{"type":"response.audio_transcript.done","transcript":"好的"}"""),
        )
        assertTrue(one("""{"type":"response.done","response":{"status":"completed"}}""").single() is DomainVoiceEvent.ResponseDone)
        assertTrue(one("""{"type":"response.function_call_arguments.done","call_id":"c1","name":"x","arguments":"{}"}""").isEmpty())
        assertTrue(one("""{"type":"conversation.item.created","item":{"type":"function_call"}}""").isEmpty())
    }

    @Test
    fun aToolCallIsAssembledFromTheDoneEventAlone() {
        val assembler = opened().newCallAssembler()
        val events = assembler.consume(
            """{"type":"response.function_call_arguments.done","call_id":"call_1","name":"control_climate","arguments":"{\"action\":\"power_on\"}"}""",
        )
        assertEquals(listOf(DomainVoiceEvent.ToolCall("call_1", "control_climate", mapOf("action" to "power_on"))), events)
        val bad = opened().newCallAssembler().consume(
            """{"type":"response.function_call_arguments.done","call_id":"call_2","name":"control_climate","arguments":"{"}""",
        ).single() as DomainVoiceEvent.ToolCall
        assertEquals("MALFORMED_JSON", bad.arguments["_validation_error"])
    }

    @Test
    fun errorsMapToQwenCodes() {
        val d = opened()
        fun code(c: String, m: String) =
            (d.parseCommonEvent("""{"type":"error","error":{"code":"$c","message":"$m"}}""", false).single() as DomainVoiceEvent.Error).code
        assertEquals("QWEN_AUTH_FAILED", code("InvalidApiKey", "Invalid API-key provided."))
        assertEquals("QWEN_AUTH_FAILED", code("AccessDenied", "denied"))
        assertEquals("QWEN_AUTH_FAILED", code("401", "x"))
        assertEquals("QWEN_QUOTA_EXHAUSTED", code("Throttling", "Requests throttled"))
        assertEquals("QWEN_QUOTA_EXHAUSTED", code("AllocationQuota.FreeTierOnly", "free quota exhausted"))
        assertEquals("QWEN_QUOTA_EXHAUSTED", code("429", "x"))
        assertEquals("QWEN_SERVER_UNAVAILABLE", code("InternalError", "x"))
        assertEquals("QWEN_PROVIDER_ERROR", code("InvalidParameter", "x"))
        val failed = d.parseCommonEvent(
            """{"type":"response.done","response":{"status":"failed","status_details":{"error":{"code":"Throttling","message":"slow down"}}}}""",
            false,
        )
        assertEquals("QWEN_QUOTA_EXHAUSTED", failed.filterIsInstance<DomainVoiceEvent.Error>().single().code)
    }

    @Test
    fun aRefusedCancelAndAnOverlappingReplyAreNotFatal() {
        val d = opened()
        val cancel = """{"type":"error","error":{"code":"invalid_request_error","message":"Cancellation failed: no active response found"}}"""
        val busy = """{"type":"error","error":{"code":"invalid_request_error","message":"Conversation already has an active response"}}"""
        assertTrue(d.parseCommonEvent(cancel, false).isEmpty())
        assertTrue(d.parseCommonEvent(busy, false).isEmpty())
        assertTrue(d.isResponseAlreadyActive(busy))
        assertFalse(d.isResponseAlreadyActive(cancel))
        assertEquals("invalid_request_error kind=cancel_refused", d.errorCode(cancel))
        assertEquals("invalid_request_error kind=response_busy", d.errorCode(busy))
    }

    @Test
    fun aRefusedTextItemTurnsTextOffForTheSessionOnly() {
        val d = opened()
        assertTrue(JSONObject(d.userTextMessage("纠正")!!).getJSONObject("item").getString("type") == "message")
        val refusal = """{"type":"error","error":{"code":"invalid_value","param":"item.type","message":"Invalid value: 'message'"}}"""
        assertTrue(d.parseCommonEvent(refusal, false).isEmpty(), "not session-fatal")
        assertEquals("invalid_value kind=text_refused", d.errorCode(refusal))
        assertNull(d.userTextMessage("纠正"))
        d.onSessionOpening(config)
        assertTrue(d.userTextMessage("纠正") != null, "a new session tries again")
    }

    @Test
    fun outboundMessagesUseTheOpenAiShapes() {
        val d = opened()
        assertEquals("input_audio_buffer.append", JSONObject(d.audioAppend("AAE=")).getString("type"))
        assertTrue(d.isAudioAppend(d.audioAppend("AAE=")))
        assertFalse(d.isAudioAppend(d.responseCreate()))
        assertEquals("""{"type":"response.cancel"}""", d.responseCancel())
        assertEquals("""{"type":"response.create"}""", d.responseCreate())
        val output = JSONObject(d.functionCallOutput("call_1", "{\"ok\":true}")).getJSONObject("item")
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        val text = JSONObject(d.userTextMessage("你好")!!).getJSONObject("item")
        assertEquals("user", text.getString("role"))
        assertEquals("input_text", text.getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test
    fun exceptionFactoriesUseQwenCodes() {
        val d = opened()
        assertEquals("QWEN_READY_TIMEOUT", d.readyTimeout().code)
        assertEquals("QWEN_CONNECTION_CLOSED", d.notConnected().code)
        assertEquals("QWEN_CONNECTION_CLOSED", d.connectionClosed(1006).code)
        assertEquals("QWEN_SESSION_FAILED", d.sessionFailed().code)
        assertEquals("QWEN_PROTOCOL_ERROR", d.protocolError(IllegalStateException()).code)
    }

    // ---- correction R1: every code the dialect can produce, as the session core classifies it ----

    @Test
    fun everyQwenCodeHasTheIntendedErrorClass() {
        val d = opened()
        fun inBand(c: String, m: String) =
            (d.parseCommonEvent("""{"type":"error","error":{"code":"$c","message":"$m"}}""", false).single() as DomainVoiceEvent.Error).code
        val produced = mapOf(
            d.readyTimeout().code to ErrorClass.RETRYABLE,
            d.notConnected().code to ErrorClass.RETRYABLE,
            d.connectionClosed(1006).code to ErrorClass.RETRYABLE,
            QwenOmniDialect.mapFailure(UnknownHostException("h"), null).code to ErrorClass.RETRYABLE,
            QwenOmniDialect.mapFailure(java.io.IOException("reset"), null).code to ErrorClass.RETRYABLE,
            inBand("InternalError", "Internal server error") to ErrorClass.RETRYABLE,
            inBand("ServiceUnavailable", "x") to ErrorClass.RETRYABLE,
            inBand("x", "server busy, try later") to ErrorClass.RETRYABLE,
            QwenOmniDialect.mapFailure(java.io.IOException("x"), response(401)).code to ErrorClass.AUTH,
            inBand("InvalidApiKey", "x") to ErrorClass.AUTH,
            QwenOmniDialect.mapFailure(java.io.IOException("x"), response(429)).code to ErrorClass.RATE_LIMIT,
            inBand("Throttling", "x") to ErrorClass.RATE_LIMIT,
            inBand("invalid_value", "bad field") to ErrorClass.TERMINAL,
            d.sessionFailed().code to ErrorClass.TERMINAL,
            d.protocolError(IllegalStateException()).code to ErrorClass.TERMINAL,
        )
        assertEquals(
            setOf(
                "QWEN_READY_TIMEOUT", "QWEN_CONNECTION_CLOSED", "QWEN_DNS_FAILED", "QWEN_CONNECTION_FAILED",
                "QWEN_SERVER_UNAVAILABLE", "QWEN_AUTH_FAILED", "QWEN_QUOTA_EXHAUSTED", "QWEN_PROVIDER_ERROR",
                "QWEN_SESSION_FAILED", "QWEN_PROTOCOL_ERROR",
            ),
            produced.keys,
        )
        produced.forEach { (code, expected) -> assertEquals(expected, classifyVoiceError(code), code) }
        // Settings codes, as they classify today (config failures are not retried).
        listOf(
            "QWEN_API_KEY_MISSING", "QWEN_CONSENT_MISSING", "QWEN_WORKSPACE_MISSING", "QWEN_WORKSPACE_INVALID",
            "QWEN_MODEL_INVALID", "QWEN_VOICE_INVALID", "QWEN_SILENCE_INVALID", "QWEN_VAD_INVALID", "QWEN_ENDPOINT_INVALID",
        ).forEach { assertEquals(ErrorClass.TERMINAL, classifyVoiceError(it), it) }
    }

    // ---- correction R3, R4, R5, R6 ----

    @Test
    fun aFunctionCallOutputRefusalDoesNotTurnTextOff() {
        val d = opened()
        d.functionCallOutput("call_1", "{}")
        val callIdError = """{"type":"error","error":{"code":"invalid_value","param":"item.call_id","message":"unknown call"}}"""
        d.parseCommonEvent(callIdError, false)
        assertTrue(d.userTextMessage("纠正") != null, "no text item was outstanding")
        d.parseCommonEvent(callIdError, false)
        assertTrue(d.userTextMessage("纠正") != null, "item.call_id belongs to a function_call_output")
    }

    @Test
    fun aLateTextRefusalIsStillNotFatal() {
        // The correction went out after a reset or behind a running reply: other events came first.
        val d = opened()
        d.userTextMessage("纠正")
        d.parseCommonEvent("""{"type":"conversation.item.created","item":{"type":"message"}}""", false)
        d.parseCommonEvent("""{"type":"response.created","response":{"id":"r1"}}""", false)
        assertTrue(d.parseCommonEvent("""{"type":"error","error":{"code":"invalid_value","param":"item.type","message":"x"}}""", false).isEmpty())
        assertNull(d.userTextMessage("纠正"), "text is off for this session")
    }

    @Test
    fun aRefusedFunctionCallOutputIsNotFatal() {
        val d = opened()
        val refusal = """{"type":"error","error":{"code":"invalid_value","param":"item.call_id","message":"unknown call"}}"""
        assertTrue(d.parseCommonEvent(refusal, false).isEmpty())
        assertEquals("invalid_value kind=call_output_refused", d.errorCode(refusal))
    }

    @Test
    fun internalMatchesOnlyAsAWordStart() {
        val d = opened()
        fun code(c: String, m: String) =
            (d.parseCommonEvent("""{"type":"error","error":{"code":"$c","message":"$m"}}""", false).single() as DomainVoiceEvent.Error).code
        assertEquals("QWEN_SERVER_UNAVAILABLE", code("InternalError", "x"))
        assertEquals("QWEN_SERVER_UNAVAILABLE", code("x", "internal server error"))
        assertEquals("QWEN_PROVIDER_ERROR", code("invalid_value", "international number format"))
    }

    @Test
    fun statusNumbersMatchOnlyAsWholeTokens() {
        val d = opened()
        fun code(c: String, m: String) =
            (d.parseCommonEvent("""{"type":"error","error":{"code":"$c","message":"$m"}}""", false).single() as DomainVoiceEvent.Error).code
        assertEquals("QWEN_PROVIDER_ERROR", code("invalid_value", "audio 4010 ms too long"))
        assertEquals("QWEN_PROVIDER_ERROR", code("invalid_value", "14290 bytes"))
        assertEquals("QWEN_AUTH_FAILED", code("401", "x"))
        assertEquals("QWEN_AUTH_FAILED", code("x", "HTTP 403 forbidden"))
        assertEquals("QWEN_QUOTA_EXHAUSTED", code("x", "status 429"))
    }

    @Test
    fun aFailedTranscriptionIsNotAnError() {
        assertTrue(opened().parseCommonEvent("""{"type":"conversation.item.input_audio_transcription.failed","error":{"code":"x"}}""", false).isEmpty())
    }

    @Test
    fun anUnparseableWorkspaceNeverReachesAnExceptionMessage() {
        val d = QwenOmniDialect(requireTls = false, endpoint = { "wss://bad host $workspace/x" })
        val failure = assertThrows<VoiceProviderException> { runBlocking { d.buildRequest(config) } }
        assertEquals("QWEN_WORKSPACE_INVALID", failure.code)
        assertFalse(failure.toString().contains(workspace))
        assertNull(failure.cause)
    }

    @Test
    fun workspaceRedactionIgnoresCase() {
        val event = opened().parseCommonEvent(
            """{"type":"error","error":{"code":"x","message":"host ${workspace.uppercase()} failed"}}""", false,
        ).single() as DomainVoiceEvent.Error
        assertFalse(event.message.contains(workspace, ignoreCase = true), event.message)
    }

    @Test
    fun theProviderSkipsWaitCuesWithoutThrowing() {
        val provider = QwenOmniProvider(config)
        val declared = QwenOmniProvider::class.java.getDeclaredMethod("onLocalSpeechActivity", Boolean::class.javaPrimitiveType)
        assertEquals(QwenOmniProvider::class.java, declared.declaringClass)
        provider.onLocalSpeechActivity(true)
        provider.onLocalSpeechActivity(false)
        provider.close()
    }

    /** Correction R2: an enabled but incomplete Azure voice never fails or shapes a Qwen start. */
    @Test
    fun anEnabledAzureVoiceWithoutAKeyStillYieldsTheQwenConfig() {
        val built = QwenSettingsValidator.sessionConfig(azureVoiceEnabled = true) {
            QwenSettingsValidator.configOrThrow(settings, key, "PERSONA")
        }
        assertEquals(config, built)
        assertEquals(config, QwenSettingsValidator.sessionConfig(azureVoiceEnabled = false) { config })
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://example.invalid/").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("m").build()
}
