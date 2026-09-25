package com.novadrive.app

import com.novadrive.app.ScreenAffordanceRunner.Companion.CLIMATE
import com.novadrive.app.ScreenAffordanceRunner.Companion.MUSIC_PLAY
import com.novadrive.app.ScreenAffordanceRunner.Companion.MUSIC_STOP
import com.novadrive.app.ScreenAffordanceRunner.Companion.RECENTER
import com.novadrive.app.ScreenAffordanceRunner.Companion.TEMP_DOWN
import com.novadrive.app.ui.Affordance
import com.novadrive.app.ui.ScreenAffordances
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.vehicle.ClimateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-010 B3–B5: a matched control runs through [ScreenControls], once per turn, by the word said. */
class ScreenAffordanceRunnerTest {
    private val registry = ScreenAffordances().apply {
        publish(
            "bar",
            listOf(
                Affordance(MUSIC_PLAY, listOf("播放")),
                Affordance(MUSIC_STOP, listOf("暂停")),
                Affordance(TEMP_DOWN, listOf("温度减")),
                Affordance(CLIMATE, listOf("空调")),
                Affordance(RECENTER, listOf("回到当前位置")),
            ),
        )
    }
    private val controls = FakeControls()
    private val context = DriverContext().apply { onDriverUtterance("x", epoch = 1) }
    private val failures = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val runner = ScreenAffordanceRunner(
        registry, controls, { context }, CoroutineScope(Dispatchers.Unconfined), { logs += it }, { failures += it },
    )

    @Test
    fun aSpokenControlRunsThroughScreenControls() {
        assertTrue(runner.tryHandle("温度减"))
        assertEquals(listOf("adjust:-1.0"), controls.calls)
        assertTrue(logs.any { it.startsWith("affordance_match id=temp_down") })
    }

    @Test
    fun aSentenceIsLeftToTheModel() {
        assertFalse(runner.tryHandle("把温度调低一点然后去公司"))
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    fun theModelAlreadyRanItSoTheScreenDoesNothing() {
        context.claimCapability(1, "control_climate", ClimateToolActions.ADJUST_TEMPERATURE, DriverContext.ClaimSource.MODEL)
        assertFalse(runner.tryHandle("温度减"))
        assertTrue(controls.calls.isEmpty())
        assertTrue(logs.contains("affordance_skip reason=claimed"))
    }

    @Test
    fun aFailureIsReportedForTheModelToSay() {
        controls.adjustResult = ScreenControls.Outcome(false, "HVAC_FAULT")
        assertTrue(runner.tryHandle("温度减"))
        assertEquals(listOf("HVAC_FAULT"), failures)
        assertTrue(ScreenAffordanceRunner.failureMessage("HVAC_FAULT").contains("不要说已经完成"))
    }

    @Test
    fun pauseWhilePausedGoesToTheModelInsteadOfToggling() {
        assertFalse(runner.tryHandle("暂停"))
        assertTrue(runner.tryHandle("播放"))
        assertEquals(listOf("toggle_music"), controls.calls)
    }

    @Test
    fun climateActsByTheWordNotAsABlindToggle() {
        assertNull(ScreenAffordanceRunner.plan(CLIMATE, "关掉", musicPlaying = false, climateOn = false))
        assertNull(ScreenAffordanceRunner.plan(CLIMATE, "打开", musicPlaying = false, climateOn = true))
        assertEquals(ClimateToolActions.POWER_OFF, ScreenAffordanceRunner.plan(CLIMATE, "关闭", false, true)?.action)
        assertEquals(ClimateToolActions.POWER_ON, ScreenAffordanceRunner.plan(CLIMATE, null, false, false)?.action)
    }

    @Test
    fun cameraAndRecenterActByTheWordToo() {
        val camera = ScreenAffordanceRunner.CAMERA
        assertNull(ScreenAffordanceRunner.plan(camera, "关掉", false, false, cameraShowing = false), "关掉摄像头 must not open it")
        assertNull(ScreenAffordanceRunner.plan(camera, "打开", false, false, cameraShowing = true))
        assertTrue(ScreenAffordanceRunner.plan(camera, "关闭", false, false, cameraShowing = true) != null)
        assertTrue(ScreenAffordanceRunner.plan(camera, null, false, false, cameraShowing = true) != null)
        assertNull(ScreenAffordanceRunner.plan(RECENTER, "关闭", false, false), "关闭定位 is not a recentre")
    }

    @Test
    fun aRecentreFailureIsNotSpokenBecauseTheMapAlreadyShowsIt() {
        controls.recenterResult = ScreenControls.Outcome(false, "RECENTER_NO_FIX")
        assertTrue(runner.tryHandle("回到当前位置"))
        assertTrue(failures.isEmpty())
    }

    @Test
    fun namesAreSplitFromResources() {
        assertEquals(listOf("上一首", "重新播放"), ScreenAffordanceRunner.names("上一首| 重新播放 |"))
    }

    private class FakeControls : ScreenControls {
        val calls = mutableListOf<String>()
        var recenterResult = ScreenControls.Outcome(true)
        var adjustResult = ScreenControls.Outcome(true)
        override val musicPlaying: StateFlow<Boolean> = MutableStateFlow(false)
        override val climate: StateFlow<ClimateState> = MutableStateFlow(ClimateState(powerOn = false, targetTemperatureCelsius = 24.0, fanLevel = 2))
        override suspend fun toggleMusic(): ScreenControls.Outcome { calls += "toggle_music"; return ScreenControls.Outcome(true) }
        override suspend fun restartMusic(): ScreenControls.Outcome { calls += "restart"; return ScreenControls.Outcome(true) }
        override suspend fun toggleClimatePower(): ScreenControls.Outcome { calls += "power"; return ScreenControls.Outcome(true) }
        override suspend fun adjustTemperature(delta: Double): ScreenControls.Outcome {
            calls += "adjust:$delta"; return adjustResult
        }
        override suspend fun recenter(): ScreenControls.Outcome { calls += "recenter"; return recenterResult }
        override suspend fun toggleCamera(): ScreenControls.Outcome { calls += "camera"; return ScreenControls.Outcome(true) }
        override fun cameraShowing(): Boolean = false
    }
}
