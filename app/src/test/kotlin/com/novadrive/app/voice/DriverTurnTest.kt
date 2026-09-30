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
    fun aDroppedClaimAfterAFailedExecutionReportsTheFailureInsteadOfRetrying() {
        // Simulation benchmark, 2026-09-24 (HVAC_MODEL_IGNORES_ERROR): the tool failed, the model
        // said 「已经为你调好了」, the claim was dropped unheard - and the follow-up asked the model to
        // perform the action again, so the driver never learned it had failed.
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        t.onExecutionResult(ok = false, failure = "VEHICLE_UNAVAILABLE")
        assertTrue(t.executionFailed)
        val verdict = t.onResponseDone("好的，已经为你调好了。", hadToolCallInResponse = false)
        assertTrue(verdict is DriverTurn.Verdict.Drop)
        val correction = (verdict as DriverTurn.Verdict.Drop).correction.orEmpty()
        assertTrue("VEHICLE_UNAVAILABLE" in correction, "the reason reaches the model: $correction")
        assertTrue("没有成功" in correction && "不要调用任何工具" in correction, "report, do not retry: $correction")
    }

    @Test
    fun aDroppedClaimWithNoExecutionStillAsksForTheAction() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        val verdict = t.onResponseDone("导航已开始。", hadToolCallInResponse = false) as DriverTurn.Verdict.Drop
        assertFalse(t.executionFailed)
        assertEquals(ActionClaimGuard.nudgeFor(requestFor(DriverTurn.Kind.ACTION)), verdict.correction)
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

    /**
     * P36, measured 2026-09-28 06:51 on the emulator: 「裙子。」 then 「陪你真好，咖啡。」, a
     * conversation turn, got a 16-character chat reply that TURN_DROP unverified_claim silenced.
     * Chat that names no car control and no control verb is not a claim and must play.
     */
    @Test
    fun aChatReplyWithNoCarActionIsSpoken() {
        for (reply in listOf("好的，那我们开始聊咖啡吧。", "陪着你就好了，咖啡我也爱喝。", "好呀，选一杯拿铁也不错。")) {
            val t = DriverTurn(epoch = 9)
            t.onResponseStarted(goodAudio, false)
            t.hold("audio")
            t.onUserTranscript("陪你真好，咖啡。") { DriverTurn.Kind.CONVERSATION }
            val verdict = t.onResponseDone(reply, hadToolCallInResponse = false)
            assertEquals(DriverTurn.Verdict.Release("no_claim_made"), verdict, reply)
        }
    }

    @Test
    fun anUnverifiedClaimNamesWhichKindOfClaimItWas() {
        val car = DriverTurn(epoch = 1)
        car.onResponseStarted(goodAudio, false)
        car.hold("audio")
        car.onUserTranscript("发诺克拉。") { DriverTurn.Kind.CONVERSATION }
        assertEquals("unverified_claim_car_action",
            (car.onResponseDone("导航到家。正在搜索您的家地址。", false) as DriverTurn.Verdict.Drop).reason)
        val done = DriverTurn(epoch = 2)
        done.onResponseStarted(goodAudio, false)
        done.hold("audio")
        done.onUserTranscript("那个。") { DriverTurn.Kind.CONVERSATION }
        assertEquals("unverified_claim_done_claim",
            (done.onResponseDone("好的，已为你打开。", false) as DriverTurn.Verdict.Drop).reason)
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
        t.onUserTranscript("你能做什么。") { DriverTurn.classify(it) }
        assertEquals(DriverTurn.Kind.CAPABILITY_HELP, t.kind)
        assertEquals(DriverTurn.HoldReason.CAPABILITY_HELP, t.holdReason)
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
        t.onUserTranscript("你能做什么。") { DriverTurn.classify(it) }
        assertEquals(DriverTurn.Kind.CAPABILITY_HELP, t.kind)
        val verdict = t.onResponseDone("没听清，再说一遍。", hadToolCallInResponse = false)
        assertEquals("help_incomplete", (verdict as DriverTurn.Verdict.Drop).reason)
        assertTrue(verdict.correction?.contains("导航") == true)
        assertTrue(verdict.correction?.contains("原样") == true)
        assertTrue(verdict.correction?.contains("没有听清楚") == false)
    }

    @Test
    fun colloquialGanShaMustNotBecomeUnheard() {
        // Device 2026-09-22: transcript=你能干啥 → TURN_DROP unverified_claim → unheard nudge.
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        t.onUserTranscript("你能干啥。") { DriverTurn.classify(it) }
        assertEquals(DriverTurn.Kind.CAPABILITY_HELP, t.kind)
        assertEquals(DriverTurn.HoldReason.CAPABILITY_HELP, t.holdReason)
        val verdict = t.onResponseDone("好的，我来帮你处理一下。", hadToolCallInResponse = false)
        assertEquals("help_incomplete", (verdict as DriverTurn.Verdict.Drop).reason)
        assertTrue(verdict.correction?.contains("不要说没听清") == true)
        assertTrue(verdict.correction?.contains("原样") == true)
        assertTrue(verdict.correction?.contains("没有听清楚") == false)
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
        // D-10(b): content proves the turn real, not the reply honest; it is judged at the end.
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("前面第二个路口右转就到了。"))
        assertEquals(DriverTurn.HoldReason.UNCLASSIFIED_CLAIM, t.holdReason)
        assertEquals(DriverTurn.Verdict.Release("no_claim_made"), t.onResponseDone("前面第二个路口右转就到了。", false))
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
        assertEquals(DriverTurn.Kind.CAPABILITY_HELP, DriverTurn.classify("你能干啥"))
        assertEquals(DriverTurn.Kind.REALTIME_INFO, DriverTurn.classify("今天天气怎么样"))
        assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify("把音量调大一点"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("导航去珠海站"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("你能帮我导航吗"))
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

    // ---- owner demo 2026-09-28 (P39) ----

    private fun chatTurn(transcript: String): DriverTurn {
        val t = DriverTurn(epoch = 16)
        t.onResponseStarted(goodAudio, contextAwaitingAnswer = true)
        t.hold("audio")
        t.onUserTranscript(transcript) { DriverTurn.classify(it) }
        return t
    }

    /**
     * 08:32:44 「什么这也可以是吧。」 (35-character reply) and 08:36:50 「就是怪的。」 (7 characters), both
     * with a list on screen, both `TURN_DROP unverified_claim_car_action kind=CONVERSATION`. The
     * reply texts were not logged; these are the prompts a list on screen makes the model say.
     */
    @Test
    fun aPromptToChooseWithAListOnScreenIsSpoken() {
        val cases = listOf(
            "什么这也可以是吧。" to "是的，你可以直接说第几个，或者在屏幕上点选目的地。",
            "就是怪的。" to "要选哪条路线？",
            "就是怪的。" to "请选一条路线。",
        )
        for ((heard, reply) in cases) {
            assertEquals(DriverTurn.Kind.CONVERSATION, DriverTurn.classify(heard))
            val t = chatTurn(heard)
            assertEquals(DriverTurn.Verdict.Release("no_claim_made"), t.onResponseDone(reply, false), reply)
        }
    }

    @Test
    fun aClaimThatAlsoAsksIsStillDroppedAndSaysWhichWordsMatched() {
        for (reply in listOf("好的，已为你选择第二条路线，还要改吗？", "退出导航中，请选择下一个目的地。")) {
            val verdict = chatTurn("就是怪的。").onResponseDone(reply, false)
            assertTrue(verdict is DriverTurn.Verdict.Drop, reply)
            val detail = (verdict as DriverTurn.Verdict.Drop).detail.orEmpty()
            assertTrue(detail.startsWith("predicate=car_action noun="), detail)
            // Vocabulary words only: the log line never carries the reply itself.
            assertFalse(detail.contains(reply))
        }
    }

    /**
     * 08:33:14 「你办公室也不怎么吵。」 → 「我没听清，再说一遍。」, released. The recogniser heard a
     * whole sentence; the model gave up on the audio. Once per utterance the repair is replaced by
     * an answer to the transcript.
     */
    @Test
    fun aRepairToAHeardSentenceIsReplacedOnceByAnAnswer() {
        val t = DriverTurn(epoch = 22)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        t.onUserTranscript("你办公室也不怎么吵。") { DriverTurn.classify(it) }
        val first = t.onResponseDone("我没听清，再说一遍。", false)
        assertTrue(first is DriverTurn.Verdict.Drop)
        first as DriverTurn.Verdict.Drop
        assertEquals("repair_for_heard_speech", first.reason)
        assertTrue(first.correction!!.contains("你办公室也不怎么吵"), "self-contained: it may land after a reset")
        assertTrue(first.correction!!.contains("不要说没听清"))
        // The model still cannot make sense of it: that repair is honest and is heard.
        t.onResponseStarted(null, false)
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Release("no_claim_made"), t.onResponseDone("没听清，再说一遍。", false))
    }

    @Test
    fun theAppsOwnLongerRepairWordingIsAlsoReplaced() {
        // 08:32:51: after the app's 「刚才没有听清楚…」 correction the model repeated it verbatim.
        val verdict = chatTurn("这个问题我想问一下你。").onResponseDone("刚才没有听清楚，也没有执行任何操作，请再说一遍。", false)
        assertEquals("repair_for_heard_speech", (verdict as DriverTurn.Verdict.Drop).reason)
    }

    @Test
    fun aRepairToAFragmentOrAnActionIsLeftAlone() {
        // 「这个。」 is exactly what a repair is for.
        assertEquals(DriverTurn.Verdict.Release("no_claim_made"), chatTurn("这个。").onResponseDone("没听清，再说一遍。", false))
        // An action turn is judged on execution proof, not here.
        val action = turn(DriverTurn.Kind.ACTION)
        action.onResponseStarted(goodAudio, false)
        action.hold("audio")
        assertEquals(DriverTurn.Verdict.Release("no_claim_made"), action.onResponseDone("没听清，再说一遍。", false))
        // A real answer is untouched.
        assertEquals(
            DriverTurn.Verdict.Release("no_claim_made"),
            chatTurn("你办公室也不怎么吵。").onResponseDone("是啊，挺安静的。", false),
        )
    }

    // ---- D-10(a): a call registered mid-response, after the hold was decided ----

    private fun actionMidCall(): DriverTurn {
        val t = turn(DriverTurn.Kind.ACTION)
        assertEquals(DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF, t.onResponseStarted(goodAudio, false))
        t.onToolCall("c1", "control_climate")
        assertEquals(DriverTurn.HoldReason.AWAITING_TOOL_RESULT, t.holdReason)
        t.hold("audio")
        return t
    }

    @Test
    fun d10a1MidResponseCallThenSuccessThenClaimIsReleased() {
        val t = actionMidCall()
        assertEquals(DriverTurn.Verdict.Release("execution_proved"), t.onExecutionResult(true, null, callId = "c1"))
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("已为您打开空调。"))
        assertEquals(DriverTurn.Verdict.Release("not_held"), t.onResponseDone("已为您打开空调。", true))
    }

    @Test
    fun d10a2MidResponseCallThenFailureThenClaimReportsTheFailure() {
        val t = actionMidCall()
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(false, "空调不可用", callId = "c1"))
        assertEquals(DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF, t.holdReason)
        t.onAssistantText("已为您打开空调。")
        val v = t.onResponseDone("已为您打开空调。", true)
        assertEquals(
            DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("空调不可用")),
            v,
        )
    }

    @Test
    fun d10a3AFailureAnnouncedBeforeASuccessfulResultIsNeverHeard() {
        val t = actionMidCall()
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("抱歉，空调暂时无法使用。"))
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "c1"))
        assertTrue(t.isHolding)
        assertEquals(DriverTurn.Verdict.Drop("reply_before_tool_result"), t.onResponseDone("抱歉，空调暂时无法使用。", true))
    }

    @Test
    fun d10a3AnyWordsBeforeAMidResponseCameraResultAreNeverHeard() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.onToolCall("v1", "describe_camera_view")
        t.onAssistantText("前面是一辆白色的车。")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "v1"))
        assertEquals(DriverTurn.Verdict.Drop("reply_before_tool_result"), t.onResponseDone("前面是一辆白色的车。", true))
    }

    @Test
    fun d10a3ADoneClaimBeforeAFailedResultReportsTheFailure() {
        val t = actionMidCall()
        t.onAssistantText("已为您打开空调。")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(false, "空调不可用", callId = "c1"))
        assertEquals(
            DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("空调不可用")),
            t.onResponseDone("已为您打开空调。", true),
        )
    }

    @Test
    fun d10a3ADoneClaimBeforeASuccessfulResultIsProved() {
        val t = actionMidCall()
        t.onAssistantText("已为您打开空调。")
        assertEquals(DriverTurn.Verdict.Release("execution_proved"), t.onExecutionResult(true, null, callId = "c1"))
    }

    @Test
    fun d10a3OutcomeWordsWithTheResultStillPendingAtTheEndAreDropped() {
        val t = actionMidCall()
        t.onAssistantText("已为您打开空调。")
        assertEquals(DriverTurn.Verdict.Drop("reply_before_tool_result"), t.onResponseDone("已为您打开空调。", true))
    }

    @Test
    fun d10a4WordsStatingNoOutcomeWaitForTheResultThenTheKindsHold() {
        val ok = actionMidCall()
        assertEquals(DriverTurn.Verdict.Wait, ok.onAssistantText("好的，稍等"))
        assertEquals(DriverTurn.Verdict.Release("execution_proved"), ok.onExecutionResult(true, null, callId = "c1"))

        val failed = actionMidCall()
        failed.onAssistantText("好的，稍等")
        assertEquals(DriverTurn.Verdict.Wait, failed.onExecutionResult(false, "空调不可用", callId = "c1"))
        assertEquals(DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF, failed.holdReason)
        val v = failed.onResponseDone("好的，稍等已为您打开空调。", true)
        assertTrue(v is DriverTurn.Verdict.Drop && v.reason == "unproven_action_claim")
    }

    @Test
    fun d10a5NoWordsThenResultThenReplyInALaterResponseIsUnchanged() {
        val t = actionMidCall()
        assertEquals(DriverTurn.Verdict.Release("tool_called"), t.onResponseDone("", true))
        // The hold was already settled with the response: the result proves, nothing is held.
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "c1"))
        assertTrue(t.proven)
        assertEquals(DriverTurn.HoldReason.NONE, t.onResponseStarted(goodAudio, false))
        assertEquals(DriverTurn.Verdict.Release("not_held"), t.onResponseDone("已为您打开空调。", false))
    }

    @Test
    fun d10a6AResponseThatStartedWithTheCallAwaitedIsUnchanged() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onToolCall("v1", "describe_camera_view")
        assertEquals(DriverTurn.HoldReason.AWAITING_TOOL_RESULT, t.onResponseStarted(goodAudio, false))
        t.onToolCall("c2", "control_climate")
        t.onAssistantText("抱歉，摄像头暂时无法使用。")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "v1"))
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "c2"))
        assertEquals(DriverTurn.Verdict.Drop("reply_before_tool_result"), t.onResponseDone("抱歉，摄像头暂时无法使用。", false))
    }

    // ---- D-10(b): phantom audio, then content ----

    @Test
    fun d10bPhantomThenClaimWithNoCallIsDropped() {
        val t = DriverTurn(epoch = 1)
        assertEquals(DriverTurn.HoldReason.PHANTOM_AUDIO, t.onResponseStarted(doubtfulAudio, false))
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("好的。"))
        val claim = "好的，我已经为您把空调打开了，温度二十四度。"
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText(claim))
        assertEquals(DriverTurn.HoldReason.UNCLASSIFIED_CLAIM, t.holdReason)
        val v = t.onResponseDone(claim, false)
        assertTrue(v is DriverTurn.Verdict.Drop && v.reason.startsWith("unverified_claim_"), "$v")
    }

    @Test
    fun d10bPhantomThenChatIsReleasedAsNoClaim() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("今天路上车不多，大概二十分钟就能到公司。"))
        assertEquals(DriverTurn.HoldReason.UNCLASSIFIED_CLAIM, t.holdReason)
        assertEquals(DriverTurn.Verdict.Release("no_claim_made"), t.onResponseDone("今天路上车不多，大概二十分钟就能到公司。", false))
    }

    @Test
    fun d10bAShortPhantomReplyStillGoesThroughThePhantomGate() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(doubtfulAudio, false)
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("今天车不多。"))
        assertEquals(DriverTurn.HoldReason.PHANTOM_AUDIO, t.holdReason)
        val v = t.onResponseDone("今天车不多。", false)
        assertEquals(DriverTurn.Verdict.Drop("generic_repair_no_action_no_user_speech"), v)
    }

    // ---- D-10(a) review: words before the call, failures of any kind, order, proof, cancel ----

    @Test
    fun anActionClaimSaidBeforeAMidResponseCallIsNotHeardWhileTheResultIsPending() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.onAssistantText("导航已开始。")
        t.onToolCall("n1", "navigate_to")
        assertEquals(DriverTurn.Verdict.Drop("claim_before_call_unproven"), t.onResponseDone("导航已开始。", true))
    }

    @Test
    fun anIncompleteHelpAnswerSaidBeforeAMidResponseCallIsNotHeard() {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("你能做什么") { DriverTurn.Kind.CAPABILITY_HELP }
        assertEquals(DriverTurn.HoldReason.CAPABILITY_HELP, t.onResponseStarted(goodAudio, false))
        t.onAssistantText("好的。")
        t.onToolCall("c1", "control_music")
        assertEquals(DriverTurn.Verdict.Drop("claim_before_call_unproven"), t.onResponseDone("好的。", true))
    }

    @Test
    fun aDoneClaimAfterAFailedMidResponseCallReportsTheFailureForEveryKind() {
        val claim = "已为您打开空调。"
        for (kind in listOf(
            DriverTurn.Kind.UNKNOWN,
            DriverTurn.Kind.CONVERSATION,
            DriverTurn.Kind.NO_TOOL_ACTION,
            DriverTurn.Kind.REALTIME_INFO,
        )) {
            val t = turn(kind)
            t.onResponseStarted(goodAudio, false)
            t.onToolCall("c1", "control_climate")
            t.onExecutionResult(false, "空调不可用", callId = "c1")
            t.onAssistantText(claim)
            assertEquals(
                DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("空调不可用")),
                t.onResponseDone(claim, true),
                "kind $kind",
            )
        }
    }

    private fun twoMidCalls(failFirst: Boolean, claimBeforeResults: Boolean): DriverTurn.Verdict {
        val claim = "已为您打开空调。"
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.onToolCall("c1", "control_climate")
        t.onToolCall("c2", "control_music")
        t.hold("audio")
        if (claimBeforeResults) t.onAssistantText(claim)
        val results = if (failFirst) {
            listOf(t.onExecutionResult(false, "空调不可用", callId = "c2"), t.onExecutionResult(true, null, callId = "c1"))
        } else {
            listOf(t.onExecutionResult(true, null, callId = "c1"), t.onExecutionResult(false, "空调不可用", callId = "c2"))
        }
        assertEquals(listOf(DriverTurn.Verdict.Wait, DriverTurn.Verdict.Wait), results, "nothing released mid-response")
        if (!claimBeforeResults) t.onAssistantText(claim)
        assertTrue(t.isHolding && t.heldCount > 0, "the claim is still unheard before the response ends")
        return t.onResponseDone(claim, true)
    }

    @Test
    fun aFailedMidResponseCallIsReportedWhateverOrderTheResultsCameIn() {
        val expected = DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("空调不可用"))
        for (claimBefore in listOf(true, false)) {
            assertEquals(expected, twoMidCalls(failFirst = true, claimBeforeResults = claimBefore), "fail first, claimBefore=$claimBefore")
            assertEquals(expected, twoMidCalls(failFirst = false, claimBeforeResults = claimBefore), "ok first, claimBefore=$claimBefore")
        }
    }

    @Test
    fun aFailedMidResponseCallNeverReleasesAsExecutionProved() {
        val t = DriverTurn(epoch = 1)
        t.onResponseStarted(goodAudio, true)
        t.onUserTranscript("把音量调大一点") { DriverTurn.Kind.NO_TOOL_ACTION }
        assertEquals(DriverTurn.HoldReason.NO_TOOL_REQUEST, t.holdReason)
        t.onToolCall("c1", "control_climate")
        val v = t.onExecutionResult(false, "空调不可用", callId = "c1")
        assertEquals(DriverTurn.Verdict.Wait, v)
        assertFalse(t.proven)
    }

    @Test
    fun aTurnCancelledDuringAReplacedHoldReleasesNothingLater() {
        val t = actionMidCall()
        assertEquals(DriverTurn.Verdict.Drop("cancelled_superseded"), t.cancel("superseded"))
        val rejected = t.rejectedEvents
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "c1"))
        assertEquals(rejected + 1, t.rejectedEvents)
    }

    // Review RC1/RC2: a response the end check may still drop is never released mid-response.

    @Test
    fun aFailedMidCallAfterAKnownNoToolRequestKeepsTheClaimUnheard() {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("把音量调大一点") { DriverTurn.Kind.NO_TOOL_ACTION }
        t.onResponseStarted(goodAudio, true)
        t.hold("audio")
        t.onToolCall("c1", "control_climate")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(false, "空调不可用", callId = "c1"))
        t.onAssistantText("已为您打开空调。")
        assertTrue(t.isHolding && t.heldCount > 0)
        val v = t.onResponseDone("已为您打开空调。", true)
        assertEquals(DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("空调不可用")), v)
    }

    @Test
    fun aSecondMidCallThatFailsAfterAProvedOneKeepsTheLaterClaimUnheard() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.onToolCall("c1", "control_climate")
        t.onExecutionResult(true, null, callId = "c1")
        t.onToolCall("c2", "open_app")
        t.hold("audio")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(false, "车窗不可用", callId = "c2"))
        t.onAssistantText("已为您打开车窗。")
        assertTrue(t.isHolding && t.heldCount > 0)
        val v = t.onResponseDone("已为您打开车窗。", true)
        assertEquals(DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("车窗不可用")), v)
    }

    @Test
    fun capabilityHelpWordsBeforeAProvedMidCallAreStillJudgedBeforeRelease() {
        val t = turn(DriverTurn.Kind.CAPABILITY_HELP)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        t.onAssistantText("我什么都能做。")
        t.onToolCall("c1", "control_climate")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "c1"))
        assertTrue(t.isHolding && t.heldCount > 0)
        val v = t.onResponseDone("我什么都能做。", true)
        assertTrue(v is DriverTurn.Verdict.Drop, "was $v")
    }

    @Test
    fun aForeignResultDuringAwaitingProofIsNotProof() {
        // D-11: a result for a call this turn never dispatched cannot prove its claim (I-1).
        val t = turn(DriverTurn.Kind.ACTION)
        assertEquals(DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF, t.onResponseStarted(goodAudio, false))
        t.hold("audio")
        t.onAssistantText("导航已开始。")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "old_call"))
        assertTrue(t.isHolding, "a foreign result must not release the claim")
        assertFalse(t.proven)
        assertFalse(t.executionFailed)
        assertEquals(1, t.foreignResults)
    }

    @Test
    fun anOwnResultStillReleasesAfterAForeignOneWasIgnored() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        t.onToolCall("n1", "navigate_to")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "old_call"))
        assertTrue(t.isHolding)
        val v = t.onExecutionResult(true, null, callId = "n1")
        assertTrue(t.proven, "the turn's own result is proof")
        assertTrue(v is DriverTurn.Verdict.Release, "was $v")
        assertEquals(1, t.foreignResults)
    }

    @Test
    fun aForeignSuccessAfterAFailedMidCallKeepsTheClaimHeld() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onResponseStarted(goodAudio, false)
        t.hold("audio")
        t.onToolCall("c1", "control_climate")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(false, "空调不可用", callId = "c1"))
        t.onAssistantText("已为您打开空调。")
        assertEquals(DriverTurn.Verdict.Wait, t.onExecutionResult(true, null, callId = "old_call"))
        assertTrue(t.isHolding && t.heldCount > 0)
        assertFalse(t.proven)
        val v = t.onResponseDone("已为您打开空调。", true)
        assertEquals(DriverTurn.Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure("空调不可用")), v)
    }

    @Test
    fun aLateResultForAnAlreadySettledOwnCallIsStillOwn() {
        val t = turn(DriverTurn.Kind.ACTION)
        t.onToolCall("c1", "control_climate")
        t.onExecutionResult(true, null, callId = "c1")
        t.onExecutionResult(true, null, callId = "c1")
        assertEquals(0, t.foreignResults)
    }
}
