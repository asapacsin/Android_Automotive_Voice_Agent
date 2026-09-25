package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.app.voice.DriverContext.ClaimSource
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-010 A3: a local on-screen match and the model's call for the same capability in one turn
 * execute once, whichever arrives first — even when their argument text differs (`-1` / `-1.0`).
 */
class AffordanceTurnClaimTest {
    private val vehicle = SimulatedVehicleControl()
    private val context = DriverContext().apply { onDriverUtterance("温度减", epoch = 1) }
    private val dispatcher = AndroidToolDispatcher(Executor(), ClimateToolHandler(vehicle), noCamera()) { context }

    private fun modelCall(value: String) = DomainVoiceEvent.ToolCall(
        "m1", "control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to value),
    )

    private fun localClaim() =
        context.claimCapability(1, "control_climate", ClimateToolActions.ADJUST_TEMPERATURE, ClaimSource.LOCAL)

    @Test
    fun localFirstThenModelRunsOnce() {
        assertTrue(localClaim())
        val model = dispatcher.dispatch(modelCall("-1.0"))
        assertEquals("DUPLICATE_IN_TURN", JSONObject(model.output!!).getString("error"))
    }

    @Test
    fun modelFirstThenLocalDoesNothing() {
        val model = dispatcher.dispatch(modelCall("-1"))
        assertTrue(JSONObject(model.output!!).getBoolean("ok"))
        assertFalse(localClaim(), "the matcher must skip a capability the model already ran")
    }

    @Test
    fun aNewTurnClaimsAfresh() {
        assertTrue(localClaim())
        context.onDriverUtterance("温度减", epoch = 2)
        assertTrue(context.claimCapability(2, "control_climate", ClimateToolActions.ADJUST_TEMPERATURE, ClaimSource.MODEL))
    }

    @Test
    fun theModelMayStillMakeTwoDifferentAdjustmentsItself() {
        assertTrue(JSONObject(dispatcher.dispatch(modelCall("-1")).output!!).getBoolean("ok"))
        assertTrue(JSONObject(dispatcher.dispatch(modelCall("-2")).output!!).getBoolean("ok"))
    }

    @Test
    fun anotherCapabilityIsNotBlocked() {
        assertTrue(localClaim())
        assertTrue(context.claimCapability(1, "control_music", "stop", ClaimSource.MODEL))
    }

    private class Executor : AndroidActionExecutor {
        private val ok = AndroidActionResult.Accepted()
        override fun navigate(destination: String) = ok
        override fun openApp(app: AllowedApp) = ok
        override fun playMusic() = ok
        override fun stopMusic() = ok
        override fun exitNavigationMode() = ok
    }
}
