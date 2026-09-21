package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The per-turn state machine, and the invariant it exists for: **only deterministic execution
 * evidence may establish that an external action occurred** (`docs/INVARIANTS.md` I-1).
 *
 * Each test is a sequence of events in the order the protocol can actually deliver them.
 */
class DriverTurnTest {
    private fun turn(kind: DriverTurn.Kind = DriverTurn.Kind.UNKNOWN): DriverTurn {
        val t = DriverTurn(epoch = 1)
        if (kind != DriverTurn.Kind.UNKNOWN) t.onUserTranscript(requestFor(kind)) { kind }
        return t
    }

    private fun requestFor(kind: DriverTurn.Kind) = when (kind) {
        DriverTurn.Kind.ACTION -> "开始导航"
        DriverTurn.Kind.NO_TOOL_ACTION -> "把音量调大一点"
        DriverTurn.Kind.REALTIME_INFO -> "今天天气怎么样"
        else -> "你好"
    }

    private val goodAudio = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 14, peak = 9_000)
    private val doubtfulAudio = SpeechUplinkGate.Segment(durationMs = 300, voicedFrames = 2, peak = 9_000)

    // ---- the D-7 case: a claim before proof ----

    @Test
    fun aSuccessClaimIsNotReleasedUntilExecutionProvesIt() {
        // Device, 2026-09-18: 「开始导航」 was answered 「导航已开始。」 before the tool ran.
        val t = turn(DriverTurn.Kind.ACTION)
        assertEquals(DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF, t.onResponseStarted(goodAudio, false))
        t.hold("audio")
        // The model's wording alone changes nothing.
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("导航已开始。"))
        assertTrue(t.isHolding, "a claim with no proof must stay held")

        val verdict = t.onExecutionResult(ok = true, failure = null)
        assertTrue(verdict is DriverTurn.Verdict.Release)
        assertEquals("execution_proved", (verdict as DriverTurn.Verdict.Release).reason)
        assertFalse(t.isHolding)
    }

    @Test
    fun aClaimThatIsNeverProvedIsDroppedAndCorrected() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val verdict = t.onResponseDone("导航已开始。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Drop)
        assertEquals("unproven_action_claim", (verdict as DriverTurn.Verdict.Drop).reason)
        assertTrue(verdict.correction?.isNotBlank() == true, "the model must be told to actually do it")
    }

    @Test
    fun aFailedExecutionIsNotProof() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(ok = false, failure = "NO_CANDIDATES"))
        assertTrue(t.isHolding, "a failure must never release a success claim")
        assertEquals("NO_CANDIDATES", t.lastFailure)
        val verdict = t.onResponseDone("导航已开始。", hadToolCallInResponse = true)
        assertTrue(verdict is DriverTurn.Verdict.Drop)
    }

    @Test
    fun anActionReplyThatClaimsNothingIsNeverDelayedPastItsResponse() {
        // 「找到5个地点，请说第几个。」 asserts no execution; it is released even without proof.
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val verdict = t.onResponseDone("找到5个地点，请说第几个。", hadToolCallInResponse = true)
        assertTrue(verdict is DriverTurn.Verdict.Release)
        assertEquals("no_claim_made", (verdict as DriverTurn.Verdict.Release).reason)
    }

    @Test
    fun theNormalFlowIsNotHeldAtAll() {
        // Proof first (the model answers from the tool result), so the confirmation never waits.
        val t = turn(DriverTurn.Kind.ACTION)
        t.onToolCall()
        t.onExecutionResult(ok = true, failure = null)
        assertEquals(DriverTurn.HoldReason.NONE, t.onResponseStarted(goodAudio, false))
    }

    @Test
    fun aSecondResponseInTheSameTurnIsStillCoveredByTheProof() {
        // One command produces the tool response and then the spoken result.
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.onToolCall()
        t.onExecutionResult(ok = true, failure = null)
        t.onResponseDone("", hadToolCallInResponse = true)
        assertEquals(DriverTurn.HoldReason.NONE, t.onResponseStarted(goodAudio, false))
        val verdict = t.onResponseDone("音乐已开始播放。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Release, "the confirmation of a proved action is spoken")
    }

    // ---- T07 / T09: requests with no tool ----

    @Test
    fun anUnsupportedRequestHoldsUntilTheWordingIsKnownToBeHonest() {
        val t = turn(DriverTurn.Kind.NO_TOOL_ACTION)
        assertEquals(DriverTurn.HoldReason.NO_TOOL_REQUEST, t.onResponseStarted(goodAudio, false))
        t.hold("audio")
        val verdict = t.onResponseDone("正在调整音量", hadToolCallInResponse = false)
        assertEquals("false_claim_unsupported", (verdict as DriverTurn.Verdict.Drop).reason)
    }

    @Test
    fun anHonestRefusalOfAnUnsupportedRequestIsSpoken() {
        val t = turn(DriverTurn.Kind.NO_TOOL_ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val verdict = t.onResponseDone("抱歉，我无法调节音量。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Release)
    }

    @Test
    fun anInventedForecastIsDroppedAndCorrected() {
        val t = turn(DriverTurn.Kind.REALTIME_INFO)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val verdict = t.onResponseDone("今天北京晴，20到28度。", hadToolCallInResponse = false)
        assertEquals("fabricated_realtime_info", (verdict as DriverTurn.Verdict.Drop).reason)
        assertTrue(verdict.correction?.contains("不要给出任何城市") == true)
    }

    // ---- T10: doubtful audio ----

    @Test
    fun doubtfulAudioIsHeldAndAContentlessReplyDropped() {
        val t = DriverTurn(epoch = 1)
        assertEquals(DriverTurn.HoldReason.PHANTOM_AUDIO, t.onResponseStarted(doubtfulAudio, false))
        t.hold("audio")
        val verdict = t.onResponseDone("没听清，再说一遍。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Drop)
    }

    @Test
    fun aTranscribedConversationNeverWaitsForAnExecution() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        // Chat depends on no execution, so it must never wait for proof that cannot come. It does
        // wait for the response to finish - the classification came from a transcript, and a
        // transcript can be wrong (P23). Measured cost of that wait: 160-423 ms.
        val reason = t.onUserTranscript("你好") { DriverTurn.Kind.CONVERSATION }
        assertEquals(DriverTurn.HoldReason.UNCLASSIFIED_CLAIM, reason)
        val verdict = t.onResponseDone("你好，有什么可以帮你的？", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Release, "an honest chat reply is spoken")
    }

    @Test
    fun aClaimAfterAMisheardTurnIsNeverSpoken() {
        // The measured failure, 2026-09-19: 「返屋企啦」 arrived as 「发诺克拉。」, was classified as
        // conversation, and the reply announced a navigation that never happened. Before this the
        // driver heard it and was corrected afterwards; now they never hear it.
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        t.onUserTranscript("发诺克拉。") { DriverTurn.Kind.CONVERSATION }
        val verdict = t.onResponseDone("导航到家。正在搜索您的家地址。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Drop, "a claim nothing performed must not be spoken")
    }

    @Test
    fun aCapabilityHelpAnswerIsSpokenEvenIfItNamesTools() {
        // HELP-001 2026-09-21: 「你能做什么」 was transcribed, the model listed capabilities
        // (56 chars), and UNCLASSIFIED_CLAIM dropped it as unverified_claim before playback.
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        t.onUserTranscript("你能做什么。") { DriverTurn.Kind.CONVERSATION }
        val verdict = t.onResponseDone(
            "我能帮你导航、放音乐、调空调，也能看摄像头和打电话。",
            hadToolCallInResponse = false,
        )
        assertEquals("capability_help", (verdict as DriverTurn.Verdict.Release).reason)
    }

    @Test
    fun aHelpQuestionAnsweredAsNoiseGetsANudgeNotSilence() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        t.onUserTranscript("你能做什么。") { DriverTurn.Kind.CONVERSATION }
        val verdict = t.onResponseDone("没听清，再说一遍。", hadToolCallInResponse = false)
        assertEquals("help_incomplete", (verdict as DriverTurn.Verdict.Drop).reason)
        assertTrue(verdict.correction?.contains("导航") == true)
    }

    @Test
    fun aRealActionAfterAMisheardTurnIsStillSpoken() {
        // Cantonese tool calling is intermittent, not absent: the same utterance did call
        // control_climate on 2026-09-19. When it does, the confirmation must be heard.
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        t.onUserTranscript("有的人帮我说服的。") { DriverTurn.Kind.CONVERSATION }
        val verdict = t.onResponseDone("温度已调低到22度。", hadToolCallInResponse = true)
        assertTrue(verdict is DriverTurn.Verdict.Release)
    }

    @Test
    fun aTranscribedActionKeepsWaitingButForTheRightReason() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        // It was held because the audio looked doubtful; now it is held because the reply's truth
        // depends on an execution that has not happened. The hold is no longer about the noise.
        assertEquals(
            DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF,
            t.onUserTranscript("播放音乐") { DriverTurn.Kind.ACTION },
        )
        t.onExecutionResult(ok = true, failure = null)
        assertFalse(t.isHolding, "proof releases it")
    }

    @Test
    fun aRealReplyReleasesADoubtfulHoldBeforeTheResponseEnds() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        val verdict = t.onAssistantText("前面第二个路口右转就到了。")
        assertTrue(verdict is DriverTurn.Verdict.Release)
    }

    // ---- ordering, cancellation, illegal combinations ----

    @Test
    fun aCancelledTurnNeverReleasesItsHeldOutput() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val cancelled = t.cancel("superseded")
        assertTrue(cancelled is DriverTurn.Verdict.Drop)
        assertEquals(DriverTurn.Phase.CANCELLED, t.phase)
        // Late events from the old turn change nothing and are counted.
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(ok = true, failure = null))
        assertFalse(t.proven, "a cancelled turn cannot be proved after the fact")
        assertTrue(t.rejectedEvents > 0, "late events must be visible, not silent")
    }

    @Test
    fun provenCannotBeTrueWithoutAnExecutionResult() {
        // The illegal combination that independent booleans allowed: there is no setter for it.
        val t = turn(DriverTurn.Kind.ACTION)
        t.onToolCall()
        assertFalse(t.proven, "dispatching a call is not evidence that it succeeded")
    }

    @Test
    fun theHoldBudgetAlwaysReleasesRatherThanStalling() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val verdict = t.onHoldBudgetExceeded()
        assertTrue(verdict is DriverTurn.Verdict.Release)
        assertFalse(t.isHolding)
    }

    @Test
    fun classificationRoutesEachRequestToWhatItsTruthDependsOn() {
        assertEquals(DriverTurn.Kind.REALTIME_INFO, DriverTurn.classify("今天天气怎么样"))
        assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify("把音量调大一点"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("导航去珠海站"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("打电话给张三"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("拨打张三电话"))
        assertEquals(DriverTurn.Kind.CONVERSATION, DriverTurn.classify("你好，你是谁"))
    }

    /**
     * Device, 2026-09-18: with a route list on screen, `contextAwaitingAnswer` exempted the whole
     * turn and a second 「导航已开始。」 was spoken for a navigation that had not started. What is on
     * screen says nothing about whether an action happened.
     */
    @Test
    fun aListOnScreenDoesNotExemptAnActionClaimFromNeedingProof() {
        val t = turn(DriverTurn.Kind.ACTION)
        assertEquals(
            DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF,
            t.onResponseStarted(goodAudio, contextAwaitingAnswer = true),
        )
    }

    @Test
    fun aListOnScreenDoesExemptADoubtfulOrNoToolTurn() {
        // There the hold exists to avoid chatter; the driver is mid-choice and must be prompted.
        val doubtful = DriverTurn(epoch = 1)
        assertEquals(DriverTurn.HoldReason.NONE, doubtful.onResponseStarted(doubtfulAudio, true))
        val noTool = turn(DriverTurn.Kind.NO_TOOL_ACTION)
        assertEquals(DriverTurn.HoldReason.NONE, noTool.onResponseStarted(goodAudio, true))
    }

    @Test
    fun aPickerOnScreenDoesNotExemptAClaim() {
        // The 2026-09-18 lesson, one layer down: contextAwaitingAnswer means a *prompt* is wanted,
        // not that an action happened. Measured 2026-09-19, a claim slipped through this exemption
        // because the turn was classified as conversation.
        // Real speech, clearly audible, simply misheard - not the doubtful-audio case, which is
        // exempt on purpose so a repair reaches a driver who is mid-choice.
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(goodAudio, contextAwaitingAnswer = true)
        t.onUserTranscript("发诺克拉。") { DriverTurn.Kind.CONVERSATION }
        t.hold("audio")
        val verdict = t.onResponseDone("已为您打开空调。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Drop)
    }

    @Test
    fun aGenuinePromptIsStillSpoken() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(goodAudio, contextAwaitingAnswer = true)
        t.onUserTranscript("第二个") { DriverTurn.Kind.CONVERSATION }
        t.hold("audio")
        val verdict = t.onResponseDone("找到几个地点，请在屏幕上选择。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Release, "a prompt claims nothing")
    }
}
