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
 * Owner demo, warm worker run 4d1ad5c (2026-10-16): mid-drive 「算了」 after an empty along-route
 * answer was answered with exit_navigation_mode and the drive ended. Only an explicit end request
 * may end navigation when no list is on screen.
 */
class ToolCallGuardVagueExitTest {
    private val exit = DomainVoiceEvent.ToolCall("c1", "exit_navigation_mode", emptyMap())

    private fun context(said: String, epoch: Long = 1): DriverContext = DriverContext().apply {
        onSpeechStarted(epoch)
        onDriverUtterance(said, epoch)
    }

    @AfterEach
    fun stop() = NavigationState.reset()

    @Test
    fun `a vague never-mind mid-drive does not end the drive`() {
        listOf("算了", "不用了", "没事了", "没事", "不去了", "好了", "算 了").forEach {
            assertEquals(
                ToolCallGuards.VAGUE_EXIT_WHILE_NAVIGATING,
                ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false),
                it,
            )
        }
    }

    @Test
    fun `an explicit end request ends the drive`() {
        listOf("结束导航", "退出导航", "停止导航", "取消导航", "关闭导航", "不导航了", "导航结束", "别导航了", "算了，结束导航吧").forEach {
            assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false), it)
        }
    }

    @Test
    fun `with a list open never-mind closes the list`() {
        assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context("算了"), navigating = true, listOpen = true))
    }

    @Test
    fun `not navigating, other tools, stale words and no context are untouched`() {
        assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context("算了"), navigating = false, listOpen = false))
        val climate = DomainVoiceEvent.ToolCall("c2", "control_climate", mapOf("action" to "on"))
        assertNull(ToolCallGuards.vagueExitWhileNavigating(climate, context("算了"), navigating = true, listOpen = false))
        val stale = context("算了", epoch = 1).apply { onSpeechStarted(2) }
        assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, stale, navigating = true, listOpen = false))
        assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, null, navigating = true, listOpen = false))
    }

    @Test
    fun `through the dispatcher the drive continues`() {
        NavigationState.begin()
        val executor = CountingExecutor()
        val car = SimulatedVehicleControl()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(car), noCamera(), cabin = car) {
            context("算了")
        }
        val output = JSONObject(dispatcher.dispatch(exit).output!!)
        assertFalse(output.getBoolean("ok"))
        assertEquals(ToolCallGuards.VAGUE_EXIT_WHILE_NAVIGATING, output.getString("error"))
        assertTrue("导航还在继续" in output.getString("next"), output.toString())
        assertEquals(0, executor.exits, "navigation must not be stopped")
        assertTrue(NavigationState.navigating)
    }

    private class CountingExecutor : AndroidActionExecutor {
        var exits = 0
        override fun navigate(destination: String) = AndroidActionResult.Accepted("navigating")
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")
        override fun playMusic() = AndroidActionResult.Accepted("playing")
        override fun stopMusic() = AndroidActionResult.Accepted("stopped")
        override fun exitNavigationMode(): AndroidActionResult { exits++; return AndroidActionResult.Accepted("exited") }
    }
}
