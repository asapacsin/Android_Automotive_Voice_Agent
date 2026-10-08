package com.novadrive.app.voice

/**
 * SPEC-020: the fixed wait cues, and the decision of which one is due. Pure: no clock, no voice.
 * The cues say she is working on it, never that anything is done (I-1, ActionClaimGuardTest).
 */
object WaitCues {
    const val ACK_ACTION = "收到，正在处理。"
    const val ACK_CHAT = "嗯，我想想。"
    const val PROVIDER_SLOW = "网络有点慢，请稍等。"
    const val VERIFYING = "我确认一下，马上回答你。"
    const val STILL_WAITING = "还在处理，网络可能不太稳定，再等我一下。"
    const val TOOL_ROUTE = "正在搜索路线，稍等一下。"
    const val TOOL_QUERY = "正在查询，稍等一下。"
    const val TOOL_MUSIC = "正在找歌，稍等一下。"
    const val TOOL_CAMERA = "正在看画面，稍等一下。"
    const val TOOL_DEFAULT = "正在处理，稍等一下。"

    const val ACK_ACTION_MS = 1_000L
    const val ACK_CHAT_MS = 1_800L
    const val REASON_MS = 5_000L
    const val STILL_WAITING_MS = 12_000L
    private val THRESHOLDS = longArrayOf(ACK_ACTION_MS, ACK_CHAT_MS, REASON_MS, STILL_WAITING_MS)

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
    private var ackDone = false
    private var reasonDone = false
    private var stillDone = false

    fun onToolCall(name: String) { if (toolName == null) toolName = name; toolOutstanding = true }
    fun onResponseStarted() { responseStarted = true; toolOutstanding = false }
    fun onToolResultDelivered() { toolOutstanding = false }

    /** Something says the driver spoke to her: the model answered, or the audio looked like speech. */
    val hasEvidence: Boolean get() = responseStarted || toolName != null || !suspiciousAudio

    /** The clock runs and may still speak. */
    val live: Boolean get() = clockStarted && !stopped

    /** The cue due at [elapsedMs] (marking it used), or null. Call repeatedly until null. */
    fun due(elapsedMs: Long): WaitCue? {
        if (!hasEvidence) return null  // C2, C3 and C6 never speak without evidence; C1, C4, C5 need it
        val tool = toolName
        if (!ackDone && (elapsedMs >= WaitCues.ACK_CHAT_MS || (tool != null && elapsedMs >= WaitCues.ACK_ACTION_MS))) {
            ackDone = true
            // Both ack thresholds passed in one wake-up: an ack late by seconds says nothing useful.
            if (elapsedMs < WaitCues.REASON_MS) {
                return if (tool != null) WaitCue("ack_action", WaitCues.ACK_ACTION) else WaitCue("ack_chat", WaitCues.ACK_CHAT)
            }
        }
        if (!reasonDone && elapsedMs >= WaitCues.REASON_MS) {
            reasonDone = true
            if (elapsedMs < WaitCues.STILL_WAITING_MS) {
                return when {
                    tool != null -> WaitCue("tool_running", WaitCues.toolPhrase(tool))
                    !responseStarted -> WaitCue("provider_slow", WaitCues.PROVIDER_SLOW)
                    else -> WaitCue("verifying", WaitCues.VERIFYING)
                }
            }
        }
        if (!stillDone && elapsedMs >= WaitCues.STILL_WAITING_MS) {
            stillDone = true
            return WaitCue("still_waiting", WaitCues.STILL_WAITING)
        }
        return null
    }
}
