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
        listOf("结束导航", "退出导航", "停止导航", "取消导航", "关闭导航", "不导航了", "导航结束", "别导航了", "算了，结束导航吧",
            "不要导航了", "不用导航了", "导航关了", "把导航关了", "导航关掉", "结束路线", "不去了，结束导航", "别导航", "导航不要了", "关了导航",
        ).forEach {
            assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false), it)
        }
    }

    @Test
    fun `structural explicit end requests end the drive`() {
        listOf("把导航取消", "把导航取消了", "导航取消", "导航关闭", "把导航关闭", "不需要导航了", "不用导航", "不要导航",
            "关导航", "终止导航", "取消路线", "停止路线", "把导航停了", "导航可以结束了", "别再导航了", "不想导航了",
            "算了不用导航", "结束导航", "退出导航", "停止导航", "不要导航了", "導航結束",
        ).forEach {
            assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false), it)
        }
    }

    @Test
    fun `continuing or a negated end is not an end request`() {
        listOf("继续导航", "导航继续", "不要结束导航").forEach {
            assertFalse(ToolCallGuards.isExplicitEnd(it), it)
            assertEquals(
                ToolCallGuards.VAGUE_EXIT_WHILE_NAVIGATING,
                ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false),
                it,
            )
        }
    }

    @Test
    fun `lines about the guidance voice or the route are not end requests`() {
        listOf("导航别说话了", "导航太吵了别说了", "关掉导航声音", "把导航声音关小一点", "关闭导航播报", "导航不要走高速",
            "我不想走这条路线", "关窗，然后导航去公司", "关空调导航到公司", "不用管导航", "关这个空调导航去公司",
        ).forEach {
            assertEquals(
                ToolCallGuards.VAGUE_EXIT_WHILE_NAVIGATING,
                ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false),
                it,
            )
        }
    }

    @Test
    fun `suggestions and near forms end the drive`() {
        listOf("要不结束导航吧", "要不关了导航吧", "导航不要了", "导航不用了", "把导航取消", "导航可以结束了", "关了导航",
            "把导航停了", "先把导航關掉",
            "结束当前导航", "退出当前导航", "结束本次导航", "取消这个导航", "停止这次导航", "结束当前的导航", "结束一下导航",
            "导航不要了吧", "导航不用了吧",
        ).forEach {
            assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context(it), navigating = true, listOpen = false), it)
        }
    }

    @Test
    fun `a pending search before guidance may still be cancelled`() {
        // Legacy flag set by navigate before the search; phase RESOLVING_DESTINATION (SPEC-006 CVC-36).
        NavigationState.begin()
        val pre = listOf(
            com.novadrive.app.nav.NavigationPhase.RESOLVING_DESTINATION,
            com.novadrive.app.nav.NavigationPhase.AWAITING_DESTINATION_SELECTION,
            com.novadrive.app.nav.NavigationPhase.PLANNING_ROUTE,
            com.novadrive.app.nav.NavigationPhase.CALCULATING_ROUTE,
            com.novadrive.app.nav.NavigationPhase.AWAITING_ROUTE_SELECTION,
            com.novadrive.app.nav.NavigationPhase.ROUTE_READY,
        )
        pre.forEach { assertFalse(ToolCallGuards.guidanceRunning(it, navigating = true), it.name) }
        val navigating = ToolCallGuards.guidanceRunning(com.novadrive.app.nav.NavigationPhase.RESOLVING_DESTINATION, NavigationState.navigating)
        assertNull(ToolCallGuards.vagueExitWhileNavigating(exit, context("算了，不去了"), navigating = navigating, listOpen = false))
        assertTrue(ToolCallGuards.guidanceRunning(com.novadrive.app.nav.NavigationPhase.NAVIGATING, navigating = false))
        assertTrue(ToolCallGuards.guidanceRunning(null, navigating = true))
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
        val next = output.getString("next")
        assertTrue("导航还在继续" in next, output.toString())
        assertTrue("结束导航" in next, output.toString())
        assertFalse("导航已结束" in next.replace("不要说导航已结束", ""), output.toString())
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
