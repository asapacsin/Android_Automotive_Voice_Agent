package com.novadrive.evaluation

import kotlin.math.ceil

data class LatencyStats(
    val n: Int,
    val mean: Double?,
    val p50: Double?,
    val p90: Double?,
    val p95: Double?,
    val p99: Double?,
    val max: Double?,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "n" to n, "mean" to mean, "p50" to p50, "p90" to p90, "p95" to p95, "p99" to p99, "max" to max,
    )

    companion object {
        /**
         * P99 needs a large sample: with ~100 samples it is just the maximum. Only STRESS runs
         * (explicit repetitions) reach this.
         */
        const val P99_MIN_SAMPLES = 1_000

        /**
         * Nearest-rank percentiles. A percentile is only reported when the sample can support it:
         * p90 needs 10 samples, p95 20, p99 [P99_MIN_SAMPLES].
         */
        fun of(samples: List<Double>): LatencyStats {
            if (samples.isEmpty()) return LatencyStats(0, null, null, null, null, null, null)
            val sorted = samples.sorted()
            fun pct(p: Double, minN: Int): Double? {
                if (sorted.size < minN) return null
                val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
                return sorted[rank - 1]
            }
            return LatencyStats(
                n = sorted.size,
                mean = sorted.average(),
                p50 = pct(50.0, 1),
                p90 = pct(90.0, 10),
                p95 = pct(95.0, 20),
                p99 = pct(99.0, P99_MIN_SAMPLES),
                max = sorted.last(),
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>) = LatencyStats(
            (m["n"] as Number).toInt(), m["mean"].asDouble(), m["p50"].asDouble(), m["p90"].asDouble(),
            m["p95"].asDouble(), m["p99"].asDouble(), m["max"].asDouble(),
        )
    }
}

/** A ratio with its counts, so "100%" over 3 samples is never mistaken for 100% over 3000. */
data class Rate(val hits: Int, val total: Int) {
    val value: Double? get() = if (total == 0) null else hits.toDouble() / total
    fun pct(): String = value?.let { String.format(java.util.Locale.US, "%.2f%%", it * 100) } ?: "n/a"
    fun toMap(): Map<String, Any?> = linkedMapOf("hits" to hits, "total" to total, "value" to value)

    companion object {
        fun fromMap(m: Map<String, Any?>) = Rate((m["hits"] as Number).toInt(), (m["total"] as Number).toInt())
    }
}

data class FailureRecord(val scenarioId: String, val iteration: Int, val variant: String, val seed: Long, val reasons: List<String>)

