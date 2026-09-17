package com.novadrive.evaluation

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Interaction telemetry shared by real use and by every benchmark mode. One schema, one recorder;
 * the benchmark only adds a [RunContext] and turns on [TelemetryRecorder.captureText].
 *
 * Privacy: no audio is ever recorded. Utterances, replies and tool arguments are only stored while
 * [TelemetryRecorder.captureText] is on (benchmark runs with scripted or synthetic input); in normal
 * use events carry tool names, codes, flags and timings only.
 */
enum class EventType {
    SESSION_START,
    SESSION_END,
    WAKE_DETECTED,

    /** The benchmark delivered a scripted turn (text or synthetic audio). Starts an interaction. */
    INPUT_SENT,
    SPEECH_START,
    SPEECH_END,
    ASR_RESULT,
    AGENT_REQUEST_START,
    TOOL_CALL_RECEIVED,
    TOOL_EXECUTION_START,
    TOOL_EXECUTION_END,
    STATE_VERIFIED,
    TTS_START,
    TTS_END,
    ASSISTANT_REPLY,
    INTERRUPT_DETECTED,
    AUDIO_STOPPED,
    GUARD_FOLLOW_UP,
    RECONNECT,
    FAULT_INJECTED,
    NAVIGATION_EVENT,
    TASK_COMPLETE,
    ERROR,
}

data class TelemetryEvent(
    val seq: Long,
    val timestampNanos: Long,
    val type: EventType,
    val interactionId: String?,
    val runId: String? = null,
    val scenarioId: String? = null,
    val testMode: String? = null,
    val toolType: String? = null,
    val expectedValue: String? = null,
    val actualValue: String? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
    val detail: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "seq" to seq,
        "timestampNanos" to timestampNanos,
        "eventType" to type.name,
        "interactionId" to interactionId,
        "runId" to runId,
        "scenarioId" to scenarioId,
        "testMode" to testMode,
        "toolType" to toolType,
        "expectedValue" to expectedValue,
        "actualValue" to actualValue,
        "success" to success,
        "errorCode" to errorCode,
        "detail" to detail,
    ).filterValues { it != null }

    fun toJson(): String = Json.write(toMap())
}

/** Monotonic nanoseconds. Android binds this to SystemClock.elapsedRealtimeNanos(). */
fun interface MonotonicClock {
    fun nanos(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun nanos(): Long = System.nanoTime()
}

/** Which benchmark run the events belong to; null in normal use. */
data class RunContext(val runId: String, val scenarioId: String, val testMode: String)

fun interface TelemetrySink {
    fun accept(event: TelemetryEvent)
}

class TelemetryRecorder(
    @Volatile var clock: MonotonicClock = SystemMonotonicClock,
    private val capacity: Int = 20_000,
) {
    private val lock = Any()
    private val buffer = ArrayDeque<TelemetryEvent>()
    private val seq = AtomicLong(0)
    private val interactionSeq = AtomicLong(0)
    private val sinks = CopyOnWriteArrayList<TelemetrySink>()

    @Volatile var context: RunContext? = null

    /** Store utterances, replies and tool arguments. Benchmark runs only. */
    @Volatile var captureText: Boolean = false

    @Volatile var currentInteractionId: String? = null
        private set

    @Volatile private var interactionHasSpeech = false

    @Volatile var lastEventNanos: Long = 0L
        private set

    fun addSink(sink: TelemetrySink) {
        sinks += sink
    }

    fun removeSink(sink: TelemetrySink) {
        sinks -= sink
    }

    fun beginInteraction(): String {
        val id = "i" + interactionSeq.incrementAndGet()
        currentInteractionId = id
        interactionHasSpeech = false
        return id
    }

    /**
     * Records one event. [text] is only evaluated when [captureText] is on, so callers can pass
     * user-derived content without it ever being stored in normal use.
     */
    fun record(
        type: EventType,
        toolType: String? = null,
        success: Boolean? = null,
        errorCode: String? = null,
        expectedValue: String? = null,
        detail: String? = null,
        text: (() -> String?)? = null,
    ): TelemetryEvent {
        when (type) {
            EventType.INPUT_SENT -> beginInteraction()
            // Live speech opens its own interaction unless a scripted input just opened one.
            EventType.SPEECH_START -> {
                if (currentInteractionId == null || interactionHasSpeech) beginInteraction()
                interactionHasSpeech = true
            }
            EventType.WAKE_DETECTED -> beginInteraction()
            else -> Unit
        }
        val ctx = context
        val event = TelemetryEvent(
            seq = seq.incrementAndGet(),
            timestampNanos = clock.nanos(),
            type = type,
            interactionId = currentInteractionId,
            runId = ctx?.runId,
            scenarioId = ctx?.scenarioId,
            testMode = ctx?.testMode,
            toolType = toolType,
            expectedValue = expectedValue,
            actualValue = if (captureText) text?.invoke() else null,
            success = success,
            errorCode = errorCode,
            detail = detail,
        )
        synchronized(lock) {
            buffer.addLast(event)
            while (buffer.size > capacity) buffer.removeFirst()
            lastEventNanos = event.timestampNanos
        }
        sinks.forEach { runCatching { it.accept(event) } }
        return event
    }

    fun events(): List<TelemetryEvent> = synchronized(lock) { buffer.toList() }

    fun eventsAfter(seqExclusive: Long): List<TelemetryEvent> =
        synchronized(lock) { buffer.filter { it.seq > seqExclusive } }

    val lastSeq: Long get() = seq.get()

    fun clear() {
        synchronized(lock) { buffer.clear() }
        currentInteractionId = null
        interactionHasSpeech = false
    }
}

/**
 * Process-wide recorder. Production code records through this; tests and the device runner swap
 * [recorder] or set its context. Recording never throws into the caller.
 */
object Telemetry {
    @Volatile var recorder: TelemetryRecorder = TelemetryRecorder()

    fun record(
        type: EventType,
        toolType: String? = null,
        success: Boolean? = null,
        errorCode: String? = null,
        detail: String? = null,
        text: (() -> String?)? = null,
    ) {
        runCatching { recorder.record(type, toolType, success, errorCode, null, detail, text) }
    }
}
