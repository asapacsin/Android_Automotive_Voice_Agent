package com.novadrive.app.vehicle

import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-015 B1/B4/B5: 「座位有点高」 lowers the driver seat one level and says where it is now. */
class SeatToolHandlerTest {
    private val port = SimulatedVehicleControl()
    private val handler = SeatToolHandler(port)

    private fun run(vararg args: Pair<String, String>): Pair<BodyToolOutcome, JSONObject> {
        val outcome = runBlocking { handler.handle(args.toMap()) }
        return outcome to JSONObject(outcome.output)
    }

    @Test
    fun loweringOneStepReadsBackTheNewHeight() {
        val (outcome, json) = run("action" to "adjust_height", "value" to "-1")
        assertTrue(json.getBoolean("ok"))
        assertEquals("control_seat", json.getString("tool"))
        assertEquals("driver", json.getString("seat"))
        assertEquals(4, json.getInt("seat_height"))
        assertFalse(json.getBoolean("limit_reached"))
        assertEquals("座椅降低了一档，现在是4档", json.getString("announce"))
        assertEquals("✓ 座椅 4档", outcome.chip)
        assertEquals(4, port.cabinState.value.seatHeights.getValue(SeatId.DRIVER))
        assertEquals(5, port.cabinState.value.seatHeights.getValue(SeatId.PASSENGER))
    }

    @Test
    fun raisingAndSetting() {
        assertEquals("座椅升高了一档，现在是6档", run("action" to "adjust_height", "value" to "1").second.getString("announce"))
        val (outcome, json) = run("action" to "set_height", "seat" to "passenger", "value" to "2")
        assertEquals("副驾座椅调到了2档", json.getString("announce"))
        assertEquals("✓ 副驾座椅 2档", outcome.chip)
    }

    @Test
    fun clampingAtEitherEndSaysSo() {
        run("action" to "set_height", "value" to "0")
        val (_, low) = run("action" to "adjust_height", "value" to "-1")
        assertTrue(low.getBoolean("limit_reached"))
        assertEquals(0, low.getInt("seat_height"))
        assertEquals("座椅已经是最低了", low.getString("announce"))
        run("action" to "set_height", "value" to "10")
        val (_, high) = run("action" to "adjust_height", "value" to "3")
        assertTrue(high.getBoolean("limit_reached"))
        assertEquals("座椅已经是最高了", high.getString("announce"))
    }

    @Test
    fun getStateReadsBack() {
        val (_, json) = run("action" to "get_state")
        assertEquals(5, json.getInt("seat_height"))
        assertEquals("座椅现在是5档", json.getString("announce"))
    }

    @Test
    fun invalidRequestsChangeNothing() {
        listOf(
            mapOf("action" to "adjust_height") to "MISSING_VALUE",
            mapOf("action" to "set_height", "value" to "11") to "INVALID_ARGUMENT",
            mapOf("action" to "set_height", "value" to "1.5") to "INVALID_VALUE",
            mapOf("action" to "get_state", "seat" to "rear") to "INVALID_VALUE",
            mapOf("action" to "recline") to "ACTION_NOT_ALLOWED",
        ).forEach { (args, code) ->
            val outcome = runBlocking { handler.handle(args) }
            val json = JSONObject(outcome.output)
            assertFalse(json.getBoolean("ok"), "$args")
            assertEquals(code, json.getString("error"), "$args")
            assertFalse(json.has("announce"))
            assertNull(outcome.chip)
        }
        assertEquals(5, port.cabinState.value.seatHeights.getValue(SeatId.DRIVER))
    }

    @Test
    fun aPermissionDenialIsReportedFaithfully() {
        val denied = object : VehicleControlPort by port {
            override suspend fun changeSeatHeight(seat: SeatId, delta: Int) =
                VehicleActionResult.PermissionDenied("no seat permission")
        }
        val json = JSONObject(runBlocking { SeatToolHandler(denied).handle(mapOf("action" to "adjust_height", "value" to "-1")) }.output)
        assertEquals("VEHICLE_PERMISSION_DENIED", json.getString("error"))
    }
}
