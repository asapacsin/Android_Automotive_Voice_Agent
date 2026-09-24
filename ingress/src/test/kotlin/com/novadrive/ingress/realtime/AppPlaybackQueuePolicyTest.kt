package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppPlaybackQueuePolicyTest {
    @Test
    fun pendingMsCountsQueuedRemainderAndUnwrittenSlice() {
        val bytes =
            AppPlaybackQueuePolicy.pendingBytes(
                queuedBytes = 3200,
                remainderBytes = 1600,
                unwrittenSliceBytes = 800,
            )
        assertEquals(5600, bytes)
        assertEquals(175, AppPlaybackQueuePolicy.pendingMs(bytes, sampleRateHz = 16_000))
    }

    @Test
    fun atLimitIncomingChunkIsStillAccepted() {
        val atLimitBytes = 16_000 * 2 / 2 // 500 ms at 16 kHz mono PCM16
        assertFalse(
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = atLimitBytes,
                remainderBytes = 0,
                unwrittenSliceBytes = 0,
                incomingBytes = 0,
                sampleRateHz = 16_000,
            ),
        )
    }

    @Test
    fun oneBytePastLimitTriggersOverflow() {
        val atLimitBytes = 16_000 * 2 / 2
        assertTrue(
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = atLimitBytes,
                remainderBytes = 0,
                unwrittenSliceBytes = 0,
                incomingBytes = 2,
                sampleRateHz = 16_000,
            ),
        )
    }

    @Test
    fun newerEpochWouldBeAcceptedAfterOverflowClearsPendingAudio() {
        val nearlyFull = 16_000 * 2 / 2 - 320
        assertFalse(
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = nearlyFull,
                remainderBytes = 0,
                unwrittenSliceBytes = 0,
                incomingBytes = 320,
                sampleRateHz = 16_000,
            ),
        )
        assertTrue(
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = nearlyFull,
                remainderBytes = 0,
                unwrittenSliceBytes = 0,
                incomingBytes = 322,
                sampleRateHz = 16_000,
            ),
        )
        assertFalse(
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = 0,
                remainderBytes = 0,
                unwrittenSliceBytes = 0,
                incomingBytes = 320,
                sampleRateHz = 16_000,
            ),
        )
    }
}
