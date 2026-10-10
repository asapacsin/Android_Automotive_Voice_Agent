package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * How a streamed reply reaches the driver (OPEN_PROBLEMS P50): the words a provider streams ahead
 * of its audio, the audio the gate may release early, whether the player ran dry, and whether the
 * response stalled. Mechanics only; what may be said is [DriverTurn]'s decision (I-1).
 */

/** Milliseconds of 16-bit mono PCM in a base64 chunk. */
internal fun pcm16Millis(base64: String, sampleRateHz: Int): Long =
    (base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }) * 1_000L / (sampleRateHz * 2L)

/**
 * SPEC-014: the audio a response may play early, from its checked words, and what it has played.
 * Held output leaves from the front while the audio stays within the budget; a subtitle or
 * AudioDone is reached only after the audio before it.
 */
internal class ClauseAudioBudget(private val sampleRateHz: Int) {
    private var budgetMs = 0L
    var releasedMs = 0L
        private set

    val hasRoom: Boolean get() = releasedMs < budgetMs

    fun reset() {
        budgetMs = 0
        releasedMs = 0
    }

    fun grant(checkedChars: Int) {
        budgetMs = checkedChars.toLong() * DriverTurn.CLAUSE_AUDIO_MS_PER_CHAR
    }

    fun take(turn: DriverTurn): List<Any> = turn.takeHeldWhile { event ->
        val ms = (event as? DomainVoiceEvent.AudioDelta)?.let { pcm16Millis(it.pcm16leBase64, sampleRateHz) } ?: 0L
        (releasedMs + ms <= budgetMs).also { if (it) releasedMs += ms }
    }
}

/**
 * The reply's words as the provider streams them, fed to the gate as they come. The final
 * transcript then adds only what the deltas missed; if it disagrees with them, all of it is
 * added, so the end-of-response check sees every word that was said (I-1).
 */
internal class StreamedReplyWords(private val logPrefix: String) {
    private val words = StringBuilder()

    fun reset() = words.setLength(0)

    /** The delta to feed to the gate, or null. */
    fun onDelta(delta: String): String? = delta.takeIf { it.isNotEmpty() }?.also { words.append(it) }

    /** What to feed to the gate for the final transcript, or null for nothing. */
    fun onDone(full: String): String? {
        val streamed = words.toString()
        words.setLength(0)
        return when {
            streamed.isEmpty() -> full
            full.startsWith(streamed) -> full.substring(streamed.length).ifEmpty { null }
            else -> "\n$full".also {
                DebugVoiceLog.log("${logPrefix}_transcript_mismatch streamed=${streamed.length} done=${full.length}")
            }
        }
    }
}

/**
 * Choppiness as the driver hears it (docs/DEMO_REQUIREMENTS.md section 1): reply audio leaves the
 * client after the gate, and the player runs dry when a chunk comes later than the audio before it
 * lasts. A pause inside the voice itself is not counted. Numbers only (I-8).
 */
internal class ReplyUnderrunMonitor(
    private val sampleRateHz: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var startMs = 0L
    @Volatile private var audioMs = 0L

    fun onResponseCreated() {
        startMs = 0L
        audioMs = 0L
    }

    fun onAudio(delta: DomainVoiceEvent.AudioDelta) {
        val now = clock()
        if (startMs == 0L) {
            startMs = now
        } else {
            val dryMs = (now - startMs) - audioMs
            if (dryMs >= UNDERRUN_LOG_MS) {
                DebugVoiceLog.log("reply_underrun ms=$dryMs")
                startMs = now
                audioMs = 0L
            }
        }
        audioMs += pcm16Millis(delta.pcm16leBase64, sampleRateHz)
    }

    private companion object {
        /** Shorter silences than this between chunks are covered by the player's own buffer. */
        const val UNDERRUN_LOG_MS = 150L
    }
}

/**
 * A response that stops producing anything is cancelled and asked for once more. Measured
 * 2026-10-09 on Qwen (emulator, two takes): a reply after a music hand-off produced 「已经」 and then
 * nothing for 15.7 s and 23.1 s, until the driver's next line cancelled it; the same words said on
 * their own never stalled (9/9), so the stall is the server's. A stalled retry is left to finish:
 * it is the turn's last chance, and a slow answer beats none.
 */
internal class ResponseStallWatchdog(
    private val scope: CoroutineScope,
    private val stallMs: Long,
    private val logPrefix: String,
    private val cancel: () -> Unit,
    private val retry: () -> Unit,
) {
    private val seq = AtomicLong(0)
    @Volatile private var lastProgressMs = 0L
    /** The sequence number of the response this watchdog cancelled; only that response's done retries. */
    private val cancelledSeq = AtomicLong(NONE)
    @Volatile private var retriedThisTurn = false

    /** A new driver turn may be retried once again. */
    fun onDriverTurn() {
        retriedThisTurn = false
    }

    /** The response produced something: audio, words, a call, an output item. */
    fun onProgress() {
        lastProgressMs = System.currentTimeMillis()
    }

    /** A response started on a connection; [alive] is false once that connection is gone. */
    fun onResponseCreated(alive: () -> Boolean) = watch(alive)

    fun onResponseDone(alive: () -> Boolean) {
        val done = seq.getAndIncrement()
        if (cancelledSeq.getAndSet(NONE) != done) return
        if (retriedThisTurn) return
        retriedThisTurn = true
        DebugVoiceLog.log("${logPrefix}_stall_retry")
        // After this done is processed: the cancelled response is settled first.
        scope.launch { if (alive()) retry() }
    }

    private fun watch(alive: () -> Boolean) {
        val current = seq.get()
        lastProgressMs = System.currentTimeMillis()
        scope.launch {
            while (alive() && seq.get() == current) {
                delay(minOf(CHECK_MS, stallMs / 4))
                val quietMs = System.currentTimeMillis() - lastProgressMs
                if (seq.get() != current || !alive()) return@launch
                if (quietMs >= stallMs) {
                    DebugVoiceLog.log("${logPrefix}_response_stalled quiet_ms=$quietMs retried=$retriedThisTurn")
                    // Tied to this response: a done that already passed must not retry the next one.
                    if (!retriedThisTurn && cancelledSeq.compareAndSet(NONE, current)) {
                        if (seq.get() != current) { cancelledSeq.compareAndSet(current, NONE); return@launch }
                        cancel()
                    }
                    return@launch
                }
            }
        }
    }

    companion object {
        /**
         * Longer than any measured quiet stretch inside a healthy Qwen or Baidu response: the first
         * event (output_item.added) comes with response.created, words follow within 1 s, and audio
         * has come up to 3.1 s later under load (2026-10-09), with words streaming meanwhile.
         */
        const val STALL_MS = 5_000L
        private const val CHECK_MS = 500L
        private const val NONE = -1L

    }
}
