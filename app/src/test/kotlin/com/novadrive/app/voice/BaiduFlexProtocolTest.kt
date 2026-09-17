package com.novadrive.app.voice

import com.novadrive.app.PersonaProfiles
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaiduFlexProtocolTest {
    @Test
    fun sessionUpdateContainsOnlyTwoTightlyControlledTools() {
        val root = JSONObject(BaiduFlexProtocol.sessionUpdate("x"))
        val session = root.getJSONObject("session")
        assertEquals("session.update", root.getString("type"))
        assertEquals(BaiduFlexProtocol.MODEL, session.getString("model"))
        assertEquals("auto", session.getString("tool_choice"))
        val tools = session.getJSONArray("tools")
        assertEquals(6, tools.length())
        assertEquals(
            setOf("navigate_to", "open_app", "control_music", "control_climate", "describe_camera_view", "exit_navigation_mode"),
            (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet(),
        )
        val openApp = (0 until tools.length()).map { tools.getJSONObject(it) }.single { it.getString("name") == "open_app" }
        val allowed = openApp.getJSONObject("parameters").getJSONObject("properties").getJSONObject("app").getJSONArray("enum")
        assertEquals(listOf("maps", "settings"), (0 until allowed.length()).map(allowed::getString))
        val controlMusic = (0 until tools.length()).map { tools.getJSONObject(it) }.single { it.getString("name") == "control_music" }
        val actions = controlMusic.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action").getJSONArray("enum")
        assertEquals(listOf("play", "stop"), (0 until actions.length()).map(actions::getString))
        assertFalse(root.toString().contains("package"))
        assertFalse(root.toString().contains("shell"))
    }

    @Test
    fun controlMusicDescriptionBindsChineseStopAndPlayVerbs() {
        val session = JSONObject(BaiduFlexProtocol.sessionUpdate("x")).getJSONObject("session")
        val tools = session.getJSONArray("tools")
        assertEquals(6, tools.length())
        val controlMusic = (0 until tools.length()).map { tools.getJSONObject(it) }
            .single { it.getString("name") == "control_music" }
        val description = controlMusic.getString("description")
        assertTrue(description.contains("关闭音乐"))
        assertTrue(description.contains("播放音乐"))
        val action = controlMusic.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action")
        assertTrue(action.optString("description").isNotEmpty())
        assertEquals(0.62, session.getJSONObject("turn_detection").getDouble("threshold"))
    }

    @Test
    fun sessionUpdatePrefixesPersonaAndAppendsFlexToolRule() {
        val session = JSONObject(BaiduFlexProtocol.sessionUpdate("你是小诺")).getJSONObject("session")
        val instructions = session.getString("instructions")
        assertTrue(instructions.startsWith("你是小诺"))
        assertTrue(instructions.endsWith(PersonaProfiles.FLEX_TOOL_RULE))
        val turn = session.getJSONObject("turn_detection")
        assertEquals("server_vad", turn.getString("type"))
        assertEquals(0.62, turn.getDouble("threshold"))
        assertEquals(300, turn.getInt("prefix_padding_ms"))
        assertEquals(200, turn.getInt("silence_duration_ms"))
        assertTrue(turn.getBoolean("create_response"))
        assertTrue(turn.getBoolean("interrupt_response"))
        val tools = session.getJSONArray("tools")
        assertEquals(6, tools.length())
        assertEquals(
            setOf("navigate_to", "open_app", "control_music", "control_climate", "describe_camera_view", "exit_navigation_mode"),
            (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet(),
        )
    }

    @Test
    fun exitNavigationModeIsDeclaredAndDoesNotClaimToStopAmap() {
        val session = JSONObject(BaiduFlexProtocol.sessionUpdate("x")).getJSONObject("session")
        val tools = session.getJSONArray("tools")
        assertEquals(6, tools.length())
        val exit = (0 until tools.length()).map { tools.getJSONObject(it) }
            .single { it.getString("name") == "exit_navigation_mode" }
        val description = exit.getString("description")
        assertTrue(description.contains("结束导航"))
        assertTrue(description.contains("算了"))
        assertTrue(description.contains("真正停止"))
        assertFalse(description.contains("并不会关闭高德"), "the embedded SDK makes the old disclaimer false")
        assertEquals(0.62, session.getJSONObject("turn_detection").getDouble("threshold"))
        val parameters = exit.getJSONObject("parameters")
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertFalse(parameters.getBoolean("additionalProperties"))
    }

    @Test
    fun exitNavigationModeAcceptsEmptyObjectAndRejectsExtraFields() {
        val empty = FlexFunctionCallAssembler().apply { consume(item("call_exit", "exit_navigation_mode")) }
            .consume(done("call_exit", "{}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("exit_navigation_mode", empty.name)
        assertFalse(empty.arguments.containsKey("_validation_error"))

        val extra = FlexFunctionCallAssembler().apply { consume(item("call_extra", "exit_navigation_mode")) }
            .consume(done("call_extra", "{\"foo\":\"bar\"}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("INVALID_FIELDS", extra.arguments["_validation_error"])
    }

    @Test
    fun sessionUpdateUsesConfigurableVoiceAndSpeed() {
        val session = JSONObject(BaiduFlexProtocol.sessionUpdate("x", "4157", 1.1)).getJSONObject("session")
        assertEquals("4157", session.getString("voice"))
        assertEquals(1.1, session.getDouble("speed"))
    }

    @Test
    fun sessionUpdateDefaultEmitsTunedVadThreshold() {
        val session = JSONObject(BaiduFlexProtocol.sessionUpdate("x")).getJSONObject("session")
        val turn = session.getJSONObject("turn_detection")
        assertEquals(0.62, turn.getDouble("threshold"))
        assertEquals(BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD, turn.getDouble("threshold"))
        assertEquals(6, session.getJSONArray("tools").length())
        assertEquals(300, turn.getInt("prefix_padding_ms"))
        assertEquals(200, turn.getInt("silence_duration_ms"))
    }

    @Test
    fun sessionUpdateNavigationVadThresholdLeavesPaddingAndToolsUnchanged() {
        val session = JSONObject(
            BaiduFlexProtocol.sessionUpdate("x", vadThreshold = BaiduFlexProtocol.NAVIGATION_VAD_THRESHOLD),
        ).getJSONObject("session")
        val turn = session.getJSONObject("turn_detection")
        assertEquals(0.75, turn.getDouble("threshold"))
        assertEquals(BaiduFlexProtocol.NAVIGATION_VAD_THRESHOLD, turn.getDouble("threshold"))
        assertEquals(6, session.getJSONArray("tools").length())
        assertEquals(300, turn.getInt("prefix_padding_ms"))
        assertEquals(200, turn.getInt("silence_duration_ms"))
    }

    @Test
    fun streamsArgumentsButEmitsOnlyOnDoneAndOnlyOnce() {
        val assembler = FlexFunctionCallAssembler()
        assertTrue(assembler.consume(item("call_1", "navigate_to")).isEmpty())
        assertTrue(assembler.consume(delta("call_1", "{\"destination\":")).isEmpty())
        assertTrue(assembler.consume(delta("call_1", "\"Macau Tower\"}")).isEmpty())
        val event = assembler.consume(done("call_1", "{\"destination\":\"Macau Tower\"}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("call_1", event.callId)
        assertEquals("navigate_to", event.name)
        assertEquals("Macau Tower", event.arguments["destination"])
        assertTrue(assembler.consume(done("call_1", "{\"destination\":\"Other\"}")).isEmpty())
    }

    @Test
    fun controlMusicStopIsExecutableAndPauseIsRejected() {
        val stop = FlexFunctionCallAssembler().apply { consume(item("call_music", "control_music")) }
            .consume(done("call_music", "{\"action\":\"stop\"}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("control_music", stop.name)
        assertEquals("stop", stop.arguments["action"])

        val pause = FlexFunctionCallAssembler().apply { consume(item("call_pause", "control_music")) }
            .consume(done("call_pause", "{\"action\":\"pause\"}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("ACTION_NOT_ALLOWED", pause.arguments["_validation_error"])
    }

    @Test
    fun malformedAndInvalidArgumentsNeverBecomeExecutableArguments() {
        val malformed = FlexFunctionCallAssembler().apply { consume(item("call_bad", "navigate_to")) }
            .consume(done("call_bad", "not-json")).single() as DomainVoiceEvent.ToolCall
        assertEquals("MALFORMED_JSON", malformed.arguments["_validation_error"])

        val disallowed = FlexFunctionCallAssembler().apply { consume(item("call_app", "open_app")) }
            .consume(done("call_app", "{\"app\":\"com.example.anything\"}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("APP_NOT_ALLOWED", disallowed.arguments["_validation_error"])
    }

    @Test
    fun reconnectClearDropsPartialFunctionState() {
        val assembler = FlexFunctionCallAssembler()
        assembler.consume(item("call_active", "navigate_to"))
        assembler.consume(delta("call_active", "{\"destination\":\"Old"))
        assembler.clear()
        val event = assembler.consume(done("call_active", "{\"destination\":\"Old\"}")).single() as DomainVoiceEvent.ToolCall
        assertEquals("MISSING_TOOL_METADATA", event.arguments["_validation_error"])
    }

    @Test
    fun functionResultPreservesCallIdAndResponseLoopIsDocumented() {
        val output = JSONObject(BaiduFlexProtocol.functionCallOutput("call_42", "{\"ok\":true}"))
        assertEquals("conversation.item.create", output.getString("type"))
        assertEquals("function_call_output", output.getJSONObject("item").getString("type"))
        assertEquals("call_42", output.getJSONObject("item").getString("call_id"))
        assertEquals("response.create", JSONObject(BaiduFlexProtocol.responseCreate()).getString("type"))
    }

    @Test
    fun cancelledResponseRemainsAnInterruption() {
        val events = BaiduFlexProtocol.parseCommonEvent(
            """{"type":"response.done","response":{"status":"cancelled","status_details":{"reason":"turn_detected"}}}""",
            speaking = true,
        )
        assertTrue(events.first() is DomainVoiceEvent.Interrupted)
    }

    @Test
    fun benignCancellationRefusalEmitsNothing() {
        val events = BaiduFlexProtocol.parseCommonEvent(
            """{"type":"error","error":{"code":"invalid_request_error","message":"Cancellation failed: no active response found"}}""",
            speaking = true,
        )
        assertTrue(events.isEmpty())
        assertTrue(events.none { it is DomainVoiceEvent.Error })
    }

    @Test
    fun userTextMessageIsAUserInputTextItem() {
        val json = JSONObject(BaiduFlexProtocol.userTextMessage("请读出：前方有车"))
        assertEquals("conversation.item.create", json.getString("type"))
        val item = json.getJSONObject("item")
        assertEquals("message", item.getString("type"))
        assertEquals("user", item.getString("role"))
        val part = item.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", part.getString("type"))
        assertEquals("请读出：前方有车", part.getString("text"))
    }

    @Test
    fun refusedSessionUpdateIsNotSessionFatal() {
        // Verbatim from the device log, 2026-09-16.
        val events = BaiduFlexProtocol.parseCommonEvent(
            """{"type":"error","error":{"code":"invalid_value","message":"Invalid value: 0.750000. Cannot update a session's turn detection threshold while input audio is in progress, current value is 0.620000."}}""",
            speaking = false,
        )
        assertTrue(events.none { it is DomainVoiceEvent.Error })
    }

    @Test
    fun genuineQuotaAndAccessDeniedErrorsStillMapAsBefore() {
        val quota = BaiduFlexProtocol.parseCommonEvent(
            """{"type":"error","error":{"code":"quota_exceeded","message":"quota exceeded"}}""",
            speaking = false,
        ).single() as DomainVoiceEvent.Error
        assertEquals("BAIDU_FLEX_QUOTA_EXHAUSTED", quota.code)

        val denied = BaiduFlexProtocol.parseCommonEvent(
            """{"type":"error","error":{"code":"permission","message":"access denied"}}""",
            speaking = false,
        ).single() as DomainVoiceEvent.Error
        assertEquals("BAIDU_FLEX_ACCESS_DENIED", denied.code)
        assertEquals("Baidu Flex public-beta/model access was denied", denied.message)
    }

    private fun item(callId: String, name: String) =
        """{"type":"response.output_item.added","item":{"id":"item_1","type":"function_call","call_id":"$callId","name":"$name"}}"""
    private fun delta(callId: String, value: String) = JSONObject().put("type", "response.function_call_arguments.delta")
        .put("call_id", callId).put("delta", value).toString()
    private fun done(callId: String, value: String) = JSONObject().put("type", "response.function_call_arguments.done")
        .put("call_id", callId).put("arguments", value).toString()
}
