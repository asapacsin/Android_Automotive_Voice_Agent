package com.novadrive.app.voice

import com.novadrive.app.voice.ContextResolver.Resolution
import com.novadrive.app.voice.DriverContext.Dimension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-015 B7 / FZ-05 / FZ-14: window and seat follow-ups resolve from the app's own record. */
class BodyContextResolverTest {

    private var now = 1_000_000L
    private val context = DriverContext { now }

    private val seatOk = """{"ok":true,"tool":"control_seat","action":"adjust_height","seat":"driver","seat_height":4,"limit_reached":false}"""
    private val windowOk = """{"ok":true,"tool":"control_window","action":"adjust","window":"front_left",""" +
        """"windows":{"front_left":50,"front_right":0,"rear_left":0,"rear_right":0},"limit_reached":false}"""
    private val climateOk = """{"ok":true,"tool":"control_climate","power_on":true,"temperature_c":23,"fan_level":3,"limit_reached":false}"""

    private fun seatLowered(epoch: Long = 1) =
        context.onBodyResult("control_seat", "adjust_height", -1.0, seatOk, epoch)

    private fun windowOpened(epoch: Long = 1) =
        context.onBodyResult("control_window", "adjust", 20.0, windowOk, epoch)

    private fun temperatureLowered(epoch: Long = 1) =
        context.onClimateResult(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, climateOk, epoch)

    private fun resolve(text: String, epoch: Long = 2): Resolution {
        context.onDriverUtterance(text, epoch)
        return ContextResolver.resolve(text, context, epoch)
    }

    private fun assertAdjusts(r: Resolution, dimension: Dimension, delta: Double) {
        val adjust = r as? Resolution.Adjust ?: throw AssertionError("expected adjust, got $r")
        assertEquals(dimension, adjust.dimension)
        assertEquals(delta, adjust.delta)
        assertFalse(adjust.powerOnFirst)
    }

    private fun assertClarifies(r: Resolution, reason: String): Resolution.Clarify {
        val clarify = r as? Resolution.Clarify ?: throw AssertionError("expected clarify, got $r")
        assertEquals(reason, clarify.reason)
        return clarify
    }

    @Test
    fun `FZ-05 after the seat was lowered, a neutral lower means the seat`() {
        seatLowered()
        assertAdjusts(resolve("再低一点。"), Dimension.SEAT_HEIGHT, -1.0)
    }

    @Test
    fun `FZ-14 seat and temperature both changed is ambiguous`() {
        seatLowered()
        temperatureLowered()
        val clarify = assertClarifies(resolve("再低一点。"), ContextResolver.REASON_AMBIGUOUS)
        assertEquals(setOf(Dimension.SEAT_HEIGHT, Dimension.TEMPERATURE), clarify.options.toSet())
    }

    @Test
    fun `a window-only referent makes a neutral open mean the window`() {
        windowOpened()
        assertAdjusts(resolve("再开一点。"), Dimension.WINDOW, 20.0)
        assertAdjusts(resolve("再关一点。", epoch = 3), Dimension.WINDOW, -20.0)
    }

    @Test
    fun `open with a non-window referent is asked about`() {
        temperatureLowered()
        assertClarifies(resolve("再开一点。"), ContextResolver.REASON_AMBIGUOUS)
    }

    @Test
    fun `a window referent never resolves a neutral lower`() {
        windowOpened()
        val clarify = assertClarifies(resolve("再低一点。"), ContextResolver.REASON_NO_REFERENT)
        assertEquals(Dimension.entries.toList(), clarify.options)
    }

    @Test
    fun `a window referent plus seat referent makes a neutral lower the seat`() {
        windowOpened()
        seatLowered()
        assertAdjusts(resolve("再低一点。"), Dimension.SEAT_HEIGHT, -1.0)
    }

    @Test
    fun `naming the seat binds lexically even with a temperature referent`() {
        temperatureLowered()
        assertAdjusts(resolve("座椅再低一点。"), Dimension.SEAT_HEIGHT, -1.0)
        assertAdjusts(resolve("座位再升一点。", epoch = 3), Dimension.SEAT_HEIGHT, 1.0)
    }

    @Test
    fun `naming the window binds lexically but high and low are not a window direction`() {
        temperatureLowered()
        assertAdjusts(resolve("车窗再开大一点。"), Dimension.WINDOW, 20.0)
        assertAdjusts(resolve("车窗再关小一点。", epoch = 3), Dimension.WINDOW, -20.0)
        assertEquals(Resolution.NotContextual, resolve("车窗再高一点。", epoch = 4))
    }

    @Test
    fun `an answer naming the seat carries out the pending direction with the seat step`() {
        context.recordClarification(listOf(Dimension.TEMPERATURE, Dimension.SEAT_HEIGHT), -1.0, epoch = 5)
        assertAdjusts(resolve("座椅。", epoch = 6), Dimension.SEAT_HEIGHT, -1.0)
        context.recordClarification(listOf(Dimension.WINDOW, Dimension.FAN), 1.0, epoch = 6)
        assertAdjusts(resolve("车窗。", epoch = 7), Dimension.WINDOW, 20.0)
    }

    @Test
    fun `a failed body result records nothing`() {
        context.onBodyResult("control_seat", "adjust_height", -1.0, """{"ok":false,"error":"VEHICLE_UNAVAILABLE"}""", 1)
        assertTrue(context.validReferents().isEmpty())
        assertNull(context.cabinState())
    }

    @Test
    fun `absolute sets record a zero delta and a cabin snapshot`() {
        context.onBodyResult("control_window", "open", null, windowOk, 1)
        context.onBodyResult("control_seat", "set_height", 4.0, seatOk, 1)
        assertEquals(setOf(0.0), context.validReferents().map { it.delta }.toSet())
        val cabin = context.cabinState()!!
        assertEquals(50, cabin.windows!!["front_left"])
        assertEquals(4, cabin.seatHeight)
    }

    @Test
    fun `get_state is not a referent`() {
        context.onBodyResult("control_window", "get_state", null, windowOk, 1)
        assertTrue(context.validReferents().isEmpty())
    }

    @Test
    fun `hints describe the cabin and name the body tool`() {
        seatLowered()
        windowOpened()
        val cabin = context.cabinState()
        val seatHint = VoiceContextHints.compose(null, false, cabin = cabin, referents = listOf(Dimension.SEAT_HEIGHT))!!
        assertTrue(seatHint.contains("车窗：主驾50%"), seatHint)
        assertTrue(seatHint.contains("座椅高度4档"), seatHint)
        assertTrue(seatHint.contains("control_seat 的 adjust_height"), seatHint)
        val windowHint = VoiceContextHints.compose(null, false, referents = listOf(Dimension.WINDOW))!!
        assertTrue(windowHint.contains("control_window 的 adjust"), windowHint)
        val both = VoiceContextHints.compose(null, false, referents = listOf(Dimension.SEAT_HEIGHT, Dimension.TEMPERATURE))!!
        assertTrue(both.contains("座椅高度还是温度"), both)
    }
}
