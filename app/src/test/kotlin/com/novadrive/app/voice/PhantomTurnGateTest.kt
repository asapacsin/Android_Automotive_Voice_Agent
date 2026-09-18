package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The post-model gate: a turn is silenced only when all four deterministic conditions hold. The
 * interesting cases are the ones that must still be spoken.
 */
class PhantomTurnGateTest {
    private fun segment(durationMs: Int, voicedFrames: Int = durationMs / 100, peak: Int = 5_000) =
        SpeechUplinkGate.Segment(durationMs = durationMs, voicedFrames = voicedFrames, peak = peak)

    private fun turn(
        hadToolCall: Boolean = false,
        contextAwaitingAnswer: Boolean = false,
        audio: SpeechUplinkGate.Segment? = segment(300, voicedFrames = 2),
        assistantText: String = "没听清，再说一遍。",
        hadUserTranscript: Boolean = true,
    ) = PhantomTurnGate.Turn(hadToolCall, contextAwaitingAnswer, audio, assistantText, hadUserTranscript)

    @Test
    fun theReportedPhantomTurnIsDropped() {
        // 2026-09-18: a stray noise produced 「怎么回事。」 and the assistant answered 「没听清，再说一遍。」
        val verdict = PhantomTurnGate.judge(turn())
        assertTrue(verdict is PhantomTurnGate.Verdict.Drop)
        assertEquals("generic_repair_no_action_short_audio", (verdict as PhantomTurnGate.Verdict.Drop).reason)
    }

    @Test
    fun aTurnThatAskedForAnActionIsAlwaysSpoken() {
        // 「暂停」 is short audio and could look suspicious; it called a tool, so it is real.
        assertEquals(PhantomTurnGate.Verdict.Speak, PhantomTurnGate.judge(turn(hadToolCall = true)))
    }

    @Test
    fun aGenuineQuestionWithNoToolCallIsSpoken() {
        // The rule must not become "no tool call means silence".
        val spoken = PhantomTurnGate.judge(
            turn(assistantText = "今天限行尾号是三和八，你的车可以上路。"),
        )
        assertEquals(PhantomTurnGate.Verdict.Speak, spoken)
    }

    @Test
    fun aRepairIsSpokenWhenTheDriverClearlySaidSomething() {
        // Long, well-voiced audio: the driver spoke and deserves to know they were not understood.
        val spoken = PhantomTurnGate.judge(turn(audio = segment(1_800, voicedFrames = 15)))
        assertEquals(PhantomTurnGate.Verdict.Speak, spoken)
    }

    @Test
    fun aRepairIsSpokenWhileSomethingOnScreenIsWaiting() {
        // With a destination list open, 「没听清」 tells the driver to repeat their choice.
        val spoken = PhantomTurnGate.judge(turn(contextAwaitingAnswer = true))
        assertEquals(PhantomTurnGate.Verdict.Speak, spoken)
    }

    @Test
    fun withoutAnAudioMeasurementNothingIsSuppressed() {
        // A typed turn, an app prompt, or a reply after the gate was bypassed: never guess.
        assertEquals(PhantomTurnGate.Verdict.Speak, PhantomTurnGate.judge(turn(audio = null)))
    }

    @Test
    fun sparseAudioIsReportedAsWeakVoicedRatio() {
        // Long but mostly silent: a door closing inside a quiet stretch.
        val verdict = PhantomTurnGate.judge(turn(audio = segment(2_000, voicedFrames = 2)))
        assertEquals("generic_repair_no_action_weak_voiced_ratio", (verdict as PhantomTurnGate.Verdict.Drop).reason)
    }

    @Test
    fun repairDetectionIsLengthBoundedNotJustKeywordBased() {
        assertTrue(PhantomTurnGate.isGenericRepair("没听清"))
        assertTrue(PhantomTurnGate.isGenericRepair(""))
        assertTrue(PhantomTurnGate.isGenericRepair("Sorry, I didn't catch that."))
        // The same marker inside a real answer is not a repair: it carries information.
        assertFalse(
            PhantomTurnGate.isGenericRepair(
                "刚才那条路的名字我没听清，不过导航已经开始了，下一个路口右转。",
            ),
        )
    }

    /**
     * Device, 2026-09-18: noise came back transcribed as 「。」 and a blank check treated that as
     * the driver speaking, so the phantom reply was released and spoken.
     */
    /**
     * Device, 2026-09-18: room noise was answered with 「嗯。」 — a filler that no repair-phrase list
     * would have held. The primary test is therefore the shape of the reply, not its vocabulary.
     */
    @Test
    fun aContentlessReplyIsJudgedByShapeNotVocabulary() {
        assertTrue(PhantomTurnGate.isContentlessReply("嗯。"))
        assertTrue(PhantomTurnGate.isContentlessReply("啊？"))
        assertTrue(PhantomTurnGate.isContentlessReply(""))
        assertTrue(PhantomTurnGate.isContentlessReply("。"))
        // Long enough to be telling the driver something.
        assertFalse(PhantomTurnGate.isContentlessReply("空调已打开，当前温度24摄氏度，风量2档。"))
        assertFalse(PhantomTurnGate.isContentlessReply("前面第二个路口右转就到了。"))
        // ...but a long apology is still an apology.
        assertTrue(PhantomTurnGate.isContentlessReply("不好意思，刚才那句话我没听清，请再说一遍。"))
    }

    @Test
    fun theFillerReplyToRoomNoiseIsDropped() {
        val verdict = PhantomTurnGate.judge(turn(assistantText = "嗯。"))
        assertTrue(verdict is PhantomTurnGate.Verdict.Drop, "a filler answer to noise must not be spoken")
    }

    @Test
    fun punctuationIsNotSpeech() {
        assertFalse(PhantomTurnGate.hasWords("。"))
        assertFalse(PhantomTurnGate.hasWords(" ，。！ "))
        assertFalse(PhantomTurnGate.hasWords(""))
        assertTrue(PhantomTurnGate.hasWords("暂停"))
        assertTrue(PhantomTurnGate.hasWords("ok"))
        assertTrue(PhantomTurnGate.hasWords("第2个"))
    }

    @Test
    fun oneSyllableOfNoiseIsNotTheDriverSpeaking() {
        // Device, 2026-09-18: noise transcribed as 「嗯。」 released a phantom reply.
        assertFalse(PhantomTurnGate.isMeaningfulTranscript("嗯。"))
        assertFalse(PhantomTurnGate.isMeaningfulTranscript("。"))
        assertFalse(PhantomTurnGate.isMeaningfulTranscript(""))
        assertTrue(PhantomTurnGate.isMeaningfulTranscript("暂停"))
        assertTrue(PhantomTurnGate.isMeaningfulTranscript("关闭音乐"))
    }

    @Test
    fun anEmptyReplyAfterSuspiciousAudioIsDropped() {
        // The model produced nothing at all; there is certainly nothing to say out loud.
        assertTrue(PhantomTurnGate.judge(turn(assistantText = "")) is PhantomTurnGate.Verdict.Drop)
    }
}
