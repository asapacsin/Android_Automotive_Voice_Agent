package com.novadrive.app.vehicle

import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.CabinState
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import com.novadrive.vehicle.WindowId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-015 B1/B4/B5: `control_window` over the port, with the state read back and an announce line. */
class WindowToolHandlerTest {
    private val port = SimulatedVehicleControl()
    private val handler = WindowToolHandler(port)

    private fun run(vararg args: Pair<String, String>): Pair<BodyToolOutcome, JSONObject> {
        val outcome = runBlocking { handler.handle(args.toMap()) }
        return outcome to JSONObject(outcome.output)
    }

    @Test
    fun openingHalfwaySetsEveryWindowAndSaysSo() {
        val (outcome, json) = run("action" to "set", "value" to "50")
        assertTrue(json.getBoolean("ok"))
        assertEquals("control_window", json.getString("tool"))
        val windows = json.getJSONObject("windows")
        listOf("front_left", "front_right", "rear_left", "rear_right").forEach { assertEquals(50, windows.getInt(it)) }
        assertFalse(json.getBoolean("limit_reached"))
        assertEquals("车窗都开了一半", json.getString("announce"))
        assertTrue(json.getString("instruction").contains("announce"))
        assertEquals("✓ 车窗 50%", outcome.chip)
        assertNull(outcome.errorCode)
        assertEquals(50, port.cabinState.value.windows.getValue(WindowId.REAR_RIGHT))
    }

    @Test
    fun openAndCloseAreFullAndZero() {
        assertEquals("车窗都打开了", run("action" to "open").second.getString("announce"))
        assertEquals(100, port.cabinState.value.windows.getValue(WindowId.FRONT_LEFT))
        assertEquals("车窗都关好了", run("action" to "close").second.getString("announce"))
        assertEquals(0, port.cabinState.value.windows.getValue(WindowId.FRONT_LEFT))
    }

    @Test
    fun driverWindowAdjustsOnlyTheFrontLeftFromTheCurrentState() {
        run("action" to "set", "window" to "driver", "value" to "20")
        val (outcome, json) = run("action" to "adjust", "window" to "driver", "value" to "20")
        assertEquals(40, json.getJSONObject("windows").getInt("front_left"))
        assertEquals(0, json.getJSONObject("windows").getInt("front_right"))
        assertEquals("主驾车窗开到了40%", json.getString("announce"))
        assertEquals("✓ 车窗 左前40% 右前0% 左后0% 右后0%", outcome.chip)
    }

    @Test
    fun adjustWithoutValueOpensByTheDefaultStep() {
        val (_, json) = run("action" to "adjust", "window" to "front")
        assertEquals(20, json.getJSONObject("windows").getInt("front_right"))
        assertEquals("前排车窗开到了20%", json.getString("announce"))
    }

    @Test
    fun rowChipWhenFrontAndRearDiffer() {
        val (outcome, _) = run("action" to "set", "window" to "front", "value" to "50")
        assertEquals("✓ 车窗 前50% 后0%", outcome.chip)
    }

    @Test
    fun clampingReportsTheLimit() {
        run("action" to "set", "value" to "90")
        val (_, json) = run("action" to "adjust", "value" to "20")
        assertTrue(json.getBoolean("limit_reached"))
        assertEquals(100, json.getJSONObject("windows").getInt("rear_left"))
        assertEquals("车窗已经全开了", json.getString("announce"))
        val (_, closed) = run("action" to "adjust", "window" to "passenger", "value" to "-150")
        assertTrue(closed.getBoolean("limit_reached"))
        assertEquals("副驾车窗已经关到底了", closed.getString("announce"))
    }

    @Test
    fun getStateReadsBackWithoutChanging() {
        run("action" to "set", "value" to "30")
        val (_, json) = run("action" to "get_state")
        assertTrue(json.getBoolean("ok"))
        assertEquals("车窗现在都开着30%", json.getString("announce"))
        assertEquals(30, port.cabinState.value.windows.getValue(WindowId.FRONT_LEFT))
    }

    @Test
    fun invalidRequestsAreOkFalseWithNoAnnouncement() {
        listOf(
            mapOf("action" to "set", "value" to "150") to "INVALID_ARGUMENT",
            mapOf("action" to "set") to "INVALID_VALUE",
            mapOf("action" to "set", "value" to "12.5") to "INVALID_VALUE",
            mapOf("action" to "open", "window" to "sunroof") to "INVALID_VALUE",
            mapOf("action" to "fly") to "ACTION_NOT_ALLOWED",
            emptyMap<String, String>() to "MISSING_ACTION",
        ).forEach { (args, code) ->
            val outcome = runBlocking { handler.handle(args) }
            val json = JSONObject(outcome.output)
            assertFalse(json.getBoolean("ok"), "$args")
            assertEquals(code, json.getString("error"), "$args")
            assertEquals(code, outcome.errorCode)
            assertFalse(json.has("announce"), "$args")
            assertNull(outcome.chip)
        }
        assertEquals(CabinState.DEFAULT, port.cabinState.value)
    }

    @Test
    fun aBackendFailureIsReportedFaithfully() {
        val failing = object : VehicleControlPort by port {
            override suspend fun setWindows(windows: Set<WindowId>, openPercent: Int) =
                VehicleActionResult.Unavailable("ignition off")
        }
        val outcome = runBlocking { WindowToolHandler(failing).handle(mapOf("action" to "open")) }
        val json = JSONObject(outcome.output)
        assertFalse(json.getBoolean("ok"))
        assertEquals("VEHICLE_UNAVAILABLE", json.getString("error"))
        assertFalse(json.has("announce"))
    }
}
