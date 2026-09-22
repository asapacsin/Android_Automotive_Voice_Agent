package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechUplinkGateDiagnosticsTest {
    @Test
    fun lastFrameRmsTracksMostRecentOffer() {
        val gate = SpeechUplinkGate()
        val quiet = ByteArray(3200)
        gate.offer(quiet)
        val quietRms = gate.lastFrameRms
        val loud = ByteArray(3200) { if (it % 2 == 0) 0x7f.toByte() else 0x00 }
        gate.offer(loud)
        assertTrue(gate.lastFrameRms > quietRms)
    }
}
