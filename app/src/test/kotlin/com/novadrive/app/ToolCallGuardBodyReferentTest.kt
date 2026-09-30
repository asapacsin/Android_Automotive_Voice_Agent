package com.novadrive.app

import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** SPEC-015 B7: an ambiguous relative seat/window call is held exactly like climate. */
class ToolCallGuardBodyReferentTest {
    private val seatOk = """{"ok":true,"tool":"control_seat","seat_height":4,"limit_reached":false}"""
    private val climateOk = """{"ok":true,"power_on":true,"temperature_c":23,"fan_level":3}"""
    private val seatAdjust = DomainVoiceEvent.ToolCall("c1", "control_seat", mapOf("action" to "adjust_height", "value" to "-1"))

    @Test
    fun `FZ-14 an ambiguous seat adjust is held and the question recorded`() {
        val context = DriverContext()
        context.onBodyResult("control_seat", "adjust_height", -1.0, seatOk, 1)
        context.onClimateResult(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, climateOk, 1)
        context.onDriverUtterance("再低一点。", 2)
        assertEquals(ToolCallGuards.AMBIGUOUS_REFERENT, ToolCallGuards.ambiguousReferent(seatAdjust, context))
        assertNotNull(context.pendingClarification(3))
        val window = DomainVoiceEvent.ToolCall("c2", "control_window", mapOf("action" to "adjust", "value" to "-20"))
        assertEquals(ToolCallGuards.AMBIGUOUS_REFERENT, ToolCallGuards.ambiguousReferent(window, context))
    }

    @Test
    fun `FZ-05 an unambiguous seat adjust goes through`() {
        val context = DriverContext()
        context.onBodyResult("control_seat", "adjust_height", -1.0, seatOk, 1)
        context.onDriverUtterance("再低一点。", 2)
        assertNull(ToolCallGuards.ambiguousReferent(seatAdjust, context))
    }

    @Test
    fun `absolute body actions are never held`() {
        val context = DriverContext()
        context.onDriverUtterance("再低一点。", 2)
        val set = DomainVoiceEvent.ToolCall("c3", "control_seat", mapOf("action" to "set_height", "value" to "3"))
        assertNull(ToolCallGuards.ambiguousReferent(set, context))
    }
}