data class BenchmarkSummary(
    val suite: String,
    val mode: TestMode,
    val runId: String,
    val build: String,
    val device: String,
    val date: String,
    val seed: Long,
    val scenarioRuns: Int,
    val skipped: Int,
    val taskSuccess: Rate,
    val toolSelectionAccuracy: Rate,
    val toolParameterAccuracy: Rate,
    val toolExecutionSuccess: Rate,
    val finalStateAccuracy: Rate,
    val falseSuccess: Rate,
    val prematureClaim: Rate,
    val unexpectedToolCalls: Int,
    val unexpectedToolCallRate: Rate,
    val duplicateExecutionRate: Rate,
    val sessionFailureRate: Rate,
    val interruptDetection: Rate,
    val crashCount: Int,
    val latency: Map<String, LatencyStats>,
    val perScenario: Map<String, Rate>,
    val falseSuccessCases: Set<String>,
    val unexpectedCallCases: Set<String>,
    val crashCases: Set<String>,
    val failures: List<FailureRecord>,
    /** Simulation/test execution wall time for the whole run (no Gradle, build or configuration). */
    val executionMs: Long = 0,
    /** Wall-time distributions: scenario, turn, settle wait, setup, teardown. */
    val timing: Map<String, LatencyStats> = emptyMap(),
    val intentionalDelayMs: Long = 0,
    val waitStepMs: Long = 0,
    /** Slowest scenarios by mean wall time, slowest first (id to ms). */
    val slowest: List<Pair<String, Long>> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "suite" to suite, "mode" to mode.name, "level" to mode.level.name, "runId" to runId, "build" to build,
        "device" to device, "date" to date, "seed" to seed, "scenarioRuns" to scenarioRuns, "skipped" to skipped,
        "taskSuccess" to taskSuccess.toMap(),
        "toolSelectionAccuracy" to toolSelectionAccuracy.toMap(),
        "toolParameterAccuracy" to toolParameterAccuracy.toMap(),
        "toolExecutionSuccess" to toolExecutionSuccess.toMap(),
        "finalStateAccuracy" to finalStateAccuracy.toMap(),
        "falseSuccess" to falseSuccess.toMap(),
        "prematureClaim" to prematureClaim.toMap(),
        "unexpectedToolCalls" to unexpectedToolCalls,
        "unexpectedToolCallRate" to unexpectedToolCallRate.toMap(),
        "duplicateExecutionRate" to duplicateExecutionRate.toMap(),
        "sessionFailureRate" to sessionFailureRate.toMap(),
        "interruptDetection" to interruptDetection.toMap(),
        "crashCount" to crashCount,
        "latency" to latency.mapValues { it.value.toMap() },
        "perScenario" to perScenario.mapValues { it.value.toMap() },
        "falseSuccessCases" to falseSuccessCases.sorted(),
        "unexpectedCallCases" to unexpectedCallCases.sorted(),
        "crashCases" to crashCases.sorted(),
        "failures" to failures.map {
            linkedMapOf("scenarioId" to it.scenarioId, "iteration" to it.iteration, "variant" to it.variant, "seed" to it.seed, "reasons" to it.reasons)
        },
        "executionMs" to executionMs,
        "timing" to timing.mapValues { it.value.toMap() },
        "intentionalDelayMs" to intentionalDelayMs,
        "waitStepMs" to waitStepMs,
        "slowest" to slowest.map { linkedMapOf("scenarioId" to it.first, "ms" to it.second) },
    )

    fun toJson(): String = Json.write(toMap())

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(text: String): BenchmarkSummary {
            val m = Json.parseObject(text)
            fun rate(k: String) = Rate.fromMap(m[k] as Map<String, Any?>)
            fun strings(k: String) = (m[k] as? List<Any?>).orEmpty().map { it.toString() }.toSet()
            return BenchmarkSummary(
                suite = m["suite"] as String, mode = TestMode.valueOf(m["mode"] as String),
                runId = m["runId"] as String, build = m["build"] as String, device = m["device"] as String,
                date = m["date"] as String, seed = (m["seed"] as Number).toLong(),
                scenarioRuns = (m["scenarioRuns"] as Number).toInt(), skipped = (m["skipped"] as Number).toInt(),
                taskSuccess = rate("taskSuccess"), toolSelectionAccuracy = rate("toolSelectionAccuracy"),
                toolParameterAccuracy = rate("toolParameterAccuracy"), toolExecutionSuccess = rate("toolExecutionSuccess"),
                finalStateAccuracy = rate("finalStateAccuracy"), falseSuccess = rate("falseSuccess"),
                prematureClaim = rate("prematureClaim"),
                unexpectedToolCalls = (m["unexpectedToolCalls"] as Number).toInt(),
                unexpectedToolCallRate = rate("unexpectedToolCallRate"), duplicateExecutionRate = rate("duplicateExecutionRate"),
                sessionFailureRate = rate("sessionFailureRate"), interruptDetection = rate("interruptDetection"),
                crashCount = (m["crashCount"] as Number).toInt(),
                latency = (m["latency"] as Map<String, Any?>).mapValues { LatencyStats.fromMap(it.value as Map<String, Any?>) },
                perScenario = (m["perScenario"] as Map<String, Any?>).mapValues { Rate.fromMap(it.value as Map<String, Any?>) },
                falseSuccessCases = strings("falseSuccessCases"),
                unexpectedCallCases = strings("unexpectedCallCases"),
                crashCases = strings("crashCases"),
                failures = (m["failures"] as List<Any?>).map { f ->
                    f as Map<String, Any?>
                    FailureRecord(
                        f["scenarioId"] as String, (f["iteration"] as Number).toInt(), f["variant"] as String,
                        (f["seed"] as Number).toLong(), (f["reasons"] as List<Any?>).map { it.toString() },
                    )
                },
                executionMs = (m["executionMs"] as? Number)?.toLong() ?: 0,
                timing = (m["timing"] as? Map<String, Any?>).orEmpty().mapValues { LatencyStats.fromMap(it.value as Map<String, Any?>) },
                intentionalDelayMs = (m["intentionalDelayMs"] as? Number)?.toLong() ?: 0,
                waitStepMs = (m["waitStepMs"] as? Number)?.toLong() ?: 0,
                slowest = (m["slowest"] as? List<Any?>).orEmpty().map { e ->
                    e as Map<String, Any?>
                    (e["scenarioId"] as String) to (e["ms"] as Number).toLong()
                },
            )
        }
    }
}

