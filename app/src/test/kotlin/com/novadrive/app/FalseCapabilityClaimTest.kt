package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.app.voice.DriverTurn
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two ways the product could tell the driver that something happened when it did not, both of
 * which pass every other guard because a tool really did return `ok=true`.
 *
 * `media` is one bundled track with play/stop — no library, no search, no metadata. So a request
 * that names a song is not a media request this product can serve, and answering it by starting the
 * bundled track makes `ok=true` mean "you got what you asked for"
 * ([I-2](../../../../../../docs/INVARIANTS.md)).
 *
 * And a relative adjustment is not idempotent: the same `adjust_temperature{-2}` dispatched twice
 * is −4 °C, a physical change the driver never asked for.
 */
class FalseCapabilityClaimTest {

    private fun call(name: String, arguments: Map<String, String>) =
        DomainVoiceEvent.ToolCall("call_1", name, arguments)

    /** Accepts everything it is asked to do, so a refusal in a test can only come from the guard. */
    private class WillingExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Accepted("navigating")

        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")

        override fun playMusic() = AndroidActionResult.Accepted("playing")

        override fun stopMusic() = AndroidActionResult.Accepted("stopped")

        override fun exitNavigationMode() = AndroidActionResult.Accepted("exited")
    }

    private fun dispatcher(context: DriverContext) = AndroidToolDispatcher(
        WillingExecutor(),
        ClimateToolHandler(SimulatedVehicleControl()),
        noCamera(),
    ) { context }

    // ---- recognising a request this product cannot serve --------------------

    @Test
    fun aRequestNamingASongIsRecognisedAsUnsupported() {
        listOf(
            "放一下周杰伦那首我忘了名字的歌，就是讲晴天的那个。",
            "放周杰伦的歌。",
            "我想听点轻音乐的歌曲。",
        ).forEach {
            assertTrue(ActionClaimGuard.isSpecificMediaRequest(it), "should be unsupported: $it")
            assertTrue(ActionClaimGuard.isUnsupportedRequest(it), "should classify as unsupported: $it")
            assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify(it), it)
        }
    }

    @Test
    fun aGenericMusicRequestIsStillSupported() {
        listOf("播放音乐。", "放首歌。", "来点音乐。", "我想听点音乐。").forEach {
            assertFalse(ActionClaimGuard.isSpecificMediaRequest(it), "should stay supported: $it")
            assertFalse(ActionClaimGuard.isUnsupportedRequest(it), "should stay supported: $it")
        }
    }

    @Test
    fun stoppingMusicAndUnrelatedSentencesAreNotMediaRequests() {
        // 「放大地图」 contains 放 but no music noun; 「关闭音乐」 is not a play request at all.
        listOf("关闭音乐。", "放大地图。", "导航去珠海站。").forEach {
            assertFalse(ActionClaimGuard.isSpecificMediaRequest(it), it)
        }
    }

    @Test
    fun skippingTracksIsRecognisedAsUnsupported() {
        // media.next_track is `unsupported` in the registry and had no recogniser at all.
        listOf("下一首。", "换一首。", "切歌。").forEach {
            assertTrue(ActionClaimGuard.isUnsupportedRequest(it), it)
        }
    }

    // ---- and refusing to execute it ----------------------------------------

    @Test
    fun aNamedSongNeverStartsTheBundledTrack() {
        val context = DriverContext()
        context.onDriverUtterance("放一下周杰伦那首讲晴天的歌。", epoch = 1)
        val result = dispatcher(context).dispatch(call("control_music", mapOf("action" to "play")))
        val output = JSONObject(result.output!!)

        assertFalse(output.getBoolean("ok"), "a song this product cannot play must not report success")
        assertEquals("MEDIA_LIBRARY_UNSUPPORTED", output.getString("error"))
        assertTrue(output.getString("next").contains("没有音乐库"), "the model must be told what to say")
    }

    @Test
    fun aGenericPlayRequestStillPlays() {
        val context = DriverContext()
        context.onDriverUtterance("播放音乐。", epoch = 1)
        val result = dispatcher(context).dispatch(call("control_music", mapOf("action" to "play")))
        assertTrue(JSONObject(result.output!!).getBoolean("ok"))
    }

    // ---- and not doing the same thing twice ---------------------------------

    @Test
    fun theSameRelativeAdjustmentIsNotAppliedTwiceInOneTurn() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onDriverUtterance("空调调凉一点。", epoch = 1)
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val arguments = mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-2")

        val first = dispatcher.dispatch(call("control_climate", arguments))
        val afterFirst = vehicle.climateState.value.targetTemperatureCelsius
        assertTrue(JSONObject(first.output!!).getBoolean("ok"))

        val repeat = dispatcher.dispatch(call("control_climate", arguments))
        assertFalse(JSONObject(repeat.output!!).getBoolean("ok"))
        assertEquals("DUPLICATE_IN_TURN", JSONObject(repeat.output!!).getString("error"))
        assertEquals(
            afterFirst,
            vehicle.climateState.value.targetTemperatureCelsius,
            "the repeat must not move the temperature again",
        )
    }

    @Test
    fun askingAgainInANewTurnIsNotADuplicate() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val arguments = mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")

        context.onDriverUtterance("再凉一点。", epoch = 1)
        dispatcher.dispatch(call("control_climate", arguments))
        val afterFirst = vehicle.climateState.value.targetTemperatureCelsius

        context.onDriverUtterance("再凉一点。", epoch = 2)
        dispatcher.dispatch(call("control_climate", arguments))

        assertEquals(
            afterFirst - 1.0,
            vehicle.climateState.value.targetTemperatureCelsius,
            "a second utterance is a second intent and must take effect",
        )
    }

    // ---- and not guessing when the driver did not say what to change ---------

    @Test
    fun anAmbiguousRelativeAdjustmentIsRefusedRatherThanGuessed() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        val before = vehicle.climateState.value.targetTemperatureCelsius
        // Both dimensions adjusted, so 「再低一点」 could mean either.
        context.onClimateResult(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, okClimate(), epoch = 1)
        context.onClimateResult(ClimateToolActions.ADJUST_FAN, 1.0, okClimate(), epoch = 1)
        context.onDriverUtterance("再低一点。", epoch = 2)

        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")),
        )
        val output = JSONObject(result.output!!)

        assertFalse(output.getBoolean("ok"), "guessing right is still guessing")
        assertEquals("AMBIGUOUS_REFERENT", output.getString("error"))
        assertEquals(
            before,
            vehicle.climateState.value.targetTemperatureCelsius,
            "nothing may change while the question is unanswered",
        )
        // And the question is recorded, so the driver's one-word answer resolves next turn.
        assertEquals(2, context.pendingClarification(3)?.options?.size)
    }

    @Test
    fun anUnambiguousRelativeAdjustmentStillRuns() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onClimateResult(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, okClimate(), epoch = 1)
        context.onDriverUtterance("再低一点。", epoch = 2)

        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")),
        )
        assertTrue(JSONObject(result.output!!).getBoolean("ok"), "one referent is not ambiguous")
    }

    @Test
    fun anExplicitCommandIsNeverBlockedByTheAmbiguityGuard() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onDriverUtterance("空调调到二十二度。", epoch = 1)
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.SET_TEMPERATURE, "value" to "22")),
        )
        assertTrue(JSONObject(result.output!!).getBoolean("ok"))
    }

    private fun okClimate(): String =
        """{"ok":true,"tool":"control_climate","power_on":true,"temperature_c":24.0,""" +
            """"fan_level":3,"limit_reached":false}"""

    // ---- the context record only trusts proven execution --------------------

    @Test
    fun aFailedClimateResultLeavesNoReferentBehind() {
        val context = DriverContext()
        context.onClimateResult(
            action = ClimateToolActions.ADJUST_TEMPERATURE,
            value = -2.0,
            output = """{"ok":false,"tool":"control_climate","error":"VEHICLE_UNAVAILABLE"}""",
            epoch = 1,
        )
        assertTrue(context.validReferents().isEmpty(), "an action that failed is not a referent")
    }

    @Test
    fun aLateResultForACancelledTurnIsIgnored() {
        val context = DriverContext()
        context.cancel(1)
        context.onClimateResult(
            action = ClimateToolActions.ADJUST_TEMPERATURE,
            value = -2.0,
            output = """{"ok":true,"tool":"control_climate","power_on":true,"temperature_c":22.0,""" +
                """"fan_level":3,"limit_reached":false}""",
            epoch = 1,
        )
        assertTrue(context.validReferents().isEmpty(), "a cancelled turn may not write context")
    }
}
