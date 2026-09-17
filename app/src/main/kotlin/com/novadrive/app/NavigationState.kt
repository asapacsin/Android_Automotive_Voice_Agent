package com.novadrive.app

object NavigationState {
    @Volatile var navigating: Boolean = false
    @Volatile var onNavigatingChanged: ((Boolean) -> Unit)? = null
    @Volatile private var confirmUntilMs: Long = 0L
    const val CONFIRM_WINDOW_MS = 10_000L
    private val lock = Any()

    fun begin() {
        val changed = synchronized(lock) {
            if (navigating) false else {
                navigating = true
                true
            }
        }
        if (changed) notifyNavigatingChanged(true)
    }

    fun end() { navigating = false }

    fun allowConfirmation(nowMs: Long = System.currentTimeMillis()) { confirmUntilMs = nowMs + CONFIRM_WINDOW_MS }

    /**
     * The driver asked something: its answer may be spoken during navigation. Only unprompted
     * speech stays muted (product rule P1); an answer the driver asked for never is.
     */
    fun allowReply(nowMs: Long = System.currentTimeMillis()) = allowConfirmation(nowMs)

    /** A permitted reply is playing: keep the window open until it has finished. */
    fun extendWhileSpeaking(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs <= confirmUntilMs) confirmUntilMs = maxOf(confirmUntilMs, nowMs + CONFIRM_WINDOW_MS)
    }

    fun shouldMuteSpeech(nowMs: Long = System.currentTimeMillis()): Boolean = navigating && nowMs > confirmUntilMs

    fun reset() {
        val changed = synchronized(lock) {
            val wasNavigating = navigating
            navigating = false
            confirmUntilMs = 0L
            wasNavigating
        }
        if (changed) notifyNavigatingChanged(false)
    }

    private fun notifyNavigatingChanged(value: Boolean) {
        runCatching { onNavigatingChanged?.invoke(value) }
    }
}