object Metrics {
    fun summarize(
        results: List<ScenarioResult>,
        suite: String,
        mode: TestMode,
        runId: String,
        build: String,
        device: String,
        date: String,
        seed: Long,
        executionMs: Long = 0,
    ): BenchmarkSummary {
        val ran = results.filter { it.skipped == null }
        val turns = ran.flatMap { r -> r.turns.filter { it.kind != "check" } }
        val withTools = turns.filter { it.expectedTools.isNotEmpty() || it.actualCalls.isNotEmpty() }
        val selected = withTools.filter { it.verdict.toolSelectionCorrect }
        // Turns meant to succeed that reached a tool; injected-failure turns are excluded by design.
        val expectedExecutions = turns.filter {
            it.outcome == Outcome.SUCCESS && it.expectedTools.isNotEmpty() && it.verdict.executionSucceeded != null
        }
        val latencies = LatencyMetric.entries.associate { metric ->
            metric.key to LatencyStats.of(turns.mapNotNull { it.latency.toMap()[metric.key] })
        }
        return BenchmarkSummary(
            suite = suite, mode = mode, runId = runId, build = build, device = device, date = date, seed = seed,
            scenarioRuns = ran.size, skipped = results.size - ran.size,
            taskSuccess = Rate(ran.count { it.taskSuccess }, ran.size),
            toolSelectionAccuracy = Rate(selected.size, withTools.size),
            toolParameterAccuracy = Rate(selected.count { it.verdict.parametersCorrect }, selected.size),
            // Against the turns where the scenario wanted the action to work (injected failures excluded).
            toolExecutionSuccess = Rate(expectedExecutions.count { it.verdict.executionSucceeded == true }, expectedExecutions.size),
            finalStateAccuracy = Rate(turns.count { it.verdict.finalStateCorrect == true } +
                ran.flatMap { it.turns }.count { it.kind == "check" && it.verdict.finalStateCorrect == true },
                turns.count { it.verdict.finalStateCorrect != null } +
                    ran.flatMap { it.turns }.count { it.kind == "check" }),
            falseSuccess = Rate(turns.count { it.verdict.falseSuccess }, turns.size),
            prematureClaim = Rate(turns.count { it.verdict.prematureClaim }, turns.size),
            unexpectedToolCalls = turns.sumOf { it.verdict.unexpectedToolCalls },
            unexpectedToolCallRate = Rate(turns.count { it.verdict.unexpectedToolCalls > 0 }, turns.size),
            duplicateExecutionRate = Rate(turns.count { it.verdict.duplicateExecutions > 0 }, turns.size),
            sessionFailureRate = Rate(ran.count { it.sessionFailed }, ran.size),
            interruptDetection = Rate(turns.count { it.interruptDetected == true }, turns.count { it.interruptDetected != null }),
            crashCount = ran.count { it.crashed },
            latency = latencies,
            perScenario = ran.groupBy { it.scenarioId }.mapValues { (_, rs) -> Rate(rs.count { it.taskSuccess }, rs.size) }.toSortedMap(),
            falseSuccessCases = ran.filter { r -> r.turns.any { it.verdict.falseSuccess } }.map { it.scenarioId }.toSet(),
            unexpectedCallCases = ran.filter { r -> r.turns.any { it.verdict.unexpectedToolCalls > 0 } }.map { it.scenarioId }.toSet(),
            crashCases = ran.filter { it.crashed }.map { it.scenarioId }.toSet(),
            failures = ran.filter { !it.taskSuccess }.map { FailureRecord(it.scenarioId, it.iteration, it.variant, it.seed, it.failures) },
            executionMs = executionMs,
            timing = linkedMapOf(
                TIMING_SCENARIO to LatencyStats.of(ran.map { it.durationMs.toDouble() }),
                TIMING_TURN to LatencyStats.of(ran.flatMap { r -> r.turns.filter { it.wallMs > 0 }.map { it.wallMs.toDouble() } }),
                TIMING_SETTLE to LatencyStats.of(ran.flatMap { r -> r.turns.filter { it.kind == "say" || it.kind == "barge_in" }.map { it.settleWaitMs.toDouble() } }),
                TIMING_SETUP to LatencyStats.of(ran.map { it.setupMs.toDouble() }),
                TIMING_TEARDOWN to LatencyStats.of(ran.map { it.teardownMs.toDouble() }),
            ),
            intentionalDelayMs = ran.sumOf { it.intentionalDelayMs },
            waitStepMs = ran.sumOf { it.waitStepMs },
            slowest = ran.groupBy { it.scenarioId }
                .map { (id, rs) -> id to rs.map { it.durationMs }.average().toLong() }
                .sortedByDescending { it.second }
                .take(10),
        )
    }

