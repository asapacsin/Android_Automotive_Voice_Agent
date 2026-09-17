package com.novadrive.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stops the microphone reaching Baidu while navigation guidance is being spoken (P3).
 *
 * - Closes immediately when guidance starts.
 * - Reopens [tailMs] after it ends, so the room echo of the last word is not sent either.
 * - Reopens after [maxClosedMs] even if the end is never reported: a lost callback must not
 *   leave the assistant deaf for the rest of the drive.
 */
class GuidanceMicGate(
    private val scope: CoroutineScope,
    private val onGateChanged: (closed: Boolean) -> Unit,
    private val tailMs: Long = DEFAULT_TAIL_MS,
    private val maxClosedMs: Long = DEFAULT_MAX_CLOSED_MS,
) {
    private var generation = 0
    private var pending: Job? = null

    @Volatile
    var closed: Boolean = false
        private set

    fun onGuidanceSpeaking(speaking: Boolean) {
        synchronized(this) {
            pending?.cancel()
            val current = ++generation
            if (speaking) {
                set(true)
                pending = scope.launch {
                    delay(maxClosedMs)
                    openIf(current)
                }
            } else {
                pending = scope.launch {
                    delay(tailMs)
                    openIf(current)
                }
            }
        }
    }

    fun reset() {
        synchronized(this) {
            pending?.cancel()
            pending = null
            generation++
            set(false)
        }
    }

    private fun openIf(expected: Int) {
        synchronized(this) {
            if (generation == expected) set(false)
        }
    }

    private fun set(value: Boolean) {
        if (closed == value) return
        closed = value
        onGateChanged(value)
    }

    companion object {
        const val DEFAULT_TAIL_MS = 500L
        const val DEFAULT_MAX_CLOSED_MS = 20_000L
    }
}
