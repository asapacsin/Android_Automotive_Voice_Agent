package com.novadrive.app.voice

/**
 * Catches a reply that claims a car action happened when no tool was called.
 *
 * Measured 2026-09-17 (speech harness, fresh conversation, camera open): 「温度调高一点。」 was
 * answered 「调高温度了。」 with outputs=[message] and no control_climate call — the temperature did
 * not change. The persona already forbids this; the model still does it about 1 time in 4.
 *
 * When the driver asked for a control action (or about the camera picture) and the reply finished
 * without any tool call and without declining, the client sends [nudgeFor] as a follow-up turn so
 * the model performs the action and answers from the tool result. At most once per utterance.
 * The false sentence has already been spoken by then; the follow-up makes it true or corrects it.
 */
class ActionClaimGuard {
    private var userText: String? = null
    private var toolCalledThisTurn = false
    private var nudged = false

    @Synchronized
    fun onUserTranscript(text: String) {
        userText = text.trim().takeIf { it.isNotEmpty() }
        toolCalledThisTurn = false
        nudged = false
    }

    /** A response finished. Returns the follow-up text to send, or null. */
    @Synchronized
    fun onResponseDone(outputKinds: List<String>, assistantText: String): String? {
        if ("function_call" in outputKinds) {
            toolCalledThisTurn = true
            return null
        }
        if ("message" !in outputKinds) return null
        val request = userText ?: return null
        userText = null
        if (toolCalledThisTurn || nudged) return null
        val reply = assistantText.trim()
        val suspicious = when {
            isCameraQuestion(request) -> !declines(reply)
            isUnsupportedRequest(request) -> claimsDone(reply)
            isControlRequest(request) -> claimsDone(reply)
            else -> false
        }
        if (!suspicious) return null
        nudged = true
        return nudgeFor(request)
    }

    @Synchronized
    fun reset() {
        userText = null
        toolCalledThisTurn = false
        nudged = false
    }

    companion object {
        private val CONTROL_WORDS = listOf(
            "空调", "温度", "度", "风量", "风速", "暖风", "冷风", "除雾",
            "音乐", "歌", "播放", "暂停",
            "导航", "带我去", "出发", "目的地",
            "打开", "关闭", "关掉", "开启",
            "调高", "调低", "调大", "调小", "调到", "升高", "降低",
            "结束", "退出", "取消", "算了",
            "路线", "第一", "第二", "第三", "第四", "第五", "最快", "最短", "免费", "红绿灯",
        )
        /**
         * Requests with no tool. Measured 2026-09-17: the generic follow-up for 「音量调大。」 made the
         * model call control_climate and raise the fan — a wrong action. These get a correction instead.
         */
        private val UNSUPPORTED_WORDS = listOf("音量", "声音", "大声", "小声", "车窗", "窗户", "天窗", "座椅", "电话", "后备箱", "车门", "车灯", "雨刷")
        private val CAMERA_WORDS = listOf("镜头", "摄像头", "画面", "拍到", "看看前面", "前面有什么", "前面是什么", "前面是谁", "前面有谁")
        private val DONE_WORDS = listOf("已", "了", "好的", "正在", "为你", "为您", "马上", "这就")
        private val ACTION_WORDS = listOf(
            "调", "打开", "开启", "开了", "关闭", "关掉", "关了", "播放", "暂停", "停止", "停了",
            "导航", "出发", "设置", "设为", "退出", "结束", "取消", "升", "降", "提高",
            "选", "开始",
        )
        private val DECLINE_WORDS = listOf(
            "不支持", "无法", "不能", "没法", "没有", "暂不", "暂时不", "抱歉", "对不起", "没听清", "再说一遍",
            "请问", "吗", "？", "?", "哪", "什么",
        )

        fun isControlRequest(text: String): Boolean = CONTROL_WORDS.any { it in text }

        fun isUnsupportedRequest(text: String): Boolean = UNSUPPORTED_WORDS.any { it in text }

        fun isCameraQuestion(text: String): Boolean = CAMERA_WORDS.any { it in text }

        fun declines(reply: String): Boolean = DECLINE_WORDS.any { it in reply }

        fun claimsDone(reply: String): Boolean =
            !declines(reply) && DONE_WORDS.any { it in reply } && ACTION_WORDS.any { it in reply }

        /** Self-contained: it may land in a fresh conversation after a reset. */
        fun nudgeFor(request: String): String =
            if (isUnsupportedRequest(request)) {
                "用户刚才说：「$request」。你没有能完成这个请求的工具，上一句说已经完成是错误的。" +
                    "不要调用任何工具，只用一句话向用户更正：这个操作没有执行，暂时不支持。"
            } else {
                "用户刚才说：「$request」。你上一句回答没有调用任何工具，所以这个操作实际上并没有执行。" +
                    "现在请调用与这个请求完全对应的工具真正完成它（询问摄像头画面就调用 describe_camera_view），" +
                    "不要调用无关的工具，然后只根据工具返回的结果简短如实回答。"
            }
    }
}