    const val TIMING_SCENARIO = "scenarioWallMs"
    const val TIMING_TURN = "turnWallMs"
    const val TIMING_SETTLE = "turnSettleWaitMs"
    const val TIMING_SETUP = "scenarioSetupMs"
    const val TIMING_TEARDOWN = "scenarioTeardownMs"

    fun csv(results: List<ScenarioResult>): String {
        val header = listOf(
            "runId", "scenarioId", "mode", "seed", "iteration", "variant", "turn", "kind", "utterance",
            "expectedTools", "actualCalls", "toolSelectionCorrect", "parametersCorrect", "executionSucceeded",
            "finalStateCorrect", "falseSuccess", "prematureClaim", "unexpectedToolCalls", "duplicateExecutions",
            "passed", "taskSuccess", "scenarioWallMs", "scenarioSetupMs", "scenarioTeardownMs",
            "scenarioWaitStepMs", "scenarioIntentionalDelayMs", "turnWallMs", "turnSettleWaitMs",
        ) + LatencyMetric.entries.map { it.key } + listOf("failures")
        val rows = results.flatMap { r ->
            r.turns.map { t ->
                listOf(
                    r.runId, r.scenarioId, r.mode.name, r.seed, r.iteration, r.variant, t.index + 1, t.kind, t.utterance,
                    t.expectedTools.joinToString(" → "), t.actualCalls.joinToString(" → ") { ToolCallSpec(it.name, it.args).toString() },
                    t.verdict.toolSelectionCorrect, t.verdict.parametersCorrect, t.verdict.executionSucceeded,
                    t.verdict.finalStateCorrect, t.verdict.falseSuccess, t.verdict.prematureClaim,
                    t.verdict.unexpectedToolCalls, t.verdict.duplicateExecutions, t.verdict.passed, r.taskSuccess,
                    r.durationMs, r.setupMs, r.teardownMs, r.waitStepMs, r.intentionalDelayMs, t.wallMs, t.settleWaitMs,
                ) + LatencyMetric.entries.map { t.latency.toMap()[it.key]?.let { v -> String.format(java.util.Locale.US, "%.1f", v) } } +
                    listOf(t.verdict.failures.joinToString(" | "))
            }
        }
        return (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(",") { csvCell(it) } } + "\n"
    }

