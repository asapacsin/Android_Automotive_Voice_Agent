package com.novadrive.app.wake

/**
 * The decisions [WakeWordController] makes about wake ownership, without Android (Astra P3).
 *
 * - A wake event counts only while wake is armed: after the controller has disarmed (a session
 *   took the microphone, wake was disabled, or the event was already acted on) a late callback
 *   from the engine must not start a second session.
 * - A failed capture or engine start is retried with a bounded, growing delay. Before this, a
 *   capture error only logged: the dead recorder kept its slot and wake stayed deaf until the
 *   lifecycle changed. An engine stuck in ERROR was re-initialised on every 1.5 s tick, forever.
 */
class WakeArming(
    private val nowMs: () -> Long,
    baseDelayMs: Long = 1_500L,
    maxDelayMs: Long = 30_000L,
    maxAttempts: Int = 6,
) {
    val capture = BoundedRetry(nowMs, baseDelayMs, maxDelayMs, maxAttempts)
    val engine = BoundedRetry(nowMs, baseDelayMs, maxDelayMs, maxAttempts)

    @Volatile
    var armed: Boolean = false
        private set

    fun onArmed() {
        armed = true
    }

    fun onDisarmed() {
        armed = false
    }

    /**
     * True when a detection may start a session. Disarms on acceptance, so one spoken phrase
     * reported twice by the engine starts one session.
     */
    @Synchronized
    fun acceptWake(conversationOwnsMicrophone: Boolean, enabled: Boolean): Boolean {
        if (!armed || conversationOwnsMicrophone || !enabled) return false
        armed = false
        return true
    }

    /** A lifecycle, permission or settings change: a fresh start deserves fresh attempts. */
    fun onReconciled() {
        capture.reset()
        engine.reset()
    }
}

/** Attempts with an exponentially growing delay, abandoned after [maxAttempts] until [reset]. */
class BoundedRetry(
    private val nowMs: () -> Long,
    private val baseDelayMs: Long,
    private val maxDelayMs: Long,
    private val maxAttempts: Int,
) {
    private var failures = 0
    private var nextAttemptAtMs = 0L

    @Synchronized
    fun mayAttempt(): Boolean = failures < maxAttempts && nowMs() >= nextAttemptAtMs

    /** True once the budget is spent: the caller logs it once and stops trying. */
    @Synchronized
    fun recordFailure(): Boolean {
        failures += 1
        val delay = (baseDelayMs shl (failures - 1).coerceAtMost(20)).coerceAtMost(maxDelayMs)
        nextAttemptAtMs = nowMs() + delay
        return failures >= maxAttempts
    }

    @Synchronized
    fun reset() {
        failures = 0
        nextAttemptAtMs = 0L
    }

    @get:Synchronized
    val exhausted: Boolean get() = failures >= maxAttempts
}
