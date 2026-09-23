package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowLatencyPlaybackBufferTest {
    @Test
    fun initialBufferIsAtLeastTenMs() {
        val manager = LowLatencyPlaybackBuffer(sampleRateHz = 24_000, platformMinBufferBytes = 100)
        val bytes = manager.initialBufferBytes()
        assertTrue(bytes >= 24_000 * 2 / 100)
        assertEquals((bytes * 1000) / (24_000 * 2), manager.bufferMs)
    }

    @Test
    fun resamplerDelayMatchesWebRtcKernel() {
        assertEquals(0, VoicePlayoutDelay.resamplerDelayMs(16_000))
        assertEquals(0, VoicePlayoutDelay.resamplerDelayMs(24_000))
    }
}
