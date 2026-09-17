package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionClaimGuardTest {
    private val guard = ActionClaimGuard()
    private val message = listOf("message")
    private val call = listOf("function_call")

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
}
