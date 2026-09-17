package com.novadrive.evaluation

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Latencies of one turn, in milliseconds; null when the stage did not occur. */
data class TurnLatency(
    val speechEndToTool: Double? = null,
    val toolCallToExecutionStart: Double? = null,
    val toolExecution: Double? = null,
    val speechEndToStateConfirmed: Double? = null,
    val speechEndToTts: Double? = null,
    val interruptionToAudioStop: Double? = null,
) {
    fun toMap(): Map<String, Double?> = linkedMapOf(
        LatencyMetric.SPEECH_END_TO_TOOL.key to speechEndToTool,
        LatencyMetric.TOOL_CALL_TO_EXECUTION_START.key to toolCallToExecutionStart,
        LatencyMetric.TOOL_EXECUTION.key to toolExecution,
        LatencyMetric.SPEECH_END_TO_STATE.key to speechEndToStateConfirmed,
        LatencyMetric.SPEECH_END_TO_TTS.key to speechEndToTts,
        LatencyMetric.INTERRUPTION_TO_AUDIO_STOP.key to interruptionToAudioStop,
    )
}

enum class LatencyMetric(val key: String, val label: String) {
    SPEECH_END_TO_TOOL("speechEndToToolMs", "Speech → Tool"),
    TOOL_CALL_TO_EXECUTION_START("toolCallToExecutionStartMs", "Tool call → Execution start"),
    TOOL_EXECUTION("toolExecutionMs", "Tool execution"),
    SPEECH_END_TO_STATE("speechEndToStateConfirmedMs", "Speech → Verified state"),
    SPEECH_END_TO_TTS("speechEndToTtsMs", "Speech → TTS"),
    INTERRUPTION_TO_AUDIO_STOP("interruptionToAudioStopMs", "Interruption → Audio stop"),
}

data class TurnResult(
    val index: Int,
    val kind: String,
    val utterance: String,
    val expectedTools: List<ToolCallSpec>,
    val actualCalls: List<ObservedCall>,
    val expectedState: Map<String, String>,
    val actualState: Map<String, String>,
    val replies: List<String>,
    val verdict: TurnVerdict,
    val latency: TurnLatency,
    val interruptDetected: Boolean? = null,
    val outcome: Outcome = Outcome.SUCCESS,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "index" to index,
        "kind" to kind,
        "utterance" to utterance,
        "expectedTools" to expectedTools.map { mapOf("name" to it.name, "args" to it.args) },
        "actualCalls" to actualCalls.map {
            linkedMapOf("name" to it.name, "args" to it.args, "success" to it.success, "errorCode" to it.errorCode)
        },
        "expectedState" to expectedState,
        "actualState" to actualState.filterKeys { it in expectedState },
        "replies" to replies,
        "toolSelectionCorrect" to verdict.toolSelectionCorrect,
        "parametersCorrect" to verdict.parametersCorrect,
        "executionSucceeded" to verdict.executionSucceeded,
        "finalStateCorrect" to verdict.finalStateCorrect,
        "falseSuccess" to verdict.falseSuccess,
        "prematureClaim" to verdict.prematureClaim,
        "unexpectedToolCalls" to verdict.unexpectedToolCalls,
        "duplicateExecutions" to verdict.duplicateExecutions,
        "interruptDetected" to interruptDetected,
        "outcome" to outcome.name,
        "passed" to verdict.passed,
        "failures" to verdict.failures,
        "latency" to latency.toMap(),
    )
}

