package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * P47 (2026-10-15): a steady noise bed that is already there when the gate starts - a fan at
 * -32 dBFS, RMS ~820, against a starting threshold of ~176. The gate learned the floor only from
 * frames it judged quiet, so every frame was "speech": it opened on the fan and never closed, the
 * server got one endless segment and missed 8 of 19 tryout lines, and the app believed the driver
 * never stopped talking.
 */
class SpeechUplinkGateNoiseBedTest {
    private val samplesPerFrame = PcmAudioCapture.FRAME_BYTES / 2
    private val frameMs = SpeechUplinkGate.DEFAULT_FRAME_MS
    private val random = Random(47)

    private fun pcm(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            val c = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (c and 0xff).toByte()
            out[i * 2 + 1] = ((c shr 8) and 0xff).toByte()
        }
        return out
    }

    /** Gaussian noise at about [rms]. */
    private fun noise(rms: Int) = pcm(IntArray(samplesPerFrame) { (random.nextGaussian() * rms).toInt() })

    /** Noise plus a 220 Hz tone of [amplitude] - speech over the fan. */
    private fun speechOver(rms: Int, amplitude: Int) = pcm(
        IntArray(samplesPerFrame) { i ->
            (random.nextGaussian() * rms).toInt() + (amplitude * sin(2.0 * Math.PI * 220.0 * i / 16000.0)).toInt()
        },
    )

    private fun Random.nextGaussian(): Double {
        val u = 1.0 - nextDouble()
        val v = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
    }

    private fun frames(ms: Int) = ms / frameMs

    @Test
    fun aFanThatIsOnFromTheStartIsLearnedAndTheGateCloses() {
        val gate = SpeechUplinkGate()
        repeat(frames(SpeechUplinkGate.MIN_TRACK_MS + SpeechUplinkGate.HANGOVER_MS + 500)) { gate.offer(noise(820)) }
        assertFalse(gate.isOpen, "a steady fan is not a driver who never stops talking")
        assertTrue(gate.voicedThreshold > 820, "the floor learned the fan: threshold ${gate.voicedThreshold}")
        repeat(frames(5_000)) { gate.offer(noise(820)) }
        assertFalse(gate.isOpen, "and it stays closed while the fan runs")
    }

    @Test
    fun speechOverTheFanOpensTheGateAndItClosesWhenTheDriverStops() {
        val gate = SpeechUplinkGate()
        repeat(frames(SpeechUplinkGate.MIN_TRACK_MS + 2_000)) { gate.offer(noise(820)) }
        assertFalse(gate.isOpen)
        var opened = false
        repeat(frames(1_500)) { opened = opened || gate.offer(speechOver(820, 3_300)).send.isNotEmpty() }
        assertTrue(opened && gate.isOpen, "a driver ~9 dB over the fan is heard")
        var closedAfterMs = -1
        for (i in 1..frames(3_000)) {
            gate.offer(noise(820))
            if (!gate.isOpen) { closedAfterMs = i * frameMs; break }
        }
        assertTrue(closedAfterMs in 1..(SpeechUplinkGate.HANGOVER_MS + 200), "closed ${closedAfterMs} ms after the driver stopped")
    }

    @Test
    fun aLongSentenceInAQuietRoomDoesNotRaiseTheFloor() {
        val gate = SpeechUplinkGate()
        repeat(frames(1_000)) { gate.offer(noise(40)) }
        val before = gate.voicedThreshold
        // Gap-free voicing shorter than the window: the minimum still includes the quiet room.
        repeat(frames(SpeechUplinkGate.MIN_TRACK_MS - 200)) { gate.offer(speechOver(40, 6_000)) }
        assertEquals(before, gate.voicedThreshold, "speech must not teach the floor")
        assertTrue(gate.isOpen)
    }

    @Test
    fun aFanAlreadyRunningIsLearnedBeforeTheFirstLine() {
        // The noisy tryout's first line came 0.8 s after the session opened.
        val gate = SpeechUplinkGate()
        repeat(frames(SpeechUplinkGate.MIN_WARMUP_MS + SpeechUplinkGate.HANGOVER_MS + 300)) { gate.offer(noise(820)) }
        assertFalse(gate.isOpen, "closed again ${SpeechUplinkGate.MIN_WARMUP_MS + SpeechUplinkGate.HANGOVER_MS + 300} ms in")
    }

    @Test
    fun resetForgetsTheLearnedFan() {
        val gate = SpeechUplinkGate()
        repeat(frames(SpeechUplinkGate.MIN_TRACK_MS + 500)) { gate.offer(noise(820)) }
        assertTrue(gate.voicedThreshold > 820)
        gate.reset()
        assertEquals(maxOf(SpeechUplinkGate.ABSOLUTE_RMS_FLOOR, (SpeechUplinkGate.INITIAL_NOISE_RMS * SpeechUplinkGate.NOISE_FACTOR).toInt()), gate.voicedThreshold)
    }
}