    private fun csvCell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

/**
 * Regression thresholds. The defaults are the ones proposed by the product owner (2026-09-17) and
 * are recorded in benchmarks/thresholds.json; change them there, with a reason, not in code.
 */
data class RegressionThresholds(
    /** Task-success drop, in percentage points, that fails the run. */
    val taskSuccessDropFailPp: Double = 1.0,
    /** Task-success drop that only warns (smaller statistical noise). */
    val taskSuccessDropWarnPp: Double = 0.5,
    /** P95 latency increase, in percent, that warns. */
    val p95IncreaseWarnPct: Double = 20.0,
    /** P95 latency increase that fails. */
    val p95IncreaseFailPct: Double = 50.0,
    val failOnNewCrash: Boolean = true,
    val failOnNewFalseSuccess: Boolean = true,
    val failOnNewUnexpectedToolCall: Boolean = true,
    /** Latency comparisons need this many samples on both sides, or they are reported as not comparable. */
    val minLatencySamples: Int = 20,
) {
    companion object {
        fun fromJson(text: String): RegressionThresholds {
            val m = Json.parseObject(text)
            val d = RegressionThresholds()
            return RegressionThresholds(
                taskSuccessDropFailPp = m["taskSuccessDropFailPp"].asDouble() ?: d.taskSuccessDropFailPp,
                taskSuccessDropWarnPp = m["taskSuccessDropWarnPp"].asDouble() ?: d.taskSuccessDropWarnPp,
                p95IncreaseWarnPct = m["p95IncreaseWarnPct"].asDouble() ?: d.p95IncreaseWarnPct,
                p95IncreaseFailPct = m["p95IncreaseFailPct"].asDouble() ?: d.p95IncreaseFailPct,
                failOnNewCrash = m["failOnNewCrash"] as? Boolean ?: d.failOnNewCrash,
                failOnNewFalseSuccess = m["failOnNewFalseSuccess"] as? Boolean ?: d.failOnNewFalseSuccess,
                failOnNewUnexpectedToolCall = m["failOnNewUnexpectedToolCall"] as? Boolean ?: d.failOnNewUnexpectedToolCall,
                minLatencySamples = (m["minLatencySamples"] as? Number)?.toInt() ?: d.minLatencySamples,
            )
        }
    }
}

enum class Status { PASS, WARN, FAIL }

data class Finding(val status: Status, val message: String)

data class RegressionComparison(val status: Status, val findings: List<Finding>, val lines: List<String>)

object Baselines {
    fun compare(baseline: BenchmarkSummary?, candidate: BenchmarkSummary, t: RegressionThresholds = RegressionThresholds()): RegressionComparison {
        val findings = mutableListOf<Finding>()
        val lines = mutableListOf<String>()
        // Absolute rules, baseline or not.
        if (candidate.crashCount > 0) findings += Finding(Status.FAIL, "crashes: ${candidate.crashCases.joinToString()}")
        if (candidate.falseSuccess.hits > 0 && baseline == null) {
            findings += Finding(Status.FAIL, "false success in ${candidate.falseSuccessCases.joinToString()}")
        }
        if (baseline == null) {
            lines += "no baseline — nothing to compare against"
            return RegressionComparison(worst(findings), findings, lines)
        }
        if (baseline.suite != candidate.suite || baseline.mode != candidate.mode) {
            findings += Finding(Status.WARN, "baseline is ${baseline.suite}/${baseline.mode}, candidate ${candidate.suite}/${candidate.mode}")
        }
        val b = baseline.taskSuccess.value
        val c = candidate.taskSuccess.value
        if (b != null && c != null) {
            val dropPp = (b - c) * 100
            lines += "Task success  ${baseline.taskSuccess.pct()} → ${candidate.taskSuccess.pct()}"
            when {
                dropPp > t.taskSuccessDropFailPp -> findings += Finding(Status.FAIL, "task success dropped ${fmt(dropPp)} pp (limit ${t.taskSuccessDropFailPp})")
                dropPp > t.taskSuccessDropWarnPp -> findings += Finding(Status.WARN, "task success dropped ${fmt(dropPp)} pp")
            }
        }
        lines += "False success ${baseline.falseSuccess.pct()} → ${candidate.falseSuccess.pct()}"
        for (metric in LatencyMetric.entries) {
            val bs = baseline.latency[metric.key] ?: continue
            val cs = candidate.latency[metric.key] ?: continue
            if (bs.n == 0 || cs.n == 0) continue
            if (bs.n < t.minLatencySamples || cs.n < t.minLatencySamples || bs.p95 == null || cs.p95 == null) {
                lines += "${metric.label} P95 not comparable (n=${bs.n}/${cs.n}, need ${t.minLatencySamples})"
                continue
            }
            val incPct = (cs.p95 - bs.p95) / bs.p95 * 100
            lines += "${metric.label} P95 ${ms(bs.p95)} → ${ms(cs.p95)} (${if (incPct >= 0) "+" else ""}${fmt(incPct)}%)"
            when {
                incPct > t.p95IncreaseFailPct -> findings += Finding(Status.FAIL, "${metric.label} P95 +${fmt(incPct)}%")
                incPct > t.p95IncreaseWarnPct -> findings += Finding(Status.WARN, "${metric.label} P95 +${fmt(incPct)}%")
            }
        }
        val newCrashes = candidate.crashCases - baseline.crashCases
        if (t.failOnNewCrash && newCrashes.isNotEmpty()) findings += Finding(Status.FAIL, "new crash: ${newCrashes.joinToString()}")
        val newFalse = candidate.falseSuccessCases - baseline.falseSuccessCases
        if (t.failOnNewFalseSuccess && newFalse.isNotEmpty()) findings += Finding(Status.FAIL, "new false success: ${newFalse.joinToString()}")
        else if (candidate.falseSuccess.hits > 0) findings += Finding(Status.WARN, "false success persists: ${candidate.falseSuccessCases.joinToString()}")
        val newUnexpected = candidate.unexpectedCallCases - baseline.unexpectedCallCases
        if (t.failOnNewUnexpectedToolCall && newUnexpected.isNotEmpty()) {
            findings += Finding(Status.FAIL, "new unexpected tool call: ${newUnexpected.joinToString()}")
        }
        return RegressionComparison(worst(findings), findings, lines)
    }

