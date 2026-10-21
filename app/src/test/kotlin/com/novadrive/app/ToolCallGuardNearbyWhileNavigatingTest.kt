package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P61 (GitHub emulator navigation demo, 2026-10-16): mid-drive 「附近有加油站吗？」 got navigate_to,
 * which replaced the active route with a list of gas stations. With a route, a nearby question is
 * query_live_info along_route (P54's split); only words that ask to go may replace the route.
 */
class ToolCallGuardNearbyWhileNavigatingTest {
    private val gas = DomainVoiceEvent.ToolCall("c1", "navigate_to", mapOf("destination" to "附近的加油站"))

    private fun context(said: String, epoch: Long = 1): DriverContext = DriverContext().apply {
        onSpeechStarted(epoch)
        onDriverUtterance(said, epoch)
    }

    @AfterEach
    fun stop() = NavigationState.reset()

    @Test
    fun `a nearby question mid-drive does not replace the route`() {
        listOf("附近有加油站吗？", "附 近 有 加 油 站 吗", "路上有加油站吗", "前面有没有服务区", "最近的加油站在哪").forEach {
            assertEquals(
                ToolCallGuards.NEARBY_QUESTION_WHILE_NAVIGATING,
                ToolCallGuards.nearbyQuestionWhileNavigating(gas, context(com.novadrive.app.voice.TranscriptText.normalize(it)), navigating = true),
                it,
            )
        }
    }

    @Test
    fun `asking to go there still navigates`() {
        listOf("带我去附近的加油站", "导航去最近的加油站", "附近有加油站吗？那就去最近的那个", "换个目的地，去附近的加油站").forEach {
            assertNull(ToolCallGuards.nearbyQuestionWhileNavigating(gas, context(it), navigating = true), it)
        }
    }

    @Test
    fun `with no route a nearby question is a nearby search (P54)`() {
        assertNull(ToolCallGuards.nearbyQuestionWhileNavigating(gas, context("附近有加油站吗？"), navigating = false))
    }

    @Test
    fun `the previous turn's words and other tools are untouched`() {
        val stale = context("附近有加油站吗？", epoch = 1).apply { onSpeechStarted(2) }
        assertNull(ToolCallGuards.nearbyQuestionWhileNavigating(gas, stale, navigating = true))
        val climate = DomainVoiceEvent.ToolCall("c2", "control_climate", mapOf("action" to "on"))
        assertNull(ToolCallGuards.nearbyQuestionWhileNavigating(climate, context("附近有加油站吗"), navigating = true))
        assertNull(ToolCallGuards.nearbyQuestionWhileNavigating(gas, null, navigating = true))
    }

    @Test
    fun `through the dispatcher the route is kept and the advice names along_route`() {
        NavigationState.begin()
        val executor = CountingExecutor()
        val car = SimulatedVehicleControl()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(car), noCamera(), cabin = car) {
            context("附近有加油站吗？")
        }
        val output = JSONObject(dispatcher.dispatch(gas).output!!)
        assertFalse(output.getBoolean("ok"))
        assertEquals(ToolCallGuards.NEARBY_QUESTION_WHILE_NAVIGATING, output.getString("error"))
        assertTrue("along_route" in output.getString("next"), output.toString())
        assertEquals(0, executor.navigations, "the active route must not be replaced")
    }

    private class CountingExecutor : AndroidActionExecutor {
        var navigations = 0
        override fun navigate(destination: String): AndroidActionResult { navigations++; return AndroidActionResult.Accepted("navigating") }
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")
        override fun playMusic() = AndroidActionResult.Accepted("playing")
        override fun stopMusic() = AndroidActionResult.Accepted("stopped")
        override fun exitNavigationMode() = AndroidActionResult.Accepted("exited")
    }
}
