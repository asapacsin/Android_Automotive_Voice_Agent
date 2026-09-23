package com.novadrive.app.voice

/**
 * Splits fixed-duration PCM16 mono frames into WebRTC AEC3 10 ms chunks.
 */
object PcmAecFraming {
    const val AEC_SAMPLE_RATE_HZ = 16_000
    const val FRAME_MS = 10
    const val SAMPLES_PER_FRAME = AEC_SAMPLE_RATE_HZ / (1000 / FRAME_MS)
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2

    fun splitInto10MsFrames(pcm16le: ByteArray, sampleRateHz: Int = AEC_SAMPLE_RATE_HZ): List<ByteArray> {
        require(sampleRateHz == AEC_SAMPLE_RATE_HZ) { "AEC framing expects 16 kHz PCM" }
        require(pcm16le.size % BYTES_PER_FRAME == 0) { "PCM length must be a multiple of $BYTES_PER_FRAME bytes" }
        if (pcm16le.isEmpty()) return emptyList()
        return pcm16le.toList().chunked(BYTES_PER_FRAME).map { it.toByteArray() }
    }

    fun mergeFrames(frames: List<ByteArray>): ByteArray {
        if (frames.isEmpty()) return ByteArray(0)
        val out = ByteArray(frames.sumOf { it.size })
        var offset = 0
        for (frame in frames) {
            frame.copyInto(out, offset)
            offset += frame.size
        }
        return out
    }
}
