package com.novadrive.app.vehicle

import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Level B: the tool layer calls the right [VehicleControlPort] method with the right argument,
 * and maps every result kind faithfully. Uses a recording port, never simulator internals.
 */
class ClimateToolHandlerTest {
    private class RecordingPort(var next: VehicleActionResult? = null) : VehicleControlPort {
        val calls = mutableListOf<String>()
        private val state = ClimateState(powerOn = true, targetTemperatureCelsius = 22.0, fanLevel = 3)
        override val climateState: StateFlow<ClimateState> = MutableStateFlow(state)
        private fun record(call: String): VehicleActionResult {
            calls += call
            return next ?: VehicleActionResult.Success(state)
        }
        override suspend fun setHvacPower(on: Boolean) = record("setHvacPower($on)")
        override suspend fun setCabinTemperature(celsius: Double) = record("setCabinTemperature($celsius)")
        override suspend fun changeCabinTemperature(deltaCelsius: Double) = record("changeCabinTemperature($deltaCelsius)")
        override suspend fun setFanLevel(level: Int) = record("setFanLevel($level)")
        override suspend fun changeFanLevel(delta: Int) = record("changeFanLevel($delta)")
        override suspend fun getClimateState(): ClimateState {
            calls += "getClimateState()"
            return state
        }
    }

    private fun handle(port: RecordingPort, vararg args: Pair<String, String>) =
        runBlocking { ClimateToolHandler(port).handle(mapOf(*args)) }

    @Test
    fun eachActionCallsExactlyOnePortMethod() {
        val cases = listOf(
            mapOf("action" to "power_on") to "setHvacPower(true)",
            mapOf("action" to "power_off") to "setHvacPower(false)",
            mapOf("action" to "set_temperature", "value" to "22") to "setCabinTemperature(22.0)",
            mapOf("action" to "set_temperature", "value" to "22.5") to "setCabinTemperature(22.5)",
            mapOf("action" to "adjust_temperature", "value" to "1") to "changeCabinTemperature(1.0)",
            mapOf("action" to "adjust_temperature", "value" to "-2") to "changeCabinTemperature(-2.0)",
            mapOf("action" to "adjust_temperature") to "changeCabinTemperature(1.0)",
            mapOf("action" to "set_fan", "value" to "3") to "setFanLevel(3)",
            mapOf("action" to "set_fan", "value" to "3.0") to "setFanLevel(3)",
            mapOf("action" to "adjust_fan", "value" to "1") to "changeFanLevel(1)",
            mapOf("action" to "adjust_fan") to "changeFanLevel(1)",
            mapOf("action" to "adjust_fan", "value" to "-1") to "changeFanLevel(-1)",
            mapOf("action" to "get_state") to "getClimateState()",
        )
        for ((args, expected) in cases) {
            val port = RecordingPort()
            val outcome = runBlocking { ClimateToolHandler(port).handle(args) }
            assertEquals(listOf(expected), port.calls, "$args")
            assertTrue(outcome.ok, "$args")
        }
    }

    @Test
    fun successOutputCarriesTheStateReadBackFromThePort() {
        val outcome = handle(RecordingPort(), "action" to "power_on")
        val json = JSONObject(outcome.output)
        assertTrue(json.getBoolean("ok"))
        assertTrue(json.getBoolean("power_on"))
        assertEquals(22.0, json.getDouble("temperature_c"))
        assertEquals(3, json.getInt("fan_level"))
        assertFalse(json.getBoolean("limit_reached"))
        assertEquals("✓ 空调开 · 22°C · 风3", outcome.chip)
        assertNull(outcome.errorCode)
    }

    @Test
    fun limitReachedIsPassedThrough() {
        val limited = VehicleActionResult.Success(ClimateState(true, 22.0, 7), limitReached = true)
        val json = JSONObject(handle(RecordingPort(limited), "action" to "adjust_fan").output)
        assertTrue(json.getBoolean("limit_reached"))
        assertEquals(7, json.getInt("fan_level"))
    }

    @Test
    fun everyFailureKindIsReportedAsNotOkWithoutChip() {
        val cases = mapOf(
            VehicleActionResult.InvalidArgument("bad") to "INVALID_ARGUMENT",
            VehicleActionResult.Unsupported("hvac") to "VEHICLE_UNSUPPORTED",
            VehicleActionResult.Unavailable("offline") to "VEHICLE_UNAVAILABLE",
            VehicleActionResult.PermissionDenied("no") to "VEHICLE_PERMISSION_DENIED",
            VehicleActionResult.Failure("boom") to "VEHICLE_EXECUTION_FAILED",
        )
        for ((result, code) in cases) {
            val outcome = handle(RecordingPort(result), "action" to "power_on")
            val json = JSONObject(outcome.output)
            assertFalse(outcome.ok, code)
            assertFalse(json.getBoolean("ok"), code)
            assertEquals(code, json.getString("error"))
            assertEquals(code, outcome.errorCode)
            assertNull(outcome.chip, code)
            assertTrue(json.getString("instruction").contains("不要说已经完成"))
            assertFalse(json.has("power_on"), "a failure must not present a state as if it applied")
        }
    }

    @Test
    fun malformedArgumentsNeverReachThePort() {
        val bad = listOf(
            emptyMap(),
            mapOf("action" to "open_sunroof"),
            mapOf("action" to "set_temperature"),
            mapOf("action" to "set_temperature", "value" to "warm"),
            mapOf("action" to "set_fan", "value" to "2.5"),
            mapOf("action" to "adjust_fan", "value" to "lots"),
            mapOf("action" to "adjust_temperature", "value" to "hot"),
        )
        for (args in bad) {
            val port = RecordingPort()
            val outcome = runBlocking { ClimateToolHandler(port).handle(args) }
            assertTrue(port.calls.isEmpty(), "$args")
            assertFalse(outcome.ok, "$args")
        }
    }
}
