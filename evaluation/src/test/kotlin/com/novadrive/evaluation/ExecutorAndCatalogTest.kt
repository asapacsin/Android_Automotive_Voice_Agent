package com.novadrive.evaluation

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ExecutorAndCatalogTest {
    /** A trivial world: a thermostat, a "model" that either acts or only talks. */
    private class ThermostatDriver(private val recorder: TelemetryRecorder, private val act: Boolean) : ScenarioDriver {
        override val mode = TestMode.SIM_LOGIC
        var temperature = 20.0
        override suspend fun prepare(scenario: Scenario, seed: Long) { temperature = scenario.setup.temperature }
        override suspend fun deliver(turn: Step.Say, variant: String) {
            recorder.record(EventType.SPEECH_END)
            delay(5)
            if (act) {
                recorder.record(EventType.TOOL_CALL_RECEIVED, toolType = "control_climate", detail = "c1",
                    text = { """{"action":"set_temperature","value":24}""" })
                recorder.record(EventType.TOOL_EXECUTION_START, toolType = "control_climate", detail = "c1")
                temperature = 24.0
                recorder.record(EventType.TOOL_EXECUTION_END, toolType = "control_climate", detail = "c1", success = true)
            }
            recorder.record(EventType.TTS_START)
            recorder.record(EventType.ASSISTANT_REPLY, text = { "已调到24度。" })
        }
        override suspend fun awaitSettled(timeoutMs: Long) = true
        override fun snapshot() = mapOf(StateKeys.HVAC_TEMP to StateKeys.num(temperature))
        override suspend fun apply(step: Step) = Unit
        override suspend fun bargeIn(step: Step.BargeIn, variant: String) = Unit
        override fun sessionFailed() = false
        override suspend fun finish(scenario: Scenario) = Unit
    }

    private val scenario = ScenarioCatalog.byId("HVAC_SET_TEMP_24_A")!!

    @Test
    fun executorJudgesStateAndMeasuresLatency() = runBlocking {
        val recorder = TelemetryRecorder()
        val result = ScenarioExecutor(recorder, ThermostatDriver(recorder, act = true)).run(scenario, "r1", 42, 0)
        assertTrue(result.taskSuccess, result.failures.toString())
        val turn = result.turns.single()
        assertNotNull(turn.latency.speechEndToTool)
        assertNotNull(turn.latency.speechEndToTts)
        assertNotNull(turn.latency.speechEndToStateConfirmed)
        assertEquals("control_climate", result.toMap()["actualTool"])
        assertEquals(mapOf("action" to "set_temperature", "value" to "24"), turn.actualCalls.single().args)
        assertTrue(recorder.events().all { it.runId == "r1" && it.scenarioId == scenario.id })
    }

    @Test
    fun executorCatchesAClaimWithoutAction() = runBlocking {
        val recorder = TelemetryRecorder()
        val result = ScenarioExecutor(recorder, ThermostatDriver(recorder, act = false)).run(scenario, "r2", 42, 0)
        assertFalse(result.taskSuccess)
        assertTrue(result.turns.single().verdict.falseSuccess)
        assertTrue(result.toMap()["falseSuccess"] as Boolean)
    }

    @Test
    fun seedsAreReproduciblePerScenarioAndIteration() {
        assertEquals(ScenarioExecutor.scenarioSeed(7, "A", 1), ScenarioExecutor.scenarioSeed(7, "A", 1))
        assertTrue(ScenarioExecutor.scenarioSeed(7, "A", 1) != ScenarioExecutor.scenarioSeed(7, "A", 2))
    }

    @Test
    fun textIsOnlyCapturedDuringBenchmarkRuns() {
        val recorder = TelemetryRecorder()
        recorder.record(EventType.ASR_RESULT, text = { "导航到我家" })
        assertEquals(null, recorder.events().single().actualValue, "normal use stores no user text")
        recorder.captureText = true
        recorder.record(EventType.ASR_RESULT, text = { "导航到我家" })
        assertEquals("导航到我家", recorder.events().last().actualValue)
    }

    @Test
    fun liveSpeechOpensAnInteractionUnlessAScriptedInputJustDid() {
        val recorder = TelemetryRecorder()
        recorder.record(EventType.SPEECH_START)
        val first = recorder.currentInteractionId
        recorder.record(EventType.SPEECH_START)
        assertTrue(first != recorder.currentInteractionId)
        recorder.record(EventType.INPUT_SENT)
        val scripted = recorder.currentInteractionId
        recorder.record(EventType.SPEECH_START)
        assertEquals(scripted, recorder.currentInteractionId)
    }

    @Test
    fun catalogCoversEverySuiteAndMode() {
        for (suite in listOf(Suite.SMOKE, Suite.REGRESSION, Suite.CHAOS, Suite.LONG_SESSION, Suite.NAVIGATION, Suite.VEHICLE, Suite.MEDIA, Suite.VISION, Suite.RELEASE)) {
            assertTrue(ScenarioCatalog.suite(suite, TestMode.SIM_LOGIC).isNotEmpty(), "$suite empty in SIM_LOGIC")
        }
        assertTrue(ScenarioCatalog.suite(Suite.AUDIO_E2E, TestMode.AUDIO_E2E).size >= 10)
        assertTrue(ScenarioCatalog.suite(Suite.VISION_REAL, TestMode.TEXT_LIVE).isNotEmpty())
        assertEquals(100, ScenarioCatalog.byId("LONG_SESSION_100")!!.turns.size)
        assertFalse(ScenarioCatalog.suite(Suite.RELEASE, TestMode.SIM_LOGIC).any { it.id == "LONG_SESSION_100" })
        // Every expected state key is one a driver reports.
        val known = StateKeys::class.java.declaredFields.filter { it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }.toSet()
        ScenarioCatalog.all.forEach { s ->
            s.steps.forEach { step ->
                val keys = when (step) {
                    is Step.Say -> step.expect.state.keys
                    is Step.Check -> step.state.keys
                    is Step.WaitFor -> step.state.keys
                    else -> emptySet()
                }
                assertTrue(known.containsAll(keys), "${s.id} uses unknown state keys ${keys - known}")
            }
        }
    }

    /**
     * The synthetic-speech generator reads benchmarks/scenario-utterances.json. Regenerate with
     * `-Dnova.updateUtterances=true` when scenarios change.
     */
    @Test
    fun utteranceListForTheAudioGeneratorIsCurrent() {
        val root = File(System.getProperty("nova.repo.root"))
        val file = File(root, "benchmarks/scenario-utterances.json")
        val expected = Json.write(mapOf("utterances" to ScenarioCatalog.utterances().toList())) + "\n"
        if (System.getProperty("nova.updateUtterances") == "true") file.writeText(expected)
        assertEquals(expected, file.readText(), "run with -Dnova.updateUtterances=true to refresh")
    }
}
