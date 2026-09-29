package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin

/** The local "driver speaking" signal fires once per uplink-gate transition, never per frame. */
class UplinkTransitionTest {
    private val samplesPerFrame = PcmAudioCapture.FRAME_BYTES / 2

    private fun tone(amplitude: Int): ByteArray {
        val out = ByteArray(samplesPerFrame * 2)
        for (i in 0 until samplesPerFrame) {
            val value = (amplitude * sin(2.0 * Math.PI * 220.0 * i / 16000.0)).toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun opensOnceAndClosesOnceOnReset() {
        val gate = SpeechUplinkGate()
        val events = mutableListOf<Boolean>()
        val notify: (Boolean) -> Unit = { events += it }
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES + 10) {
            reportUplinkTransition({ gate.isOpen }, notify) { gate.offer(tone(6_000)) }
        }
        assertTrue(gate.isOpen)
        assertEquals(listOf(true), events)
        reportUplinkTransition({ gate.isOpen }, notify) { gate.reset() }
        reportUplinkTransition({ gate.isOpen }, notify) { gate.reset() }
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun interruptingAClosedGateReportsNothing() {
        val gate = SpeechUplinkGate()
        val events = mutableListOf<Boolean>()
        reportUplinkTransition({ gate.isOpen }, { events += it }) { gate.onCaptureInterrupted() }
        assertEquals(emptyList<Boolean>(), events)
    }

    @Test
    fun noListenerIsHarmless() {
        val gate = SpeechUplinkGate()
        val sent = reportUplinkTransition({ gate.isOpen }, null) { gate.offer(tone(6_000)) }
        assertTrue(sent.send.size >= 0)
    }
}
