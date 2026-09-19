package com.novadrive.app.voice

import com.novadrive.app.nav.NavigationPhase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceContextHintsTest {
    @Test
    fun nothingOnScreenMeansNoHint() {
        assertNull(VoiceContextHints.compose(null, cameraOpen = false))
        assertNull(VoiceContextHints.compose(NavigationPhase.IDLE, cameraOpen = false))
        assertNull(VoiceContextHints.compose(NavigationPhase.STOPPED, cameraOpen = false))
        assertNull(VoiceContextHints.compose(NavigationPhase.ARRIVED, cameraOpen = false))
    }

    @Test
    fun openPickersTellTheModelThatCancelMeansExitNavigation() {
        for (phase in listOf(NavigationPhase.AWAITING_DESTINATION_SELECTION, NavigationPhase.AWAITING_ROUTE_SELECTION)) {
            val hint = VoiceContextHints.compose(phase, cameraOpen = false)!!
            assertTrue(hint.contains("算了"), "$phase")
            assertTrue(hint.contains("exit_navigation_mode"), "$phase")
        }
    }

    @Test
    fun openPickersListTheOptionsAndPointAtTheChoiceTool() {
        val hint = VoiceContextHints.compose(
            NavigationPhase.AWAITING_ROUTE_SELECTION,
            cameraOpen = false,
            options = "1. 46.0公里，约44分钟，推荐；2. 42.6公里，约47分钟，常规",
        )!!
        assertTrue(hint.contains("2. 42.6公里"))
        assertTrue(hint.contains("choose_navigation_option"))
        assertTrue(hint.contains("最快"))
        assertTrue(hint.contains("开始导航") && hint.contains("recommended"), "a plain go-ahead picks the recommended route")
    }

    @Test
    fun activeNavigationIsDescribed() {
        val hint = VoiceContextHints.compose(NavigationPhase.NAVIGATING, cameraOpen = false)!!
        assertTrue(hint.contains("正在导航"))
        assertTrue(hint.contains("exit_navigation_mode"))
    }

    @Test
    fun openCameraPointsAtTheVisionTool() {
        val hint = VoiceContextHints.compose(null, cameraOpen = true)!!
        assertTrue(hint.contains("describe_camera_view"))
        val both = VoiceContextHints.compose(NavigationPhase.NAVIGATING, cameraOpen = true)!!
        assertTrue(both.contains("正在导航") && both.contains("describe_camera_view"))
    }

    // ---- SPEC-006 C6: changing the task without restating it -----------------
    //
    // These rules lived only in the hint text, which is prose the model reads. Prose that nothing
    // asserts is prose that quietly changes. Each case below is a sentence a driver actually says
    // whose correct tool call differs by phase.

    @Test
    fun aFartherDestinationIsSwappedWithNearestWhileTheListIsUp() {
        val hint = VoiceContextHints.compose(
            NavigationPhase.AWAITING_DESTINATION_SELECTION,
            cameraOpen = false,
        )!!
        assertTrue(hint.contains("换个近一点的"), "the phrasing the driver uses must be named")
        assertTrue(hint.contains("preference=nearest"), "nearest is supported for destinations")
    }

    @Test
    fun aShorterRouteIsShortestNotNearest() {
        // `nearest` is rejected for routes (PREFERENCE_NOT_FOR_ROUTES), so the hint must not
        // suggest it here — a wasted turn the driver pays for.
        val hint = VoiceContextHints.compose(
            NavigationPhase.AWAITING_ROUTE_SELECTION,
            cameraOpen = false,
        )!!
        assertTrue(hint.contains("preference=shortest"))
        assertTrue(hint.contains("nearest 只能用于地点"), "and it must say why nearest is wrong here")
    }

    @Test
    fun changingDestinationMidDriveAsksInsteadOfGuessing() {
        // There is no candidate list while navigating, so 「换个近一点的」 has nothing to resolve
        // against. Guessing a destination, or silently exiting, are both worse than one question.
        val hint = VoiceContextHints.compose(NavigationPhase.NAVIGATING, cameraOpen = false)!!
        assertTrue(hint.contains("问清楚"), "it must ask")
        assertTrue(hint.contains("不要自己猜目的地"))
        assertTrue(hint.contains("也不要直接退出导航"))
    }

    // ---- SPEC-006 C3/C5: what a bare 「再低一点」 refers to -----------------------

    @Test
    fun oneAdjustedDimensionIsNamedSoTheNextSentenceResolves() {
        val hint = VoiceContextHints.compose(
            null,
            cameraOpen = false,
            referents = listOf(DriverContext.Dimension.FAN),
        )!!
        assertTrue(hint.contains("风量"))
        assertTrue(hint.contains("adjust_fan"))
    }

    @Test
    fun twoAdjustedDimensionsTellTheModelToAsk() {
        val hint = VoiceContextHints.compose(
            null,
            cameraOpen = false,
            referents = listOf(DriverContext.Dimension.TEMPERATURE, DriverContext.Dimension.FAN),
        )!!
        assertTrue(hint.contains("反问"))
        assertTrue(hint.contains("不要自己选一个"))
    }

    @Test
    fun nothingAdjustedSaysNothingAtAll() {
        // The dispatcher refuses an ambiguous adjustment anyway, so a line on every fresh
        // conversation about a sentence the driver has not said is pure noise.
        assertNull(VoiceContextHints.compose(null, cameraOpen = false, referents = emptyList()))
    }

    @Test
    fun aPendingQuestionIsCarriedSoTheOneWordAnswerWorks() {
        val hint = VoiceContextHints.compose(
            null,
            cameraOpen = false,
            pendingClarification = listOf(DriverContext.Dimension.TEMPERATURE, DriverContext.Dimension.FAN),
        )!!
        assertTrue(hint.contains("你刚才已经问过"))
        assertTrue(hint.contains("不要再问一遍"))
    }

    @Test
    fun theClimateStateIsStatedWithoutInvitingArithmetic() {
        val hint = VoiceContextHints.compose(
            null,
            cameraOpen = false,
            climate = DriverContext.Climate(powerOn = true, temperatureC = 23.0, fanLevel = 2),
        )!!
        assertTrue(hint.contains("23"))
        assertTrue(hint.contains("adjust_temperature"))
        assertFalse(hint.contains("set_temperature"), "relative changes must not become arithmetic")
    }
}
