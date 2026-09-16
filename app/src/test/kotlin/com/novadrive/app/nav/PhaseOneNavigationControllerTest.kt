package com.novadrive.app.nav

import com.novadrive.app.AndroidActionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class PhaseOneNavigationControllerTest {
    @Test
    fun navigateReturnsEmbeddedRoutingNotImplemented() {
        val controller = PhaseOneNavigationController()
        val result = controller.navigate("人民广场")
        assertTrue(result is AndroidActionResult.Rejected)
        assertEquals("EMBEDDED_ROUTING_NOT_IMPLEMENTED", (result as AndroidActionResult.Rejected).code)
        assertEquals("人民广场", controller.lastDestination)
        assertEquals(NavigationPhase.IDLE, controller.state().value)
    }

    @Test
    fun sourceConstructsNoIntentAndReferencesNoAmapPackage() {
        val source = controllerSource().readText()
        assertFalse(source.contains("android.content.Intent"))
        assertFalse(source.contains("Intent("))
        assertFalse(source.contains("com.autonavi.minimap"))
        assertFalse(source.contains("androidamap"))
        assertFalse(source.contains("com.amap"))
    }

    private fun controllerSource(): File {
        val candidates =
            listOf(
                File("src/main/kotlin/com/novadrive/app/nav/PhaseOneNavigationController.kt"),
                File("app/src/main/kotlin/com/novadrive/app/nav/PhaseOneNavigationController.kt"),
            )
        return candidates.first { it.isFile }
    }
}
