package com.novadrive.app

import com.novadrive.app.ScreenAffordanceRunner.Companion.CLIMATE
import com.novadrive.app.ui.Affordance
import com.novadrive.app.ui.ScreenAffordances
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.vehicle.ClimateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P67: both capability claim points log one text-free line, including the paths that skip claiming. */
class CapabilityClaimLogTest {
    private val registry = ScreenAffordances().apply { publish("bar", listOf(Affordance(CLIMATE, listOf("空调")))) }
    private val controls = FakeControls()
    private val logs = mutableListOf<String>()

    private fun runner(context: DriverContext?) = ScreenAffordanceRunner(
        registry, controls, { context }, CoroutineScope(Dispatchers.Unconfined), { logs += it }, { },
    )

    private fun claimLines() = logs.filter { it.startsWith("claim src=LOCAL") }

    @Test
    fun noContextLogsSkippedAndStillRuns() {
        assertTrue(runner(null).tryHandle("空调"))
        assertEquals(listOf("power"), controls.calls)
        val line = claimLines().single()
        assertTrue(line.contains("ok=skipped"), line)
        assertTrue(line.contains("ctx=null"), line)
    }

    @Test
    fun freshContextWithEpochZeroLogsSkipped() {
        val context = DriverContext()
        assertTrue(runner(context).tryHandle("空调"))
        assertEquals(listOf("power"), controls.calls)
        val line = claimLines().single()
        assertTrue(line.contains("ok=skipped"), line)
        assertTrue(line.contains("epoch=0"), line)
    }

    @Test
    fun claimedLocalThenModelCallIsDuplicate() {
        val context = DriverContext().apply { onSpeechStarted(7) }
        assertTrue(runner(context).tryHandle("空调"))
        val line = claimLines().single()
        assertTrue(line.contains("ok=true"), line)
        assertTrue(line.contains("epoch=7"), line)
        assertTrue(line.contains("ctx=${System.identityHashCode(context)}"), line)
        assertTrue(line.contains("tool=control_climate group=power"), line)

        val model = DomainVoiceEvent.ToolCall(
            "m1", "control_climate", mapOf("action" to ClimateToolActions.POWER_OFF),
        )
        assertEquals("DUPLICATE_IN_TURN", ToolCallGuards.repeatedInTurn(model, context))
    }

    private class FakeControls : ScreenControls {
        val calls = mutableListOf<String>()
        override val musicPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override val climate: StateFlow<ClimateState> = MutableStateFlow(ClimateState(powerOn = false, targetTemperatureCelsius = 24.0, fanLevel = 2))
        override suspend fun toggleMusic(): ScreenControls.Outcome { calls += "toggle_music"; return ScreenControls.Outcome(true) }
        override suspend fun restartMusic(): ScreenControls.Outcome { calls += "restart"; return ScreenControls.Outcome(true) }
        override suspend fun toggleClimatePower(): ScreenControls.Outcome { calls += "power"; return ScreenControls.Outcome(true) }
        override suspend fun adjustTemperature(delta: Double): ScreenControls.Outcome { calls += "adjust"; return ScreenControls.Outcome(true) }
        override suspend fun recenter(): ScreenControls.Outcome { calls += "recenter"; return ScreenControls.Outcome(true) }
        override suspend fun toggleCamera(): ScreenControls.Outcome { calls += "camera"; return ScreenControls.Outcome(true) }
        override fun cameraShowing(): Boolean = false
    }
}
