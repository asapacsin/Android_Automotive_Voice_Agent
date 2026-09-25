package com.novadrive.app.voice

/**
 * SPEC-012: the one owner of whether 小诺 may speak and whether the microphone may reach Baidu.
 *
 * Today the answer is split across `NavigationState` (P1 mute window), the guidance listener,
 * [GuidanceMicGate] (P3) and `AndroidPlaybackPort.applyFocusChange`. This class holds the same
 * rules as one table, driven by events and an injected clock so every row is testable on the JVM.
 * It is **not wired yet** (SPEC-012 step 3); until then it must answer exactly as those owners do,
 * which `SpeechRulesCharacterizationTest` checks for every combination of inputs.
 *
 * Rows, highest first, for a new chunk of reply audio:
 * R4 permanent focus loss → DROP · R6 navigating outside the permitted window → DROP (P1) ·
 * R1 guidance speaking → HOLD · R5 transient focus loss → HOLD · otherwise PLAY.
 * Volume: a duck request is honoured unless a permitted reply is playing during navigation (R7/R8).
 * Uplink: closed while guidance speaks and for [tailMs] after (R1/R2), reopened after
 * [maxClosedMs] if the end is never reported (R3).
 */
class SpeechArbiter(
    private val clock: () -> Long,
    private val tailMs: Long = GuidanceMicGate.DEFAULT_TAIL_MS,
    private val maxClosedMs: Long = GuidanceMicGate.DEFAULT_MAX_CLOSED_MS,
    private val windowMs: Long = WINDOW_MS,
) {
    enum class Reply { PLAY, HOLD, DROP }
    enum class Volume { FULL, DUCK }
    enum class Uplink { OPEN, CLOSED }
    enum class Focus { HELD, DUCK, TRANSIENT_LOSS, PERMANENT_LOSS }

    private val lock = Any()
    private var navigating = false
    private var windowUntilMs = 0L
    private var focus = Focus.HELD
    private var guidanceSpeaking = false
    private var guidanceStartedMs = 0L
    private var guidanceEndedMs: Long? = null

    fun onNavigating(value: Boolean) = synchronized(lock) {
        navigating = value
        if (!value) windowUntilMs = 0L
    }

    /** The driver asked something, or a tool asked for confirmation: its answer may be spoken. */
    fun onDriverRequest() = synchronized(lock) { windowUntilMs = clock() + windowMs }

    fun onConfirmation() = onDriverRequest()

    /** A permitted reply is playing: keep the window open until it finishes; never reopen it. */
    fun onReplyAudio() = synchronized(lock) {
        val now = clock()
        if (now <= windowUntilMs) windowUntilMs = maxOf(windowUntilMs, now + windowMs)
    }

    fun onFocus(value: Focus) = synchronized(lock) { focus = value }

    fun onGuidanceSpeaking(speaking: Boolean) = synchronized(lock) {
        if (speaking) {
            guidanceSpeaking = true
            guidanceStartedMs = clock()
            guidanceEndedMs = null
        } else if (guidanceSpeaking) {
            guidanceSpeaking = false
            guidanceEndedMs = clock()
        }
    }

    /** Session over: nothing held survives it (SPEC-009 epoch). */
    fun reset() = synchronized(lock) {
        navigating = false
        windowUntilMs = 0L
        focus = Focus.HELD
        guidanceSpeaking = false
        guidanceEndedMs = null
    }

    fun reply(): Reply = synchronized(lock) {
        when {
            focus == Focus.PERMANENT_LOSS -> Reply.DROP
            muted() -> Reply.DROP
            guidanceSpeaking -> Reply.HOLD
            focus == Focus.TRANSIENT_LOSS -> Reply.HOLD
            else -> Reply.PLAY
        }
    }

    fun volume(): Volume = synchronized(lock) {
        if (focus == Focus.DUCK && !(navigating && !muted())) Volume.DUCK else Volume.FULL
    }

    fun uplink(): Uplink = synchronized(lock) {
        val now = clock()
        val closed = if (guidanceSpeaking) {
            now - guidanceStartedMs < maxClosedMs
        } else {
            guidanceEndedMs?.let { now - it < tailMs } ?: false
        }
        if (closed) Uplink.CLOSED else Uplink.OPEN
    }

    private fun muted(): Boolean = navigating && clock() > windowUntilMs

    companion object {
        const val WINDOW_MS = 10_000L
    }
}
