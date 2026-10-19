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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P67: both capability claim points log one text-free line, including the paths that skip claiming. */
class CapabilityClaimLogTest {
    private val registry = ScreenAffordances().apply { publish("bar", listOf(Affordance(CLIMATE, listOf("空调")))) }
    private val controls = FakeControls()
    private val logs = mutableListOf<String>()

    private val modelLogs = mutableListOf<String>()

    init {
        DebugVoiceLog.sink = { modelLogs += it }
    }

    @AfterEach
    fun restoreSink() {
        DebugVoiceLog.sink = null
    }

    private fun modelCall(action: String) =
        DomainVoiceEvent.ToolCall("m1", "control_climate", mapOf("action" to action))

    private fun modelLines() = modelLogs.filter { it.startsWith("claim src=MODEL") }

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

        assertEquals("DUPLICATE_IN_TURN", ToolCallGuards.repeatedInTurn(modelCall(ClimateToolActions.POWER_OFF), context))
        val modelLine = modelLines().single()
        assertTrue(modelLine.contains("ok=false"), modelLine)
        assertTrue(modelLine.contains("epoch=7"), modelLine)
        assertTrue(modelLine.contains("ctx=${System.identityHashCode(context)}"), modelLine)
        assertTrue(modelLine.contains("group=power"), modelLine)
    }

    @Test
    fun modelWithoutContextLogsSkipped() {
        assertNull(ToolCallGuards.repeatedInTurn(modelCall(ClimateToolActions.POWER_OFF), null))
        val line = modelLines().single()
        assertTrue(line.contains("ok=skipped"), line)
        assertTrue(line.contains("ctx=null"), line)
    }

    @Test
    fun modelWithEpochZeroLogsSkipped() {
        assertNull(ToolCallGuards.repeatedInTurn(modelCall(ClimateToolActions.POWER_OFF), DriverContext()))
        val line = modelLines().single()
        assertTrue(line.contains("ok=skipped"), line)
        assertTrue(line.contains("epoch=0"), line)
    }

    @Test
    fun modelClaimsFirstLogsTrue() {
        val context = DriverContext().apply { onSpeechStarted(3) }
        assertNull(ToolCallGuards.repeatedInTurn(modelCall(ClimateToolActions.POWER_OFF), context))
        val line = modelLines().single()
        assertTrue(line.contains("ok=true"), line)
        assertTrue(line.contains("epoch=3"), line)
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
