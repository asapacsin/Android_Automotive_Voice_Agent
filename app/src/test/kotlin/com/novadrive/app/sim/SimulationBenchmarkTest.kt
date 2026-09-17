package com.novadrive.app.sim

import com.novadrive.evaluation.BenchmarkRunner
import com.novadrive.evaluation.RegressionThresholds
import com.novadrive.evaluation.ScenarioCatalog
import com.novadrive.evaluation.Status
import com.novadrive.evaluation.Suite
import com.novadrive.evaluation.Telemetry
import com.novadrive.evaluation.TelemetryRecorder
import com.novadrive.evaluation.TestMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Level A benchmark: the real voice stack against the scripted server in the simulated world.
 *
 * Default (`gradlew test`): the SMOKE suite once — it must pass.
 * Configured runs (docs/EVALUATION.md):
 *   gradlew :app:testDebugUnitTest --tests "*SimulationBenchmarkTest*" \
 *       -PbenchSuite=REGRESSION -PbenchRepeat=5 -PbenchSeed=184729 [-PbenchScenario=ID] [-PbenchUpdateBaseline=true]
 * Output: app build dir `bench/<runId>/` (report.txt, summary.json, results.jsonl/csv, telemetry.jsonl).
 */
class SimulationBenchmarkTest {
    @Test
    fun simulationSuite() = runBlocking {
        val suiteName = System.getProperty("nova.benchSuite") ?: "SMOKE"
        val repeat = System.getProperty("nova.benchRepeat")?.toInt() ?: 1
        val seed = System.getProperty("nova.benchSeed")?.toLong() ?: 184_729L
        val only = System.getProperty("nova.benchScenario")
        val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
        val out = File(System.getProperty("nova.bench.out") ?: File(root, "app/build/bench").path)

        val suite = Suite.valueOf(suiteName)
        // STRESS repeats on purpose and only with an explicit count; other suites run once unless asked.
        require(suite != Suite.STRESS || System.getProperty("nova.benchRepeat") != null) {
            "STRESS needs an explicit -PbenchRepeat (e.g. 60 passes to catch a 5% flake with 95% confidence)"
        }
        val scenarios = if (only != null) {
            listOf(ScenarioCatalog.byId(only) ?: error("unknown scenario $only"))
        } else {
            ScenarioCatalog.suite(suite, TestMode.SIM_LOGIC)
        }
        val suiteLabel = only ?: suiteName
        val recorder = TelemetryRecorder()
        val previous = Telemetry.recorder
        val baselineFile = BenchmarkRunner.baselineFile(File(root, "benchmarks/baselines"), TestMode.SIM_LOGIC, suiteLabel)
        val outcome = try {
            BenchmarkRunner.run(
                scenarios = scenarios,
                driver = SimulationDriver(recorder),
                recorder = recorder,
                suiteName = suiteLabel,
                seed = seed,
                repeat = repeat,
                variants = listOf("scripted"),
                build = gitDescribe(root),
                device = "JVM ${System.getProperty("java.version")} (simulation)",
                outRoot = out,
                baseline = BenchmarkRunner.loadBaseline(baselineFile),
                thresholds = RegressionThresholds.fromJson(File(root, "benchmarks/thresholds.json").readText()),
                onProgress = { println("[bench] $it") },
            )
        } finally {
            Telemetry.recorder = previous
        }
        println(outcome.report)
        println("[bench] simulation execution: ${outcome.summary.executionMs} ms for ${outcome.results.size} scenario runs " +
            "(Gradle configuration/compilation and JVM start are not included)")
        println("[bench] output: ${outcome.outDir}")
        if (System.getProperty("nova.benchUpdateBaseline") == "true") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(outcome.summary.toJson() + "\n")
            println("[bench] baseline written: $baselineFile")
        }
        if (suiteName == "SMOKE" && only == null) {
            assertTrue(outcome.summary.failures.isEmpty(), "SMOKE failures:\n" + outcome.summary.failures.joinToString("\n") { "${it.scenarioId}: ${it.reasons}" })
            assertTrue(outcome.comparison.status != Status.FAIL, "regression against baseline:\n${outcome.report}")
        }
    }

    private fun gitDescribe(root: File): String =
        runCatching {
            val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(root).redirectErrorStream(true).start()
            val sha = p.inputStream.bufferedReader().readText().trim()
            val dirty = ProcessBuilder("git", "status", "--porcelain").directory(root).start()
                .inputStream.bufferedReader().readText().isNotBlank()
            sha + if (dirty) "+dirty" else ""
        }.getOrDefault("unknown")
}
