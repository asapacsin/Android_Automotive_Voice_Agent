package com.novadrive.app.voice

/**
 * Network fault injection for benchmark runs (debug runner only; inert in normal use).
 * Inbound latency is applied on the socket reader thread, so event order is preserved.
 */
object NetworkFaults {
    @Volatile var inboundDelayMs: Long = 0

    @Volatile internal var dropConnection: (() -> Unit)? = null

    /** Cancels the realtime socket as a network reset would. Returns false when none is open. */
    fun dropConnectionNow(): Boolean {
        val drop = dropConnection ?: return false
        drop()
        return true
    }

    fun clear() {
        inboundDelayMs = 0
    }
}
