package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.SpeakingStyle
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.SystemSessionClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ADR-016: Gemini stays the agent and one assistant voice speaks. This sits between the provider's
 * event stream and the session: the provider's own [DomainVoiceEvent.AudioDelta] is discarded, and
 * every released [DomainVoiceEvent.SpeechText] is cut into clauses and spoken through [voice] as
 * new AudioDelta events. Nothing here judges claims: SpeechText reaches it only after the claim gate
 * (DriverTurn) released it, exactly as the provider audio did.
 *
 * Ordering: one lane keeps the provider's event order, so a reply's AudioDelta still sits between its
 * ResponseStarted and its AudioDone/ResponseDone (the session drops audio outside an open reply).
 * Only SpeechStarted/SpeechStopped bypass the lane (barge-in timing). Interrupted, Error and Closed
 * cancel the clause being synthesised at once and discard queued speech; they still keep their place.
 *
 * Failure (ADR-016 §6): the subtitle still shows, the rest of that reply is not spoken, the driver is
 * told once per reply through [onFailure]. Never a fallback to the provider's voice (a second voice).
 * Logs codes, counts and timings only — never the text (I-8).
 */
class AssistantVoiceRevoicer(
    private val voice: AssistantVoice,
    private val style: () -> SpeakingStyle,
    private val onFailure: (String) -> Unit = AssistantVoiceNotices::report,
    private val clock: () -> Long = SystemSessionClock::nowMs,
) {
    fun revoice(upstream: Flow<RealtimeEvent>): Flow<RealtimeEvent> = channelFlow {
        val lane = Channel<Queued>(Channel.UNLIMITED)
        val epoch = AtomicLong(0)
        val synthesis = AtomicReference<Job?>(null)

        fun cancelSpeech(reason: String) {
            epoch.incrementAndGet()
            synthesis.get()?.let { job ->
                if (job.isActive) {
                    job.cancel()
                    DebugVoiceLog.log("assistant_voice_cancelled reason=$reason")
                }
            }
        }

        val worker = launch {
            val segmenter = ClauseSegmenter()
            var replyFailed = false
            var droppedAudio = 0

            fun failOnce(code: String) {
                if (replyFailed) return
                replyFailed = true
                DebugVoiceLog.log("assistant_voice_failed code=$code")
                onFailure(code)
            }

            suspend fun speak(clause: String, at: Long) {
                if (replyFailed || at != epoch.get()) return
                var failure: String? = null
                val job = launch {
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
                        failure = error.code
                    } catch (error: Exception) {
                        failure = "ASSISTANT_VOICE_FAILED"
                    }
                }
                synthesis.set(job)
                if (at != epoch.get()) job.cancel()  // an interrupt landed between the check and here
                job.join()
                // join() orders the child's write before this read.
                failure?.let(::failOnce)
            }

            suspend fun flush(at: Long) {
                segmenter.flush()?.let { speak(it, at) }
            }

            fun newReply() {
                segmenter.reset()
                replyFailed = false
            }

            for (item in lane) {
                val event = item.event.payload
                when (event) {
                    is DomainVoiceEvent.SpeechText -> {
                        if (item.epoch == epoch.get()) segmenter.append(event.text).forEach { speak(it, item.epoch) }
                        continue
                    }
                    is DomainVoiceEvent.AudioDelta -> {
                        droppedAudio++
                        continue
                    }
                    DomainVoiceEvent.ResponseStarted -> {
                        flush(item.epoch)
                        newReply()
                    }
                    is DomainVoiceEvent.AppPromptTurn -> {
                        flush(item.epoch)
                        if (event.phase == DomainVoiceEvent.AppPromptTurn.Phase.OPENED) newReply()
                    }
                    DomainVoiceEvent.AudioDone, is DomainVoiceEvent.ResponseDone -> flush(item.epoch)
                    is DomainVoiceEvent.Interrupted, is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> newReply()
                    else -> Unit
                }
                if (event is DomainVoiceEvent.ResponseDone && droppedAudio > 0) {
                    DebugVoiceLog.log("assistant_voice_provider_audio_dropped count=$droppedAudio")
                    droppedAudio = 0
                }
                send(item.event)
            }
        }

        upstream.collect { event ->
            when (event.payload) {
                DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.SpeechStopped -> send(event)
                is DomainVoiceEvent.Interrupted -> { cancelSpeech("interrupted"); lane.send(Queued(event, epoch.get())) }
                is DomainVoiceEvent.Error, DomainVoiceEvent.Closed -> { cancelSpeech("session"); lane.send(Queued(event, epoch.get())) }
                else -> lane.send(Queued(event, epoch.get()))
            }
        }
        lane.close()
        worker.join()
    }

    private class Queued(val event: RealtimeEvent, val epoch: Long)
}
