package com.novadrive.app.voice

import com.novadrive.ingress.realtime.AudioFrameTiming

/**
 * Adaptive input gain for the live microphone, applied before audio is sent to Baidu.
 *
 * Measured on device 2026-09-17: Baidu Flex server VAD (threshold 0.62) ignores speech peaking
 * at about 1900 (-25 dBFS) and below, while the VOICE_COMMUNICATION path with noise suppression
 * delivers normal speech at arm's length at roughly 1300–1900. Quiet turns therefore got no
 * response at all. Harness measurement of the floor: heard at a peak of ~3800, ignored at ~2700. Lowering the VAD threshold would also admit noise and our own echo, so the
 * quiet speech is lifted instead:
 *
 * - start at [maxGain], so the first quiet word of a turn is already lifted (a loud first frame
 *   lowers the gain on that same frame, before it is applied, so nothing clips);
 * - boost towards [targetPeak], by at most [maxGain];
 * - frames at or below [noiseFloor] are silence: the gain recovers towards [maxGain] there, so the
 *   next quiet sentence after a loud one is lifted too (silence * maxGain stays far below the VAD floor);
 * - lower the gain immediately when a frame would exceed the target (no clipping of loud speech),
 *   raise it gradually (no pumping on every syllable);
 * - hard-limit to the PCM16 range as a last resort.
 */
class MicInputGain(
    private val maxGain: Double = DEFAULT_MAX_GAIN,
    private val targetPeak: Int = 12_000,
    private val noiseFloor: Int = 250,
    private val riseFactor: Double = 1.15,
) {
    var gain: Double = maxGain
        private set

    fun process(
        frame: ByteArray,
        frameMs: Int = AudioFrameTiming.CAPTURE_FRAME_MS,
    ): ByteArray {
        val peak = peakAbs(frame)
        val scaledRise = AudioFrameTiming.scaledRiseFactor(riseFactor, frameMs)
        gain = if (peak > noiseFloor) {
            val desired = (targetPeak.toDouble() / peak).coerceIn(1.0, maxGain)
            if (desired < gain) desired else minOf(desired, gain * scaledRise)
        } else {
            minOf(maxGain, gain * scaledRise)
        }
        if (gain <= 1.0) return frame
        val out = ByteArray(frame.size)
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i].toInt() and 0xff) or (frame[i + 1].toInt() shl 8)).toShort().toInt()
            // Symmetric limit: -32768 would be one step louder than +32767.
            val scaled = (sample * gain).toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = (scaled and 0xff).toByte()
            out[i + 1] = ((scaled shr 8) and 0xff).toByte()
            i += 2
        }
        if (frame.size % 2 == 1) out[frame.size - 1] = frame[frame.size - 1]
        return out
    }

    fun reset() {
        gain = maxGain
    }

    companion object {
        /**
         * 3x lifts 1300-1900 speech to 3900-5700 (above the ~3800 floor). 4x also lifted moderate
         * noise over the floor and produced phantom turns in a normal room (measured 2026-09-17).
         */
        const val DEFAULT_MAX_GAIN = 3.0
    }
}
