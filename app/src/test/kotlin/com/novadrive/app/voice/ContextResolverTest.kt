package com.novadrive.app.voice

import com.novadrive.app.voice.ContextResolver.Resolution
import com.novadrive.app.voice.DriverContext.Dimension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The CONTEXT benchmark of [SPEC-006](../../../../../../../SPECS/SPEC-006-complex-voice-commands.md)
 * at the level where it has a real oracle: **which dimension, which step, or a decision to ask.**
 *
 * A scripted model emitting the expected call would prove plumbing, not resolution, so the cases
 * that can be settled deterministically are settled here instead — no model, no network, no device.
 * Each test names the `CVC-nn` case it implements.
 */
class ContextResolverTest {

    private var now = 1_000_000L
    private val context = DriverContext { now }

    private fun climateResult(
        ok: Boolean = true,
        powerOn: Boolean = true,
        temperature: Double = 24.0,
        fan: Int = 3,
        limitReached: Boolean = false,
    ): String =
        if (ok) {
            """{"ok":true,"tool":"control_climate","power_on":$powerOn,""" +
                """"temperature_c":$temperature,"fan_level":$fan,"limit_reached":$limitReached}"""
        } else {
            """{"ok":false,"tool":"control_climate","error":"VEHICLE_UNAVAILABLE"}"""
        }

    private fun adjusted(
        action: String,
        value: Double,
        epoch: Long = 1,
        ok: Boolean = true,
        powerOn: Boolean = true,
        limitReached: Boolean = false,
    ) {
        context.onClimateResult(
            action = action,
            value = value,
            output = climateResult(ok = ok, powerOn = powerOn, limitReached = limitReached),
            epoch = epoch,
        )
    }

    private fun resolve(text: String, epoch: Long = 2): Resolution {
        context.onDriverUtterance(text, epoch)
        return ContextResolver.resolve(text, context, epoch)
    }

    private fun assertAdjusts(
        resolution: Resolution,
        dimension: Dimension,
        delta: Double,
        powerOnFirst: Boolean = false,
    ): Resolution.Adjust {
        val adjust = resolution as? Resolution.Adjust
            ?: throw AssertionError("expected an adjustment, got $resolution")
        assertEquals(dimension, adjust.dimension)
        assertEquals(delta, adjust.delta)
        assertEquals(powerOnFirst, adjust.powerOnFirst, "powerOnFirst")
        return adjust
    }

    private fun assertClarifies(resolution: Resolution, reason: String): Resolution.Clarify {
        val clarify = resolution as? Resolution.Clarify
            ?: throw AssertionError("expected a clarification, got $resolution")
        assertEquals(reason, clarify.reason)
        return clarify
    }

    // ---- C2, implicit goal -------------------------------------------------

    @Test
    fun `CVC-04 a stated discomfort becomes a real adjustment, not an acknowledgement`() {
        adjusted(ClimateToolActions.SET_TEMPERATURE, 26.0)
        assertAdjusts(resolve("有点热。"), Dimension.TEMPERATURE, -2.0)
    }

    @Test
    fun `CVC-05 the opposite discomfort moves the other way`() {
        adjusted(ClimateToolActions.SET_TEMPERATURE, 20.0)
        assertAdjusts(resolve("有点冷。"), Dimension.TEMPERATURE, 2.0)
    }

    @Test
    fun `CVC-06 with the climate off the driver would feel nothing, so power comes first`() {
        context.onClimateResult(
            ClimateToolActions.POWER_OFF,
            null,
            climateResult(powerOn = false),
            epoch = 1,
        )
        assertAdjusts(resolve("有点热。"), Dimension.TEMPERATURE, -2.0, powerOnFirst = true)
    }

    @Test
    fun `CVC-07 too much wind adjusts the fan, never the temperature`() {
        adjusted(ClimateToolActions.SET_FAN, 5.0)
        assertAdjusts(resolve("风太大了。"), Dimension.FAN, -1.0)
    }

    // ---- C3, relative continuation -----------------------------------------

    @Test
    fun `CVC-09 a word that names the dimension resolves without any history`() {
        assertAdjusts(resolve("再凉一点。"), Dimension.TEMPERATURE, -1.0)
    }

    @Test
    fun `CVC-10 one adjusted dimension makes a neutral direction unambiguous`() {
        adjusted(ClimateToolActions.ADJUST_FAN, 1.0)
        assertAdjusts(resolve("再大一点。"), Dimension.FAN, 1.0)
    }

