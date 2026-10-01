package com.novadrive.app.tools

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.ToolCallGuards
import com.novadrive.app.noCamera
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vehicle.ComfortScenarios
import com.novadrive.app.voice.ContextResolver
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleControlPort
import com.novadrive.vehicle.WindowId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-015 FZ-09/FZ-10, B7/B8: run_scenario through the same server path a direct call uses. */
class ComfortScenariosTest {
    private class Executor : AndroidActionExecutor {
        var navigations = 0
        var plays = 0
        override fun navigate(destination: String): AndroidActionResult { navigations++; return AndroidActionResult.Accepted() }
        override fun openApp(app: AllowedApp): AndroidActionResult = AndroidActionResult.Accepted()
        override fun playMusic(): AndroidActionResult { plays++; return AndroidActionResult.Accepted("music_playing") }
        override fun stopMusic(): AndroidActionResult = AndroidActionResult.Accepted("music_stopped")
        override fun exitNavigationMode(): AndroidActionResult = AndroidActionResult.Accepted()
    }

    private val context = DriverContext().also { it.onSpeechStarted(1); it.onDriverUtterance("有点闷", 1) }
    private val executor = Executor()
    private var id = 0

    private fun dispatcher(port: VehicleControlPort = SimulatedVehicleControl()) =
        AndroidToolDispatcher(executor, ClimateToolHandler(port), noCamera(), cabin = port) { context }

    private fun call(name: String, vararg args: Pair<String, String>) = DomainVoiceEvent.ToolCall("c${id++}", name, mapOf(*args))

    private fun scenario(d: AndroidToolDispatcher, name: String): JSONObject {
        val result = d.dispatch(call("run_scenario", "name" to name))
        val output = result.output ?: runBlocking { result.deferredOutput!!() }
        return JSONObject(output)
    }

    private fun steps(out: JSONObject) = (0 until out.getJSONArray("steps").length()).map { out.getJSONArray("steps").getJSONObject(it) }

    private fun powerFails(base: SimulatedVehicleControl) = object : VehicleControlPort by base {
        override suspend fun setHvacPower(on: Boolean): VehicleActionResult<ClimateState> = VehicleActionResult.Failure("fault")
    }

    @Test
    fun everyPlaybookStepIsAValidToolCall() {
        ComfortScenarios.NAMES.forEach { name ->
            ComfortScenarios.steps(name)!!.forEach { step ->
                val args = JSONObject().apply { step.arguments.forEach { (k, v) -> put(k, v.toDoubleOrNull() ?: v) } }
                assertNull(ToolRegistry.PRODUCT.validate(step.tool, args), "$name ${step.tool}")
            }
        }
        assertEquals("comfort", ToolRegistry.PRODUCT.domainOf("run_scenario")!!.id)
        assertTrue("run_scenario" in ToolRegistry.PRODUCT.repeatSensitiveNames)
    }

    @Test
    fun stuffyRunsItsStepsInOrderOnTheRealHandlers() {
        val port = SimulatedVehicleControl()
        val fanBefore = port.climateState.value.fanLevel
        val out = scenario(dispatcher(port), "stuffy")
        assertTrue(out.getBoolean("ok"))
        assertEquals(listOf("control_climate:power_on", "control_climate:adjust_fan", "control_window:set"),
            steps(out).map { "${it.getString("tool")}:${it.getString("action")}" })
        assertTrue(port.climateState.value.powerOn)
        assertEquals(fanBefore + 1, port.climateState.value.fanLevel)
        assertEquals(20, port.cabinState.value.windows.getValue(WindowId.FRONT_LEFT))
        assertEquals(0, port.cabinState.value.windows.getValue(WindowId.REAR_LEFT))
    }

