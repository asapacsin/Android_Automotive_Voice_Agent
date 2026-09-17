package com.novadrive.evaluation

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BenchmarkOutcome(
    val results: List<ScenarioResult>,
    val summary: BenchmarkSummary,
    val comparison: RegressionComparison,
    val report: String,
    val outDir: File,
)

/**
 * Runs scenarios × variants × repetitions through one driver and writes, into [outDir]:
 * results.jsonl, results.csv, telemetry.jsonl, summary.json, report.txt.
 * Shared by the JVM simulation benchmark and the phone runner.
 */
object BenchmarkRunner {
    suspend fun run(
        scenarios: List<Scenario>,
        driver: ScenarioDriver,
        recorder: TelemetryRecorder,
        suiteName: String,
        seed: Long,
        repeat: Int,
        variants: List<String>,
        build: String,
        device: String,
        outRoot: File,
        baseline: BenchmarkSummary?,
        thresholds: RegressionThresholds,
        runId: String = newRunId(),
        onProgress: (String) -> Unit = {},
    ): BenchmarkOutcome {
        val outDir = File(outRoot, runId).apply { mkdirs() }
        recorder.clear()
        val telemetry = File(outDir, "telemetry.jsonl").bufferedWriter()
        val sink = TelemetrySink { e -> synchronized(telemetry) { telemetry.write(e.toJson()); telemetry.newLine() } }
        recorder.addSink(sink)
        val executor = ScenarioExecutor(recorder, driver)
        val results = mutableListOf<ScenarioResult>()
        val started = System.nanoTime()
        val jsonl = File(outDir, "results.jsonl").bufferedWriter()
        try {
            for (iteration in 0 until repeat) {
                for (variant in variants) {
                    for (scenario in scenarios) {
                        val result = executor.run(scenario, runId, seed, iteration, variant)
                        results += result
                        jsonl.write(result.toJson())
                        jsonl.newLine()
                        jsonl.flush()
                        onProgress(
                            "${scenario.id} #$iteration $variant: " +
                                (result.skipped?.let { "SKIPPED ($it)" } ?: if (result.taskSuccess) "PASS" else "FAIL ${result.failures.take(2)}") +
                                "  ${result.durationMs} ms (setup ${result.setupMs}, settle ${result.turns.sumOf { it.settleWaitMs }}, " +
                                "waits ${result.waitStepMs}, deliberate delay ${result.intentionalDelayMs}, teardown ${result.teardownMs})",
                        )
                    }
                }
            }
        } finally {
            recorder.removeSink(sink)
            jsonl.close()
            synchronized(telemetry) { telemetry.close() }
        }
        val executionMs = (System.nanoTime() - started) / 1_000_000
        val summary = Metrics.summarize(results, suiteName, driver.mode, runId, build, device, today(), seed, executionMs)
        val comparison = Baselines.compare(baseline, summary, thresholds)
        val report = BenchmarkReport.render(summary, comparison)
        File(outDir, "results.csv").writeText(Metrics.csv(results))
        File(outDir, "summary.json").writeText(summary.toJson() + "\n")
        File(outDir, "report.txt").writeText(report)
        return BenchmarkOutcome(results, summary, comparison, report, outDir)
    }

    fun newRunId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + (1000..9999).random()

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date())

    /** Baselines live next to the thresholds: `<dir>/<MODE>-<SUITE>.json`. */
    fun baselineFile(dir: File, mode: TestMode, suite: String): File = File(dir, "${mode.name}-$suite.json")

    fun loadBaseline(file: File): BenchmarkSummary? =
        if (file.isFile) runCatching { BenchmarkSummary.fromJson(file.readText()) }.getOrNull() else null
}
