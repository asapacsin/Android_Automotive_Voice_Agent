package com.novadrive.app.voice

import com.novadrive.ingress.realtime.ErrorClass
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.classifyVoiceError
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiLiveProtocolTest {
    private fun setup(silence: Int? = null, handle: String? = null) = JSONObject(
        GeminiLiveProtocol.setup(
            model = "gemini-3.8-live-extended-thinking",
            voice = "Kore",
            thinkingLevel = "MEDIUM",
            instructions = "你是小诺。",
            silenceDurationMs = silence,
            resumptionHandle = handle,
        ),
    ).getJSONObject("setup")

    @Test
    fun setupPerModelFollowsTheThinkingLevelTrait() {
        fun gen(model: String) = JSONObject(
            GeminiLiveProtocol.setup(
                model = model,
                voice = "Kore",
                thinkingLevel = "LOW".takeIf { VoiceCatalog.geminiAcceptsThinkingLevel(model) },
                instructions = "你是小诺。",
                silenceDurationMs = null,
                resumptionHandle = null,
            ),
        ).getJSONObject("setup").getJSONObject("generationConfig")
        assertFalse(gen(VoiceCatalog.GEMINI_LIVE_FAST).has("thinkingConfig"))
        assertEquals("LOW", gen(VoiceCatalog.GEMINI_LIVE).getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun setupCarriesModelVoiceThinkingLanguageAndTranscription() {
        val s = setup()
        assertEquals("models/gemini-3.8-live-extended-thinking", s.getString("model"))
        val gen = s.getJSONObject("generationConfig")
        assertEquals("AUDIO", gen.getJSONArray("responseModalities").getString(0))
        assertEquals("MEDIUM", gen.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val speech = gen.getJSONObject("speechConfig")
        assertEquals("zh-CN", speech.getString("languageCode"))
        assertEquals("Kore", speech.getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertTrue(s.has("inputAudioTranscription"))
        assertTrue(s.has("outputAudioTranscription"))
        assertTrue(s.getJSONObject("contextWindowCompression").has("slidingWindow"))
        assertEquals("models/x", GeminiLiveProtocol.modelName("models/x"))
    }

    @Test
    fun systemInstructionHasPersonaThenCallFirstHint() {
        val text = setup().getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.startsWith("你是小诺。"))
        assertTrue(text.contains(GeminiLiveProtocol.CALL_FIRST_HINT))
    }

    @Test
    fun resumptionHandleAndSilenceOnlyWhenSet() {
        val plain = setup()
        assertFalse(plain.getJSONObject("sessionResumption").has("handle"))
        assertFalse(plain.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").has("silenceDurationMs"))
        val set = setup(silence = 1200, handle = "h-1")
        assertEquals("h-1", set.getJSONObject("sessionResumption").getString("handle"))
        assertEquals(1200, set.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getInt("silenceDurationMs"))
    }

    @Test
    fun oneDeclarationPerCatalogueToolUsingParametersJsonSchemaAndNoForbiddenFields() {
        val raw = GeminiLiveProtocol.setup("m", "Kore", "LOW", "p", null, null)
        val declarations = JSONObject(raw).getJSONObject("setup").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val catalogue = RealtimeToolCatalog.tools()
        assertEquals(catalogue.size, declarations.length())
        catalogue.forEachIndexed { i, spec ->
            val d = declarations.getJSONObject(i)
            assertEquals(spec.name, d.getString("name"))
            assertEquals(spec.parameters.toString(), d.getJSONObject("parametersJsonSchema").toString())
            assertFalse(d.has("parameters"))
            assertFalse(d.has("behavior"))
        }
        assertFalse(raw.contains("includeThoughts"))
        assertFalse(raw.contains("\"behavior\""))
        assertFalse(raw.contains("googleSearch"))
        assertFalse(raw.contains("\"parameters\""))
    }

    @Test
    fun audioTextAndToolResponseShapes() {
        val audio = JSONObject(GeminiLiveProtocol.audio("AAAA")).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("AAAA", audio.getString("data"))
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        val text = JSONObject(GeminiLiveProtocol.textTurn("你好")).getJSONObject("clientContent")
        assertTrue(text.getBoolean("turnComplete"))
        val turn = text.getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals("你好", turn.getJSONArray("parts").getJSONObject(0).getString("text"))
        val ok = JSONObject(GeminiLiveProtocol.toolResponse("c1", "open_app", "{\"ok\":true}"))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", ok.getString("id"))
        assertEquals("open_app", ok.getString("name"))
        assertTrue(ok.getJSONObject("response").getBoolean("ok"))
        assertFalse(ok.has("scheduling"))
        val wrapped = JSONObject(GeminiLiveProtocol.toolResponse("c2", "open_app", "plain text"))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("plain text", wrapped.getJSONObject("response").getString("output"))
    }

    @Test
    fun parsesEveryServerMessageKind() {
        assertTrue(GeminiLiveProtocol.parse("""{"setupComplete":{}}""").setupComplete)
        val resume = GeminiLiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"h","resumable":true}}""")
        assertEquals("h", resume.resumptionHandle); assertTrue(resume.resumable)
        val content = GeminiLiveProtocol.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"QUJD"}},""" +
                """{"text":"思考","thought":true},{"text":"plain"}]},"inputTranscription":{"text":"打开"},""" +
                """"outputTranscription":{"text":"好的"},"generationComplete":true,"turnComplete":true,"interrupted":true,""" +
                """"interactionStatus":"IN_PROGRESS","someFutureKey":1},"unknownTop":{}}""",
        )
        assertEquals(listOf("QUJD"), content.audio)
        assertEquals(1, content.thoughtParts)
        assertEquals("打开", content.inputTranscription)
        assertEquals("好的", content.outputTranscription)
        assertTrue(content.generationComplete && content.turnComplete && content.interrupted)
        assertEquals(true, content.workPending)
        assertEquals(false, GeminiLiveProtocol.parse("""{"serverContent":{"interactionStatus":"IDLE"}}""").workPending)
        assertEquals(true, GeminiLiveProtocol.parse("""{"serverContent":{"interactionStatus":"INTERACTION_STATUS_IN_PROGRESS"}}""").workPending)
        assertNull(GeminiLiveProtocol.parse("""{"serverContent":{}}""").workPending)
        val call = GeminiLiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"c1","name":"open_app","args":{"app":"maps"}}]}}""")
        assertEquals(1, call.toolCalls.size)
        val event = GeminiLiveProtocol.toolCall(call.toolCalls[0])!!
        assertEquals(mapOf("app" to "maps"), event.arguments)
        assertEquals(listOf("a", "b"), GeminiLiveProtocol.parse("""{"toolCallCancellation":{"ids":["a","b"]}}""").cancelledCallIds)
        assertTrue(GeminiLiveProtocol.parse("""{"goAway":{"timeLeft":"10s"}}""").goAway)
        // BINARY-delivered JSON is decoded to the same text before parsing.
        val bytes = okio.ByteString.of(*"""{"setupComplete":{}}""".toByteArray())
        assertTrue(GeminiLiveProtocol.parse(bytes.utf8()).setupComplete)
    }

    @Test
    fun toolCallValidationShapes() {
        fun call(json: String) = GeminiLiveProtocol.toolCall(
            GeminiLiveProtocol.parse("""{"toolCall":{"functionCalls":[$json]}}""").toolCalls[0],
        )
        assertEquals("APP_NOT_ALLOWED", call("""{"id":"c1","name":"open_app","args":{"app":"x"}}""")!!.arguments["_validation_error"])
        assertEquals("MALFORMED_JSON", call("""{"id":"c1","name":"open_app","args":"x"}""")!!.arguments["_validation_error"])
        val big = "a".repeat(RealtimeToolCatalog.MAX_ARGUMENT_BYTES + 1)
        assertEquals("ARGUMENTS_TOO_LARGE", call("""{"id":"c1","name":"navigate_to","args":{"destination":"$big"}}""")!!.arguments["_validation_error"])
        assertNull(call("""{"id":"bad id!","name":"open_app","args":{"app":"maps"}}"""))
        assertEquals(mapOf("action" to "set_temperature", "value" to "22"), call("""{"id":"c2","name":"control_climate","args":{"action":"set_temperature","value":22}}""")!!.arguments)
    }

    @Test
    fun closeCodesMapToStableCodesAndErrorClasses() {
        val cases = listOf(
            GeminiLiveProtocol.closeCode(1007, "Unknown name") to ErrorClass.MALFORMED,
            GeminiLiveProtocol.closeCode(1008, null) to ErrorClass.AUTH,
            GeminiLiveProtocol.socketFailure(401, null).code to ErrorClass.AUTH,
            GeminiLiveProtocol.socketFailure(403, null).code to ErrorClass.AUTH,
            GeminiLiveProtocol.closeCode(1011, "You exceeded your current quota") to ErrorClass.RATE_LIMIT,
            GeminiLiveProtocol.closeCode(1011, "internal") to ErrorClass.RETRYABLE,
            GeminiLiveProtocol.closeCode(1000, "") to ErrorClass.RETRYABLE,
            GeminiLiveProtocol.TIMEOUT to ErrorClass.RETRYABLE,
            GeminiLiveProtocol.socketFailure(null, null).code to ErrorClass.RETRYABLE,
        )
        assertEquals(
            listOf(
                GeminiLiveProtocol.MALFORMED_SETUP, GeminiLiveProtocol.AUTH_FAILED, GeminiLiveProtocol.AUTH_FAILED,
                GeminiLiveProtocol.AUTH_FAILED, GeminiLiveProtocol.QUOTA_EXCEEDED, GeminiLiveProtocol.UNAVAILABLE,
                GeminiLiveProtocol.CONNECTION_CLOSED, GeminiLiveProtocol.TIMEOUT, GeminiLiveProtocol.CONNECTION_FAILED,
            ),
            cases.map { it.first },
        )
        cases.forEach { (code, expected) -> assertEquals(expected, classifyVoiceError(code), code) }
        val failure = GeminiLiveProtocol.closeFailure(1011, "quota for project secret-thing")
        assertFalse(failure.safeMessage.contains("quota"))
    }

    @Test
    fun rejectedResumedSetupIsRetryableNotMalformed() {
        val resumed = GeminiLiveProtocol.closeFailure(1007, "invalid handle", resumeRejected = true)
        assertEquals(GeminiLiveProtocol.RESUME_UNAVAILABLE, resumed.code)
        assertEquals(ErrorClass.RETRYABLE, classifyVoiceError(resumed.code))
        assertEquals(ErrorClass.MALFORMED, classifyVoiceError(GeminiLiveProtocol.closeFailure(1007, "x").code))
        assertEquals(GeminiLiveProtocol.UNAVAILABLE, GeminiLiveProtocol.closeFailure(1011, "x", resumeRejected = true).code)
    }

    @Test
    fun parsesVoiceActivity() {
        fun type(json: String) = GeminiLiveProtocol.parse(json).voiceActivity
        assertEquals("ACTIVITY_START", type("""{"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"0.360s"}}"""))
        assertEquals("ACTIVITY_END", type("""{"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"1.2s"}}"""))
        assertEquals("ACTIVITY_START", type("""{"serverContent":{"voiceActivity":{"type":"ACTIVITY_START"}}}"""))
        assertNull(type("""{"voiceActivity":{"type":"SOMETHING_ELSE"}}"""))
        assertNull(type("""{"serverContent":{"turnComplete":true}}"""))
    }
}