    @Test
    fun aFailedPowerOnSkipsItsDependentButNotIndependentSteps() {
        val base = SimulatedVehicleControl()
        val fanBefore = base.climateState.value.fanLevel
        val out = scenario(dispatcher(powerFails(base)), "stuffy")
        assertFalse(out.getBoolean("ok"))
        assertEquals("partial", out.getString("status"))
        val s = steps(out)
        assertFalse(s[0].getBoolean("ok"))
        assertEquals("SKIPPED_PREREQUISITE_FAILED", s[1].getString("error"))
        assertTrue(s[2].getBoolean("ok"))
        assertEquals(fanBefore, base.climateState.value.fanLevel)
        val announce = out.getString("announce")
        assertTrue("空调没打开" in announce, announce)
        assertTrue("风量没调大" in announce, announce)
        assertTrue("前排车窗开到了20%" in announce, announce)
        assertFalse("都弄好了" in announce)
    }

    @Test
    fun eachStepLeavesItsOwnReferent() {
        scenario(dispatcher(), "stuffy")
        val dims = context.validReferents().map { it.dimension }.toSet()
        assertTrue(DriverContext.Dimension.FAN in dims, dims.toString())
        assertTrue(DriverContext.Dimension.WINDOW in dims, dims.toString())
        context.onSpeechStarted(2)
        context.onDriverUtterance("再大一点", 2)
        val next = ContextResolver.resolve("再大一点", context, 2)
        assertTrue(next is ContextResolver.Resolution.Clarify ||
            (next is ContextResolver.Resolution.Adjust && next.dimension == DriverContext.Dimension.FAN), next.toString())
    }

    @Test
    fun mosquitoThenMosquitoDone() {
        val port = SimulatedVehicleControl()
        val d = dispatcher(port)
        val closedFirst = scenario(d, "mosquito_done")
        assertEquals("already_closed", closedFirst.getString("status"))
        assertTrue("本来就是关着" in closedFirst.getString("announce"))

        context.onSpeechStarted(2); context.onDriverUtterance("有蚊子", 2)
        val opened = scenario(d, "mosquito")
        assertTrue(opened.getBoolean("ok"))
        assertTrue(port.cabinState.value.windows.values.all { it == 50 })
        assertEquals("车窗都开了一半。", opened.getString("announce"))

        context.onSpeechStarted(3); context.onDriverUtterance("蚊子出去了", 3)
        val closed = scenario(d, "mosquito_done")
        assertTrue(closed.getBoolean("ok"))
        assertTrue(port.cabinState.value.windows.values.all { it == 0 })
        assertEquals("车窗都关好了。", closed.getString("announce"))
    }

    @Test
    fun drowsyOffersARestStopAndNeverNavigates() {
        val out = scenario(dispatcher(), "drowsy")
        assertTrue(out.getBoolean("ok"), out.toString())
        assertEquals(4, steps(out).size)
        assertEquals(1, executor.plays)
        assertEquals(0, executor.navigations)
        assertTrue(out.getString("announce").endsWith(ComfortScenarios.REST_STOP_OFFER))
        assertTrue("温度调到了" in out.getString("announce"))
    }

    @Test
    fun theRepeatGuardAppliesToTheScenarioNotItsSteps() {
        val port = SimulatedVehicleControl()
        val d = dispatcher(port)
        // A direct call already ran this turn; the scenario's identical sub-step must still run.
        val direct = d.dispatch(call("control_window", "action" to "set", "window" to "all", "value" to "50"))
        assertTrue(JSONObject(direct.output!!).getBoolean("ok"))
        val first = d.dispatch(call("run_scenario", "name" to "mosquito"))
        assertNull(first.blockedReason)
        assertTrue(JSONObject(first.output!!).getBoolean("ok"))
        val again = d.dispatch(call("run_scenario", "name" to "mosquito"))
        assertEquals(ToolCallGuards.DUPLICATE_IN_TURN, again.blockedReason)
    }

    @Test
    fun stuffyIsNoLongerAnImplicitFanRequest() {
        val fresh = DriverContext().also { it.onDriverUtterance("有点闷", 1) }
        val r = ContextResolver.resolve("有点闷", fresh, 1)
        assertFalse(r is ContextResolver.Resolution.Adjust && r.dimension == DriverContext.Dimension.FAN, r.toString())
        assertNotEquals(true, ContextResolver.isImplicitComfortRequest("有点闷"))
    }
}
