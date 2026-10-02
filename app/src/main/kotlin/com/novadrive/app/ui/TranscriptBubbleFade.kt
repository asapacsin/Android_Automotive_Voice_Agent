package com.novadrive.app.ui

import com.novadrive.ingress.realtime.VoiceUiState

/**
 * B-035: the last 你:/小诺: exchange leaves the map by itself. The bubble clears once the
 * conversation has been quiet for [fadeMs]: nobody speaking, 小诺 not thinking, nothing left in
 * the playout queue, and no question on screen waiting for the driver (a selection list, the open
 * camera). Any of those during the wait starts the quiet period again, and so does a new line, so
 * an older wait never clears a newer exchange (the B-033 rule).
 *
 * Pure and clock-injected: the view polls [tick] while a line is shown. Not thread-safe; the view
 * calls it on the main thread only.
 */
internal class TranscriptBubbleFade(private val fadeMs: Long = TRANSCRIPT_FADE_MS) {
    /** True while the bubble holds transcript lines this policy may clear. */
    var showing: Boolean = false
        private set

    private var quietSinceMs: Long? = null

    /** A transcript line was written into the bubble: the quiet period starts again. */
    fun lineShown() {
        showing = true
        quietSinceMs = null
    }

    /** Something else took the bubble (an error card, the placeholder): nothing to clear. */
    fun replaced() {
        showing = false
        quietSinceMs = null
    }

    /**
     * One poll at [nowMs]. [state] is the session's turn state; [held] is everything else that
     * keeps the exchange relevant (playout still audible, a question waiting on screen). True means
     * "clear the bubble now", and the policy forgets the lines.
     */
    fun tick(nowMs: Long, state: VoiceUiState, held: Boolean): Boolean {
        if (!showing) return false
        if (held || conversationBusy(state)) {
            quietSinceMs = null
            return false
        }
        val since = quietSinceMs ?: nowMs.also { quietSinceMs = it }
        if (nowMs - since < fadeMs) return false
        replaced()
        return true
    }

    companion object {
        /** The driver is talking, or 小诺 is working out or saying a reply. */
        fun conversationBusy(state: VoiceUiState): Boolean =
            state == VoiceUiState.USER_SPEAKING || state == VoiceUiState.THINKING || state == VoiceUiState.SPEAKING
    }
}

/** B-035: how long a quiet conversation keeps its last exchange on the map. */
internal const val TRANSCRIPT_FADE_MS = 10_000L

/** How often the view checks whether the conversation is quiet, while a line is shown. */
internal const val TRANSCRIPT_FADE_POLL_MS = 1_000L
