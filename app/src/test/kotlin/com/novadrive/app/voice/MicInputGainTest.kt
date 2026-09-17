package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MicInputGainTest {
    private fun frame(peak: Int, samples: Int = 1600): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // Alternating ±peak so the frame's peak is exactly `peak`.
            val v = if (i % 2 == 0) peak else -peak
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun quietSpeechIsLiftedAboveTheMeasuredVadFloor() {
        val gain = MicInputGain()
        var out = frame(1900)
        repeat(20) { out = gain.process(frame(1900)) }
        // Device floor: ~3800 heard, ~2700 ignored. 1900 * 3 = 5700.
        assertTrue(peakAbs(out) >= 5_000, "peak=${peakAbs(out)}")
        assertEquals(MicInputGain.DEFAULT_MAX_GAIN, gain.gain, 1e-9)
    }

    @Test
    fun theFirstQuietFrameIsAlreadyLifted() {
        val gain = MicInputGain()
        val out = gain.process(frame(1900))
        assertTrue(peakAbs(out) >= 5_000, "first frame peak=${peakAbs(out)}")
    }

    @Test
    fun afterLoudSpeechGainRisesGraduallyNotInOneFrame() {
        val gain = MicInputGain()
        gain.process(frame(12_000))
        assertEquals(1.0, gain.gain, 1e-9)
        gain.process(frame(1900))
        assertTrue(gain.gain < 1.2, "gain=${gain.gain}")
    }

    @Test
    fun silenceAfterLoudSpeechRestoresFullGainForTheNextQuietSentence() {
        val gain = MicInputGain()
        repeat(5) { gain.process(frame(15_000)) }
        assertEquals(1.0, gain.gain, 1e-9)
        repeat(12) { gain.process(frame(100)) } // ~1.2 s of silence
        assertEquals(MicInputGain.DEFAULT_MAX_GAIN, gain.gain, 1e-9)
    }

    @Test
    fun aLoudFirstFrameIsNotClipped() {
        val gain = MicInputGain()
        val out = gain.process(frame(20_000))
        assertTrue(peakAbs(out) <= 20_000, "peak=${peakAbs(out)}")
    }

    @Test
    fun loudSpeechIsNeverBoostedOrClipped() {
        val gain = MicInputGain()
        val loud = frame(19_000)
        repeat(10) { assertArrayEquals(loud, gain.process(loud)) }
        assertEquals(1.0, gain.gain, 1e-9)
    }

    @Test
    fun gainDropsImmediatelyWhenInputGetsLoud() {
        val gain = MicInputGain()
        repeat(20) { gain.process(frame(1500)) }
        val out = gain.process(frame(9_000))
        assertTrue(peakAbs(out) <= 12_000, "no overshoot: ${peakAbs(out)}")
        assertTrue(peakAbs(out) >= 9_000, "not attenuated below input: ${peakAbs(out)}")
    }

    @Test
    fun roomNoiseStaysBelowTheVadFloorEvenAtFullGain() {
        val gain = MicInputGain()
        repeat(50) { assertTrue(peakAbs(gain.process(frame(120))) < 1_900) }
    }

    @Test
    fun silenceAfterSpeechStaysBelowTheVadFloor() {
        val gain = MicInputGain()
        repeat(20) { gain.process(frame(1900)) }
        val out = gain.process(frame(200))
        // Held gain on noise: 200 * 3 = 600, far under the ~2700 level Baidu ignores.
        assertTrue(peakAbs(out) < 1_900, "peak=${peakAbs(out)}")
    }

    @Test
    fun outputNeverExceedsPcm16Range() {
        val gain = MicInputGain(maxGain = 4.0, targetPeak = 40_000)
        repeat(20) { gain.process(frame(9_000)) }
        val out = gain.process(frame(9_000))
        assertTrue(peakAbs(out) <= Short.MAX_VALUE)
    }

    @Test
    fun resetReturnsToFullGain() {
        val gain = MicInputGain()
        repeat(5) { gain.process(frame(15_000)) }
        gain.reset()
        assertEquals(MicInputGain.DEFAULT_MAX_GAIN, gain.gain, 1e-9)
    }
}
