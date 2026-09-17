package com.novadrive.app.voice

/**
 * Local interception of "stop listening" (TERMINATE_LISTENING) before the model is involved.
 *
 * Only whole utterances are matched, never words inside a command: 「关闭小诺」 ends listening,
 * 「关闭导航」「关闭空调」「close the window」 do not (they go to the model and its tools).
 *
 * Precedence, highest first:
 * 1. A specific task command (anything that is not one of the phrases below) → model / tools.
 * 2. Contextual cancellation: while a destination or route list is on screen, or tool work is still
 *    pending, 「不用了」「never mind」「close」 belong to that task → model (exit_navigation_mode).
 *    「算了」「取消」「cancel」「stop」 are never intercepted: they keep their existing task meaning.
 * 3. Assistant-session termination: the phrases below → STANDBY.
 * 4. Everything else → model.
 */
object ListeningIntent {
    enum class Decision { TERMINATE_LISTENING, PASS_TO_MODEL }

    /** What is going on that a cancel-like phrase could refer to. */
    data class Context(
        /** A destination or route list is waiting for the driver's choice. */
        val pickerOpen: Boolean = false,
        /** A tool is still running or its result has not been answered yet. */
        val taskPending: Boolean = false,
    )

    /** Unambiguous in any context: they name the assistant or listening itself. */
    private val ALWAYS = setOf(
        "stoplistening", "gotosleep", "thatsall", "thatisall",
        "关闭小诺", "小诺关闭", "关掉小诺", "别听了", "不要听了", "停止监听", "停止聆听",
        "休息吧", "小诺休息吧", "你休息吧", "去休息吧", "小诺休息",
    )

    /** Could also mean "cancel this task"; only terminate when no task could consume them. */
    private val CONTEXTUAL = setOf("close", "nevermind", "不用了", "不需要了", "没事了")

    private val POLITE_SUFFIXES = listOf("谢谢你", "谢谢", "thanks", "thankyou", "please", "吧", "啦", "了", "啊")
    private val POLITE_PREFIXES = listOf("你好小诺", "小诺", "好的", "好", "ok", "okay", "hey")

    fun classify(utterance: String, context: Context): Decision {
        val phrase = normalise(utterance)
        if (phrase.isEmpty()) return Decision.PASS_TO_MODEL
        if (matches(phrase, ALWAYS)) return Decision.TERMINATE_LISTENING
        if (matches(phrase, CONTEXTUAL)) {
            return if (context.pickerOpen || context.taskPending) Decision.PASS_TO_MODEL else Decision.TERMINATE_LISTENING
        }
        return Decision.PASS_TO_MODEL
    }

    /** Also tries the phrase with a leading address / trailing courtesy removed (「小诺，别听了吧」). */
    private fun matches(phrase: String, set: Set<String>): Boolean {
        if (phrase in set) return true
        var p = phrase
        POLITE_PREFIXES.firstOrNull { p.startsWith(it) && p.length > it.length }?.let { p = p.removePrefix(it) }
        if (p in set) return true
        repeat(2) {
            POLITE_SUFFIXES.firstOrNull { p.endsWith(it) && p.length > it.length && p.removeSuffix(it) in set }?.let {
                return true
            }
            POLITE_SUFFIXES.firstOrNull { p.endsWith(it) && p.length > it.length }?.let { p = p.removeSuffix(it) }
            if (p in set) return true
        }
        return false
    }

    internal fun normalise(text: String): String =
        text.lowercase()
            .replace("’", "'")
            .replace("'", "")
            .filter { it.isLetterOrDigit() }

    /**
     * A user turn that counts as conversational activity for the inactivity timer. Noise the
     * server VAD turned into 「嗯」 or an empty transcript does not keep listening alive.
     */
    fun isMeaningful(utterance: String): Boolean {
        val p = normalise(utterance)
        return p.length >= 2 && p.any { it !in FILLERS }
    }

    private const val FILLERS = "嗯啊哦呃额唔哈呀噢喔诶欸ummhah"
}
