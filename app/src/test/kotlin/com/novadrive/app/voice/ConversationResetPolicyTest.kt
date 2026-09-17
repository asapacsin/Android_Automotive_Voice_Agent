package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationResetPolicyTest {
    @Test
    fun resetsAfterAToolTurnHasBeenAnswered() {
        val policy = ConversationResetPolicy()
        assertFalse(policy.onResponseDone(listOf("function_call")))
        policy.onToolResultSent()
        assertTrue(policy.onResponseDone(listOf("message")))
    }

    @Test
    fun neverResetsWhileAToolResultIsStillOwed() {
        val policy = ConversationResetPolicy()
        policy.onResponseDone(listOf("function_call"))
        // e.g. the camera look is still running and a filler message arrived meanwhile
        assertFalse(policy.onResponseDone(listOf("message")))
        policy.onToolResultSent()
        assertTrue(policy.onResponseDone(listOf("message")))
    }

    @Test
    fun plainConversationResetsOnlyAfterSeveralTurns() {
        val policy = ConversationResetPolicy(maxPlainTurns = 3)
        assertFalse(policy.onResponseDone(listOf("message")))
        assertFalse(policy.onResponseDone(listOf("message")))
        assertTrue(policy.onResponseDone(listOf("message")))
    }

    @Test
    fun emptyAndCancelledResponsesDoNotTriggerAReset() {
        val policy = ConversationResetPolicy(maxPlainTurns = 1)
        assertFalse(policy.onResponseDone(emptyList()))
        assertFalse(policy.onResponseDone(listOf("function_call")))
        policy.onToolResultSent()
        assertFalse(policy.onResponseDone(emptyList()))
    }

    @Test
    fun twoCallsNeedTwoResultsBeforeReset() {
        val policy = ConversationResetPolicy()
        policy.onResponseDone(listOf("function_call", "function_call"))
        policy.onToolResultSent()
        assertFalse(policy.onResponseDone(listOf("message")))
        policy.onToolResultSent()
        assertTrue(policy.onResponseDone(listOf("message")))
    }

    @Test
    fun resetClearsState() {
        val policy = ConversationResetPolicy(maxPlainTurns = 2)
        policy.onResponseDone(listOf("function_call"))
        policy.reset()
        assertFalse(policy.onResponseDone(listOf("message")))
        assertTrue(policy.onResponseDone(listOf("message")))
    }
}
