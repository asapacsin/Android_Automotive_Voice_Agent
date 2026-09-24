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
    const val MAX_PENDING_MS = 500

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
