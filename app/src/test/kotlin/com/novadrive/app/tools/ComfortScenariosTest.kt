package com.novadrive.app.tools

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.ToolCallGuards
import com.novadrive.app.noCamera
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vehicle.ComfortScenarios
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.ContextResolver
import com.novadrive.app.voice.DriverTurn
import com.novadrive.app.voice.toolResultProvesExecution
import com.novadrive.ingress.realtime.ResponseOutcome
import com.novadrive.vehicle.CabinState
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
import org.junit.jupiter.api.Assertions.assertNotNull
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
        // The fan step is the latest relative adjustment; the window step was absolute.
        assertEquals(ContextResolver.Resolution.Adjust(DriverContext.Dimension.FAN, 1.0, powerOnFirst = false), next)
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

        // 「蚊子还没走」 runs mosquito again: nothing changes, so it must not be announced as an opening.
        context.onSpeechStarted(3); context.onDriverUtterance("蚊子还没走", 3)
        val again = scenario(d, "mosquito")
        assertTrue(again.getBoolean("ok"))
        assertEquals("already_open", again.getString("status"))
        assertEquals(0, steps(again).size)
        assertEquals("车窗已经开着一半了，这次没有再动。", again.getString("announce"))
        assertTrue(port.cabinState.value.windows.values.all { it == 50 })

        context.onSpeechStarted(4); context.onDriverUtterance("蚊子出去了", 4)
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

    private fun windowsFail(base: SimulatedVehicleControl) = object : VehicleControlPort by base {
        override suspend fun setWindows(windows: Set<WindowId>, openPercent: Int): VehicleActionResult<CabinState> =
            VehicleActionResult.Failure("fault")
    }

    private fun partialStuffyOutput(): String {
        val result = dispatcher(powerFails(SimulatedVehicleControl())).dispatch(call("run_scenario", "name" to "stuffy"))
        return result.output!!
    }

    @Test
    fun anHonestPartialReplyIsReleasedAndAllDoneIsCorrected() {
        val output = partialStuffyOutput()
        val announce = JSONObject(output).getString("announce")
        assertEquals("空调没打开，风量没调大，前排车窗开到了20%。", announce)

        val honest = ActionClaimGuard()
        honest.onUserTranscript("有点闷")
        assertNull(honest.onResponseDone(ResponseOutcome.toolsOnly("c"), ""))
        honest.onToolResult(output)
        assertNull(honest.onResponseDone(ResponseOutcome.spokenOnly(), announce))

        val liar = ActionClaimGuard()
        liar.onUserTranscript("有点闷")
        liar.onResponseDone(ResponseOutcome.toolsOnly("c"), "")
        liar.onToolResult(output)
        assertNotNull(liar.onResponseDone(ResponseOutcome.spokenOnly(), "好的，都弄好了"))

        val omits = ActionClaimGuard()
        omits.onUserTranscript("有点闷")
        omits.onResponseDone(ResponseOutcome.toolsOnly("c"), "")
        omits.onToolResult(output)
        assertNotNull(omits.onResponseDone(ResponseOutcome.spokenOnly(), "好的，已经打开车窗了"))
    }

    @Test
    fun onlyAPartialWithAnOkStepProvesExecution() {
        assertTrue(toolResultProvesExecution(partialStuffyOutput()))
        val allFailed = dispatcher(windowsFail(SimulatedVehicleControl())).dispatch(call("run_scenario", "name" to "mosquito"))
        val out = JSONObject(allFailed.output!!)
        assertEquals("failed", out.getString("status"))
        assertEquals("VEHICLE_EXECUTION_FAILED", allFailed.blockedReason)
        assertTrue(allFailed.successChip!!.contains("✗"))
        assertFalse(toolResultProvesExecution(allFailed.output!!))
    }

    @Test
    fun aSkippedStepIsMarkedInTheChip() {
        val result = dispatcher(powerFails(SimulatedVehicleControl())).dispatch(call("run_scenario", "name" to "stuffy"))
        assertEquals("场景 ✗ control_climate·power_on · – control_climate·adjust_fan · ✓ control_window·set", result.successChip)
    }

    @Test
    fun everyScenarioPhraseIsAnAction() {
        listOf("有蚊子", "有虫子飞进来了", "蚊子出去了", "好了关上吧", "有点闷", "空气不好", "有异味", "好困", "有点犯困", "有点困")
            .forEach { assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify(it), it) }
        assertNotEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("这个问题有点难"))
    }

    @Test
    fun mosquitoDoneNeedsARecentMosquito() {
        val port = SimulatedVehicleControl()
        val d = dispatcher(port)
        d.dispatch(call("control_window", "action" to "set", "window" to "all", "value" to "50"))
        val result = d.dispatch(call("run_scenario", "name" to "mosquito_done"))
        assertEquals(ComfortServer.NO_RECENT_MOSQUITO, result.blockedReason)
        assertTrue(JSONObject(result.output!!).has("next"))
        assertTrue(port.cabinState.value.windows.values.all { it == 50 })
    }

    @Test
    fun mosquitoDoneExpiresAfterTenMinutes() {
        val port = SimulatedVehicleControl()
        var clock = 0L
        val body = BodyServer(port)
        val env = ToolCallEnv({ null }, { c, code, _ -> com.novadrive.ingress.realtime.ToolDispatchResult(null, null, blockedReason = code, output = JSONObject().put("ok", false).put("tool", c.name).put("error", code).toString()) }, { c, _ -> com.novadrive.ingress.realtime.ToolDispatchResult(null, null, output = c.name) })
        val server = ComfortServer(route = { body.call(it, env) }, cabinState = { port.cabinState.value }, now = { clock })
        assertTrue(JSONObject(server.call(call("run_scenario", "name" to "mosquito"), env).output!!).getBoolean("ok"))
        clock = ComfortServer.MOSQUITO_TTL_MS + 1
        assertEquals(ComfortServer.NO_RECENT_MOSQUITO, server.call(call("run_scenario", "name" to "mosquito_done"), env).blockedReason)
        clock = 0
        assertTrue(JSONObject(server.call(call("run_scenario", "name" to "mosquito"), env).output!!).getBoolean("ok"))
        clock = ComfortServer.MOSQUITO_TTL_MS - 1
        assertTrue(JSONObject(server.call(call("run_scenario", "name" to "mosquito_done"), env).output!!).getBoolean("ok"))
    }

    @Test
    fun drowsyWithANamedSongSkipsTheMusicStep() {
        context.onSpeechStarted(2)
        context.onDriverUtterance("好困，放周杰伦的歌", 2)
        val out = scenario(dispatcher(), "drowsy")
        val music = steps(out).last()
        assertFalse(music.getBoolean("ok"))
        assertEquals(ToolCallGuards.MEDIA_LIBRARY_UNSUPPORTED, music.getString("error"))
        assertEquals(0, executor.plays)
    }
}
