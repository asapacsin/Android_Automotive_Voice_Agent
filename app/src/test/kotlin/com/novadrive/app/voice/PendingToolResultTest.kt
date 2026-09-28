package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A reply made while a tool of the same turn is still running cannot be grounded in its result.
 *
 * Replays the 2026-09-28 demo (08:37:40-53): 「停止说话」 → set_speech_output ok (its result landed
 * in the next turn and "proved" it), 「看看前面有什么。」 → describe_camera_view (deferred, ~8 s), and
 * a response created from the queued set_speech_output reply said 「抱歉，摄像头暂时无法使用。」
 * before the vision request had even been sent. The look then succeeded.
 */
class PendingToolResultTest {
    private val goodAudio = SpeechUplinkGate.Segment(durationMs = 2_000, voicedFrames = 20, peak = 9_000)

    private fun cameraTurn(): DriverTurn {
        val t = DriverTurn(epoch = 37)
        // 08:37:41.034: the previous turn's set_speech_output result arrives in this turn.
        t.onExecutionResult(ok = true, failure = null, callId = "call_speech")
        // 08:37:43.175: the tool-call response; transcript and call arrive during it.
        t.onResponseStarted(goodAudio, contextAwaitingAnswer = false)
        t.onUserTranscript("看看前面有什么。") { DriverTurn.classify(it) }
        t.onToolCall("call_cam", "describe_camera_view")
        t.onResponseDone("", hadToolCallInResponse = true)
        return t
    }

    @Test
    fun theDemoFailureClaimMadeBeforeTheVisionResultIsNeverHeard() {
        val t = cameraTurn()
        // 08:37:44.386: a response created while the vision request is still pending.
        assertEquals(DriverTurn.HoldReason.AWAITING_TOOL_RESULT, t.onResponseStarted(goodAudio, false))
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("抱歉，摄像头暂时无法使用。"))
        val verdict = t.onResponseDone("抱歉，摄像头暂时无法使用。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Drop, "was $verdict")
        verdict as DriverTurn.Verdict.Drop
        assertEquals("reply_before_tool_result", verdict.reason)
        assertNull(verdict.correction, "the pending result's own delivery asks for the real answer")

        // 08:37:52: the vision result is delivered; its reply is the answer and is heard.
        t.onExecutionResult(ok = true, failure = null, callId = "call_cam")
        assertEquals(DriverTurn.HoldReason.NONE, t.onResponseStarted(goodAudio, false))
    }

    @Test
    fun aPictureDescribedBeforeTheResultIsDroppedEvenIfTheResultLandsMidReply() {
        val t = cameraTurn()
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(ok = true, failure = null, callId = "call_cam"))
        val verdict = t.onResponseDone("前方有一辆红色的车。", hadToolCallInResponse = false)
        assertEquals("reply_before_tool_result", (verdict as DriverTurn.Verdict.Drop).reason)
    }

    @Test
    fun otherPendingToolsOnlyLoseRepliesThatStateAnOutcome() {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("附近有什么加油站") { DriverTurn.Kind.ACTION }
        t.onToolCall("call_live", "query_live_info")
        t.onResponseStarted(goodAudio, false)
        assertTrue(t.onResponseDone("稍等，我查一下。", false) is DriverTurn.Verdict.Release)

        t.onResponseStarted(goodAudio, false)
        assertTrue(t.onResponseDone("抱歉，暂时查不到。", false) is DriverTurn.Verdict.Drop)
    }

    @Test
    fun withNothingPendingTheOldRulesApply() {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("开始导航") { DriverTurn.Kind.ACTION }
        t.onToolCall("call_nav", "choose_navigation_option")
        t.onExecutionResult(ok = true, failure = null, callId = "call_nav")
        assertEquals(DriverTurn.HoldReason.NONE, t.onResponseStarted(goodAudio, false))
    }
}
