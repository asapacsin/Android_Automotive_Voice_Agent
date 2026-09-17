package com.novadrive.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OracleTest {
    private val set24 = ToolCallSpec("control_climate", mapOf("action" to "set_temperature", "value" to "24"))
    private fun call(name: String, args: Map<String, String>, ok: Boolean = true, at: Long = 10, error: String? = null) =
        ObservedCall(name, args, receivedNanos = at, executionStartNanos = at + 1, executionEndNanos = at + 2, success = ok, errorCode = error)
    private fun reply(text: String, at: Long = 100) = ObservedReply(text, at)

    @Test
    fun correctCallAndStatePasses() {
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(set24), state = mapOf("hvac.temperature" to "24.0")),
            listOf(call("control_climate", mapOf("action" to "set_temperature", "value" to "24.0"))),
            mapOf("hvac.temperature" to "24.0"),
            listOf(reply("已调到24度。")),
        )
        assertTrue(v.passed, v.failures.toString())
        assertTrue(v.toolSelectionCorrect && v.parametersCorrect)
        assertEquals(true, v.finalStateCorrect)
    }

    @Test
    fun aClaimWithoutTheStateChangeIsFalseSuccess() {
        // The model said it; the vehicle did not change.
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(set24), state = mapOf("hvac.temperature" to "24.0")),
            emptyList(),
            mapOf("hvac.temperature" to "20.0"),
            listOf(reply("已调到24度。")),
        )
        assertTrue(v.falseSuccess)
        assertFalse(v.toolSelectionCorrect)
        assertEquals(false, v.finalStateCorrect)
    }

    @Test
    fun aFailedToolWithAnHonestReplyPassesAsReportedFailure() {
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(set24), state = mapOf("hvac.temperature" to "20.0"), outcome = Outcome.REPORTED_FAILURE),
            listOf(call("control_climate", set24.args, ok = false, error = "VEHICLE_UNAVAILABLE")),
            mapOf("hvac.temperature" to "20.0"),
            listOf(reply("空调暂时无法调节。")),
        )
        assertTrue(v.passed, v.failures.toString())
        assertEquals(false, v.executionSucceeded)
        assertFalse(v.falseSuccess)
    }

    @Test
    fun aFailedToolWithASuccessClaimIsFalseSuccess() {
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(set24), outcome = Outcome.REPORTED_FAILURE),
            listOf(call("control_climate", set24.args, ok = false)),
            emptyMap(),
            listOf(reply("好的，已经调到24度了。")),
        )
        assertTrue(v.falseSuccess)
        assertFalse(v.passed)
    }

    @Test
    fun aRetractedClaimIsCorrectedNotFalse() {
        val v = Oracle.judge(
            TurnExpectation(outcome = Outcome.NO_ACTION),
            emptyList(),
            emptyMap(),
            listOf(reply("好的，已经调大了。", at = 100), reply("这个操作没有执行，暂时不支持。", at = 200)),
        )
        assertFalse(v.falseSuccess)
        assertTrue(v.prematureClaim)
        assertTrue(v.passed, v.failures.toString())
    }

    @Test
    fun aClaimBeforeTheActionThatLaterHappenedIsPremature() {
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(set24), state = mapOf("hvac.temperature" to "24")),
            listOf(call("control_climate", set24.args, at = 500)),
            mapOf("hvac.temperature" to "24.0"),
            listOf(reply("调好了。", at = 100), reply("温度已调到24度。", at = 900)),
        )
        assertTrue(v.passed)
        assertTrue(v.prematureClaim)
        assertFalse(v.falseSuccess)
    }

    @Test
    fun duplicateExecutionIsCounted() {
        val play = ToolCallSpec("control_music", mapOf("action" to "play"))
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(play)),
            listOf(call("control_music", play.args, at = 1), call("control_music", play.args, at = 5)),
            emptyMap(),
            listOf(reply("音乐开始播放了。")),
        )
        assertEquals(1, v.duplicateExecutions)
        assertFalse(v.passed)
    }

    @Test
    fun wrongToolAndUnexpectedCallsAreReported() {
        val v = Oracle.judge(
            TurnExpectation(outcome = Outcome.NO_ACTION),
            listOf(call("control_climate", mapOf("action" to "adjust_fan"))),
            emptyMap(),
            listOf(reply("风量已调大。")),
        )
        assertFalse(v.toolSelectionCorrect)
        assertEquals(1, v.unexpectedToolCalls)
    }

    @Test
    fun alternativesAndToleratedReadsAreAccepted() {
        val on = ToolCallSpec("control_climate", mapOf("action" to "power_on"))
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(set24), alternatives = listOf(listOf(on, set24)), tolerated = setOf("control_climate:get_state")),
            listOf(
                call("control_climate", mapOf("action" to "get_state"), at = 1),
                call("control_climate", on.args, at = 2),
                call("control_climate", set24.args, at = 3),
            ),
            emptyMap(),
            listOf(reply("空调已打开，温度24度。")),
        )
        assertTrue(v.passed, v.failures.toString())
        assertEquals(0, v.unexpectedToolCalls)
    }

    @Test
    fun forbiddenWordsAfterAVisionFailureAreFabrication() {
        val v = Oracle.judge(
            TurnExpectation(tools = listOf(ToolCallSpec("describe_camera_view")), outcome = Outcome.REPORTED_FAILURE, forbiddenReplyWords = listOf("行人")),
            listOf(call("describe_camera_view", mapOf("question" to "前面有什么"), ok = false)),
            emptyMap(),
            listOf(reply("前面有两个行人。")),
        )
        assertTrue(v.falseSuccess)
    }

    @Test
    fun valueMatchingRules() {
        assertTrue(Oracle.valueMatches("24", "24.0"))
        assertTrue(Oracle.valueMatches("~澳门大学", "澳门大学。"))
        assertTrue(Oracle.valueMatches("~澳门大学", "澳门大学图书馆"))
        assertFalse(Oracle.valueMatches("~横琴", "澳门大学"))
        assertTrue(Oracle.valueMatches("!NAVIGATING", "STOPPED"))
        assertFalse(Oracle.valueMatches("!NAVIGATING", "NAVIGATING"))
        assertTrue(Oracle.valueMatches("*", "x"))
        assertFalse(Oracle.valueMatches("*", null))
    }

    @Test
    fun missingReplyAndTimeoutAreFailures() {
        val v = Oracle.judge(TurnExpectation(), emptyList(), emptyMap(), emptyList())
        assertTrue(v.failures.any { "no spoken reply" in it })
        val t = Oracle.judge(TurnExpectation(), emptyList(), emptyMap(), emptyList(), timedOut = true)
        assertTrue(t.failures.any { "timeout" in it })
        assertNull(t.executionSucceeded)
    }

    @Test
    fun claimDetectionMatchesTheMeasuredSentences() {
        assertTrue(ReplyClaims.claimsSuccess("调高温度了。"))
        assertTrue(ReplyClaims.claimsSuccess("导航已开始。"))
        assertFalse(ReplyClaims.claimsSuccess("暂时不支持调节音量。"))
        assertFalse(ReplyClaims.claimsSuccess("找到3个地点，请说第几个。"))
        assertFalse(ReplyClaims.claimsSuccess("空调调节失败了。"))
    }
}
