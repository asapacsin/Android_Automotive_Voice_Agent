package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** SPEC-020: thresholds, reason choice and the tool phrase table. */
class WaitCuesTest {
    private fun codes(turn: WaitCueTurn, at: Long) = generateSequence { turn.due(at) }.map { it.code }.toList()

    @Test
    fun chatAckThenProviderSlowThenStillWaiting() {
        val turn = WaitCueTurn()
        assertEquals(emptyList<String>(), codes(turn, 1_799))
        assertEquals(listOf("ack_chat"), codes(turn, 1_800))
        assertEquals(listOf("provider_slow"), codes(turn, 5_000))
        assertEquals(listOf("still_waiting"), codes(turn, 12_000))
        assertNull(turn.due(60_000))
    }

    @Test
    fun aToolCallMakesTheAckEarlierAndTheReasonTheTool() {
        val turn = WaitCueTurn().apply { onToolCall("query_live_info") }
        assertEquals(listOf("ack_action"), codes(turn, 1_000))
        val reason = turn.due(5_000)!!
        assertEquals("tool_running", reason.code)
        assertEquals(WaitCues.TOOL_QUERY, reason.text)
    }

    @Test
    fun verifyingWhenAReplyStartedWithoutATool() {
        val turn = WaitCueTurn().apply { onResponseStarted() }
        assertEquals(listOf("ack_chat", "verifying"), codes(turn, 1_800) + codes(turn, 5_000))
    }

    @Test
    fun oneCuePerGroupEvenWhenThresholdsAreMissed() {
        assertEquals(listOf("still_waiting"), codes(WaitCueTurn(), 13_000))
    }

    @Test
    fun toolPhrases() {
        assertEquals(WaitCues.TOOL_ROUTE, WaitCues.toolPhrase("choose_navigation_option"))
        assertEquals(WaitCues.TOOL_MUSIC, WaitCues.toolPhrase("control_music"))
        assertEquals(WaitCues.TOOL_CAMERA, WaitCues.toolPhrase("describe_camera_view"))
        assertEquals(WaitCues.TOOL_DEFAULT, WaitCues.toolPhrase("control_climate"))
    }
}
