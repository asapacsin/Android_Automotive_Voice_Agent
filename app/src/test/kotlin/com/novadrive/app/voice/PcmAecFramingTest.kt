package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PcmAecFramingTest {
    @Test
    fun captureFrameSplitsIntoTen10MsChunks() {
        val frame = ByteArray(PcmAudioCapture.FRAME_BYTES) { (it % 256).toByte() }
        val chunks = PcmAecFraming.splitInto10MsFrames(frame)
        assertEquals(10, chunks.size)
        chunks.forEach { chunk -> assertEquals(PcmAecFraming.BYTES_PER_FRAME, chunk.size) }
        assertEquals(frame.size, PcmAecFraming.mergeFrames(chunks).size)
    }

    @Test
    fun mergePreservesByteOrder() {
        val chunks =
            listOf(
                byteArrayOf(0x01, 0x00),
                byteArrayOf(0x02, 0x00),
            )
        assertEquals(byteArrayOf(0x01, 0x00, 0x02, 0x00).toList(), PcmAecFraming.mergeFrames(chunks).toList())
    }
}