data class ScenarioResult(
    val scenarioId: String,
    val runId: String,
    val mode: TestMode,
    val seed: Long,
    val iteration: Int,
    val variant: String,
    val turns: List<TurnResult>,
    val sessionFailed: Boolean,
    val crashed: Boolean,
    val skipped: String?,
    val errors: List<String>,
    val durationMs: Long,
) {
    val taskSuccess: Boolean
        get() = skipped == null && !sessionFailed && !crashed && errors.isEmpty() && turns.all { it.verdict.passed }

    val failures: List<String>
        get() = errors + turns.flatMap { t -> t.verdict.failures.map { "turn ${t.index + 1} 「${t.utterance}」: $it" } } +
            listOfNotNull("session failed".takeIf { sessionFailed }, "crashed".takeIf { crashed })

    private val primary: TurnResult? get() = turns.firstOrNull { it.expectedTools.isNotEmpty() } ?: turns.firstOrNull()

    fun toMap(): Map<String, Any?> {
        val p = primary
        return linkedMapOf(
            "scenarioId" to scenarioId,
            "runId" to runId,
            "mode" to mode.name,
            "level" to mode.level.name,
            "seed" to seed,
            "iteration" to iteration,
            "variant" to variant,
            "expectedTool" to p?.expectedTools?.firstOrNull()?.name,
            "expectedParameters" to p?.expectedTools?.firstOrNull()?.args,
            "actualTool" to p?.actualCalls?.firstOrNull()?.name,
            "actualParameters" to p?.actualCalls?.firstOrNull()?.args,
            "finalStateCorrect" to turns.mapNotNull { it.verdict.finalStateCorrect }.let { if (it.isEmpty()) null else it.all { ok -> ok } },
            "falseSuccess" to turns.any { it.verdict.falseSuccess },
            "prematureClaim" to turns.any { it.verdict.prematureClaim },
            "unexpectedToolCalls" to turns.sumOf { it.verdict.unexpectedToolCalls },
            "duplicateExecutions" to turns.sumOf { it.verdict.duplicateExecutions },
            "taskSuccess" to taskSuccess,
            "sessionFailed" to sessionFailed,
            "crashed" to crashed,
            "skipped" to skipped,
            "speechEndToToolMs" to p?.latency?.speechEndToTool,
            "speechEndToStateConfirmedMs" to p?.latency?.speechEndToStateConfirmed,
            "speechEndToTtsMs" to p?.latency?.speechEndToTts,
            "durationMs" to durationMs,
            "failures" to failures,
            "turns" to turns.map { it.toMap() },
        )
    }

    fun toJson(): String = Json.write(toMap())
}

/**
 * The environment a scenario runs in. The JVM simulation and the phone runner implement this;
 * [ScenarioExecutor] owns the sequencing, the oracle and the timing so both measure the same way.
 */
interface ScenarioDriver {
    val mode: TestMode

    /** Why [step] cannot run in this driver, or null. */
    fun unsupported(step: Step): String? = null

    suspend fun prepare(scenario: Scenario, seed: Long)

    /** Delivers the user turn (text, scripted or synthetic audio). Returns once it has been sent. */
    suspend fun deliver(turn: Step.Say, variant: String)

    /** Waits until the app is idle after a turn: no reply running, no pending tool work. */
    suspend fun awaitSettled(timeoutMs: Long): Boolean

    fun snapshot(): Map<String, String>

    /** Non-turn steps: route progress, arrival, faults, camera, pauses. */
    suspend fun apply(step: Step)

    /**
     * Speaks [Step.BargeIn.primer] as a turn that makes the assistant talk, then delivers the
     * interruption [Step.BargeIn.afterTtsMs] after its speech starts. Returns once delivered.
     */
    suspend fun bargeIn(step: Step.BargeIn, variant: String)

    fun sessionFailed(): Boolean

    suspend fun finish(scenario: Scenario)
}

