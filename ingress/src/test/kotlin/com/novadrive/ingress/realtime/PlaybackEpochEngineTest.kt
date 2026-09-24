package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackEpochEngineTest {
    private val sliceBytes = 320 // 10 ms at 16 kHz mono PCM16

    @Test
    fun flushInvalidatesOldEpochBeforeNewEpochIsAccepted() {
        val engine = PlaybackEpochEngine(sampleRateHz = 16_000)
        engine.resetForStart(epoch = 1)
        engine.enqueue(byteArrayOf(1, 2, 3, 4), epoch = 1, running = true)
        engine.flush(epoch = 2)
        assertEquals(PlaybackEnqueueResult.RejectedEpoch, engine.enqueue(byteArrayOf(9), epoch = 1, running = true))
        assertEquals(PlaybackEnqueueResult.Accepted, engine.enqueue(byteArrayOf(5, 6), epoch = 2, running = true))
    }

    @Test
    fun shortWriteRetainsSuffixAndRenderReferenceIsExactPrefix() {
        val engine = PlaybackEpochEngine()
        engine.resetForStart(epoch = 1)
        val chunk = ByteArray(sliceBytes) { (it + 1).toByte() }
        engine.enqueue(chunk, epoch = 1, running = true)
        val slice = ByteArray(sliceBytes)
        assertTrue(engine.stagePendingSlice(sliceBytes, slice))
        val half = sliceBytes / 2
        val accepted = engine.acceptShortWrite(half)
        assertNotNull(accepted)
        assertEquals(half, accepted!!.size)
        assertArrayEquals(chunk.copyOfRange(0, half), accepted)
        assertNotNull(engine.pendingSlice)
        assertEquals(half, engine.pendingSliceOffset)
        val second = engine.acceptShortWrite(sliceBytes - half)
        assertNotNull(second)
        assertNull(engine.pendingSlice)
        assertEquals(2, engine.renderReference.size)
        assertEquals(sliceBytes, engine.renderReference.sumOf { it.size })
    }

    @Test
    fun completionPadsOnlyFinalPartialFrameAndRejectsLateEpochPcm() {
        val engine = PlaybackEpochEngine()
        engine.resetForStart(epoch = 3)
        val tail = ByteArray(100) { 0x55 }
        engine.enqueue(tail, epoch = 3, running = true)
        engine.complete(epoch = 3)
        val slice = ByteArray(sliceBytes)
        assertTrue(engine.fillSlice(sliceBytes, slice))
        assertTrue(slice.take(100).all { it == 0x55.toByte() })
        assertTrue(slice.drop(100).all { it == 0.toByte() })
        assertEquals(PlaybackEnqueueResult.RejectedEpoch, engine.enqueue(byteArrayOf(1), epoch = 3, running = true))
        engine.beginReply(epoch = 4)
        assertEquals(PlaybackEnqueueResult.Accepted, engine.enqueue(byteArrayOf(2, 3), epoch = 4, running = true))
    }

    @Test
    fun joinTimeoutBlocksRestartWhileWorkerStillAlive() {
        assertFalse(mayStartAudioWorker(previousWorkerAlive = true, running = false))
        assertTrue(mayStartAudioWorker(previousWorkerAlive = true, running = true))
        assertTrue(mayStartAudioWorker(previousWorkerAlive = false, running = false))
    }
}
