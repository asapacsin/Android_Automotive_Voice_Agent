package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.WindowId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P57 (tryout run 4, 2026-10-13): 「把我这边的窗户开一点」 was answered with control_window
 * {adjust, all, 20} and every window opened. The driver's side is the driver window.
 */
class ToolCallGuardDriverWindowTest {
    private val adjustAll = DomainVoiceEvent.ToolCall(
        "c1",
        "control_window",
        mapOf("action" to "adjust", "window" to "all", "value" to "20"),
    )

    private fun context(said: String, epoch: Long = 1): DriverContext = DriverContext().apply {
        onSpeechStarted(epoch)
        onDriverUtterance(said, epoch)
    }

    @Test
    fun `my side opens only the driver window`() = runBlocking {
        val car = SimulatedVehicleControl()
        val before = car.getCabinState().windows
        val dispatcher = AndroidToolDispatcher(WillingExecutor, ClimateToolHandler(car), noCamera(), cabin = car) {
            context("把我这边的窗户开一点")
        }
        val output = JSONObject(dispatcher.dispatch(adjustAll).output!!)
        assertTrue(output.getBoolean("ok"), output.toString())
        assertEquals("driver", output.getString("window"))
        val after = car.getCabinState().windows
        assertTrue(after.getValue(WindowId.FRONT_LEFT) > before.getValue(WindowId.FRONT_LEFT))
        WindowId.entries.filter { it != WindowId.FRONT_LEFT }.forEach {
            assertEquals(before.getValue(it), after.getValue(it), it.name)
        }
    }

    @Test
    fun `the live spaced line is the driver window too`() {
        assertEquals("driver", ToolCallGuards.driverSideWindow(adjustAll, context("把 我 这 边 的 窗 户 开 一 点 。")))
    }

    @Test
    fun `driver-side phrasings narrow and a second window does not`() {
        listOf("我这边开一点窗", "我这侧的窗户", "我这一侧窗户开一点", "我这半边的窗户").forEach {
            assertEquals("driver", ToolCallGuards.driverSideWindow(adjustAll, context(it)), it)
        }
        listOf("打开车窗", "我这边和副驾都开一点", "把后排窗户开一点", "四个窗户都开一点", "把全部车窗打开").forEach {
            assertNull(ToolCallGuards.driverSideWindow(adjustAll, context(it)), it)
        }
    }

    @Test
    fun `an unspecified window request still moves every window`() = runBlocking {
        val car = SimulatedVehicleControl()
        val before = car.getCabinState().windows
        val dispatcher = AndroidToolDispatcher(WillingExecutor, ClimateToolHandler(car), noCamera(), cabin = car) {
            context("打开车窗")
        }
        val openAll = DomainVoiceEvent.ToolCall("c2", "control_window", mapOf("action" to "open", "window" to "all"))
        assertTrue(JSONObject(dispatcher.dispatch(openAll).output!!).getBoolean("ok"))
        val after = car.getCabinState().windows
        WindowId.entries.forEach { assertTrue(after.getValue(it) > before.getValue(it), it.name) }
    }

    @Test
    fun `a driver window already named is left alone`() {
        val driver = adjustAll.copy(arguments = adjustAll.arguments + ("window" to "driver"))
        assertNull(ToolCallGuards.driverSideWindow(driver, context("把我这边的窗户开一点")))
        val frontLeft = adjustAll.copy(arguments = adjustAll.arguments + ("window" to "front_left"))
        assertNull(ToolCallGuards.driverSideWindow(frontLeft, context("把我这边的窗户开一点")))
    }

    @Test
    fun `my side said on the previous turn does not rewrite this turn`() {
        val context = context("把我这边的窗户开一点", epoch = 1)
        context.onSpeechStarted(2)
        assertNull(ToolCallGuards.driverSideWindow(adjustAll, context))
    }

    @Test
    fun `other tools and no context are untouched`() {
        val seat = DomainVoiceEvent.ToolCall("c3", "control_seat", mapOf("action" to "adjust_height", "value" to "1"))
        assertNull(ToolCallGuards.driverSideWindow(seat, context("把我这边的窗户开一点")))
        assertNull(ToolCallGuards.driverSideWindow(adjustAll, null))
        assertNull(ToolCallGuards.driverSideWindow(adjustAll, DriverContext()))
    }

    private object WillingExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Accepted("navigating")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")
        override fun playMusic() = AndroidActionResult.Accepted("playing")
        override fun stopMusic() = AndroidActionResult.Accepted("stopped")
        override fun exitNavigationMode() = AndroidActionResult.Accepted("exited")
    }
}
