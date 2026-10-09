package com.novadrive.app.voice

/**
 * SPEC-020: the fixed wait cues, and the decision of which one is due. Pure: no clock, no voice.
 * The cues say she is working on it, never that anything is done (I-1, ActionClaimGuardTest).
 */
object WaitCues {
    /** One progress line, spoken once, and only when nothing useful has been spoken by [PROGRESS_MS]. */
    const val PROGRESS = "收到，正在处理。"
    /** A different sentence at [DELAY_MS]. It does not repeat [PROGRESS]. */
    const val DELAY = "还在处理，网络可能不太稳定，再等我一下。"

    const val ACK_ACTION = PROGRESS
    const val ACK_CHAT = "嗯，我想想。"
    const val PROVIDER_SLOW = "网络有点慢，请稍等。"
    const val VERIFYING = "我确认一下，马上回答你。"
    const val STILL_WAITING = DELAY
    const val TOOL_ROUTE = "正在搜索路线，稍等一下。"
    const val TOOL_QUERY = "正在查询，稍等一下。"
    const val TOOL_MUSIC = "正在找歌，稍等一下。"
    const val TOOL_CAMERA = "正在看画面，稍等一下。"
    const val TOOL_DEFAULT = "正在处理，稍等一下。"

    /** Owner timing 2026-10-09: silent, then a visual, then at most two different spoken lines. */
    const val VISUAL_MS = 3_000L
    const val PROGRESS_MS = 7_000L
    const val DELAY_MS = 12_000L
    const val ACK_ACTION_MS = PROGRESS_MS
    const val ACK_CHAT_MS = PROGRESS_MS
    const val REASON_MS = DELAY_MS
    const val STILL_WAITING_MS = DELAY_MS
    private val THRESHOLDS = longArrayOf(VISUAL_MS, PROGRESS_MS, DELAY_MS)

    val ALL: List<String> = listOf(
        ACK_ACTION, ACK_CHAT, PROVIDER_SLOW, VERIFYING, STILL_WAITING,
        TOOL_ROUTE, TOOL_QUERY, TOOL_MUSIC, TOOL_CAMERA, TOOL_DEFAULT,
    )

    fun toolPhrase(tool: String): String = when (tool) {
        "navigate_to", "choose_navigation_option" -> TOOL_ROUTE
        "query_live_info" -> TOOL_QUERY
        "play_music", "control_music" -> TOOL_MUSIC
        "describe_camera_view" -> TOOL_CAMERA
        else -> TOOL_DEFAULT
    }

    /** The next threshold strictly after [elapsedMs], or null when none is left. */
    fun nextThresholdAfter(elapsedMs: Long): Long? = THRESHOLDS.firstOrNull { it > elapsedMs }
}

/** A cue to speak: the log [code] and the fixed [text]. */
data class WaitCue(val code: String, val text: String)

/**
 * One driver turn's wait-cue state, from his onset until her reply is audible. It remembers what was
 * seen since the onset; the clock starts at his end of speech. At most one of C1/C2, one of C3/C4/C5
 * and one C6 per turn. Not thread-safe: the caller synchronises.
 */
class WaitCueTurn {
    var toolName: String? = null
        private set
    var responseStarted = false
        private set
    /** A tool call was seen and no ResponseStarted came after it: Gemini answers in a fresh response. */
    var toolOutstanding = false
        private set
    /** Her reply's words were queued or became audible: no clock may start, a running one stops. */
    var replyUnderway = false
    /** The closed uplink segment looked like a cough or a knock, not a sentence. */
    var suspiciousAudio = false
    var clockStarted = false
    var stopped = false
    var noEvidenceLogged = false
    /** The 3 s working mark is on screen. Cleared when the turn ends or she speaks for real. */
    var visualShown = false
        private set
    private var visualDone = false
    private var progressDone = false
    private var delayDone = false

    fun onToolCall(name: String) { if (toolName == null) toolName = name; toolOutstanding = true }
    fun onResponseStarted() { responseStarted = true; toolOutstanding = false }
    fun onToolResultDelivered() { toolOutstanding = false }

    /** Something says the driver spoke to her: the model answered, or the audio looked like speech. */
    val hasEvidence: Boolean get() = responseStarted || toolName != null || !suspiciousAudio

    /** The clock runs and may still speak. */
    val live: Boolean get() = clockStarted && !stopped

    /** The cue due at [elapsedMs] (marking it used), or null. Call repeatedly until null. */
    fun due(elapsedMs: Long): WaitCue? {
        if (!hasEvidence || replyUnderway || stopped) return null
        if (!visualDone && elapsedMs >= WaitCues.VISUAL_MS) {
            visualDone = true
            visualShown = true
            return WaitCue("visual", "")
        }
        if (!progressDone && elapsedMs >= WaitCues.PROGRESS_MS) {
            progressDone = true
            // A wake-up that already missed 7 s says the delay once, not a progress line and then the delay.
            if (elapsedMs < WaitCues.DELAY_MS) return WaitCue("progress", WaitCues.PROGRESS)
        }
        if (!delayDone && elapsedMs >= WaitCues.DELAY_MS) {
            delayDone = true
            return WaitCue("delay", WaitCues.DELAY)
        }
        return null
    }

    fun clearVisual() {
        visualShown = false
    }
}