class ScenarioExecutor(
    private val recorder: TelemetryRecorder,
    private val driver: ScenarioDriver,
    private val statePollMs: Long = 20,
) {
    suspend fun run(scenario: Scenario, runId: String, seed: Long, iteration: Int, variant: String = "normal"): ScenarioResult {
        val started = System.nanoTime()
        if (driver.mode !in scenario.modes) {
            return skipped(scenario, runId, seed, iteration, variant, "not defined for ${driver.mode}")
        }
        scenario.steps.firstNotNullOfOrNull { driver.unsupported(it) }?.let {
            return skipped(scenario, runId, seed, iteration, variant, it)
        }
        recorder.context = RunContext(runId, scenario.id, driver.mode.name)
        recorder.captureText = true
        val turns = mutableListOf<TurnResult>()
        val errors = mutableListOf<String>()
        try {
            driver.prepare(scenario, scenarioSeed(seed, scenario.id, iteration))
            for (step in scenario.steps) {
                when (step) {
                    is Step.Say -> turns += runTurn(turns.size, step.utterance, step.expect, step.timeoutMs, if (step.expect.awaitSettle) "say" else "overlap") {
                        driver.deliver(step, variant)
                    }
                    is Step.BargeIn -> turns += runTurn(turns.size, step.utterance, step.expect, 45_000, "barge_in") {
                        driver.bargeIn(step, variant)
                    }
                    is Step.Check -> {
                        val snap = driver.snapshot()
                        val mismatches = Oracle.stateMismatches(step.state, snap)
                        turns += TurnResult(
                            index = turns.size, kind = "check", utterance = "(check)",
                            expectedTools = emptyList(), actualCalls = emptyList(),
                            expectedState = step.state, actualState = snap, replies = emptyList(),
                            verdict = TurnVerdict(true, true, null, mismatches.isEmpty(), false, false, 0, 0, true,
                                mismatches.map { "state: $it" }),
                            latency = TurnLatency(),
                        )
                    }
                    is Step.Inject -> {
                        recorder.record(EventType.FAULT_INJECTED, detail = step.fault.toString())
                        driver.apply(step)
                    }
                    else -> driver.apply(step)
                }
                if (driver.sessionFailed()) break
            }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            errors += "exception: ${failure.javaClass.simpleName}: ${failure.message}"
        } finally {
            runCatching { driver.finish(scenario) }
            recorder.context = null
            recorder.captureText = false
        }
        return ScenarioResult(
            scenarioId = scenario.id, runId = runId, mode = driver.mode, seed = seed, iteration = iteration,
            variant = variant, turns = turns, sessionFailed = driver.sessionFailed(),
            crashed = errors.any { it.startsWith("exception") }, skipped = null, errors = errors,
            durationMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    private suspend fun runTurn(
        index: Int,
        utterance: String,
        expect: TurnExpectation,
        timeoutMs: Long,
        kind: String,
        deliver: suspend () -> Unit,
    ): TurnResult {
        val startSeq = recorder.lastSeq
        recorder.record(EventType.INPUT_SENT, expectedValue = expect.tools.joinToString(" → "), text = { utterance })
        var settled = false
        coroutineScope {
            val watcher = if (expect.state.isEmpty()) null else launch {
                while (isActive) {
                    if (Oracle.stateMismatches(expect.state, driver.snapshot()).isEmpty()) {
                        recorder.record(EventType.STATE_VERIFIED, success = true)
                        break
                    }
                    delay(statePollMs)
                }
            }
            deliver()
            settled = if (kind == "overlap") {
                delay(timeoutMs)
                true
            } else {
                driver.awaitSettled(timeoutMs)
            }
            // One last look: the state may settle in the same instant as the reply.
            if (watcher != null && watcher.isActive) {
                if (Oracle.stateMismatches(expect.state, driver.snapshot()).isEmpty()) {
                    watcher.cancel()
                    recorder.record(EventType.STATE_VERIFIED, success = true)
                } else {
                    watcher.cancel()
                }
            }
        }
        val all = recorder.eventsAfter(startSeq)
        // A barge-in is judged from the interruption on: the driver records a second INPUT_SENT when
        // it delivers the interrupting speech, and the primer's own reply is not part of the verdict.
        val events = if (kind == "barge_in") {
            val interruption = all.lastOrNull { it.type == EventType.INPUT_SENT }
            if (interruption == null) all else all.dropWhile { it !== interruption }
        } else {
            all
        }
        val calls = TelemetryReader.calls(events)
        val replies = TelemetryReader.replies(events)
        val snapshot = driver.snapshot()
        // An overlapping turn is only judged on the call it produced; its state and reply are
        // checked by the steps that follow.
        val verdict = if (kind == "overlap") {
            Oracle.judge(expect.copy(state = emptyMap()), calls, snapshot, replies, timedOut = false, requireReply = false)
        } else {
            Oracle.judge(expect, calls, snapshot, replies, timedOut = !settled)
        }
        return TurnResult(
            index = index, kind = kind, utterance = utterance, expectedTools = expect.tools,
            actualCalls = calls, expectedState = expect.state, actualState = snapshot,
            replies = replies.map { it.text }, verdict = verdict, latency = TelemetryReader.latency(events),
            interruptDetected = if (kind == "barge_in") events.any { it.type == EventType.INTERRUPT_DETECTED } else null,
            outcome = expect.outcome,
        )
    }

    private fun skipped(scenario: Scenario, runId: String, seed: Long, iteration: Int, variant: String, why: String) =
        ScenarioResult(scenario.id, runId, driver.mode, seed, iteration, variant, emptyList(), false, false, why, emptyList(), 0)

    companion object {
        /** Per scenario and iteration, reproducible from the run seed. */
        fun scenarioSeed(seed: Long, scenarioId: String, iteration: Int): Long =
            Random(seed xor scenarioId.hashCode().toLong() xor (iteration.toLong() shl 32)).nextLong()

        /** Polls [idle] until it holds for [quietMs] of telemetry silence, or [timeoutMs] passes. */
        suspend fun awaitQuiet(recorder: TelemetryRecorder, quietMs: Long, timeoutMs: Long, idle: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (System.nanoTime() < deadline) {
                val quietFor = (recorder.clock.nanos() - recorder.lastEventNanos) / 1_000_000
                if (idle() && quietFor >= quietMs) return true
                delay(minOf(20L, quietMs))
            }
            return false
        }
    }
}

/** Reconstructs calls, replies and latencies from one turn's events. */
object TelemetryReader {
    fun calls(events: List<TelemetryEvent>): List<ObservedCall> {
        val byId = LinkedHashMap<String, ObservedCall>()
        for (e in events) {
            val id = e.detail ?: continue
            when (e.type) {
                EventType.TOOL_CALL_RECEIVED -> byId[id] = ObservedCall(
                    name = e.toolType.orEmpty(),
                    args = e.actualValue?.let { parseArgs(it) }.orEmpty(),
                    receivedNanos = e.timestampNanos,
                )
                EventType.TOOL_EXECUTION_START -> byId[id]?.let { byId[id] = it.copy(executionStartNanos = e.timestampNanos) }
                EventType.TOOL_EXECUTION_END -> byId[id]?.let {
                    byId[id] = it.copy(executionEndNanos = e.timestampNanos, success = e.success, errorCode = e.errorCode)
                }
                else -> Unit
            }
        }
        return byId.values.toList()
    }

    fun replies(events: List<TelemetryEvent>): List<ObservedReply> =
        events.filter { it.type == EventType.ASSISTANT_REPLY && !it.actualValue.isNullOrBlank() }
            .map { ObservedReply(it.actualValue!!, it.timestampNanos) }

    private fun parseArgs(json: String): Map<String, String> =
        runCatching {
            Json.parseObject(json).mapValues { (_, v) ->
                when (v) {
                    is Double -> if (v == Math.rint(v)) v.toLong().toString() else v.toString()
                    else -> v.toString()
                }
            }
        }.getOrDefault(emptyMap())

    fun latency(events: List<TelemetryEvent>): TurnLatency {
        fun first(type: EventType, after: Long = Long.MIN_VALUE) =
            events.firstOrNull { it.type == type && it.timestampNanos >= after }?.timestampNanos
        fun ms(from: Long?, to: Long?) = if (from == null || to == null || to < from) null else (to - from) / 1e6
        val anchor = first(EventType.SPEECH_END) ?: first(EventType.INPUT_SENT)
        val toolCall = anchor?.let { first(EventType.TOOL_CALL_RECEIVED, it) }
        val callEvent = events.firstOrNull { it.type == EventType.TOOL_CALL_RECEIVED && it.timestampNanos == toolCall }
        val start = callEvent?.let { c -> events.firstOrNull { it.type == EventType.TOOL_EXECUTION_START && it.detail == c.detail }?.timestampNanos }
        val end = callEvent?.let { c -> events.firstOrNull { it.type == EventType.TOOL_EXECUTION_END && it.detail == c.detail }?.timestampNanos }
        val interrupt = first(EventType.INTERRUPT_DETECTED)
        return TurnLatency(
            speechEndToTool = ms(anchor, toolCall),
            toolCallToExecutionStart = ms(toolCall, start),
            toolExecution = ms(start, end),
            speechEndToStateConfirmed = ms(anchor, anchor?.let { first(EventType.STATE_VERIFIED, it) }),
            speechEndToTts = ms(anchor, anchor?.let { first(EventType.TTS_START, it) }),
            interruptionToAudioStop = ms(interrupt, interrupt?.let { first(EventType.AUDIO_STOPPED, it) }),
        )
    }
}
