package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AudioIoTest {
    @Test
    fun requireValidMinBufferRejectsNonPositiveSizes() {
        assertThrows<IllegalArgumentException> { AudioBufferGuard.requireValidMinBuffer(0) }
        assertThrows<IllegalArgumentException> { AudioBufferGuard.requireValidMinBuffer(-1) }
        assertThrows<IllegalArgumentException> { AudioBufferGuard.requireValidMinBuffer(-2) }
        assertEquals("AUDIO_INVALID_BUFFER", AudioBufferGuard.INVALID_BUFFER_CODE)
        assertEquals(256, AudioBufferGuard.requireValidMinBuffer(256))
        assertEquals(
            "AUDIO_CAPTURE_FAILED",
            assertThrows<IllegalArgumentException> {
                AudioBufferGuard.requireValidMinBuffer(0, "AUDIO_CAPTURE_FAILED")
            }.message,
        )
    }

    @Test
    fun terminateJoinsWorkerAndIsIdempotentForRepeatedStop() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker =
            thread(name = "nova-audio-io-test", isDaemon = true) {
                started.countDown()
                release.await()
            }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(BoundedThreadCleanup.terminate(worker, 500))
        assertFalse(worker.isAlive)
        assertTrue(BoundedThreadCleanup.terminate(worker, 500))
        assertTrue(BoundedThreadCleanup.terminate(null, 500))
    }
}
