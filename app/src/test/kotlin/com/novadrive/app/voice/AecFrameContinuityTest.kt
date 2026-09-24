package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM partition checks for AEC 10 ms framing at 16 kHz and 24 kHz render cadences.
 */
class AecFrameContinuityTest {
    @Test
    fun capturePartitionsEmitEachSampleOnceAt16Khz() {
        val pcm = ByteArray(960) { (it % 256).toByte() } // 30 ms irregular tail
        val emitted = partitionCapture(pcm, sampleRateHz = 16_000)
        assertEquals(pcm.size, emitted.size)
        assertEquals(pcm.toList(), emitted.toList())
        assertTrue(emitted.size % PcmAecFraming.BYTES_PER_FRAME == 0)
    }

    @Test
    fun renderPartitionsEmitEachSampleOnceAt24Khz() {
        val pcm = ByteArray(480) { (it % 256).toByte() } // 10 ms at 24 kHz mono PCM16
        val emitted = partitionRender(pcm, sampleRateHz = 24_000)
        assertEquals(pcm.size, emitted.size)
        assertEquals(pcm.toList(), emitted.toList())
    }

    @Test
    fun partialRenderChunksAreRetainedAcrossCalls() {
        val leftover = mutableListOf<Byte>()
        val halfFrame = ByteArray(PcmAecFraming.BYTES_PER_FRAME / 2) { (it + 1).toByte() }
        val otherHalf = ByteArray(PcmAecFraming.BYTES_PER_FRAME / 2) { (it + 100).toByte() }
        val out1 = partitionRender(halfFrame, sampleRateHz = 16_000, leftover = leftover)
        assertTrue(out1.isEmpty())
        val out2 = partitionRender(otherHalf, sampleRateHz = 16_000, leftover = leftover)
        assertEquals(PcmAecFraming.BYTES_PER_FRAME, out2.size)
    }

    private fun partitionCapture(
        pcm: ByteArray,
        sampleRateHz: Int,
        leftover: MutableList<Byte> = mutableListOf(),
    ): ByteArray {
        leftover.addAll(pcm.toList())
        val out = mutableListOf<Byte>()
        val frameBytes = PcmAecFraming.BYTES_PER_FRAME
        require(sampleRateHz == 16_000)
        while (leftover.size >= frameBytes) {
            val frame = leftover.take(frameBytes)
            repeat(frameBytes) { leftover.removeAt(0) }
            out.addAll(frame)
        }
        return out.toByteArray()
    }

    private fun partitionRender(
        pcm: ByteArray,
        sampleRateHz: Int,
        leftover: MutableList<Byte> = mutableListOf(),
    ): ByteArray {
        leftover.addAll(pcm.toList())
        val out = mutableListOf<Byte>()
        val sourceBlockBytes =
            when (sampleRateHz) {
                16_000 -> PcmAecFraming.BYTES_PER_FRAME
                24_000 -> 480 // 240 samples * 2 bytes, WebRTC 24 kHz block before 16 kHz AEC frame
                else -> error("unsupported rate $sampleRateHz")
            }
        while (leftover.size >= sourceBlockBytes) {
            val block = leftover.take(sourceBlockBytes)
            repeat(sourceBlockBytes) { leftover.removeAt(0) }
            out.addAll(block)
        }
        return out.toByteArray()
    }
}
