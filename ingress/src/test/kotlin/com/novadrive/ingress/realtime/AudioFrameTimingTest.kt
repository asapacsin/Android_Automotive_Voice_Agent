package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudioFrameTimingTest {
    @Test
    fun scaledRiseMatchesPer100MsOverElapsedTime() {
        val perFrame = AudioFrameTiming.scaledRiseFactor(1.15, AudioFrameTiming.CAPTURE_FRAME_MS)
        val tenFrames = Math.pow(perFrame, 10.0)
        val oneHundredMsElapsed = Math.pow(1.15, 1.0)
        assertEquals(oneHundredMsElapsed, tenFrames, 1e-9)
    }

    @Test
    fun scaledNoiseAdaptMatchesPer100MsOverElapsedTime() {
        val perFrame = AudioFrameTiming.scaledNoiseAdapt(0.05, AudioFrameTiming.CAPTURE_FRAME_MS)
        assertEquals(0.005, perFrame, 1e-9)
    }

    @Test
    fun heldOutboundMessagesCoverTenSecondsAtTenMs() {
        assertEquals(1000, AudioFrameTiming.heldOutboundMessagesForDuration(10_000))
    }
}
