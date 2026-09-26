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
        val atLimitBytes = limitBytes(16_000)
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
        val atLimitBytes = limitBytes(16_000)
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
        val nearlyFull = limitBytes(16_000) - 320
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

    /**
     * Regression, 2026-09-26: Flex delivered a ~3 s reply in ~1 s and the old 500 ms ceiling
     * failed it before it could be heard. A whole spoken reply arriving at once must fit.
     */
    @Test
    fun aWholeReplyDeliveredFasterThanRealTimeFits() {
        val tenSecondsAt24k = 24_000 * 2 * 10
        assertFalse(
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = tenSecondsAt24k - 9_600,
                remainderBytes = 0,
                unwrittenSliceBytes = 0,
                incomingBytes = 9_600,
                sampleRateHz = 24_000,
            ),
        )
    }

    private fun limitBytes(sampleRateHz: Int): Int =
        sampleRateHz * 2 * AppPlaybackQueuePolicy.MAX_PENDING_MS / 1000
}
