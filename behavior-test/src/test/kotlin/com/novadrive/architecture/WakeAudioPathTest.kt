package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The wake engine is configured for app-fed audio, so the app must actually feed it.
 *
 * Both halves of that sentence shipped broken. The detector never set `AUDIO_SOURCE`, so MSC
 * opened a recorder of its own; and no production code ever called `writeFrame`, so the app-fed
 * path existed only in tests. The engine's own recorder then lost the microphone a second in and
 * ended the session as `error:200061` — a *network* code — which sent three separate diagnoses
 * after the network, the APPID and the `.jet`, all of which were fine.
 *
 * `IflytekWakeWordDetector` needs the Android runtime and cannot be instantiated on the JVM, so
 * this is asserted at the source level. It is the cheapest thing that would have caught it.
 */
class WakeAudioPathTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun source(path: String): String = File(root, path).also {
        assertTrue(it.isFile) { "missing file: $path" }
    }.readText()

    @Test
    fun theWakeEngineIsToldNotToOpenItsOwnRecorder() {
        val detector = source("app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt")
        assertTrue(detector.contains("AUDIO_SOURCE")) {
            "the wake session must set AUDIO_SOURCE, or MSC opens a second recorder and fights the app for the mic"
        }
        assertTrue(detector.contains("\"-1\"")) {
            "AUDIO_SOURCE must be -1: audio arrives through writeFrame, not from a recorder of the engine's own"
        }
    }

    @Test
    fun productionCodeActuallyFeedsTheWakeEngine() {
        // Not a test double: a real call site outside src/test, or the engine starves in silence.
        val callers = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("writeFrame(") }
            .map { it.toRelativeString(root).replace(File.separatorChar, '/') }
            .toList()
        val feeders = callers.filterNot { it.endsWith("wake/IflytekWakeWordDetector.kt") }
        assertTrue(feeders.isNotEmpty()) {
            "nothing in app/src/main calls writeFrame, so the wake engine receives no audio. " +
                "Found only: $callers"
        }
    }

    @Test
    fun theWakeEngineStandsDownWhileConversationalCaptureRuns() {
        val controller = source("app/src/main/kotlin/com/novadrive/app/wake/WakeWordController.kt")
        assertTrue(controller.contains("VoiceSessionGateway.listeningState.uploads")) {
            "wake must release the microphone while conversational capture runs; two owners is the original defect"
        }
    }
}
