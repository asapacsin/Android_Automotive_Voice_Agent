package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P56 (live tryout 2026-10-13): 「帮我把天窗打开」 was answered with control_window{open, all} and
 * opened all four windows to 100 %. SPEC-006 F-7: refuse, never run a nearby supported action.
 */
class ToolCallGuardSunroofTest {
    private val openAll = DomainVoiceEvent.ToolCall("c1", "control_window", mapOf("action" to "open", "window" to "all"))

    private fun context(said: String, epoch: Long = 1): DriverContext = DriverContext().apply {
        onSpeechStarted(epoch)
        onDriverUtterance(said, epoch)
    }

    @Test
    fun `a sunroof request never moves a window`() = runBlocking {
        val car = SimulatedVehicleControl()
        val before = car.getCabinState().windows
        val dispatcher = AndroidToolDispatcher(WillingExecutor, ClimateToolHandler(car), noCamera(), cabin = car) {
            context("帮我把天窗打开。")
        }
        val output = JSONObject(dispatcher.dispatch(openAll).output!!)
        assertFalse(output.getBoolean("ok"))
        assertEquals(ToolCallGuards.SUNROOF_UNSUPPORTED, output.getString("error"))
        assertTrue("天窗" in output.getString("next") && "不要改开车窗" in output.getString("next"), output.toString())
        assertEquals(before, car.getCabinState().windows, "no window may move")
    }

    @Test
    fun `sunroof and roof-shade phrasings are refused`() {
        listOf("把天窗打开", "天窗开一下", "开一下天幕", "天窗关上吧").forEach {
            assertEquals(ToolCallGuards.SUNROOF_UNSUPPORTED, ToolCallGuards.sunroofOnWindowTool(openAll, context(it)), it)
        }
    }

    @Test
    fun `an ordinary window request goes through`() {
        listOf("把我这边的窗户开一点", "打开车窗", "天窗和车窗都打开", "车窗开一半，天窗不用").forEach {
            assertNull(ToolCallGuards.sunroofOnWindowTool(openAll, context(it)), it)
        }
    }

    @Test
    fun `a sunroof said in the previous turn does not block this turn's window call`() {
        // The call can arrive before this turn's transcript: the text still holds the last turn's.
        val context = context("帮我把天窗打开。", epoch = 1)
        context.onSpeechStarted(2)
        assertNull(ToolCallGuards.sunroofOnWindowTool(openAll, context))
    }

    @Test
    fun `other tools and no context are untouched`() {
        val seat = DomainVoiceEvent.ToolCall("c2", "control_seat", mapOf("action" to "set_height", "value" to "3"))
        assertNull(ToolCallGuards.sunroofOnWindowTool(seat, context("天窗打开，座椅调高")))
        assertNull(ToolCallGuards.sunroofOnWindowTool(openAll, null))
        assertNull(ToolCallGuards.sunroofOnWindowTool(openAll, DriverContext()))
    }

    private object WillingExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Accepted("navigating")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")
        override fun playMusic() = AndroidActionResult.Accepted("playing")
        override fun stopMusic() = AndroidActionResult.Accepted("stopped")
        override fun exitNavigationMode() = AndroidActionResult.Accepted("exited")
    }
}
