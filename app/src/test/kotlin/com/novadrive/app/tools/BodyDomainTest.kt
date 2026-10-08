package com.novadrive.app.tools

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.noCamera
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.SeatId
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-015 B1: the body domain's declarations, argument rules and dispatch. */
class BodyDomainTest {
    private fun v(name: String, json: String) = BodyDomain.validate(name, JSONObject(json))

    @Test
    fun declaresBothToolsAsRepeatSensitive() {
        val specs = BodyDomain.specs()
        assertEquals(listOf("control_window", "control_seat"), specs.map { it.name })
        assertTrue(specs.all { it.repeatSensitive })
        assertTrue(ToolRegistry.PRODUCT.repeatSensitiveNames.containsAll(listOf("control_window", "control_seat")))
        assertSame(BodyDomain, ToolRegistry.PRODUCT.domainOf("control_window"))
        assertSame(BodyDomain, ToolRegistry.PRODUCT.domainOf("control_seat"))
    }

    @Test
    fun windowValidation() {
        assertNull(v("control_window", """{"action":"open"}"""))
        assertNull(v("control_window", """{"action":"set","window":"driver","value":50}"""))
        assertNull(v("control_window", """{"action":"adjust","value":-20}"""))
        assertEquals("INVALID_FIELDS", v("control_window", """{"window":"all"}"""))
        assertEquals("INVALID_FIELDS", v("control_window", """{"action":"open","seat":"driver"}"""))
        assertEquals("INVALID_FIELD_TYPE", v("control_window", """{"action":1}"""))
        assertEquals("ACTION_NOT_ALLOWED", v("control_window", """{"action":"tilt"}"""))
        assertEquals("INVALID_FIELD_TYPE", v("control_window", """{"action":"open","window":3}"""))
        assertEquals("INVALID_FIELD_VALUE", v("control_window", """{"action":"open","window":"sunroof"}"""))
        assertEquals("INVALID_FIELD_TYPE", v("control_window", """{"action":"set","value":"half"}"""))
        assertEquals("MISSING_VALUE", v("control_window", """{"action":"set"}"""))
        assertEquals("INVALID_VALUE", v("control_window", """{"action":"adjust","value":0}"""))
    }

    @Test
    fun seatValidation() {
        assertNull(v("control_seat", """{"action":"adjust_height","value":-1}"""))
        assertNull(v("control_seat", """{"action":"get_state","seat":"passenger"}"""))
        assertEquals("INVALID_FIELDS", v("control_seat", """{"value":1}"""))
        assertEquals("INVALID_FIELDS", v("control_seat", """{"action":"get_state","window":"all"}"""))
        assertEquals("ACTION_NOT_ALLOWED", v("control_seat", """{"action":"recline"}"""))
        assertEquals("INVALID_FIELD_VALUE", v("control_seat", """{"action":"get_state","seat":"rear"}"""))
        assertEquals("INVALID_FIELD_TYPE", v("control_seat", """{"action":"set_height","value":"low"}"""))
        assertEquals("MISSING_VALUE", v("control_seat", """{"action":"adjust_height"}"""))
        assertEquals("MISSING_VALUE", v("control_seat", """{"action":"set_height"}"""))
        assertEquals("INVALID_VALUE", v("control_seat", """{"action":"adjust_height","value":0}"""))
        assertNull(v("control_seat", """{"action":"set_height","value":0}"""))
    }

    private class NoExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Rejected("X")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Rejected("X")
        override fun playMusic() = AndroidActionResult.Rejected("X")
        override fun stopMusic() = AndroidActionResult.Rejected("X")
        override fun exitNavigationMode() = AndroidActionResult.Rejected("X")
    }

    private fun call(name: String, vararg args: Pair<String, String>) = DomainVoiceEvent.ToolCall("c1", name, args.toMap())

    @Test
    fun theDispatcherRoutesBodyCallsToThePort() {
        val port = SimulatedVehicleControl()
        val dispatcher = AndroidToolDispatcher(NoExecutor(), ClimateToolHandler(port), noCamera(), cabin = port) { null }
        val seat = dispatcher.dispatch(call("control_seat", "action" to "adjust_height", "value" to "-1"))
        assertNull(seat.blockedReason)
        assertEquals("✓ 座椅 4档", seat.successChip)
        assertEquals("座椅降低了一档，现在是4档", JSONObject(seat.output!!).getString("announce"))
        assertEquals(4, port.cabinState.value.seatHeights.getValue(SeatId.DRIVER))
        val window = dispatcher.dispatch(call("control_window", "action" to "set", "value" to "50"))
        assertEquals("✓ 车窗 50%", window.successChip)
        assertEquals("车窗都开了一半", JSONObject(window.output!!).getString("announce"))
    }

    @Test
    fun withNoPortEveryBodyCallIsVehicleUnavailable() {
        val dispatcher = AndroidToolDispatcher(NoExecutor(), ClimateToolHandler(SimulatedVehicleControl()), noCamera()) { null }
        listOf(
            call("control_window", "action" to "open"),
            call("control_seat", "action" to "adjust_height", "value" to "-1"),
        ).forEach {
            val result = dispatcher.dispatch(it)
            assertEquals("VEHICLE_UNAVAILABLE", result.blockedReason)
            val json = JSONObject(result.output!!)
            assertEquals(false, json.getBoolean("ok"))
            assertEquals("VEHICLE_UNAVAILABLE", json.getString("error"))
            assertTrue(!json.has("announce"))
            assertNull(result.successChip)
        }
    }

    @Test
    fun theBodyServerRefusesAForeignTool() {
        val env = ToolCallEnv({ null }, { c, code, _ -> com.novadrive.ingress.realtime.ToolDispatchResult(null, null, blockedReason = code, output = c.name) }, { c, _ -> com.novadrive.ingress.realtime.ToolDispatchResult(null, null, output = c.name) })
        assertEquals("UNKNOWN_TOOL", BodyServer(SimulatedVehicleControl()).call(call("control_climate"), env).blockedReason)
    }
}
