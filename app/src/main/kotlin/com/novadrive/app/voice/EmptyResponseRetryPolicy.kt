package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Baidu Flex occasionally finishes a turn with `response.done status=completed output=[]` —
 * no tool call, no reply — right after the driver spoke (measured 2026-09-16: 温度调高一点).
 * The driver then hears nothing. This policy asks for one more response in that case.
 *
 * At most one retry per driver utterance, so it can never loop. Empty responses that are not
 * tied to an utterance (VAD triggered by noise) are not retried.
 *
 * The transcript and the empty response can arrive in either order, so both are handled.
 *
 * P45 Unit 2 (decider, 2026-10-18): the same owner also re-asks, provider-neutrally, a driver turn
 * that gets no answer at all: [DEADLINE_MS] after the end of speech with neither output nor an
 * input transcription (`reason=deadline`), or at once for an empty close of a transcribed turn
 * (`reason=empty_close`, through [fire]). Once per utterance; never after the driver's next onset or
 * an app-handled utterance, nor while [blocked] names a reason (suspended listening, a GUIDANCE
 * prompt, a suspicious segment, a response already under way). [send] puts the provider's silent
 * re-ask on the wire (false: the socket is dead). With no answer [GIVE_UP_MS] after it, [giveUp]
 * runs once (the client's final stall notice) and [gaveUp] stays set until the driver speaks again.
 * Logs carry codes and timings only (I-8). With no [scope] (the Baidu-only use) there is no deadline.
 */
class EmptyResponseRetryPolicy(
    private val scope: CoroutineScope? = null,
    private val prefix: String = "voice",
    private val send: () -> Boolean = { false },
    private val giveUp: () -> Unit = {},
    private val blocked: () -> String? = { null },
) {
    /** The provider's SPEC-020 `repliesSpoken`: false in quiet listening (SILENT_WAIT), which blocks a re-ask. */
    @Volatile var repliesSpoken: () -> Boolean = { true }

    private var utterancePending = false
    private var emptyWithoutUtterance = false
    private var retriedThisUtterance = false

    /** Tests shorten these; production keeps the decided 3.0 s and 5.0 s. */
    @Volatile var deadlineMs: Long = DEADLINE_MS
    @Volatile var giveUpMs: Long = GIVE_UP_MS
    private var utterance = 0L
    private var heard = false          // output, a created response or an input transcription
    private var handled = false        // the app handled this utterance
    private var reasked = false        // a re-ask went out since the last utterance boundary
    private var deadlineReasked = false
    /** The re-ask has not yet met its own busy error or a response.done (DashScope cannot match it). */
    private var busyMatchable = false
    private var deadlineJob: Job? = null
    private var answerJob: Job? = null
    @Volatile var gaveUp = false
        private set

    /**
     * New speech began. Any earlier empty response belonged to something before it (often VAD
     * noise), so it must not be attributed to this utterance.
     */
    @Synchronized
    fun onSpeechStarted() {
        emptyWithoutUtterance = false
        newUtterance()
    }

    /** The driver stopped speaking: arm the no-answer deadline for this utterance. */
    @Synchronized
    fun onEndOfSpeech() {
        val scope = scope ?: return
        if (heard || handled || reasked) return
        deadlineJob?.cancel()
        val mine = utterance
        deadlineJob = scope.launch {
            delay(deadlineMs)
            val due = synchronized(this@EmptyResponseRetryPolicy) { mine == utterance && !heard && !handled && !reasked }
            if (due) fire(DEADLINE, deadlineMs)
        }
    }

    /**
     * A response was created after the re-ask: from here [ResponseStallWatchdog] alone owns a stall,
     * so the give-up timer stops.
     */
    @Synchronized
    fun onResponseCreated() {
        onHeard()
        answerJob?.cancel(); answerJob = null
    }

    /** The server started answering or transcribed the driver: the deadline is not needed. */
    @Synchronized
    fun onHeard() {
        heard = true
        deadlineJob?.cancel(); deadlineJob = null
    }

    /** Output arrived (audio, words, a call, an output item): this utterance is being answered. */
    @Synchronized
    fun onOutput() {
        onHeard()
        answerJob?.cancel(); answerJob = null
    }

    /** The app handled this utterance itself (a local pick, 「闭嘴」): no re-ask, no give-up. */
    @Synchronized
    fun appHandled() {
        handled = true
        busyMatchable = false
        cancelJobs()
    }

    /**
     * Sends the re-ask once for this utterance unless [blocked]; true when it went out. The
     * callbacks run outside this policy's lock.
     */
    fun fire(reason: String, afterMs: Long = 0): Boolean {
        val mine = synchronized(this) {
            if (reasked) return false
            if (handled) return dropped(reason, "app_handled")
            utterance
        }
        (blocked() ?: "quiet".takeIf { !repliesSpoken() })?.let { return dropped(reason, it) }
        if (!send()) return dropped(reason, "socket")
        synchronized(this) {
            if (mine != utterance) return dropped(reason, "superseded")
            reasked = true; busyMatchable = true
            if (reason == DEADLINE) deadlineReasked = true
            DebugVoiceLog.log("${prefix}_reask reason=$reason after_ms=$afterMs")
            answerJob?.cancel()
            answerJob = scope?.launch {
                delay(giveUpMs)
                val due = synchronized(this@EmptyResponseRetryPolicy) {
                    (mine == utterance && !handled).also { if (it) { gaveUp = true; busyMatchable = false } }
                }
                if (due) { DebugVoiceLog.log("${prefix}_reask_gave_up after_ms=$giveUpMs"); giveUp() }
            }
        }
        return true
    }

    /**
     * A busy `error` arrived. While the re-ask is still unmatched it is taken as the re-ask's own
     * rejection: dropped, not re-queued (true). Otherwise the caller handles it as before (false).
     */
    @Synchronized
    fun onBusyError(): Boolean {
        if (!busyMatchable) return false
        busyMatchable = false
        answerJob?.cancel(); answerJob = null
        DebugVoiceLog.log("${prefix}_reask_dropped reason=busy")
        return true
    }

    /** Returns true when the caller should send `response.create`. */
    @Synchronized
    fun onUserTranscriptCompleted(): Boolean {
        onHeard()
        if (deadlineReasked) {
            // This utterance was already re-asked at its deadline: its empty close gets no second one.
            retriedThisUtterance = true
            emptyWithoutUtterance = false
            utterancePending = true
            return false
        }
        reasked = false
        retriedThisUtterance = false
        if (emptyWithoutUtterance) {
            // The empty response for this utterance already arrived.
            emptyWithoutUtterance = false
            retriedThisUtterance = true
            return true
        }
        utterancePending = true
        return false
    }

    /** Returns true when the caller should send `response.create`. */
    @Synchronized
    fun onResponseDone(status: String, outputCount: Int): Boolean {
        busyMatchable = false
        if (outputCount > 0 && status == "completed") onOutput() // a cancelled reply answered nothing
        if (status != "completed" || outputCount > 0) {
            utterancePending = false
            emptyWithoutUtterance = false
            return false
        }
        if (!utterancePending) {
            emptyWithoutUtterance = !retriedThisUtterance
            return false
        }
        utterancePending = false
        if (retriedThisUtterance) return false
        retriedThisUtterance = true
        return true
    }

    @Synchronized
    fun reset() {
        utterancePending = false
        emptyWithoutUtterance = false
        retriedThisUtterance = false
        newUtterance()
    }

    private fun newUtterance() {
        utterance++
        heard = false; handled = false; reasked = false; deadlineReasked = false
        busyMatchable = false; gaveUp = false
        cancelJobs()
    }

    private fun cancelJobs() {
        deadlineJob?.cancel(); deadlineJob = null
        answerJob?.cancel(); answerJob = null
    }

    private fun dropped(trigger: String, why: String): Boolean {
        DebugVoiceLog.log("${prefix}_reask_dropped reason=$why trigger=$trigger")
        return false
    }

    companion object {
        const val DEADLINE = "deadline"
        const val EMPTY_CLOSE = "empty_close"
        /** After the end of speech with nothing heard (P45 U2; the forum's 3.0 s fallback, t/128155). */
        const val DEADLINE_MS = 3_000L
        /** After the re-ask with no answer: the final stall notice. */
        const val GIVE_UP_MS = 5_000L
    }
}
