package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * SPEC-020 clock for a provider that speaks in its own voice (Maia). The decision is [WaitCueTurn];
 * this only runs it. A spoken cue is delivered by [onSpeak]. The 3 s mark is [onVisual] only.
 */
class WaitCueClock(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val speaks: () -> Boolean = { true },
    private val onVisual: (Boolean) -> Unit,
    private val onSpeak: (WaitCue, Long) -> Unit,
) {
    private val lock = Any()
    private var turn: WaitCueTurn? = null
    private var startMs = 0L
    private var timer: Job? = null
    /** The driver finished a turn and has heard no reply yet. A stall does not clear it; a reply does. */
    private var awaitingReply = false
    /** The connection dropped while [awaitingReply]: the next connect owes the driver a notice. */
    private var lostTurn = false

    fun onSpeech(active: Boolean, silenceMs: Long = 0, suspiciousAudio: Boolean = false) {
        if (active) {
            stop("driver_speech")
            synchronized(lock) { turn = WaitCueTurn() }
            return
        }
        synchronized(lock) {
            val current = turn ?: WaitCueTurn().also { turn = it }
            if (current.clockStarted || current.stopped || current.replyUnderway) return
            awaitingReply = true
            current.suspiciousAudio = suspiciousAudio
            current.clockStarted = true
            startMs = clock() - silenceMs
            timer = scope.launch { run(current) }
        }
    }

    fun onProviderEvent(event: DomainVoiceEvent) {
        when (event) {
            is DomainVoiceEvent.SpeechStarted -> onSpeech(true)
            is DomainVoiceEvent.SpeechStopped -> onSpeech(false)
            is DomainVoiceEvent.ToolCall -> rearm { it.onToolCall(event.name) }
            DomainVoiceEvent.ResponseStarted -> rearm { it.onResponseStarted() }
            is DomainVoiceEvent.AudioDelta -> onUsefulSpeech()
            is DomainVoiceEvent.ResponseDone -> {
                val done = synchronized(lock) {
                    val current = turn ?: return
                    current.responseStarted && !current.toolOutstanding && !current.replyUnderway
                }
                if (done) stop("turn_done", turnScoped = true)
            }
            is DomainVoiceEvent.Interrupted -> stop("interrupted")
            is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> synchronized(lock) {
                if (awaitingReply) {
                    awaitingReply = false
                    lostTurn = true
                    DebugVoiceLog.log("wait_cue_turn_lost")
                }
            }
            else -> Unit
        }
    }

    fun onToolResultDelivered() {
        synchronized(lock) { turn?.onToolResultDelivered() }
    }

    fun onUsefulSpeech() = stop("reply_audio", turnScoped = true)

    /** The app handled this utterance (a pick, 「闭嘴」, the wake word): no cue, even before the clock starts. */
    fun endTurn(reason: String) = stop(reason, turnScoped = true)

    fun close() {
        synchronized(lock) { lostTurn = false }
        stop("session_end", turnScoped = true)
    }

    /** A stalled response is about to be asked for again: the driver has heard nothing, so tell them. */
    fun onStalled() = notice(WaitCue("stalled", WaitCues.DELAY))

    /** Called after a (re)connect: if the drop lost an unanswered turn, the driver is told to repeat it. */
    fun onConnected() {
        val owed = synchronized(lock) { lostTurn.also { lostTurn = false } }
        if (owed) notice(WaitCue("reconnected", WaitCues.RECONNECTED))
    }

    private fun notice(cue: WaitCue) {
        if (!speaks()) {
            DebugVoiceLog.log("wait_cue_skipped reason=quiet")
            return
        }
        onSpeak(cue, synchronized(lock) { if (startMs > 0L) clock() - startMs else 0L })
    }

    private fun rearm(update: (WaitCueTurn) -> Unit) {
        synchronized(lock) {
            val current = turn ?: return
            update(current)
            if (!current.live) return
            timer?.cancel()
            timer = scope.launch { run(current) }
        }
    }

    private fun stop(reason: String, turnScoped: Boolean = false) {
        val (clear, job) = synchronized(lock) {
            // A reply, the app handling the utterance or the end of the session answers the turn;
            // an interruption (a stall cancel included) does not.
            if (reason != "interrupted") awaitingReply = false
            // The driver spoke again or a reply arrived: an older lost turn is no longer owed a notice.
            if (reason == "driver_speech" || reason == "reply_audio") lostTurn = false
            val current = turn ?: return
            if (!turnScoped && !current.clockStarted) return
            if (reason == "reply_audio") current.replyUnderway = true
            val clear = current.visualShown
            if (clear) current.clearVisual()
            current.stopped = true
            clear to timer.also { timer = null }
        }
        if (job?.isActive == true) DebugVoiceLog.log("wait_cue_cancelled reason=$reason")
        job?.cancel()
        if (clear) onVisual(false)
    }

    private suspend fun run(current: WaitCueTurn) {
        while (true) {
            val elapsed = clock() - startMs
            var skipNoEvidence = false
            val cue = synchronized(lock) {
                if (turn !== current || !current.live) return
                if (!current.hasEvidence && !current.noEvidenceLogged && elapsed >= WaitCues.PROGRESS_MS) {
                    current.noEvidenceLogged = true
                    skipNoEvidence = true
                }
                current.due(elapsed)
            }
            if (skipNoEvidence) DebugVoiceLog.log("wait_cue_skipped reason=no_turn_evidence")
            if (cue != null) {
                if (!speaks()) {
                    DebugVoiceLog.log("wait_cue_skipped reason=quiet")
                } else if (cue.code == "visual") {
                    DebugVoiceLog.log("wait_cue code=visual after_ms=$elapsed")
                    onVisual(true)
                } else {
                    onSpeak(cue, elapsed)
                }
                continue
            }
            val next = WaitCues.nextThresholdAfter(elapsed) ?: break
            kotlinx.coroutines.delay(next - elapsed)
        }
    }
}
