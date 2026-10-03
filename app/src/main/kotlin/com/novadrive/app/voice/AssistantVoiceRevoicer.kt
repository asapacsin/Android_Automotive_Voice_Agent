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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.Base64
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
 */
class AssistantVoiceRevoicer(
    private val voice: AssistantVoice,
    private val style: () -> SpeakingStyle,
    private val onFailure: (String) -> Unit = AssistantVoiceNotices::report,
    private val clock: () -> Long = SystemSessionClock::nowMs,
) {
    private val epoch = AtomicLong(0)
    private val speech = AtomicReference<Job?>(null)
    private val droppedProviderAudio = AtomicInteger(0)

    /** Stops what the voice is saying for the current reply and drops its queued words. Non-blocking. */
    fun cancelCurrentReply(reason: String) {
        epoch.incrementAndGet()
        val job = speech.getAndSet(null) ?: return
        if (job.children.any { it.isActive }) DebugVoiceLog.log("assistant_voice_cancelled reason=$reason")
        job.cancel()
    }

    fun revoice(upstream: Flow<RealtimeEvent>): Flow<RealtimeEvent> = channelFlow {
        val lane = Channel<Queued>(Channel.UNLIMITED)
        val worker = launch {
            val workerJob = coroutineContext[Job]
            val segmenter = ClauseSegmenter()
            val replyFailed = AtomicBoolean(false)
            var laneEpoch = epoch.get()
            var tail: Job? = null

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

            fun speak(raw: String, at: Long) {
                if (replyFailed.get() || at != epoch.get()) return
                val clause = speakableText(raw) ?: return
                val previous = tail
                tail = launch(speechParent()) {
                    previous?.join()
                    if (at != epoch.get() || replyFailed.get()) return@launch
                    val started = clock()
                    var first = true
                    try {
                        voice.synthesize(clause, style()) { pcm ->
                            if (at != epoch.get()) return@synthesize
                            if (first) {
                                first = false
                                DebugVoiceLog.log("assistant_voice_first_audio ms=${clock() - started} chars=${clause.length}")
                            }
                            send(RealtimeEvent(clock(), DomainVoiceEvent.AudioDelta(Base64.getEncoder().encodeToString(pcm))))
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
            }

            fun newReply() {
                segmenter.reset()
                replyFailed.set(false)
            }

            for (item in lane) {
                if (item.epoch != laneEpoch) {
                    // A cancel happened since the last item: its half clause must not leak forward.
                    laneEpoch = item.epoch
                    segmenter.reset()
                }
                when (val event = item.event.payload) {
                    is DomainVoiceEvent.SpeechText -> {
                        if (item.epoch == epoch.get()) segmenter.append(event.text).forEach { speak(it, item.epoch) }
                        continue
                    }
                    DomainVoiceEvent.ResponseStarted -> { drain(); newReply() }
                    is DomainVoiceEvent.AppPromptTurn -> when (event.phase) {
                        Phase.OPENED -> { drain(); newReply() }
                        Phase.COMPLETED -> { segmenter.flush()?.let { speak(it, item.epoch) }; drain() }
                        Phase.VOIDED -> { segmenter.reset(); drain() }
                    }
                    DomainVoiceEvent.AudioDone, is DomainVoiceEvent.ResponseDone -> {
                        segmenter.flush()?.let { speak(it, item.epoch) }
                        drain()
                    }
                    is DomainVoiceEvent.Interrupted, is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> {
                        segmenter.reset()
                        drain()
                    }
                    else -> Unit  // ToolCall, transcripts, work state: forwarded at once, never wait on the voice
                }
                if (item.event.payload is DomainVoiceEvent.ResponseDone) {
                    droppedProviderAudio.getAndSet(0).takeIf { it > 0 }?.let {
                        DebugVoiceLog.log("assistant_voice_provider_audio_dropped count=$it")
                    }
                }
                send(item.event)
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
                    send(event)
                    return@collect
                }
                is DomainVoiceEvent.Interrupted -> cancelCurrentReply("interrupted")
                is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> cancelCurrentReply("session")
                is DomainVoiceEvent.AppPromptTurn -> when (payload.phase) {
                    Phase.OPENED -> openPrompt = payload.promptId
                    Phase.COMPLETED -> openPrompt = null
                    // Only the open GUIDANCE turn's words: an armed prompt voided before it opened
                    // has said nothing, and must not cut a reply that is still being spoken.
                    Phase.VOIDED -> if (payload.promptId == openPrompt) {
                        openPrompt = null
                        cancelCurrentReply("guidance_voided")
                    }
                }
                else -> Unit
            }
            lane.send(Queued(event, epoch.get()))
        }
        lane.close()
        worker.join()
    }

    private class Queued(val event: RealtimeEvent, val epoch: Long)
}

/**
 * The words the assistant voice may read aloud, or null when nothing is left. Gemini sometimes sends
 * a placeholder instead of words (seen on the emulator 2026-10-03: `<no speech>{pause}`), and the
 * voice must never read markup like that out loud.
 */
internal fun speakableText(raw: String): String? {
    val text = raw.replace(Regex("<[^>]*>"), "").replace(Regex("\\{[^}]*\\}"), "").trim()
    return text.takeIf { t -> t.any { it.isLetterOrDigit() } }
}