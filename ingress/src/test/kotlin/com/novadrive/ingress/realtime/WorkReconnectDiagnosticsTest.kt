package com.novadrive.ingress.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class WorkCoordinatorTest {
    @Test
    fun speechInterruptionDoesNotCancelWorkAndResultDeliversOnce() {
        val work = WorkCoordinator()
        work.submit("job", "lookup")
        work.updateProgress("job", "50%")
        work.complete("job", "ok")
        assertEquals(WorkStatus.COMPLETED, work.snapshot("job")?.status)
        assertNull(work.claimForDelivery(safeConversationPoint = false))
        val first = work.claimForDelivery(true)
        assertEquals("ok", first?.result)
        assertFalse(work.snapshot("job")!!.delivered)
        assertNull(work.claimForDelivery(true))
        work.releaseDeliveryClaim("job")
        val retried = work.claimForDelivery(true)
        assertEquals("ok", retried?.result)
        work.acknowledgeDelivery("job")
        assertTrue(work.snapshot("job")!!.delivered)
        assertNull(work.claimForDelivery(true))
        work.cancel("job")
        assertEquals(WorkStatus.CANCELLED, work.snapshot("job")?.status)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkCoordinatorAsyncTest {
    @Test
    fun submitSuspendingWorkRecordsProgressAndCancelIsIsolated() =
        runTest(UnconfinedTestDispatcher()) {
            val work = WorkCoordinator()
            work.submit(this, "slow", "nav") {
                delay(5_000)
                "done"
            }
            work.submit(this, "fast", "lookup") {
                progress("50%")
                "fast-ok"
            }
            assertEquals(WorkStatus.RUNNING, work.snapshot("slow")?.status)
            assertEquals(WorkStatus.COMPLETED, work.snapshot("fast")?.status)
            assertEquals("50%", work.snapshot("fast")?.progress)
            work.refine("slow", "nav-refined")
            assertEquals("nav-refined", work.snapshot("slow")?.payload)
            work.cancel("slow")
            assertEquals(WorkStatus.CANCELLED, work.snapshot("slow")?.status)
            advanceTimeBy(5_000)
            assertEquals(WorkStatus.CANCELLED, work.snapshot("slow")?.status)
            assertEquals("fast-ok", work.snapshot("fast")?.result)
        }

    @Test
    fun failedInjectionClaimCanBeReleasedAndAcknowledgedOnce() {
        val work = WorkCoordinator()
        work.submit("w1", "payload")
        work.complete("w1", "ok")
        val claimed = work.claimForDelivery(true)
        assertEquals("w1", claimed?.id)
        assertNull(work.claimForDelivery(true))
        work.releaseDeliveryClaim("w1")
        val retried = work.claimForDelivery(true)
        assertEquals("w1", retried?.id)
        work.acknowledgeDelivery("w1")
        assertTrue(work.snapshot("w1")!!.delivered)
        assertNull(work.claimForDelivery(true))
    }

    @Test
    fun cancelAllCancelsRunningCoroutineAndLeavesTerminalSnapshots() =
        runTest(UnconfinedTestDispatcher()) {
            val work = WorkCoordinator()
            val started = CompletableDeferred<Unit>()
            val cancelled = AtomicBoolean(false)
            work.submit(this, "running", "nav") {
                started.complete(Unit)
                try {
                    delay(10_000)
                    "done"
                } catch (ex: CancellationException) {
                    cancelled.set(true)
                    throw ex
                }
            }
            work.submit("done", "lookup")
            work.complete("done", "ok")
            work.submit("failed", "tool")
            work.fail("failed", "boom")
            work.submit("already-cancelled", "x")
            work.cancel("already-cancelled")
            started.await()
            work.cancelAll()
            assertEquals(WorkStatus.CANCELLED, work.snapshot("running")?.status)
            assertTrue(cancelled.get())
            assertEquals(WorkStatus.COMPLETED, work.snapshot("done")?.status)
            assertEquals("ok", work.snapshot("done")?.result)
            assertEquals(WorkStatus.FAILED, work.snapshot("failed")?.status)
            assertEquals("boom", work.snapshot("failed")?.error)
            assertEquals(WorkStatus.CANCELLED, work.snapshot("already-cancelled")?.status)
        }
}

class ReconnectPolicyTest {
    @Test
    fun exponentialBackoffStopsAtMaxAttemptsAndCancel() {
        val policy = ReconnectPolicy(ReconnectPlan(maxAttempts = 3, initialDelayMs = 100, maxDelayMs = 250))
        assertEquals(100, policy.nextDelayMs("SERVER_DISCONNECT"))
        assertEquals(200, policy.nextDelayMs("BAIDU_TIMEOUT"))
        assertEquals(250, policy.nextDelayMs("QWEN_WS_FAILED"))
        assertNull(policy.nextDelayMs("SERVER_DISCONNECT"))
        policy.reset()
        policy.cancel()
        assertNull(policy.nextDelayMs("SERVER_DISCONNECT"))
        assertEquals(ErrorClass.AUTH, policy.classify("GPT_LIVE_AUTH_FAILED"))
    }
}

class StructuredVoiceLogTest {
    @Test
    fun redactsCredentialsAndDoesNotKeepRawAudio() {
        val log = StructuredVoiceLog()
        log.info(
            "connect",
            mapOf(
                "api_key" to "sk-live-secret",
                "model" to VoiceCatalog.QWEN_FLASH,
            ),
        )
        assertTrue(log.lines.single().contains("api_key=configured:yes"))
        assertTrue(!log.lines.single().contains("sk-live-secret"))
        assertEquals("token=***", StructuredVoiceLog.redact("token=abc123"))
    }
}
