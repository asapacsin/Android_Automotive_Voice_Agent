package com.novadrive.app

import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** P3: every actuating tool is repeat-guarded, keyed on the driver turn (speech onset). */
class ToolCallGuardRepeatTest {
    private var id = 0
    private fun call(name: String, vararg args: Pair<String, String>) =
        DomainVoiceEvent.ToolCall("c${id++}", name, mapOf(*args))

    private val actuating = listOf(
        call("place_call", "contact" to "张三"),
        call("navigate_to", "destination" to "中交汇通"),
        call("open_app", "app" to "maps"),
        call("save_place", "slot" to "home", "destination" to "中交汇通"),
        call("exit_navigation_mode"),
    )

    private fun repeat(c: DomainVoiceEvent.ToolCall) = c.copy(callId = "r${id++}")

    @Test
    fun repeatOfEachActuatingToolInOneTurnIsDuplicate() {
        actuating.forEach { first ->
            val context = DriverContext()
            context.onSpeechStarted(1)
            context.onDriverUtterance("说点什么", epoch = 1)
            assertNull(ToolCallGuards.repeatedInTurn(first, context), first.name)
            assertEquals(ToolCallGuards.DUPLICATE_IN_TURN, ToolCallGuards.repeatedInTurn(repeat(first), context), first.name)
        }
    }

    @Test
    fun geminiOrderingCallBeforeTranscriptIsStillCaught() {
        val context = DriverContext()
        context.onSpeechStarted(1)
        val dial = call("place_call", "contact" to "张三")
        assertNull(ToolCallGuards.repeatedInTurn(dial, context))
        assertEquals(ToolCallGuards.DUPLICATE_IN_TURN, ToolCallGuards.repeatedInTurn(repeat(dial), context))
    }

    @Test
    fun staleTranscriptEpochDoesNotSwallowTheNextTurn() {
        val context = DriverContext()
        context.onSpeechStarted(1)
        context.onDriverUtterance("给张三打电话", epoch = 1)
        val dial = call("place_call", "contact" to "张三")
        assertNull(ToolCallGuards.repeatedInTurn(dial, context))
        context.onSpeechStarted(2) // turn 2, call arrives before its transcript
        assertNull(ToolCallGuards.repeatedInTurn(repeat(dial), context))
    }

    @Test
    fun baiduOrderingStillCaughtForExistingTools() {
        listOf(
            call("control_climate", "action" to "adjust_temperature", "value" to "-2"),
            call("control_music", "action" to "play"),
            call("query_live_info", "kind" to "weather", "where" to "北京"),
        ).forEach { first ->
            val context = DriverContext()
            context.onDriverUtterance("说点什么", epoch = 1)
            context.onSpeechStarted(1)
            assertNull(ToolCallGuards.repeatedInTurn(first, context), first.name)
            assertEquals(ToolCallGuards.DUPLICATE_IN_TURN, ToolCallGuards.repeatedInTurn(repeat(first), context), first.name)
        }
    }

    @Test
    fun newUtteranceOrDifferentArgumentsRun() {
        val context = DriverContext()
        context.onSpeechStarted(1)
        assertNull(ToolCallGuards.repeatedInTurn(call("navigate_to", "destination" to "A"), context))
        assertNull(ToolCallGuards.repeatedInTurn(call("navigate_to", "destination" to "B"), context))
        context.onSpeechStarted(2)
        context.onDriverUtterance("再导航去A", epoch = 2)
        assertNull(ToolCallGuards.repeatedInTurn(call("navigate_to", "destination" to "A"), context))
    }

    @Test
    fun readsAndNoTurnAreNotGuarded() {
        val context = DriverContext()
        context.onSpeechStarted(1)
        val look = call("describe_camera_view")
        assertNull(ToolCallGuards.repeatedInTurn(look, context))
        assertNull(ToolCallGuards.repeatedInTurn(repeat(look), context))
        val fresh = DriverContext()
        val dial = call("place_call", "contact" to "张三")
        assertNull(ToolCallGuards.repeatedInTurn(dial, fresh))
        assertNull(ToolCallGuards.repeatedInTurn(repeat(dial), fresh))
    }
}
