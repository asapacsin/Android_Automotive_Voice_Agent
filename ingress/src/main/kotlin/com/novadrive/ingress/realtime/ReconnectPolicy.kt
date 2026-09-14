package com.novadrive.ingress.realtime

import kotlin.math.min
import kotlin.math.pow

data class ReconnectPlan(
    val maxAttempts: Int = 5,
    val initialDelayMs: Long = 200,
    val maxDelayMs: Long = 8_000,
)

class ReconnectPolicy(
    private val plan: ReconnectPlan = ReconnectPlan(),
) {
    var attempts: Int = 0
        private set
    var cancelled: Boolean = false
        private set

    fun reset() {
        attempts = 0
        cancelled = false
    }

    fun cancel() {
        cancelled = true
    }

    fun classify(code: String): ErrorClass = classifyVoiceError(code)

    fun shouldRetry(code: String): Boolean {
        if (cancelled) return false
        if (attempts >= plan.maxAttempts) return false
        return classify(code) == ErrorClass.RETRYABLE
    }

    fun nextDelayMs(code: String): Long? {
        if (!shouldRetry(code)) return null
        val exp = plan.initialDelayMs * 2.0.pow(attempts.toDouble())
        attempts += 1
        return min(plan.maxDelayMs, exp.toLong())
    }
}