    private fun worst(f: List<Finding>) = f.maxOfOrNull { it.status } ?: Status.PASS

    internal fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    internal fun ms(v: Double?): String = when {
        v == null -> "n/a"
        v >= 1000 -> String.format(java.util.Locale.US, "%.2f s", v / 1000)
        else -> String.format(java.util.Locale.US, "%.0f ms", v)
    }
}

object BenchmarkReport {
    fun render(s: BenchmarkSummary, comparison: RegressionComparison?): String = buildString {
        appendLine("AUTOMOTIVE AGENT BENCHMARK")
        appendLine()
        appendLine("Build:   ${s.build}")
        appendLine("Device:  ${s.device}")
        appendLine("Suite:   ${s.suite}")
        appendLine("Mode:    ${s.mode} (level ${s.mode.level})")
        appendLine("Date:    ${s.date}")
        appendLine("Run:     ${s.runId}   seed ${s.seed}")
        appendLine(modeNote(s.mode))
        appendLine()
        appendLine("SCENARIO RUNS        ${s.scenarioRuns}${if (s.skipped > 0) "   (skipped ${s.skipped})" else ""}")
        appendLine("TASK SUCCESS         ${s.taskSuccess.hits} / ${s.taskSuccess.total}   ${s.taskSuccess.pct()}")
        appendLine("TOOL SELECTION       ${s.toolSelectionAccuracy.pct()}   (${s.toolSelectionAccuracy.hits}/${s.toolSelectionAccuracy.total})")
        appendLine("TOOL PARAMETERS      ${s.toolParameterAccuracy.pct()}   (${s.toolParameterAccuracy.hits}/${s.toolParameterAccuracy.total})")
        appendLine("TOOL EXECUTION       ${s.toolExecutionSuccess.pct()}   (${s.toolExecutionSuccess.hits}/${s.toolExecutionSuccess.total})")
        appendLine("FINAL STATE          ${s.finalStateAccuracy.pct()}   (${s.finalStateAccuracy.hits}/${s.finalStateAccuracy.total})")
        appendLine("FALSE SUCCESS        ${s.falseSuccess.pct()}   (${s.falseSuccess.hits} turns)")
        appendLine("PREMATURE CLAIMS     ${s.prematureClaim.pct()}   (${s.prematureClaim.hits} turns; spoken before the action, later made true)")
        appendLine("UNEXPECTED CALLS     ${s.unexpectedToolCalls}   (${s.unexpectedToolCallRate.pct()} of turns)")
        appendLine("DUPLICATE EXECUTION  ${s.duplicateExecutionRate.pct()}")
        appendLine("SESSION FAILURES     ${s.sessionFailureRate.pct()}   (${s.sessionFailureRate.hits})")
        if (s.interruptDetection.total > 0) appendLine("INTERRUPT DETECTED   ${s.interruptDetection.pct()}   (${s.interruptDetection.hits}/${s.interruptDetection.total})")
        appendLine("CRASHES              ${s.crashCount}")
        appendLine()
        appendLine("LATENCY (app pipeline, per turn)")
        for (metric in LatencyMetric.entries) {
            val st = s.latency[metric.key] ?: continue
            if (st.n == 0) continue
            appendLine("  ${metric.label}  (n=${st.n})")
            val p99 = st.p99?.let { Baselines.ms(it) } ?: "n/a (needs ≥${LatencyStats.P99_MIN_SAMPLES} samples: STRESS)"
            appendLine("    P50 ${Baselines.ms(st.p50)}   P90 ${Baselines.ms(st.p90)}   P95 ${Baselines.ms(st.p95)}   max ${Baselines.ms(st.max)}   mean ${Baselines.ms(st.mean)}")
            appendLine("    P99 $p99")
        }
        appendLine()
        appendLine("TEST EXECUTION TIME (simulation only — excludes Gradle configuration, compilation and JVM start)")
        appendLine("  total ${Baselines.ms(s.executionMs.toDouble())}   of which explicit wait steps ${Baselines.ms(s.waitStepMs.toDouble())}, deliberate simulated delays ${Baselines.ms(s.intentionalDelayMs.toDouble())}")
        for ((key, label) in listOf(
            Metrics.TIMING_SCENARIO to "Scenario wall",
            Metrics.TIMING_TURN to "Turn wall",
            Metrics.TIMING_SETTLE to "Turn settle wait",
            Metrics.TIMING_SETUP to "Scenario setup",
            Metrics.TIMING_TEARDOWN to "Scenario teardown",
        )) {
            val st = s.timing[key] ?: continue
            if (st.n == 0) continue
            appendLine("  $label (n=${st.n}): P50 ${Baselines.ms(st.p50)}   P95 ${Baselines.ms(st.p95)}   max ${Baselines.ms(st.max)}")
        }
        if (s.slowest.isNotEmpty()) {
            appendLine("  Slowest scenarios:")
            s.slowest.take(5).forEach { (id, ms) -> appendLine("    ${Baselines.ms(ms.toDouble()).padStart(9)}  $id") }
        }
        appendLine()
        appendLine("FAILURES (${s.failures.size})")
        if (s.failures.isEmpty()) appendLine("  none")
        s.failures.take(50).forEach { f ->
            appendLine("  ${f.scenarioId}  #${f.iteration} ${f.variant} seed ${f.seed}")
            f.reasons.take(4).forEach { appendLine("    - $it") }
        }
        if (s.failures.size > 50) appendLine("  … ${s.failures.size - 50} more in results.jsonl")
        appendLine()
        appendLine("REGRESSION COMPARISON")
        if (comparison == null) {
            appendLine("  not run")
        } else {
            comparison.lines.forEach { appendLine("  $it") }
            comparison.findings.forEach { appendLine("  [${it.status}] ${it.message}") }
            appendLine()
            appendLine("STATUS: ${comparison.status}")
        }
    }

    fun modeNote(mode: TestMode): String = when (mode) {
        TestMode.SIM_LOGIC -> "Note: scripted model + simulated world. Measures app logic and recovery, NOT model understanding, ASR or acoustics."
        TestMode.TEXT_LIVE -> "Note: real Baidu model via text turns + simulated world. No ASR or acoustics."
        TestMode.AUDIO_E2E -> "Note: synthetic speech through the real audio path + simulated world. Not real-cabin acoustics."
    }
}
