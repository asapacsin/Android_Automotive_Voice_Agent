package com.novadrive.ingress.realtime

/**
 * Shared audio I/O guards used by Android capture/playback.
 * Hardware AEC, Bluetooth SCO, and RECORD_AUDIO remain manual-test-only.
 */
object AudioBufferGuard {
    const val INVALID_BUFFER_CODE = "AUDIO_INVALID_BUFFER"

    fun requireValidMinBuffer(
        minBuf: Int,
        errorCode: String = INVALID_BUFFER_CODE,
    ): Int {
        if (minBuf <= 0) {
            throw IllegalArgumentException(errorCode)
        }
        return minBuf
    }
}

object BoundedThreadCleanup {
    const val DEFAULT_JOIN_TIMEOUT_MS = 500L

    fun terminate(
        thread: Thread?,
        timeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
    ): Boolean {
        if (thread == null || !thread.isAlive) return true
        thread.join(timeoutMs.coerceAtLeast(0L))
        if (thread.isAlive) {
            thread.interrupt()
            thread.join(timeoutMs.coerceAtLeast(0L))
        }
        return !thread.isAlive
    }
}

/**
 * Application-owned PCM waiting to reach AudioTrack: queued chunks, slice remainder, and any
 * partially written device slice. Platform track buffering is excluded — see SPEC-009.
 */
object AppPlaybackQueuePolicy {
    /**
     * A memory ceiling, not a latency budget. Flex delivers a reply several times faster than it
     * plays (2026-09-26: a ~3 s reply arrived in ~1 s, 390 ms already queued when the first slice
     * played), so the queue legitimately holds most of a reply. The former 500 ms ceiling failed
     * every reply longer than about half a second with `AUDIO_PLAYBACK_FAILED` — nothing was
     * heard. A stale tail is cut by the reply epoch on barge-in, not by this limit.
     */
    const val MAX_PENDING_MS = 60_000

    fun pendingBytes(
        queuedBytes: Int,
        remainderBytes: Int,
        unwrittenSliceBytes: Int,
    ): Int = (queuedBytes + remainderBytes + unwrittenSliceBytes).coerceAtLeast(0)

    fun pendingMs(
        pendingBytes: Int,
        sampleRateHz: Int,
    ): Int {
        if (sampleRateHz <= 0 || pendingBytes <= 0) return 0
        val bytesPerSecond = sampleRateHz * 2
        // Round up so enforcement never underestimates elapsed audio at the 500 ms ceiling.
        return (pendingBytes * 1000 + bytesPerSecond - 1) / bytesPerSecond
    }

    /** True when accepting [incomingBytes] would push pending audio past [limitMs]. */
    fun wouldExceedLimit(
        queuedBytes: Int,
        remainderBytes: Int,
        unwrittenSliceBytes: Int,
        incomingBytes: Int,
        sampleRateHz: Int,
        limitMs: Int = MAX_PENDING_MS,
    ): Boolean = pendingMs(
        pendingBytes(queuedBytes, remainderBytes, unwrittenSliceBytes) + incomingBytes.coerceAtLeast(0),
        sampleRateHz,
    ) > limitMs
}
