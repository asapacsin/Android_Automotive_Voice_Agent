package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.SpeakingStyle
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.SystemSessionClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-016 / SPEC-019: Gemini stays the agent and one assistant voice speaks. This sits between the
 * provider's event stream and the session. The provider's own [DomainVoiceEvent.AudioDelta] is
 * dropped on arrival; every [DomainVoiceEvent.SpeechText] (already judged upstream: the claim gate
 * for a driver turn, not-voided for a GUIDANCE turn) is cut into clauses and spoken through [voice]
 * as new AudioDelta events. Nothing here judges claims or decides turn-taking.
 *
 * Pipelining (P48): a clause's request starts as soon as its words are known, at most two per reply in
 * flight; its audio is buffered and emitted strictly in clause order after the previous clause's
 * request has returned.
 *
 * Ordering: one lane keeps the provider's event order. Speech is synthesised on its own chain, so a
 * ToolCall (or any non-boundary event) is forwarded at once and never waits on the voice. Reply
 * boundaries wait for the chain: ResponseStarted / prompt OPENED (the previous reply's speech is
 * done), AudioDone / ResponseDone / prompt COMPLETED (this reply's speech is done) — so a reply's
 * audio always sits inside its reply stamp. SpeechStarted/SpeechStopped bypass the lane.
 *
 * Cancellation: [cancelCurrentReply] — called by the provider when the session flushed playback
 * (the session decides barge-in) or the reply was cancelled — and an arriving Interrupted, Error,
 * Closed or the open GUIDANCE turn's VOIDED cancel the clause in flight and discard queued words.
 *
 * Failure (ADR-016 §6): the subtitle still shows, the rest of that reply is not spoken, the driver is
 * told once per reply through [onFailure]; never a fallback to the provider's voice. Logs codes,
 * counts and timings only — never the text (I-8).
 *
 * Wait cues (SPEC-020): from the driver's end of speech until her reply is audible, fixed cues
 * ([WaitCues]) are spoken through the same voice, on the same chain, so a reply that arrives during
 * a cue follows it. [quiet] (SILENT_WAIT, sleep) and an open GUIDANCE turn suppress them.
 */
class AssistantVoiceRevoicer(
    private val voice: AssistantVoice,
    private val style: () -> SpeakingStyle,
    private val onFailure: (String) -> Unit = AssistantVoiceNotices::report,
    private val clock: () -> Long = SystemSessionClock::nowMs,
    private val quiet: () -> Boolean = { false },
    /** Synthesise the two acknowledgements at the driver's onset, so the first cue is not late (P49). */
    private val prefillAcks: Boolean = false,
) {
    private val epoch = AtomicLong(0)
    private val speech = AtomicReference<Job?>(null)
    private val droppedProviderAudio = AtomicInteger(0)
    private val cueLock = Any()
    /** The driver's current turn, from his onset (or his end of speech when no onset was seen). */
    private var cueTurn: WaitCueTurn? = null
    private var cueStartMs = 0L
    private var cueTimer: Job? = null
    @Volatile private var cueHost: CueHost? = null
    @Volatile private var closing = false
    @Volatile private var promptOpen = false
    private val cueCache = ConcurrentHashMap<Pair<String, SpeakingStyle>, ByteArray>()

    /**
     * The driver started (true) or stopped (false) speaking: local evidence or the provider's event.
     * [silenceMs] is how long he had already been silent when the end was reported (the uplink
     * gate's hangover), so the clock runs from his last word. [suspiciousAudio]: the closed segment
     * looked like a cough or a knock (`SpeechUplinkGate.Segment.isSuspicious`).
     */
    fun onDriverSpeech(active: Boolean, silenceMs: Long = 0, suspiciousAudio: Boolean = false) {
        if (active) {
            val timer = synchronized(cueLock) {
                cueTurn = WaitCueTurn()
                cueTimer.also { cueTimer = null }
            }
            if (timer?.isActive == true) DebugVoiceLog.log("wait_cue_cancelled reason=driver_speech")
            timer?.cancel()
            if (prefillAcks && !quiet()) prefillAcks()
            return
        }
        if (closing) return
        val host = cueHost ?: return
        synchronized(cueLock) {
            val turn = cueTurn ?: WaitCueTurn().also { cueTurn = it }
            // The same end of speech reported twice, a turn already over, or her reply already under way.
            if (closing || turn.clockStarted || turn.stopped || turn.replyUnderway) return
            turn.suspiciousAudio = suspiciousAudio
            turn.clockStarted = true
            cueStartMs = clock() - silenceMs
            cueTimer = host.scope.launch { runWaitCues(turn, host) }
        }
    }

    /**
     * While he is still talking, fill the cache for the current style: the first cue of a session
     * otherwise paid a synthesis round trip (0.8–1.0 s on the emulator, 2026-10-08) on top of its
     * threshold. Nothing is spoken here.
     */
    private fun prefillAcks() {
        val host = cueHost ?: return
        val missing = listOf(WaitCues.PROGRESS, WaitCues.DELAY).filter { !cueCache.containsKey(it to style()) }
        if (missing.isEmpty()) return
        host.scope.launch {
            missing.forEach { text ->
                try { cuePcm(text) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
            }
        }
    }

    private suspend fun runWaitCues(turn: WaitCueTurn, host: CueHost) {
        while (true) {
            val elapsed = clock() - cueStartMs
            var skipNoEvidence = false
            val cue = synchronized(cueLock) {
                if (cueTurn !== turn || !turn.live) return
                if (!turn.hasEvidence && !turn.noEvidenceLogged && elapsed >= WaitCues.PROGRESS_MS) {
                    turn.noEvidenceLogged = true
                    skipNoEvidence = true
                }
                turn.due(elapsed)
            }
            if (skipNoEvidence) DebugVoiceLog.log("wait_cue_skipped reason=no_turn_evidence")
            if (cue != null) {
                when {
                    promptOpen -> DebugVoiceLog.log("wait_cue_skipped reason=guidance_prompt")
                    quiet() -> DebugVoiceLog.log("wait_cue_skipped reason=quiet")
                    cue.code == "visual" -> {
                        if (host.lane.trySend(Queued(RealtimeEvent(clock(), DomainVoiceEvent.WaitCueVisual(true)), epoch.get())).isSuccess) {
                            DebugVoiceLog.log("wait_cue code=visual after_ms=$elapsed")
                        }
                    }
                    host.lane.trySend(Queued(null, epoch.get(), cue.text, turn)).isSuccess ->
                        DebugVoiceLog.log("wait_cue code=${cue.code} after_ms=$elapsed")
                }
                continue
            }
            val next = WaitCues.nextThresholdAfter(elapsed) ?: break
            delay(next - elapsed)
        }
    }

    /** New evidence or a tool call can make a cue due before the sleeping timer's next threshold. */
    private fun rearmWaitCues(turn: WaitCueTurn) {
        val host = cueHost ?: return
        synchronized(cueLock) {
            if (cueTurn !== turn || !turn.live || closing) return
            cueTimer?.cancel()
            cueTimer = host.scope.launch { runWaitCues(turn, host) }
        }
    }

    /**
     * Stops the current turn's clock. [turnScoped]: the reason belongs to this turn (her reply is
     * under way, the turn is done), so no clock may start for it later; otherwise (a cancel of the
     * reply in flight) only a running clock stops.
     */
    private fun cancelWaitCues(reason: String, turnScoped: Boolean = false) {
        val (timer, clearVisual) = synchronized(cueLock) {
            val turn = cueTurn ?: return
            if (!turnScoped && !turn.clockStarted) return
            if (reason == "reply_queued" || reason == "reply_audio") turn.replyUnderway = true
            val clearVisual = turn.visualShown
            if (clearVisual) turn.clearVisual()
            turn.stopped = true
            cueTimer.also { cueTimer = null } to clearVisual
        }
        if (timer?.isActive == true) DebugVoiceLog.log("wait_cue_cancelled reason=$reason")
        timer?.cancel()
        if (clearVisual) hideWorkingMark()
    }

    /** The app handled the driver's utterance itself: no cue for this turn, even if its clock has not started. */
    fun endWaitCueTurn(reason: String) {
        val (ended, timer, clearVisual) = synchronized(cueLock) {
            val turn = cueTurn ?: return
            val ended = !turn.stopped
            val clearVisual = turn.visualShown
            if (clearVisual) turn.clearVisual()
            turn.stopped = true
            Triple(ended, cueTimer.also { cueTimer = null }, clearVisual)
        }
        timer?.cancel()
        if (clearVisual) hideWorkingMark()
        if (ended) DebugVoiceLog.log("wait_cue_cancelled reason=$reason")
    }

    private fun hideWorkingMark() {
        cueHost?.lane?.trySend(Queued(RealtimeEvent(clock(), DomainVoiceEvent.WaitCueVisual(false)), epoch.get()))
    }

    /** A tool result went to the model: the tool is no longer outstanding for [WaitCueTurn]'s turn_done rule. */
    fun onToolResultDelivered() = synchronized(cueLock) { cueTurn?.onToolResultDelivered(); Unit }

    private fun onTurnEvent(payload: DomainVoiceEvent) {
        var rearm: WaitCueTurn? = null
        var done = false
        synchronized(cueLock) {
            val turn = cueTurn ?: return
            when (payload) {
                is DomainVoiceEvent.ToolCall -> { turn.onToolCall(payload.name); rearm = turn }
                DomainVoiceEvent.ResponseStarted -> { turn.onResponseStarted(); rearm = turn }
                // This turn's response ended with nothing audible and no tool to wait for: nothing is coming.
                is DomainVoiceEvent.ResponseDone ->
                    done = turn.responseStarted && !turn.toolOutstanding && !turn.replyUnderway
                else -> Unit
            }
        }
        if (done) cancelWaitCues("turn_done", turnScoped = true)
        rearm?.let(::rearmWaitCues)
    }

    /** Stops what the voice is saying for the current reply and drops its queued words. Non-blocking. */
    fun cancelCurrentReply(reason: String) {
        cancelWaitCues(reason)
        epoch.incrementAndGet()
        val job = speech.getAndSet(null) ?: return
        if (job.children.any { it.isActive }) DebugVoiceLog.log("assistant_voice_cancelled reason=$reason")
        job.cancel()
    }

    fun revoice(upstream: Flow<RealtimeEvent>): Flow<RealtimeEvent> = channelFlow {
        // A session opened: the first reply after start or wake must not pay the voice's setup (P48).
        voice.warmUp()
        closing = false
        val lane = Channel<Queued>(Channel.UNLIMITED)
        cueHost = CueHost(this, lane)
        val worker = launch {
            val workerJob = coroutineContext[Job]
            val segmenter = ClauseSegmenter()
            val replyFailed = AtomicBoolean(false)
            var laneEpoch = epoch.get()
            var tail: Job? = null
            var lastFetch: Job? = null
            var fetchBeforeLast: Job? = null
            val playout = Playout()

            fun failOnce(code: String) {
                if (!replyFailed.compareAndSet(false, true)) return
                DebugVoiceLog.log("assistant_voice_failed code=$code")
                onFailure(code)
            }

            fun speechParent(): Job {
                speech.get()?.takeIf { it.isActive }?.let { return it }
                val current = speech.get()
                if (current != null && current.isActive) return current
                val created = SupervisorJob(workerJob)
                if (speech.compareAndSet(current, created)) return created
                created.cancel()  // lost a race with cancelCurrentReply: never leave it open
                return speechParent()
            }

            /**
             * A wait cue, in chain order: its whole audio goes out as one [DomainVoiceEvent.WaitCueAudio],
             * after what is already queued and before what follows. Its failure is logged and skipped;
             * it never fails the reply around it.
             */
            fun speakCue(text: String, at: Long, turn: WaitCueTurn) {
                val previousEmit = tail
                val parent = speechParent()
                val fetch = async(parent) {
                    try { cuePcm(text) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { null }
                }
                tail = launch(parent) {
                    previousEmit?.join()
                    val pcm = fetch.await()
                    if (pcm == null) { DebugVoiceLog.log("wait_cue_failed"); return@launch }
                    val alive = synchronized(cueLock) { cueTurn === turn && turn.live }
                    if (at != epoch.get() || !alive) return@launch
                    send(RealtimeEvent(clock(), DomainVoiceEvent.WaitCueAudio(Base64.getEncoder().encodeToString(pcm))))
                }
            }

            fun speak(raw: String, at: Long) {
                if (replyFailed.get() || at != epoch.get()) return
                val clause = speakableText(raw) ?: return
                cancelWaitCues("reply_queued", turnScoped = true)
                val previousEmit = tail
                // Lookahead of one: clause N waits for clause N-2's request, so at most two are in flight.
                val gate = fetchBeforeLast
                val buffer = Channel<ByteArray>(Channel.UNLIMITED)
                val parent = speechParent()
                val fetch = launch(parent) {
                    var failure: Throwable? = null
                    try {
                        gate?.join()
                        if (at != epoch.get() || replyFailed.get()) return@launch
                        val started = clock()
                        var first = true
                        voice.synthesize(clause, style()) { pcm ->
                            if (at != epoch.get()) return@synthesize
                            if (first) {
                                first = false
                                DebugVoiceLog.log("assistant_voice_first_audio ms=${clock() - started} chars=${clause.length}")
                            }
                            buffer.send(pcm)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = error
                    } finally {
                        buffer.close(failure)
                    }
                }
                fetchBeforeLast = lastFetch
                lastFetch = fetch
                tail = launch(parent) {
                    previousEmit?.join()
                    if (at != epoch.get() || replyFailed.get()) { fetch.cancel(); return@launch }
                    var first = true
                    try {
                        for (pcm in buffer) {
                            if (at != epoch.get()) { fetch.cancel(); return@launch }
                            val now = clock()
                            if (first) {
                                first = false
                                cancelWaitCues("reply_audio", turnScoped = true)
                                if (playout.hasAudio) DebugVoiceLog.log("assistant_voice_gap_ms ms=${playoutGapMs(now, playout.untilMs)}")
                            }
                            playout.untilMs = playoutEndMs(now, playout.untilMs, pcm.size)
                            playout.hasAudio = true
                            send(RealtimeEvent(now, DomainVoiceEvent.AudioDelta(Base64.getEncoder().encodeToString(pcm))))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: AssistantVoiceException) {
                        failOnce(error.code)
                    } catch (error: Exception) {
                        failOnce("ASSISTANT_VOICE_FAILED")
                    }
                }
            }

            /** Waits until everything queued for the voice so far has been spoken (or cancelled). */
            suspend fun drain() {
                tail?.join()
                tail = null
                lastFetch = null
                fetchBeforeLast = null
            }

            fun newReply() {
                segmenter.reset()
                replyFailed.set(false)
                playout.hasAudio = false
                playout.untilMs = 0.0
            }

            for (item in lane) {
                if (item.epoch != laneEpoch) {
                    // A cancel happened since the last item: its half clause must not leak forward.
                    laneEpoch = item.epoch
                    segmenter.reset()
                }
                val queued = item.event ?: run {
                    val turn = item.turn
                    val alive = turn != null && synchronized(cueLock) { cueTurn === turn && turn.live }
                    // A cue whose turn was cancelled or finished while it sat in the lane is dropped.
                    if (item.epoch == epoch.get() && alive) item.cue?.let { speakCue(it, item.epoch, turn!!) }
                    null
                } ?: continue
                when (val event = queued.payload) {
                    is DomainVoiceEvent.SpeechText -> {
                        if (item.epoch == epoch.get()) {
                            val clauses = segmenter.append(event.text)
                            DebugVoiceLog.log("assistant_voice_text chars=${event.text.length} clauses=${clauses.size}")
                            clauses.forEach { speak(it, item.epoch) }
                        }
                        continue
                    }
                    DomainVoiceEvent.ResponseStarted -> { drain(); newReply() }
                    is DomainVoiceEvent.AppPromptTurn -> when (event.phase) {
                        Phase.OPENED -> { drain(); newReply() }
                        Phase.COMPLETED -> { segmenter.flush()?.let { speak(it, item.epoch) }; drain() }
                        Phase.VOIDED -> { segmenter.reset(); drain() }
                    }
                    DomainVoiceEvent.AudioDone, is DomainVoiceEvent.ResponseDone -> {
                        val rest = segmenter.flush()
                        DebugVoiceLog.log(
                            "assistant_voice_flush at=${if (event == DomainVoiceEvent.AudioDone) "audio_done" else "response_done"} " +
                                "chars=${rest?.length ?: 0}",
                        )
                        rest?.let { speak(it, item.epoch) }
                        drain()
                    }
                    is DomainVoiceEvent.Interrupted, is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> {
                        segmenter.reset()
                        drain()
                    }
                    else -> Unit  // ToolCall, transcripts, work state: forwarded at once, never wait on the voice
                }
                if (queued.payload is DomainVoiceEvent.ResponseDone) {
                    droppedProviderAudio.getAndSet(0).takeIf { it > 0 }?.let {
                        DebugVoiceLog.log("assistant_voice_provider_audio_dropped count=$it")
                    }
                }
                send(queued)
            }
            // The stream ended: let the last words finish, then release the speech parent (a
            // SupervisorJob never completes by itself and would keep this flow open).
            drain()
            speech.getAndSet(null)?.cancel()
        }

        var openPrompt: String? = null
        upstream.collect { event ->
            when (val payload = event.payload) {
                is DomainVoiceEvent.AudioDelta -> { droppedProviderAudio.incrementAndGet(); return@collect }
                DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.SpeechStopped -> {
                    // The driver is talking: a reply follows in a few seconds, so open the voice's
                    // connection now instead of paying its setup on the first clause.
                    if (payload == DomainVoiceEvent.SpeechStarted) voice.warmUp()
                    onDriverSpeech(payload == DomainVoiceEvent.SpeechStarted)
                    send(event)
                    return@collect
                }
                is DomainVoiceEvent.Interrupted -> cancelCurrentReply("interrupted")
                is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> cancelCurrentReply("session")
                is DomainVoiceEvent.AppPromptTurn -> when (payload.phase) {
                    Phase.OPENED -> { openPrompt = payload.promptId; promptOpen = true }
                    Phase.COMPLETED -> { openPrompt = null; promptOpen = false }
                    // Only the open GUIDANCE turn's words: an armed prompt voided before it opened
                    // has said nothing, and must not cut a reply that is still being spoken.
                    Phase.VOIDED -> if (payload.promptId == openPrompt) {
                        openPrompt = null
                        promptOpen = false
                        cancelCurrentReply("guidance_voided")
                    }
                }
                else -> onTurnEvent(payload)
            }
            lane.send(Queued(event, epoch.get()))
        }
        closing = true
        cancelWaitCues("session_end")
        cueHost = null
        lane.close()
        worker.join()
    }

    /** A provider event, or (event null) a wait cue's fixed text to speak in order. */
    private class Queued(val event: RealtimeEvent?, val epoch: Long, val cue: String? = null, val turn: WaitCueTurn? = null)

    private class CueHost(val scope: CoroutineScope, val lane: Channel<Queued>)

    /** A cue's whole audio from the cache, or synthesised once per (text, style) and cached (SPEC-020). */
    private suspend fun cuePcm(text: String): ByteArray {
        val key = text to style()
        cueCache[key]?.let { return it }
        val out = java.io.ByteArrayOutputStream()
        voice.synthesize(text, key.second) { pcm -> out.write(pcm) }
        return out.toByteArray().also { cueCache[key] = it }
    }

    /** Playout end of the current reply's audio on the session clock; emitters run one at a time. */
    private class Playout {
        @Volatile var untilMs: Double = 0.0
        @Volatile var hasAudio: Boolean = false
    }
}

/** Bytes per second of the voice's output: 24 kHz, 16-bit, mono. */
private const val PCM_BYTES_PER_SECOND = 48_000.0

/** Where playout ends after a chunk of [bytes] is queued at [nowMs], given it previously ended at [untilMs]. */
internal fun playoutEndMs(nowMs: Long, untilMs: Double, bytes: Int): Double =
    maxOf(nowMs.toDouble(), untilMs) + bytes * 1000.0 / PCM_BYTES_PER_SECOND

/** Silence the driver hears before a clause's first chunk queued at [nowMs]; 0 when it arrived in time. */
internal fun playoutGapMs(nowMs: Long, untilMs: Double): Long = maxOf(0.0, nowMs - untilMs).toLong()

/**
 * The words the assistant voice may read aloud, or null when nothing is left. Gemini sometimes sends
 * a placeholder instead of words (seen on the emulator 2026-10-03: `<no speech>{pause}`), and the
 * voice must never read markup like that out loud.
 */
internal fun speakableText(raw: String): String? {
    val text = raw.replace(Regex("<[^>]*>"), "").replace(Regex("\\{[^}]*\\}"), "").trim()
    return text.takeIf { t -> t.any { it.isLetterOrDigit() } }
}