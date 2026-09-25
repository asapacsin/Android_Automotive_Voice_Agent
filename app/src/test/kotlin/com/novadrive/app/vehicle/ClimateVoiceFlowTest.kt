package com.novadrive.app.vehicle

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.NavigationState
import com.novadrive.app.voice.FlexFunctionCallAssembler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.simulator.ClimateOperation
import com.novadrive.simulator.InjectedClimateFailure
import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.ClimateState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Level C: representative requests, as the realtime model emits them (function-call events on
 * the Baidu Flex wire), through the real assembler, validator and dispatcher, into the simulated
 * backend — then the state is read back.
 *
 * Scope, stated plainly: the speech → function-call step is the model's and is not reproducible
 * in a JVM test. The phrase → arguments mapping used here is the one the tool description gives
 * the model; that the model follows it is an L5 (device, human voice) check.
 */
class ClimateVoiceFlowTest {
    private val phrases = mapOf(
        "打开空调" to """{"action":"power_on"}""",
        "关闭空调" to """{"action":"power_off"}""",
        "空调调到22度" to """{"action":"set_temperature","value":22}""",
        "温度调高一点" to """{"action":"adjust_temperature","value":1}""",
        "温度调低一点" to """{"action":"adjust_temperature","value":-1}""",
        "风量调大" to """{"action":"adjust_fan","value":1}""",
        "风量调到7档" to """{"action":"set_fan","value":7}""",
        "空调调到50度" to """{"action":"set_temperature","value":50}""",
    )

    private val vehicle = SimulatedVehicleControl()
    private val dispatcher = AndroidToolDispatcher(NoopExecutor, ClimateToolHandler(vehicle), com.novadrive.app.noCamera())
    private val assembler = FlexFunctionCallAssembler()
    private var seq = 0

    @AfterEach
    fun tearDown() = NavigationState.reset()

    private fun say(phrase: String): ToolDispatchResult {
        val arguments = requireNotNull(phrases[phrase]) { "no scripted tool call for $phrase" }
        val callId = "call_${seq++}"
        assembler.consume(
            """{"type":"response.output_item.added","item":{"id":"item_$callId","type":"function_call","call_id":"$callId","name":"control_climate"}}""",
        )
        val done = JSONObject().put("type", "response.function_call_arguments.done")
            .put("call_id", callId).put("arguments", arguments).toString()
        val call = assembler.consume(done).single() as DomainVoiceEvent.ToolCall
        return dispatcher.dispatch(call)
    }

    private fun state() = runBlocking { vehicle.getClimateState() }

    private fun ToolDispatchResult.json() = JSONObject(requireNotNull(output))

    @Test
    fun acOnConfirmsFromObservedState() {
        val result = say("打开空调")
        assertTrue(result.json().getBoolean("ok"))
        assertTrue(result.json().getBoolean("power_on"))
        assertTrue(state().powerOn)
        assertEquals("✓ 空调开 · 24°C · 风2", result.successChip)
    }

    @Test
    fun absoluteTemperature() {
        assertEquals(24.0, state().targetTemperatureCelsius)
        val result = say("空调调到22度")
        assertEquals(22.0, result.json().getDouble("temperature_c"))
        assertEquals(22.0, state().targetTemperatureCelsius)
    }

    @Test
    fun relativeTemperatureUsesVehicleStateRepeatedly() {
        say("空调调到22度")
        assertEquals(23.0, say("温度调高一点").json().getDouble("temperature_c"))
        assertEquals(24.0, say("温度调高一点").json().getDouble("temperature_c"))
        assertEquals(24.0, state().targetTemperatureCelsius)
        assertEquals(23.0, say("温度调低一点").json().getDouble("temperature_c"))
    }

    @Test
    fun fanIncreaseAndMaximum() {
        assertEquals(3, say("风量调大").json().getInt("fan_level"))
        assertEquals(3, state().fanLevel)
        say("风量调到7档")
        val atMax = say("风量调大").json()
        assertTrue(atMax.getBoolean("ok"))
        assertEquals(7, atMax.getInt("fan_level"))
        assertTrue(atMax.getBoolean("limit_reached"))
        assertEquals(7, state().fanLevel)
    }

    @Test
    fun offKeepsTemperature() {
        say("打开空调")
        say("空调调到22度")
        say("关闭空调")
        assertEquals(ClimateState(powerOn = false, targetTemperatureCelsius = 22.0, fanLevel = 2), state())
    }

    @Test
    fun invalidTemperatureIsRefusedAndStateUnchanged() {
        val result = say("空调调到50度")
        assertFalse(result.json().getBoolean("ok"))
        assertEquals("INVALID_ARGUMENT", result.blockedReason)
        assertNull(result.successChip)
        assertEquals(24.0, state().targetTemperatureCelsius)
    }

    @Test
    fun vehicleFailureIsNeverReportedAsSuccess() {
        for (kind in InjectedClimateFailure.entries) {
            vehicle.faults.failAlways[ClimateOperation.POWER] = kind
            val result = say("打开空调")
            val json = result.json()
            assertFalse(json.getBoolean("ok"), "$kind")
            assertNull(result.successChip, "$kind")
            assertTrue(result.blockedReason!!.isNotBlank(), "$kind")
            assertTrue(json.getString("instruction").contains("不要说已经完成"), "$kind")
            assertFalse(state().powerOn, "$kind")
        }
        vehicle.faults.failAlways.clear()
        assertTrue(say("打开空调").json().getBoolean("ok"))
    }

    @Test
    fun malformedModelArgumentsAreRejectedBeforeTheVehicle() {
        val bad = listOf(
            """{"action":"set_temperature","value":"22"}""",
            """{"action":"set_temperature"}""",
            """{"action":"open_sunroof"}""",
            """{"action":"power_on","zone":"driver"}""",
            """{"value":1}""",
        )
        for (arguments in bad) {
            val callId = "bad_${seq++}"
            assembler.consume(
                """{"type":"response.output_item.added","item":{"id":"i","type":"function_call","call_id":"$callId","name":"control_climate"}}""",
            )
            val done = JSONObject().put("type", "response.function_call_arguments.done")
                .put("call_id", callId).put("arguments", arguments).toString()
            val call = assembler.consume(done).single() as DomainVoiceEvent.ToolCall
            assertTrue(call.arguments.containsKey("_validation_error"), arguments)
            val result = dispatcher.dispatch(call)
            assertFalse(result.json().getBoolean("ok"), arguments)
        }
        assertEquals(ClimateState.DEFAULT, state())
    }

    @Test
    fun climateResultIsAudibleDuringNavigation() {
        NavigationState.begin()
        assertTrue(com.novadrive.app.voice.SpeechAuthority.arbiter.navigationMuted())
        say("打开空调")
        assertFalse(com.novadrive.app.voice.SpeechAuthority.arbiter.navigationMuted())
    }

    private object NoopExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Rejected("unused")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Rejected("unused")
        override fun playMusic() = AndroidActionResult.Rejected("unused")
        override fun stopMusic() = AndroidActionResult.Rejected("unused")
        override fun exitNavigationMode() = AndroidActionResult.Rejected("unused")
    }
}
