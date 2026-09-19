package com.novadrive.app.voice

import com.novadrive.ingress.realtime.ResponseOutcome

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationResetPolicyTest {
    @Test
    fun resetsAfterAToolTurnHasBeenAnswered() {
        val policy = ConversationResetPolicy()
        assertFalse(policy.onResponseDone(ResponseOutcome(spoke = false, unidentifiedToolCalls = 1)))
        policy.onToolResultSent()
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()))
    }

    @Test
    fun neverResetsWhileAToolResultIsStillOwed() {
        val policy = ConversationResetPolicy()
        policy.onResponseDone(ResponseOutcome(spoke = false, unidentifiedToolCalls = 1))
        // e.g. the camera look is still running and a filler message arrived meanwhile
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()))
        policy.onToolResultSent()
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()))
    }

    @Test
    fun aResultSentBeforeItsResponseDoneIsNotOwedForever() {
        val policy = ConversationResetPolicy()
        policy.onToolResultSent("call_1")
        assertFalse(policy.onResponseDone(ResponseOutcome.toolsOnly("call_1")))
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()), "nothing is owed, so the turn resets")
    }

    @Test
    fun resultsAreMatchedByCallId() {
        val policy = ConversationResetPolicy()
        policy.onResponseDone(ResponseOutcome.toolsOnly("a", "b"))
        policy.onToolResultSent("a")
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()), "b is still owed")
        policy.onToolResultSent("unknown")
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()))
        policy.onToolResultSent("b")
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()))
    }

    @Test
    fun plainConversationResetsOnlyAfterSeveralTurns() {
        val policy = ConversationResetPolicy(maxPlainTurns = 3)
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()))
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()))
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()))
    }

    @Test
    fun emptyAndCancelledResponsesDoNotTriggerAReset() {
        val policy = ConversationResetPolicy(maxPlainTurns = 1)
        assertFalse(policy.onResponseDone(ResponseOutcome(spoke = false)))
        assertFalse(policy.onResponseDone(ResponseOutcome(spoke = false, unidentifiedToolCalls = 1)))
        policy.onToolResultSent()
        assertFalse(policy.onResponseDone(ResponseOutcome(spoke = false)))
    }

    @Test
    fun twoCallsNeedTwoResultsBeforeReset() {
        val policy = ConversationResetPolicy()
        policy.onResponseDone(ResponseOutcome(spoke = false, unidentifiedToolCalls = 2))
        policy.onToolResultSent()
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()))
        policy.onToolResultSent()
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()))
    }

    @Test
    fun resetClearsState() {
        val policy = ConversationResetPolicy(maxPlainTurns = 2)
        policy.onResponseDone(ResponseOutcome(spoke = false, unidentifiedToolCalls = 1))
        policy.reset()
        assertFalse(policy.onResponseDone(ResponseOutcome.spokenOnly()))
        assertTrue(policy.onResponseDone(ResponseOutcome.spokenOnly()))
    }
}
