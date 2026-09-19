package com.novadrive.app.voice

import com.novadrive.ingress.realtime.ResponseOutcome

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionClaimGuardTest {
    private val guard = ActionClaimGuard()
    private val message = ResponseOutcome.spokenOnly()
    private val call = ResponseOutcome.toolsOnly("call_1")

    @Test
    fun theMeasuredFalseClaimIsCaught() {
        // Verbatim from the device log, 2026-09-17.
        guard.onUserTranscript("温度调高一点。")
        val nudge = guard.onResponseDone(message, "调高温度了。")
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("温度调高一点"), "the follow-up must carry the request itself")
    }

    @Test
    fun aRealToolTurnIsLeftAlone() {
        guard.onUserTranscript("温度调高一点。")
        assertNull(guard.onResponseDone(call, ""))
        assertNull(guard.onResponseDone(message, "温度已调高到27度。"))
    }

    @Test
    fun anHonestRefusalIsLeftAlone() {
        guard.onUserTranscript("音量调大一点。")
        assertNull(guard.onResponseDone(message, "暂时不支持调节音量。"))
    }

    @Test
    fun aClarifyingQuestionIsLeftAlone() {
        guard.onUserTranscript("导航")
        assertNull(guard.onResponseDone(message, "好的，请问要去哪里？"))
    }

    @Test
    fun chatIsLeftAlone() {
        guard.onUserTranscript("你好小诺")
        assertNull(guard.onResponseDone(message, "你好，我在。"))
        guard.onUserTranscript("嗯")
        assertNull(guard.onResponseDone(message, "没听清，请再说一遍。"))
    }

    @Test
    fun aCameraAnswerWithoutLookingIsCaught() {
        guard.onUserTranscript("现在镜头前面是什么。")
        assertNotNull(guard.onResponseDone(message, "前面有一张桌子和一台电脑。"))
    }

    @Test
    fun aCameraAnswerAfterLookingIsLeftAlone() {
        guard.onUserTranscript("看看前面有什么。")
        assertNull(guard.onResponseDone(call, ""))
        assertNull(guard.onResponseDone(message, "前方天花板上有长条形灯。"))
    }

    @Test
    fun atMostOneFollowUpPerUtterance() {
        guard.onUserTranscript("空调打开。")
        assertNotNull(guard.onResponseDone(message, "空调已打开。"))
        // The follow-up's own reply, again without a tool, must not loop.
        assertNull(guard.onResponseDone(message, "空调已经打开了。"))
    }

    @Test
    fun appInitiatedRepliesWithoutADriverUtteranceAreIgnored() {
        assertNull(guard.onResponseDone(message, "已为你播放音乐。"))
    }

    @Test
    fun aFalseClaimForAnUnsupportedRequestAsksForACorrectionNotAnAction() {
        // Measured 2026-09-17: the action follow-up for 音量 made the model raise the fan instead.
        guard.onUserTranscript("音量调大。")
        val nudge = guard.onResponseDone(message, "音量已调大。")!!
        assertTrue(nudge.contains("不要调用任何工具"))
        assertTrue(!nudge.contains("完成它"))
    }

    @Test
    fun aSpokenPickThatWasNotExecutedIsCaught() {
        guard.onUserTranscript("第二个。")
        assertNotNull(guard.onResponseDone(message, "好的，已选择第二个。"))
        guard.onUserTranscript("选最快的路线。")
        assertNull(guard.onResponseDone(call, ""))
        assertNull(guard.onResponseDone(message, "导航已开始。"))
    }

    @Test
    fun aSuccessClaimAfterAFailedToolIsCorrected() {
        guard.onUserTranscript("空调调到21度。")
        assertNull(guard.onResponseDone(call, ""))
        guard.onToolResult("""{"ok":false,"tool":"control_climate","error":"VEHICLE_UNAVAILABLE","message":"空调系统暂时不可用"}""")
        val correction = guard.onResponseDone(message, "好的，已经为你调好了。")
        assertNotNull(correction)
        assertTrue(correction!!.contains("空调系统暂时不可用"))
        assertTrue(correction.contains("不要调用任何工具"))
        // Once only, and an honest reply needs nothing.
        assertNull(guard.onResponseDone(message, "好的，已经为你调好了。"))
    }

    @Test
    fun anHonestReplyAfterAFailedToolNeedsNothing() {
        guard.onUserTranscript("空调调到21度。")
        guard.onResponseDone(call, "")
        guard.onToolResult("""{"ok":false,"error":"X"}""")
        assertNull(guard.onResponseDone(message, "抱歉，空调调节失败了。"))
        guard.onToolResult("""{"ok":true}""")
        assertNull(guard.onResponseDone(message, "已调到21度。"), "a later success clears the failure")
    }

    @Test
    fun aCancelThatWasOnlySpokenIsCaught() {
        // Measured 2026-09-17 with the destination list open: 「不用了。」 → 「已取消导航。」, no tool call.
        guard.onUserTranscript("不用了。")
        assertNotNull(guard.onResponseDone(message, "已取消导航。"))
    }

    @Test
    fun claimDetectionCoversTheOtherTools() {
        assertTrue(ActionClaimGuard.claimsDone("已为你播放音乐。"))
        assertTrue(ActionClaimGuard.claimsDone("现在导航已退出。"))
        assertTrue(ActionClaimGuard.claimsDone("好的，空调关了。"))
        assertTrue(!ActionClaimGuard.claimsDone("抱歉，没有找到这个地点。"))
    }

    // ---- a claim is false on its own terms -----------------------------------------------

    @Test
    fun aFabricatedClaimIsCaughtEvenWhenTheRequestWasNotUnderstood() {
        // Verbatim from the device log, 2026-09-19. 「返屋企啦」 reached the model as garbage, so
        // every request-shaped check went blind - and it still announced a navigation that had
        // not happened, with outputs=[message].
        guard.onUserTranscript("发诺克拉。")
        val correction = guard.onResponseDone(message, "导航到家。正在搜索您的家地址，请稍候。")
        assertNotNull(correction, "an action claim with no tool call is false whatever was heard")
        assertTrue(
            correction!!.contains("没有执行"),
            "the correction must say nothing happened, not perform a guess",
        )
    }

    @Test
    fun anAppInitiatedClaimIsStillLeftAlone() {
        // No driver utterance means the app started the turn itself, and the claim may be about
        // something the app really did. This check is for a driver who spoke and was misheard.
        assertNull(guard.onResponseDone(message, "已为你播放音乐。"))
    }

    @Test
    fun anOrdinaryReplyWithNoClaimIsLeftAlone() {
        // The correction costs the driver a turn, so it must not fire on chat.
        guard.onUserTranscript("你好啊。")
        assertNull(guard.onResponseDone(message, "你好，有什么可以帮你的？"))
        guard.onUserTranscript("今天真不错。")
        assertNull(guard.onResponseDone(message, "是啊，天气不错。"))
    }

    @Test
    fun aDeclineIsNotAClaim() {
        guard.onUserTranscript("发诺克拉。")
        assertNull(guard.onResponseDone(message, "抱歉，我没听清，可以再说一遍吗？"))
    }

    @Test
    fun theCorrectionIsSentOnlyOncePerUtterance() {
        guard.onUserTranscript("发诺克拉。")
        assertNotNull(guard.onResponseDone(message, "导航到家。正在搜索。"))
        assertNull(guard.onResponseDone(message, "导航到家。正在搜索。"))
    }

    @Test
    fun aClaimThatFollowedARealToolCallIsLeftAlone() {
        guard.onUserTranscript("发诺克拉。")
        assertNull(guard.onResponseDone(call, ""))
        assertNull(guard.onResponseDone(message, "已经帮你导航到家了。"))
    }

    @Test
    fun everyWordingTheModelActuallyUsedIsCaught() {
        // All three verbatim from device logs, 2026-09-19, each after the same garbled input and
        // each with outputs=[message]. Two of them escaped earlier versions of this check.
        listOf(
            "有点热啊，我帮你调低一点温度。",
            "我帮你调低温度，现在凉快点没？",
            "我调低点温度先。",
        ).forEach { reply ->
            val fresh = ActionClaimGuard()
            fresh.onUserTranscript("有的人帮我舒服的。")
            assertNotNull(fresh.onResponseDone(message, reply), "not caught: $reply")
        }
    }

    @Test
    fun aPromisedActionThatNeverHappensIsAlsoCaught() {
        // Verbatim from the device log, 2026-09-19. The cabin stayed hot, and the driver was told
        // it was being handled - which is worse than being told nothing.
        guard.onUserTranscript("有的人帮我舒服的。")
        val correction = guard.onResponseDone(message, "有点热啊，我帮你调低一点温度。")
        assertNotNull(correction, "a promise with no tool call is as empty as a false claim")
    }

    @Test
    fun aPromiseFollowedByARealToolCallIsLeftAlone() {
        guard.onUserTranscript("有的人帮我舒服的。")
        assertNull(guard.onResponseDone(call, ""))
        assertNull(guard.onResponseDone(message, "我帮你调低了一点温度。"))
    }

    @Test
    fun chatThatMentionsHelpingIsNotAPromisedAction() {
        // 帮你 with no car action in it must not fire: the correction costs a turn at the wheel.
        guard.onUserTranscript("你好啊。")
        assertNull(guard.onResponseDone(message, "你好，我可以帮你做很多事情。"))
    }

    @Test
    fun aReplyThatClaimsAndAsksInOneBreathIsStillAClaim() {
        // Verbatim from the device log, 2026-09-19. It escaped the first version of this check
        // purely because it ended in 「？」 — while the cabin stayed exactly as hot.
        guard.onUserTranscript("有的人帮我舒服的。")
        assertNotNull(guard.onResponseDone(message, "我帮你调低温度，现在凉快点没？"))
    }

    @Test
    fun anHonestInabilityIsNeverCorrected() {
        guard.onUserTranscript("发诺克拉。")
        assertNull(guard.onResponseDone(message, "抱歉，我帮不了你调这个，没听清。"))
    }

    @Test
    fun aKnownRequestIsPerformedRatherThanQueried() {
        // Verbatim from the device log, 2026-09-20. 「算了」 is a request this app understands, so
        // the follow-up must make the model run the tool - not tell the driver it misheard a
        // sentence it heard correctly.
        guard.onUserTranscript("算了。")
        val nudge = guard.onResponseDone(message, "退出导航中，请选择下一个目的地。")
        assertNotNull(nudge)
        assertTrue(nudge!!.contains("算了"), "the follow-up must carry the request itself")
    }

    @Test
    fun anImplicitComfortRequestCountsAsSomethingWeCanAct0n() {
        // Measured on device 2026-09-20: 「有点热」 contains no control word - no 空调, no 温度,
        // no 调 - so the guard treated it as unclassified and told the driver 「刚才没听清楚」
        // about a sentence SPEC-006 resolves to a concrete adjustment.
        assertTrue(ActionClaimGuard.isControlRequest("有点热"))
        assertTrue(ActionClaimGuard.isControlRequest("空调调到22度"))
        assertFalse(ActionClaimGuard.isControlRequest("今天天气怎么样"))
    }
}
