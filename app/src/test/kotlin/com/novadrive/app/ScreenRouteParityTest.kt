package com.novadrive.app

import com.novadrive.app.nav.RecenterOutcome
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * SPEC-010 A4: 📍 and 📷 reach their action through [ScreenControls], the one route a tap and a
 * spoken command share (I-6). Before this the bar held its own click callbacks for both.
 */
class ScreenRouteParityTest {

    @Test
    fun recenterReportsTheMapOutcome() = runBlocking {
        var result = RecenterOutcome.MOVED
        val controls = controls(recenter = { result })
        assertEquals(ScreenControls.Outcome(true), controls.recenter())
        result = RecenterOutcome.NO_FIX
        assertEquals(ScreenControls.Outcome(false, "RECENTER_NO_FIX"), controls.recenter())
    }

    @Test
    fun cameraToggleIsPassedThrough() = runBlocking {
        var calls = 0
        val controls = controls(camera = { calls++; ScreenControls.Outcome(false, "CAMERA_PERMISSION_REQUIRED") })
        val outcome = controls.toggleCamera()
        assertEquals(1, calls)
        assertFalse(outcome.ok)
        assertEquals("CAMERA_PERMISSION_REQUIRED", outcome.errorCode)
    }

    @Test
    fun bottomBarHasNoPrivateRouteForRecenterOrCamera() {
        val bar = source("app/src/main/kotlin/com/novadrive/app/ui/BottomBarView.kt")
        assertFalse(bar.contains("onRecenterClick")) { "📍 must go through ScreenControls.recenter()" }
        assertFalse(bar.contains("onCameraClick")) { "📷 must go through ScreenControls.toggleCamera()" }
        assertTrue(bar.contains("it.recenter()") && bar.contains("it.toggleCamera()"))
    }

    private fun controls(
        recenter: () -> RecenterOutcome = { RecenterOutcome.MOVED },
        camera: () -> ScreenControls.Outcome = { ScreenControls.Outcome(true) },
    ): ScreenControls {
        val vehicle = SimulatedVehicleControl()
        return ExecutorScreenControls(
            executor = NoExecutor,
            climatePort = vehicle,
            climateHandler = ClimateToolHandler(vehicle),
            musicPlaying = MutableStateFlow(false),
            recenterMap = recenter,
            cameraToggle = camera,
        )
    }

    private fun source(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir), path).readText()
    }

    private object NoExecutor : AndroidActionExecutor {
        private val no = AndroidActionResult.Rejected("UNUSED")
        override fun navigate(destination: String) = no
        override fun openApp(app: AllowedApp) = no
        override fun playMusic() = no
        override fun stopMusic() = no
        override fun exitNavigationMode() = no
    }
}
