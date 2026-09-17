package com.novadrive.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsAndBaselineTest {
    @Test
    fun percentilesNeedEnoughSamples() {
        val few = LatencyStats.of(listOf(10.0, 20.0, 30.0))
        assertEquals(20.0, few.p50)
        assertNull(few.p90)
        assertNull(few.p95)
        val many = LatencyStats.of((1..100).map { it.toDouble() })
        assertEquals(50.0, many.p50)
        assertEquals(90.0, many.p90)
        assertEquals(95.0, many.p95)
        assertNull(many.p99, "100 samples are not a P99 measurement")
        assertEquals(100.0, many.max)
        assertEquals(50.5, many.mean)
        val stress = LatencyStats.of((1..1000).map { it.toDouble() })
        assertEquals(990.0, stress.p99)
    }

    @Test
    fun jsonRoundTrip() {
        val value = mapOf("a" to listOf(1L, 2.5, "x\"y\n中文"), "b" to mapOf("c" to null, "d" to true))
        assertEquals(value, Json.parse(Json.write(value)))
    }

    private fun turn(ok: Boolean, falseSuccess: Boolean = false, latency: Double = 500.0, unexpected: Int = 0) = TurnResult(
        index = 0, kind = "say", utterance = "u",
        expectedTools = listOf(ToolCallSpec("control_music", mapOf("action" to "play"))),
        actualCalls = listOf(ObservedCall("control_music", mapOf("action" to "play"), 0, success = ok)),
        expectedState = mapOf("media.playing" to "true"), actualState = mapOf("media.playing" to ok.toString()),
        replies = listOf("r"),
        verdict = TurnVerdict(true, true, ok, ok, falseSuccess, false, unexpected, 0, true,
            listOfNotNull("x".takeIf { !ok || falseSuccess || unexpected > 0 })),
        latency = TurnLatency(speechEndToTool = latency),
    )

    private fun result(id: String, vararg turns: TurnResult, i: Int = 0) =
        ScenarioResult(id, "run", TestMode.SIM_LOGIC, 1, i, "normal", turns.toList(), false, false, null, emptyList(), 10)

    private fun summary(results: List<ScenarioResult>) =
        Metrics.summarize(results, "SMOKE", TestMode.SIM_LOGIC, "run", "b", "jvm", "2026-09-17", 1)

    @Test
    fun summaryCountsAndSurvivesJson() {
        val results = (0 until 30).map { result("A", turn(ok = it != 0, latency = 400.0 + it), i = it) } +
            result("B", turn(ok = false, falseSuccess = true))
        val s = summary(results)
        assertEquals(31, s.scenarioRuns)
        assertEquals(29, s.taskSuccess.hits)
        assertEquals(setOf("B"), s.falseSuccessCases)
        assertEquals(31, s.latency[LatencyMetric.SPEECH_END_TO_TOOL.key]!!.n)
        assertEquals(s, BenchmarkSummary.fromJson(s.toJson()))
        assertTrue(Metrics.csv(results).lines().first().startsWith("runId,scenarioId"))
    }

    @Test
    fun regressionRules() {
        val base = summary((0 until 100).map { result("A", turn(ok = true, latency = 500.0), i = it) })
        val same = Baselines.compare(base, base)
        assertEquals(Status.PASS, same.status)

        val worse = summary((0 until 100).map { result("A", turn(ok = it >= 3, latency = 500.0), i = it) })
        assertEquals(Status.FAIL, Baselines.compare(base, worse).status, "3 pp drop > 1 pp limit")

        val slower = summary((0 until 100).map { result("A", turn(ok = true, latency = 650.0), i = it) })
        val slow = Baselines.compare(base, slower)
        assertEquals(Status.WARN, slow.status, "+30% P95 warns")

        val lying = summary((0 until 100).map { result("A", turn(ok = true)) } + result("C", turn(ok = false, falseSuccess = true)))
        assertTrue(Baselines.compare(base, lying).findings.any { it.status == Status.FAIL && "false success" in it.message })

        val noisy = summary((0 until 100).map { result("A", turn(ok = true)) } + result("D", turn(ok = true, unexpected = 1)))
        assertTrue(Baselines.compare(base, noisy).findings.any { it.status == Status.FAIL && "unexpected" in it.message })
    }

    @Test
    fun smallSamplesAreNotComparedForLatency() {
        val base = summary((0 until 5).map { result("A", turn(ok = true, latency = 100.0), i = it) })
        val cand = summary((0 until 5).map { result("A", turn(ok = true, latency = 900.0), i = it) })
        val c = Baselines.compare(base, cand)
        assertEquals(Status.PASS, c.status)
        assertTrue(c.lines.any { "not comparable" in it })
    }

    @Test
    fun thresholdsLoadFromTheRepositoryFile() {
        val root = java.io.File(System.getProperty("nova.repo.root"))
        val t = RegressionThresholds.fromJson(java.io.File(root, "benchmarks/thresholds.json").readText())
        assertEquals(1.0, t.taskSuccessDropFailPp)
        assertEquals(20.0, t.p95IncreaseWarnPct)
        assertTrue(t.failOnNewFalseSuccess)
    }

    @Test
    fun reportShowsTheEssentials() {
        val s = summary(listOf(result("A", turn(ok = true)), result("B", turn(ok = false))))
        val text = BenchmarkReport.render(s, Baselines.compare(null, s))
        assertTrue("AUTOMOTIVE AGENT BENCHMARK" in text)
        assertTrue("TASK SUCCESS         1 / 2" in text)
        assertTrue("NOT model understanding" in text)
        assertTrue("STATUS:" in text)
    }
}
