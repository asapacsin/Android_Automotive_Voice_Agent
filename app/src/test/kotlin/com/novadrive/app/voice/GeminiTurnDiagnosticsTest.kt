package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiTurnDiagnosticsTest {
    private val lines = mutableListOf<String>()
    private val previous = DebugVoiceLog.sink.also { DebugVoiceLog.sink = { lines += it } }

    @AfterEach
    fun restore() { DebugVoiceLog.sink = previous }

    private val scope = TestScope()
    private val diag = GeminiTurnDiagnostics(scope) { scope.testScheduler.currentTime }
    private fun quiet() = lines.filter { it.startsWith("gemini_turn_quiet") }
    private val audio = GeminiLiveProtocol.ServerMessage(audio = listOf("QUJD"), contentFields = listOf("modelTurn"))

    @Test
    fun quietLogsOncePerStretchWithGenFalseWithoutGenerationComplete() {
        diag.message(audio, turnOpen = false); diag.turnOpened()
        scope.advanceTimeBy(1_000); diag.message(audio, turnOpen = true)
        scope.advanceTimeBy(4_999); scope.runCurrent()
        assertTrue(quiet().isEmpty())
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(listOf("gemini_turn_quiet ms=5000 msgs=2 gen=false calls=0"), quiet())
        scope.advanceTimeBy(20_000); scope.runCurrent()
        assertEquals(1, quiet().size)
    }

    @Test
    fun quietSaysGenTrueAndCountsCallsWhenGenerationCompleteCameFirst() {
        val call = GeminiLiveProtocol.RawCall("c1", "control_climate", null, 0)
        diag.message(GeminiLiveProtocol.ServerMessage(toolCalls = listOf(call)), turnOpen = false); diag.turnOpened()
        diag.message(GeminiLiveProtocol.ServerMessage(generationComplete = true, contentFields = listOf("generationComplete")), turnOpen = true)
        scope.advanceTimeBy(5_001); scope.runCurrent()
        assertEquals(listOf("gemini_turn_quiet ms=5000 msgs=2 gen=true calls=1"), quiet())
    }

    @Test
    fun aClosedTurnNeverLogsQuiet() {
        diag.message(audio, turnOpen = false); diag.turnOpened(); diag.turnClosed()
        scope.advanceTimeBy(10_000); scope.runCurrent()
        assertTrue(quiet().isEmpty())
    }

    @Test
    fun resetForgetsAPendingActivityEnd() {
        diag.message(GeminiLiveProtocol.ServerMessage(voiceActivity = GeminiLiveProtocol.ACTIVITY_END), turnOpen = false)
        diag.reset()
        diag.message(GeminiLiveProtocol.ServerMessage(inputTranscription = "x", contentFields = listOf("inputTranscription")), turnOpen = false)
        assertTrue(lines.none { it.startsWith("gemini_first_transcript") })
    }
}
