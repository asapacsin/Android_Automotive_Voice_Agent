package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog

/**
 * SPEC-020 wait cue spoken by the provider itself: the response after a cue request is captured and
 * played only when it positively proves to be that cue (INVARIANT I-1; a prompt rule is not
 * enforcement). Its audio is played only when, at response.done, the status is completed, it held
 * no function call, its whole transcript equals the cue text (whitespace and punctuation aside) and
 * the driver did not start speaking. A response that turns out to be a reply is handed back, with
 * every message it sent, to the normal reply path ([Step.Demote]). Logs carry codes only.
 *
 * Only response-scoped messages of the captured response are buffered; everything else passes.
 * The adapter translates the wire into [Signal]s; this class knows no vendor vocabulary (ADR-009).
 */
internal class ProviderCueCapture {
    sealed interface Step {
        /** Not the cue's: process it exactly as without a cue. */
        object Pass : Step
        /** Held with the captured response. */
        object Buffered : Step
        /** The cue's response started: the client marks a response as running. */
        class Started(val cancel: Boolean) : Step
        /** The cue request was refused because a response is running; consumed. */
        object Skipped : Step
        /** The capture was discarded by this message: send the cancel once, then process it normally. */
        object CancelAndPass : Step
        /** The candidate is a reply: process [messages] through the normal path, in order. */
        class Demote(val messages: List<String>) : Step
        /** The captured response is done; [pcm] is played as the cue, null plays nothing. */
        class Finish(val pcm: ByteArray?) : Step
    }

    private enum class State { IDLE, REQUESTED, CAPTURING }

    private var state = State.IDLE
    private var cue = ""
    private var speechSinceRequest = false
    private var discarded = false
    private val buffered = ArrayList<String>()
    private val transcript = StringBuilder()
    private val pcm = java.io.ByteArrayOutputStream()

    @get:Synchronized val idle: Boolean get() = state == State.IDLE

    /** A cue for [text] was asked for; the next response.created is its candidate. */
    @Synchronized
    fun request(text: String) {
        reset()
        state = State.REQUESTED
        cue = normalize(text)
    }

    /** The request could not be sent, or the connection is gone. */
    @Synchronized
    fun clear() = reset()

    /**
     * The client cancels the response (barge-in, 「闭嘴」). True when a running capture was discarded
     * and its response must be cancelled; a capture not started yet is only marked.
     */
    @Synchronized
    fun discardForClientCancel(): Boolean {
        if (state == State.IDLE || discarded) return false
        discarded = true
        DebugVoiceLog.log("wait_cue_cancelled reason=client_cancel")
        return state == State.CAPTURING
    }

    /**
     * One provider message, already translated by the adapter (ADR-009: this class speaks no wire
     * vocabulary). [raw] is kept verbatim so a demoted response can be replayed exactly.
     */
    sealed interface Signal {
        object Started : Signal
        class Finished(val completed: Boolean, val hasCall: Boolean) : Signal
        object DriverSpeech : Signal
        object Error : Signal
        /** A function call (an output item or its arguments) in the response. */
        object Call : Signal
        class Words(val delta: String) : Signal
        class AllWords(val full: String) : Signal
        class Audio(val base64: String) : Signal
        /** Any other message that belongs to the response. */
        object Response : Signal
        /** Driver input, session and anything else not scoped to a response. */
        object Unrelated : Signal
    }

    @Synchronized
    fun onMessage(signal: Signal, raw: String, responseAlreadyActive: () -> Boolean): Step = when (state) {
        State.IDLE -> Step.Pass
        State.REQUESTED -> onRequested(signal, raw, responseAlreadyActive)
        State.CAPTURING -> onCapturing(signal, raw, responseAlreadyActive)
    }

    private fun onRequested(signal: Signal, raw: String, responseAlreadyActive: () -> Boolean): Step = when (signal) {
        Signal.Started -> {
            state = State.CAPTURING
            buffered += raw
            // A client cancel came before the response existed: cancel it now that it does.
            Step.Started(cancel = discarded)
        }
        Signal.Error -> {
            val active = responseAlreadyActive()
            reset()
            if (active) {
                DebugVoiceLog.log("wait_cue_skipped reason=response_active")
                Step.Skipped
            } else {
                DebugVoiceLog.log("wait_cue_failed reason=error")
                Step.Pass
            }
        }
        Signal.DriverSpeech -> {
            speechSinceRequest = true
            Step.Pass
        }
        else -> Step.Pass
    }

    private fun onCapturing(signal: Signal, raw: String, responseAlreadyActive: () -> Boolean): Step {
        when (signal) {
            Signal.DriverSpeech -> {
                speechSinceRequest = true
                if (discarded) return Step.Pass
                discarded = true
                DebugVoiceLog.log("wait_cue_cancelled reason=driver_speech")
                return Step.CancelAndPass
            }
            Signal.Error -> {
                // The cue's response.create was refused: what is being captured is a reply already
                // running. The refusal is consumed and the reply goes back to the normal path.
                if (!responseAlreadyActive()) return Step.Pass // anything else: normal path; done decides
                DebugVoiceLog.log("wait_cue_skipped reason=response_active")
                return if (discarded) Step.Skipped else demote(log = false)
            }
            Signal.Started -> {
                // The captured response's done was lost: it ends here, and the new one is not a candidate.
                DebugVoiceLog.log("wait_cue_failed reason=lost")
                reset()
                return Step.Pass
            }
            Signal.Unrelated -> return Step.Pass
            else -> Unit
        }
        buffered += raw
        if (signal is Signal.Finished) return finish(signal)
        if (discarded) return Step.Buffered
        when (signal) {
            Signal.Call -> return demote()
            is Signal.Words -> {
                transcript.append(signal.delta)
                if (!cue.startsWith(normalize(transcript.toString()))) return demote()
            }
            is Signal.AllWords -> {
                transcript.setLength(0)
                transcript.append(signal.full)
                if (!cue.startsWith(normalize(transcript.toString()))) return demote()
            }
            is Signal.Audio -> runCatching { java.util.Base64.getMimeDecoder().decode(signal.base64) }
                .getOrNull()?.let(pcm::write)
            else -> Unit
        }
        return Step.Buffered
    }

    private fun finish(done: Signal.Finished): Step {
        if (done.hasCall && !discarded) return demote()
        val audio = pcm.toByteArray()
        val reason = when {
            discarded -> null
            !done.completed -> "cancelled"
            speechSinceRequest || normalize(transcript.toString()) != cue || audio.isEmpty() -> "mismatch"
            else -> ""
        }
        reset()
        if (reason == null) return Step.Finish(null)
        if (reason.isNotEmpty()) {
            DebugVoiceLog.log("wait_cue_failed reason=$reason")
            return Step.Finish(null)
        }
        return Step.Finish(if (audio.size % 2 == 0) audio else audio + 0)
    }

    private fun demote(log: Boolean = true): Step {
        if (log) DebugVoiceLog.log("wait_cue_failed reason=not_cue")
        val messages = buffered.toList()
        reset()
        return Step.Demote(messages)
    }

    private fun reset() {
        state = State.IDLE
        cue = ""
        speechSinceRequest = false
        discarded = false
        buffered.clear()
        transcript.setLength(0)
        pcm.reset()
    }

    companion object {
        private val IGNORED = Regex("[\\s，。！？、,.!?]")

        /** The cue text and a transcript are compared without whitespace and punctuation. */
        fun normalize(text: String): String = text.replace(IGNORED, "")
    }
}
