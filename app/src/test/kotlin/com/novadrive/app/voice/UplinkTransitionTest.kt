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
    fun noListenerStillReturnsTheGatesDecision() {
        val gate = SpeechUplinkGate()
        var decision: SpeechUplinkGate.Decision? = null
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES) {
            decision = reportUplinkTransition({ gate.isOpen }, null) { gate.offer(tone(6_000)) }
        }
        assertTrue(gate.isOpen)
        // The onset frame flushes the pre-roll: the wrapper must hand back exactly what the gate sent.
        assertTrue(decision!!.send.isNotEmpty())
    }

    @Test
    fun anInterruptionMidUtteranceClosesThenANewOnsetReopens() {
        val gate = SpeechUplinkGate()
        val events = mutableListOf<Boolean>()
        val notify: (Boolean) -> Unit = { events += it }
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES + 5) {
            reportUplinkTransition({ gate.isOpen }, notify) { gate.offer(tone(6_000)) }
        }
        reportUplinkTransition({ gate.isOpen }, notify) { gate.onCaptureInterrupted() }
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES + 5) {
            reportUplinkTransition({ gate.isOpen }, notify) { gate.offer(tone(6_000)) }
        }
        assertEquals(listOf(true, false, true), events)
    }
}
