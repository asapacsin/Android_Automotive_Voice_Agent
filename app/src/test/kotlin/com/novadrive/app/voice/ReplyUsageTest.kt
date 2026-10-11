package com.novadrive.app.voice

import com.novadrive.ingress.realtime.ResponseOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Owner, 2026-10-11: the cost of every reply is logged, and the re-billed history is capped. */
class ReplyUsageTest {
    /** The response.done example of the Model Studio server-events reference (zh), trimmed. */
    private val documented = """{"type":"response.done","response":{"id":"resp_1","status":"completed",
        "output":[{"type":"message","role":"assistant","content":[{"type":"audio","transcript":"你好呀！"}]}],
        "usage":{"total_tokens":377,"input_tokens":336,"output_tokens":41,
        "input_tokens_details":{"text_tokens":228,"audio_tokens":108},
        "output_tokens_details":{"text_tokens":9,"audio_tokens":32}}}}"""

    @Test
    fun theDocumentedUsageIsReadAndLoggedAsNumbersOnly() {
        val usage = ReplyUsage.from(documented)!!
        assertEquals(ReplyUsage(377, 336, 228, 108, 41, 9, 32), usage)
        val line = usage.describe("qwen")
        assertEquals("qwen_reply_usage total=377 in=336 in_text=228 in_audio=108 out=41 out_text=9 out_audio=32", line)
        assertFalse("你好" in line, "no transcript in a log (I-8)")
    }

    @Test
    fun noUsageMeansNothingToLog() {
        assertNull(ReplyUsage.from("""{"type":"response.done","response":{"status":"completed","output":[]}}"""))
        assertNull(ReplyUsage.from("not json"))
    }

    private val spoken = ResponseOutcome.spokenOnly()
    private fun usage(input: Int) = ReplyUsage(input + 50, input, input, 0, 50, 10, 40)

    @Test
    fun theHistoryIsCappedOnlyAtASafePoint() {
        val budget = HistoryBudget(capTokens = 12_000)
        assertTrue(budget.full(usage(12_000), spoken, superseded = false, awaitingAnswer = false))
        assertFalse(budget.full(usage(11_999), spoken, superseded = false, awaitingAnswer = false), "under the cap")
        assertFalse(budget.full(null, spoken, superseded = false, awaitingAnswer = false), "no usage reported")
        assertFalse(budget.full(usage(20_000), ResponseOutcome.toolsOnly("call_1"), false, false), "a tool result is owed")
        assertFalse(budget.full(usage(20_000), spoken, superseded = true, awaitingAnswer = false), "the driver talked over it")
        assertFalse(budget.full(usage(20_000), spoken, superseded = false, awaitingAnswer = true), "a choice list waits for the driver")
        assertFalse(budget.full(usage(20_000), ResponseOutcome(spoke = false), false, false), "nothing was said")
    }

    @Test
    fun theCapLeavesRoomAboveTheFixedContext() {
        // About 7K input tokens are re-sent with every reply (persona, rules, 17 tool declarations).
        assertTrue(HistoryBudget.HISTORY_TOKEN_CAP in 10_000..20_000)
    }
}
