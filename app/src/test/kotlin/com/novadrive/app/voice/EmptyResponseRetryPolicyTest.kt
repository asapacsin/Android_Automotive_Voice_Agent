package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmptyResponseRetryPolicyTest {
    @Test
    fun emptyResponseAfterUtteranceIsRetriedOnce() {
        val policy = EmptyResponseRetryPolicy()
        assertFalse(policy.onUserTranscriptCompleted())
        assertTrue(policy.onResponseDone("completed", 0))
        // The retry also came back empty: give up rather than loop.
        assertFalse(policy.onResponseDone("completed", 0))
        assertFalse(policy.onResponseDone("completed", 0))
    }

    @Test
    fun transcriptArrivingAfterTheEmptyResponseStillTriggersOneRetry() {
        val policy = EmptyResponseRetryPolicy()
        assertFalse(policy.onResponseDone("completed", 0))
        assertTrue(policy.onUserTranscriptCompleted())
        assertFalse(policy.onResponseDone("completed", 0))
    }

    @Test
    fun nonEmptyResponsesAreNeverRetried() {
        val policy = EmptyResponseRetryPolicy()
        policy.onUserTranscriptCompleted()
        assertFalse(policy.onResponseDone("completed", 1))
        assertFalse(policy.onResponseDone("completed", 0))
    }

    @Test
    fun cancelledOrFailedResponsesAreNotRetried() {
        val policy = EmptyResponseRetryPolicy()
        policy.onUserTranscriptCompleted()
        assertFalse(policy.onResponseDone("cancelled", 0))
        policy.onUserTranscriptCompleted()
        assertFalse(policy.onResponseDone("failed", 0))
    }

    @Test
    fun eachNewUtteranceGetsItsOwnRetry() {
        val policy = EmptyResponseRetryPolicy()
        policy.onUserTranscriptCompleted()
        assertTrue(policy.onResponseDone("completed", 0))
        assertFalse(policy.onResponseDone("completed", 0))
        policy.onUserTranscriptCompleted()
        assertTrue(policy.onResponseDone("completed", 0))
    }

    @Test
    fun toolCallThenReplyIsNormalFlow() {
        val policy = EmptyResponseRetryPolicy()
        policy.onUserTranscriptCompleted()
        assertFalse(policy.onResponseDone("completed", 1)) // function_call
        assertFalse(policy.onResponseDone("completed", 1)) // message
    }

    @Test
    fun noiseEmptyResponseIsNotBlamedOnTheNextUtterance() {
        val policy = EmptyResponseRetryPolicy()
        assertFalse(policy.onResponseDone("completed", 0)) // VAD noise, no transcript
        policy.onSpeechStarted() // the driver starts a real sentence
        assertFalse(policy.onUserTranscriptCompleted())
        assertFalse(policy.onResponseDone("completed", 1))
    }

    @Test
    fun resetClearsPendingState() {
        val policy = EmptyResponseRetryPolicy()
        policy.onResponseDone("completed", 0)
        policy.reset()
        assertFalse(policy.onUserTranscriptCompleted())
    }
}