    @Test
    fun `CVC-11 two adjusted dimensions must be asked about, not guessed`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, epoch = 1)
        adjusted(ClimateToolActions.ADJUST_FAN, 1.0, epoch = 1)
        val clarify = assertClarifies(resolve("再低一点。"), ContextResolver.REASON_AMBIGUOUS)
        assertEquals(2, clarify.options.size)
    }

    @Test
    fun `CVC-12 no referent at all is asked about, never defaulted to a dimension`() {
        assertClarifies(resolve("再高一点。"), ContextResolver.REASON_NO_REFERENT)
    }

    @Test
    fun `CVC-13 S1 - a referent older than the TTL is not reused`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, epoch = 1)
        now += DriverContext.REFERENT_TTL_MS + 1
        assertClarifies(resolve("再低一点。"), ContextResolver.REASON_NO_REFERENT)
    }

    // ---- C4, relative reversal ---------------------------------------------

    @Test
    fun `CVC-14 a reversal goes one step back, not a full undo`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -2.0, epoch = 1)
        assertAdjusts(resolve("有点冷了，刚才那个调回来一点。"), Dimension.TEMPERATURE, 1.0)
    }

    @Test
    fun `CVC-15 a reversal follows the dimension that was actually adjusted`() {
        adjusted(ClimateToolActions.ADJUST_FAN, 1.0, epoch = 1)
        assertAdjusts(resolve("刚才那个调回来。"), Dimension.FAN, -1.0)
    }

    @Test
    fun `CVC-16 S6 - a failed adjustment is nothing to reverse`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -2.0, epoch = 1, ok = false)
        assertClarifies(resolve("刚才那个调回来一点。"), ContextResolver.REASON_NOTHING_TO_REVERSE)
    }

    // ---- C5, answering the question the app asked --------------------------

    @Test
    fun `CVC-18 the answer to a clarification carries out the adjustment that was pending`() {
        context.recordClarification(listOf(Dimension.TEMPERATURE, Dimension.FAN), -1.0, epoch = 5)
        assertAdjusts(resolve("温度。", epoch = 6), Dimension.TEMPERATURE, -1.0)
    }

    @Test
    fun `CVC-18b an unrelated utterance abandons the clarification instead of answering it`() {
        context.recordClarification(listOf(Dimension.TEMPERATURE, Dimension.FAN), -1.0, epoch = 5)
        assertEquals(Resolution.NotContextual, resolve("导航去珠海站。", epoch = 6))
    }

    @Test
    fun `a clarification is valid for one turn only`() {
        context.recordClarification(listOf(Dimension.TEMPERATURE, Dimension.FAN), -1.0, epoch = 5)
        // Two turns later it is gone, so the bare answer no longer resolves to anything.
        assertEquals(Resolution.NotContextual, resolve("温度。", epoch = 7))
    }

    // ---- C8, feedback-driven recovery --------------------------------------

    @Test
    fun `CVC-27 feedback that it is still too warm adjusts again`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -2.0, epoch = 1)
        assertAdjusts(resolve("还是有点热。"), Dimension.TEMPERATURE, -2.0)
    }

    @Test
    fun `CVC-28 at the limit the reply must be honest, so the resolution says so`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -2.0, epoch = 1, limitReached = true)
        val adjust = assertAdjusts(resolve("还是有点热。"), Dimension.TEMPERATURE, -2.0)
        assertTrue(adjust.atLimit, "the limit must be carried into the hint")
    }

    @Test
    fun `CVC-30 words that name the dimension resolve even with no climate history`() {
        assertAdjusts(resolve("还是有点热。"), Dimension.TEMPERATURE, -2.0)
    }

    // ---- C13, stale context ------------------------------------------------

    @Test
    fun `CVC-44 S2 - context does not survive the session ending`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, epoch = 1)
        context.onSessionEnded()
        assertClarifies(resolve("再低一点。"), ContextResolver.REASON_NO_REFERENT)
    }

    @Test
    fun `CVC-45 S3 - a fresh process starts with no referent`() {
        val fresh = DriverContext { now }
        fresh.onDriverUtterance("再低一点。", 1)
        assertClarifies(
            ContextResolver.resolve("再低一点。", fresh, 1),
            ContextResolver.REASON_NO_REFERENT,
        )
    }

    @Test
    fun `CVC-46 S5 - a cancelled turn leaves nothing to refer to`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, epoch = 1)
        context.cancel(1)
        assertClarifies(resolve("再低一点。"), ContextResolver.REASON_NO_REFERENT)
    }

    // ---- an implicit request is still a request for an action ----------------

    @Test
    fun `a stated discomfort is an action request, so its claims need proof`() {
        // Measured on device 2026-09-19: both of these were answered with a claim and no tool
        // call, and released unheld, because classify() saw no control word in them.
        listOf("有点热。", "还是有点热。", "有点冷。", "风太大了。").forEach {
            assertTrue(ContextResolver.isImplicitComfortRequest(it), it)
            assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify(it), it)
        }
    }

    @Test
    fun `an ambiguous request is not treated as a resolved one`() {
        // asksForClimateChange gates the nudge, so it must be false exactly when the ambiguity
        // policy wants a question — otherwise the nudge would override the clarification.
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, epoch = 1)
        adjusted(ClimateToolActions.ADJUST_FAN, 1.0, epoch = 1)
        context.onDriverUtterance("再低一点。", 2)
        assertClarifies(
            ContextResolver.resolve("再低一点。", context, 2),
            ContextResolver.REASON_AMBIGUOUS,
        )
    }

    @Test
    fun `chat is still chat`() {
        listOf("你好。", "你能做什么？", "今天过得怎么样。").forEach {
            assertTrue(!ContextResolver.isImplicitComfortRequest(it), it)
        }
    }

    // ---- not everything is contextual --------------------------------------

    @Test
    fun `an explicit command needs no context and is left alone`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, epoch = 1)
        assertEquals(Resolution.NotContextual, resolve("空调调到22度。"))
    }

    @Test
    fun `S4 - only the newest adjustment of a dimension is the referent`() {
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, -2.0, epoch = 1)
        adjusted(ClimateToolActions.ADJUST_TEMPERATURE, 1.0, epoch = 2)
        // The reversal must answer the +1, not the -2 that preceded it.
        assertAdjusts(resolve("刚才那个调回来一点。", epoch = 3), Dimension.TEMPERATURE, -1.0)
    }
}
