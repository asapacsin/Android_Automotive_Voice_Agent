package com.novadrive.app.voice

/**
 * The render-to-capture delay AEC3 is told (`set_stream_delay_ms`), from the audio clocks rather
 * than buffer sizes (Astra P2).
 *
 * The delay is how long a sample written to the speaker takes to be heard, plus how long a sample
 * reaching the microphone takes to be read. Both halves come from platform timestamps that pair a
 * frame position with the monotonic time it was presented or captured; each timestamp is
 * extrapolated to *now*, because it can be tens of milliseconds old when read.
 *
 * Fallbacks, in order, each reported as the [Source] used so logs show which evidence a delay
 * rests on:
 * - render: [Source.TIMESTAMP] (`AudioTrack.getTimestamp`) → [Source.HEAD_POSITION]
 *   (`playbackHeadPosition`, not extrapolated) → no estimate (AEC3 keeps its previous delay and
 *   its own internal estimator; 0 ms is never reported as a measurement);
 * - capture: [Source.TIMESTAMP] (`AudioRecord.getTimestamp`, monotonic) → [Source.BUFFER_ESTIMATE]
 *   (half the record buffer, the value used before 2026-09-24).
 *
 * Pure arithmetic: no Android, the caller supplies clocks and `System.nanoTime()`.
 */
object EchoDelayEstimator {
    enum class Source { TIMESTAMP, HEAD_POSITION, BUFFER_ESTIMATE }

    /**
     * The output track. [presentedFrames] is relative to the same origin as [framesWritten];
     * [presentedAtNanos] is the monotonic time it was presented, or null when only the head
     * position was available.
     */
    data class RenderClock(
        val framesWritten: Long,
        val presentedFrames: Long,
        val presentedAtNanos: Long?,
        val sampleRateHz: Int,
    )

    /**
     * The input recorder. [framesRead] counts every frame read since the recorder started, the
     * same base as the recorder's timestamp [stampFrames]; [stampNanos] is when that frame was
     * captured. Null stamp values mean no timestamp was available.
     */
    data class CaptureClock(
        val framesRead: Long,
        val stampFrames: Long?,
        val stampNanos: Long?,
        val sampleRateHz: Int,
        val bufferFallbackMs: Int,
    )

    data class Estimate(
        val streamDelayMs: Int,
        val renderMs: Int,
        val captureMs: Int,
        val renderSource: Source,
        val captureSource: Source,
    )

    /** Milliseconds until the last written frame is heard, and which evidence gave it. */
    fun renderLatency(clock: RenderClock, nowNanos: Long): Pair<Int, Source> {
        if (clock.sampleRateHz <= 0) return 0 to Source.HEAD_POSITION
        val stampedAt = clock.presentedAtNanos
        val presentedNow = if (stampedAt != null) {
            val elapsed = (nowNanos - stampedAt).coerceAtLeast(0)
            clock.presentedFrames + elapsed * clock.sampleRateHz / NANOS_PER_SECOND
        } else {
            clock.presentedFrames
        }
        val pending = (clock.framesWritten - presentedNow.coerceAtMost(clock.framesWritten)).coerceAtLeast(0)
        val ms = (pending * 1000 / clock.sampleRateHz).toInt().coerceIn(0, MAX_DELAY_MS)
        return ms to if (stampedAt != null) Source.TIMESTAMP else Source.HEAD_POSITION
    }

    /** Milliseconds since the latest read frame reached the microphone, and which evidence gave it. */
    fun captureLatency(clock: CaptureClock, nowNanos: Long): Pair<Int, Source> {
        val stampFrames = clock.stampFrames
        val stampNanos = clock.stampNanos
        if (stampFrames == null || stampNanos == null || clock.sampleRateHz <= 0) {
            return clock.bufferFallbackMs.coerceIn(0, MAX_DELAY_MS) to Source.BUFFER_ESTIMATE
        }
        val lastReadCapturedAt = stampNanos + (clock.framesRead - stampFrames) * NANOS_PER_SECOND / clock.sampleRateHz
        val ms = ((nowNanos - lastReadCapturedAt) / NANOS_PER_MS).toInt().coerceIn(0, MAX_DELAY_MS)
        return ms to Source.TIMESTAMP
    }

    fun estimate(render: RenderClock, capture: CaptureClock, resamplerMs: Int, nowNanos: Long): Estimate {
        val (renderMs, renderSource) = renderLatency(render, nowNanos)
        val (captureMs, captureSource) = captureLatency(capture, nowNanos)
        return Estimate(
            streamDelayMs = (renderMs + captureMs + resamplerMs).coerceIn(0, MAX_DELAY_MS),
            renderMs = renderMs,
            captureMs = captureMs,
            renderSource = renderSource,
            captureSource = captureSource,
        )
    }

    /** AEC3's useful range; the previous implementation clamped here too. */
    const val MAX_DELAY_MS = 500

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NANOS_PER_MS = 1_000_000L
}
