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

    fun onSpeech(active: Boolean, silenceMs: Long = 0, suspiciousAudio: Boolean = false) {
        if (active) {
            stop("driver_speech")
            synchronized(lock) { turn = WaitCueTurn() }
            return
        }
        synchronized(lock) {
            val current = turn ?: WaitCueTurn().also { turn = it }
            if (current.clockStarted || current.stopped || current.replyUnderway) return
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
            else -> Unit
        }
    }

    fun onToolResultDelivered() {
        synchronized(lock) { turn?.onToolResultDelivered() }
    }

    fun onUsefulSpeech() = stop("reply_audio", turnScoped = true)

    /** The app handled this utterance (a pick, 「闭嘴」, the wake word): no cue, even before the clock starts. */
    fun endTurn(reason: String) = stop(reason, turnScoped = true)

    fun close() = stop("session_end", turnScoped = true)

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
