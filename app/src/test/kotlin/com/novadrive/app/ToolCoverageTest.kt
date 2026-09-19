package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.BaiduFlexProtocol
import com.novadrive.app.voice.FlexFunctionCallAssembler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Every tool the model is offered must be accepted by the argument validator AND handled by the
 * dispatcher. A tool that is declared but not dispatched would silently answer UNKNOWN_TOOL.
 */
class ToolCoverageTest {
    @org.junit.jupiter.api.AfterEach
    fun tearDown() = NavigationState.reset()

    private val validArguments = mapOf(
        "navigate_to" to """{"destination":"珠海站"}""",
        "open_app" to """{"app":"settings"}""",
        "control_music" to """{"action":"stop"}""",
        "control_climate" to """{"action":"get_state"}""",
        "describe_camera_view" to """{"question":"前面有什么"}""",
        "exit_navigation_mode" to """{}""",
        "choose_navigation_option" to """{"index":2}""",
        "end_conversation" to """{}""",
        "set_speech_output" to """{"mode":"silent"}""",
        // A save that cannot resolve the address is still dispatched - it just fails with
        // ADDRESS_NOT_FOUND rather than UNKNOWN_TOOL, which is what this test checks.
        "save_place" to """{"slot":"home","address":"珠海站"}""",
        // Dispatched, then refused with NO_TELEPHONY on a device with no SIM - which is a
        // dispatch, not an UNKNOWN_TOOL, and that is what this test checks.
        "place_call" to """{"contact":"张三"}""",
    )

    private fun declaredTools(): List<String> {
        val tools = JSONObject(BaiduFlexProtocol.sessionUpdate("x")).getJSONObject("session").getJSONArray("tools")
        return (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
    }

    @Test
    fun everyDeclaredToolHasAValidExampleAndADispatcherBranch() {
        val declared = declaredTools()
        assertEquals(validArguments.keys, declared.toSet(), "update this test when a tool is added or removed")
        val executor = RecordingExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        for (name in declared) {
            val assembler = FlexFunctionCallAssembler()
            assembler.consume(
                """{"type":"response.output_item.added","item":{"id":"i","type":"function_call","call_id":"c_$name","name":"$name"}}""",
            )
            val call = assembler.consume(
                JSONObject().put("type", "response.function_call_arguments.done").put("call_id", "c_$name")
                    .put("arguments", validArguments.getValue(name)).toString(),
            ).single() as DomainVoiceEvent.ToolCall
            assertFalse(call.arguments.containsKey("_validation_error"), "$name: valid arguments rejected")
            val result = dispatcher.dispatch(call)
            assertNotEquals("UNKNOWN_TOOL", result.blockedReason, "$name is declared but not dispatched")
        }
        assertEquals(setOf("navigate", "openApp", "stopMusic", "exitNavigationMode", "chooseNavigationOption", "endConversation", "setSpeechSilent"), executor.calls)
    }

    @Test
    fun anUndeclaredToolNameIsRefused() {
        val result = AndroidToolDispatcher(RecordingExecutor(), ClimateToolHandler(SimulatedVehicleControl()), noCamera())
            .dispatch(DomainVoiceEvent.ToolCall("x", "unlock_doors", emptyMap()))
        assertEquals("UNKNOWN_TOOL", result.blockedReason)
    }

    private class RecordingExecutor : AndroidActionExecutor {
        val calls = mutableSetOf<String>()
        override fun navigate(destination: String): AndroidActionResult { calls += "navigate"; return AndroidActionResult.Accepted() }
        override fun openApp(app: AllowedApp): AndroidActionResult { calls += "openApp"; return AndroidActionResult.Accepted() }
        override fun playMusic(): AndroidActionResult { calls += "playMusic"; return AndroidActionResult.Accepted() }
        override fun stopMusic(): AndroidActionResult { calls += "stopMusic"; return AndroidActionResult.Accepted() }
        override fun exitNavigationMode(): AndroidActionResult { calls += "exitNavigationMode"; return AndroidActionResult.Accepted() }
        override fun endConversation(): AndroidActionResult { calls += "endConversation"; return AndroidActionResult.Accepted() }
        override fun setSpeechSilent(silent: Boolean): AndroidActionResult { calls += "setSpeechSilent"; return AndroidActionResult.Accepted() }
        override fun chooseNavigationOption(choice: com.novadrive.app.nav.NavigationChoice): AndroidActionResult {
            calls += "chooseNavigationOption"; return AndroidActionResult.Accepted()
        }
    }
}
