package com.novadrive.app.voice

import com.novadrive.app.nav.NavigationPhase
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
}
