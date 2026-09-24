package com.novadrive.app.voice

import com.novadrive.app.voice.EchoDelayEstimator.CaptureClock
import com.novadrive.app.voice.EchoDelayEstimator.RenderClock
import com.novadrive.app.voice.EchoDelayEstimator.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Astra P2: AEC3's stream delay from the audio clocks, with each fallback named. */
class EchoDelayEstimatorTest {
    private val ms = 1_000_000L
    private val now = 10_000 * ms

    @Test
    fun anOldRenderTimestampIsExtrapolatedToNow() {
        // 24 kHz: 4800 frames written, 2400 presented 20 ms ago -> 480 more presented since.
        val clock = RenderClock(framesWritten = 4_800, presentedFrames = 2_400, presentedAtNanos = now - 20 * ms, sampleRateHz = 24_000)
        assertEquals(80 to Source.TIMESTAMP, EchoDelayEstimator.renderLatency(clock, now))
    }

    @Test
    fun withoutATimestampTheHeadPositionIsTakenAsIs() {
        val clock = RenderClock(framesWritten = 4_800, presentedFrames = 2_400, presentedAtNanos = null, sampleRateHz = 24_000)
        assertEquals(100 to Source.HEAD_POSITION, EchoDelayEstimator.renderLatency(clock, now))
    }

    @Test
    fun extrapolationNeverPresentsMoreThanWasWritten() {
        val clock = RenderClock(framesWritten = 4_800, presentedFrames = 4_700, presentedAtNanos = now - 500 * ms, sampleRateHz = 24_000)
        assertEquals(0 to Source.TIMESTAMP, EchoDelayEstimator.renderLatency(clock, now), "drained, not negative")
    }

    @Test
    fun captureLatencyIsTheAgeOfTheLatestFrameRead() {
        // 16 kHz: frame 16000 was captured 1 s before now minus 30 ms; 320 more frames (20 ms) read since.
        val clock = CaptureClock(
            framesRead = 16_320, stampFrames = 16_000, stampNanos = now - 50 * ms,
            sampleRateHz = 16_000, bufferFallbackMs = 40,
        )
        assertEquals(30 to Source.TIMESTAMP, EchoDelayEstimator.captureLatency(clock, now))
    }

    @Test
    fun withoutACaptureTimestampHalfTheBufferIsUsedAndSaidSo() {
        val clock = CaptureClock(framesRead = 16_320, stampFrames = null, stampNanos = null, sampleRateHz = 16_000, bufferFallbackMs = 40)
        assertEquals(40 to Source.BUFFER_ESTIMATE, EchoDelayEstimator.captureLatency(clock, now))
    }

    @Test
    fun theStreamDelayIsRenderPlusCapturePlusResamplerWithinAec3sRange() {
        val render = RenderClock(4_800, 2_400, now - 20 * ms, 24_000)
        val capture = CaptureClock(16_320, 16_000, now - 50 * ms, 16_000, 40)
        val estimate = EchoDelayEstimator.estimate(render, capture, resamplerMs = 1, nowNanos = now)
        assertEquals(111, estimate.streamDelayMs)
        assertEquals(Source.TIMESTAMP, estimate.renderSource)
        assertEquals(Source.TIMESTAMP, estimate.captureSource)

        val huge = EchoDelayEstimator.estimate(
            RenderClock(48_000, 0, null, 24_000), capture, resamplerMs = 1, nowNanos = now,
        )
        assertEquals(EchoDelayEstimator.MAX_DELAY_MS, huge.streamDelayMs)
    }

    @Test
    fun aCaptureStampNewerThanNowIsNotANegativeDelay() {
        val clock = CaptureClock(framesRead = 16_000, stampFrames = 16_000, stampNanos = now + 5 * ms, sampleRateHz = 16_000, bufferFallbackMs = 40)
        assertEquals(0 to Source.TIMESTAMP, EchoDelayEstimator.captureLatency(clock, now))
    }
}
