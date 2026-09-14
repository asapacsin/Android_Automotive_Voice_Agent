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
