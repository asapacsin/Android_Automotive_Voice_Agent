package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against a working feature silently disappearing during an unrelated change.
 *
 * The wake word stopped working once before after another update, and nothing failed. These
 * checks are deliberately structural: each asserts one link that the feature cannot work without.
 * If a change removes a link on purpose, this test is where that decision gets made explicit.
 */
class FeaturePresenceRegressionTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    private fun assertContains(path: String, needle: String, why: String) {
        assertTrue(text(path).contains(needle), "$path must contain `$needle` — $why")
    }

    // ---- wake word: 你好小诺 -> session ----

    @Test
    fun wakeWordIsBoundAtAppStart() {
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/MainActivity.kt",
            "DebugVoiceLog.init(this)",
            "the process-lifetime wake-word owner is bound from here",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/DebugVoiceLog.kt",
            "WakeWordController.bind(context)",
            "without this nothing ever starts the detector",
        )
    }

    @Test
    fun wakeDetectionStartsTheVoiceSession() {
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/WakeWordController.kt",
            "created.onWake = { onDetected() }",
            "the detector callback must be wired",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/WakeWordController.kt",
            "VoiceSessionGateway.start()",
            "a detection must open the same session as every other entry point",
        )
    }

    @Test
    fun wakeWordEngineArtifactsArePackaged() {
        assertContains("app/build.gradle.kts", "files(\"libs/Msc.jar\")", "iFlytek MSC classes")
        for (path in listOf(
            "app/libs/Msc.jar",
            "app/src/main/assets/ivw/wakeword.jet",
            "app/src/main/jniLibs/arm64-v8a/libmsc.so",
            "app/src/main/jniLibs/arm64-v8a/libw_ivw.so",
        )) {
            assertTrue(File(root, path).isFile, "wake-word artifact missing: $path")
        }
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt",
            "\"ivw/wakeword.jet\"",
            "the detector loads this exact asset",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt",
            "IVW_NET_MODE_OFFLINE = \"0\"",
            "wake detection must stay offline (modes 1-2 upload audio)",
        )
    }

    // ---- permissions every main feature depends on ----

    @Test
    fun requiredPermissionsAreDeclaredAndRequested() {
        val manifest = "app/src/main/AndroidManifest.xml"
        for (permission in listOf("RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "INTERNET")) {
            assertContains(manifest, "android.permission.$permission", "feature depends on it")
        }
        val activity = "app/src/main/kotlin/com/novadrive/app/MainActivity.kt"
        assertContains(activity, "Manifest.permission.CAMERA", "camera permission must be requested")
        assertContains(activity, "ACTION_APPLICATION_DETAILS_SETTINGS", "settings fallback after a permanent refusal")
        assertContains(activity, "Manifest.permission.ACCESS_FINE_LOCATION", "embedded navigation needs GNSS")
    }

    // ---- the debug harness must never ship in a release build ----

    @Test
    fun debugToolsStayInTheDebugSourceSet() {
        assertTrue(
            !File(root, "app/src/main/kotlin/com/novadrive/app/DebugToolReceiver.kt").exists(),
            "DebugToolReceiver must not move into the main source set",
        )
        assertTrue(
            !text("app/src/main/AndroidManifest.xml").contains("DebugToolReceiver"),
            "the ADB receiver must only be declared in the debug manifest",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt",
            "if (!com.novadrive.app.DebugVoiceLog.isEnabled) return",
            "synthetic speech injection must be inert on non-debuggable builds",
        )
    }

    // ---- UI features that were removed-by-accident candidates ----

    @Test
    fun navigationPickerAndCameraAreStillOnTheMainScreen() {
        val screen = "app/src/main/kotlin/com/novadrive/app/ui/AssistantNavigationScreen.kt"
        assertContains(screen, "NavigationChoiceOverlay(context)", "destination / route picker")
        assertContains(screen, "choiceOverlay.bind(", "picker must be bound to the controller")
        assertContains(screen, "CameraPreviewView(context)", "camera window")
        assertContains(screen, "CameraVisionGateway.attach(", "camera must be reachable by the vision tool")
        assertContains(screen, "NavigationHostGateway.attach(", "embedded map host must be reachable")
        assertContains(screen, "bottomBar.bindClimate(", "climate controls must be bound to the port")
        assertContains(screen, "camera.onHostPause()", "the camera must be released when the screen is backgrounded")
        assertContains(screen, "camera.onHostResume()", "and reopened when the screen returns")
    }
}
