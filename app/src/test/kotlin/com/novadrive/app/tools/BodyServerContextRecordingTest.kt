package com.novadrive.app.tools

import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.simulator.SimulatedVehicleControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** BodyServer records ok=true body results into DriverContext, like ClimateServer does. */
class BodyServerContextRecordingTest {
    private val context = DriverContext().also { it.onDriverUtterance("座椅低一点", 1) }
    private val env = ToolCallEnv(
        { context },
        { c, code, _ -> ToolDispatchResult(null, null, blockedReason = code, output = c.name) },
        { c, _ -> ToolDispatchResult(null, null, output = c.name) },
    )

    private fun call(name: String, vararg args: Pair<String, String>) = DomainVoiceEvent.ToolCall("c1", name, args.toMap())

    @Test
    fun anOkSeatAdjustBecomesAReferent() {
        BodyServer(SimulatedVehicleControl()).call(call("control_seat", "action" to "adjust_height", "value" to "-1"), env)
        val referent = context.validReferents().single()
        assertEquals(DriverContext.Dimension.SEAT_HEIGHT, referent.dimension)
        assertEquals(-1.0, referent.delta)
        assertNotNull(context.cabinState()?.seatHeight)
    }

    @Test
    fun anUnavailableBackendRecordsNothing() {
        BodyServer(null).call(call("control_window", "action" to "adjust", "value" to "20"), env)
        assertTrue(context.validReferents().isEmpty())
    }
}
