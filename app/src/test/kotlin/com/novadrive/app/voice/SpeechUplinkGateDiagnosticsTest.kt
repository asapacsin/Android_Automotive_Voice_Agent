package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechUplinkGateDiagnosticsTest {
    @Test
    fun lastFrameRmsTracksMostRecentOffer() {
        val gate = SpeechUplinkGate()
        val quiet = ByteArray(PcmAudioCapture.FRAME_BYTES)
        gate.offer(quiet)
        val quietRms = gate.lastFrameRms
        val loud = ByteArray(PcmAudioCapture.FRAME_BYTES) { if (it % 2 == 0) 0x7f.toByte() else 0x00 }
        gate.offer(loud)
        assertTrue(gate.lastFrameRms > quietRms)
    }
}
