package com.novadrive.app.wake

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Astra P3: stale wake events are ignored, and failed starts are retried within a bound. */
class WakeArmingTest {
    private var now = 0L
    private val arming = WakeArming(nowMs = { now }, baseDelayMs = 1_000, maxDelayMs = 8_000, maxAttempts = 4)

    @Test
    fun aDetectionBeforeArmingOrAfterDisarmingStartsNothing() {
        assertFalse(arming.acceptWake(conversationOwnsMicrophone = false, enabled = true), "never armed")
        arming.onArmed()
        arming.onDisarmed()
        assertFalse(arming.acceptWake(conversationOwnsMicrophone = false, enabled = true), "late event after stand-down")
    }

    @Test
    fun oneArmingAcceptsOneDetection() {
        arming.onArmed()
        assertTrue(arming.acceptWake(conversationOwnsMicrophone = false, enabled = true))
        assertFalse(arming.acceptWake(conversationOwnsMicrophone = false, enabled = true), "the same phrase reported twice")
        arming.onArmed()
        assertTrue(arming.acceptWake(conversationOwnsMicrophone = false, enabled = true), "re-armed for the next cycle")
    }

    @Test
    fun aDetectionWhileConversationOwnsTheMicrophoneOrWakeIsOffIsIgnored() {
        arming.onArmed()
        assertFalse(arming.acceptWake(conversationOwnsMicrophone = true, enabled = true))
        assertFalse(arming.acceptWake(conversationOwnsMicrophone = false, enabled = false))
        assertTrue(arming.acceptWake(conversationOwnsMicrophone = false, enabled = true), "a refusal does not disarm")
    }

    @Test
    fun failuresBackOffThenStopUntilReconciled() {
        val retry = arming.capture
        assertTrue(retry.mayAttempt())
        assertFalse(retry.recordFailure())
        assertFalse(retry.mayAttempt(), "waits before the next attempt")
        now += 1_000
        assertTrue(retry.mayAttempt(), "first delay is the base")
        retry.recordFailure()
        now += 1_999
        assertFalse(retry.mayAttempt(), "the delay doubles")
        now += 1
        assertTrue(retry.mayAttempt())
        retry.recordFailure()
        now += 4_000
        assertTrue(retry.mayAttempt())
        assertTrue(retry.recordFailure(), "the fourth failure spends the budget")
        now += 1_000_000
        assertFalse(retry.mayAttempt(), "abandoned, however long we wait")
        assertTrue(retry.exhausted)
        arming.onReconciled()
        assertTrue(retry.mayAttempt(), "a lifecycle or settings change starts afresh")
    }

    @Test
    fun theDelayIsCapped() {
        val retry = BoundedRetry({ now }, baseDelayMs = 1_000, maxDelayMs = 3_000, maxAttempts = 10)
        repeat(5) {
            retry.recordFailure()
            now += 3_000
            assertTrue(retry.mayAttempt(), "never more than the cap (attempt ${it + 1})")
        }
    }

    @Test
    fun aHealthyStartClearsFailures() {
        val retry = arming.engine
        retry.recordFailure()
        retry.recordFailure()
        retry.reset()
        assertTrue(retry.mayAttempt())
        assertFalse(retry.exhausted)
    }
}
