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
 *
 * R6a (SPEC-012 B5, workload hold): while navigating, once the distance to the next manoeuvre
 * ([onManeuverDistance]) falls under [holdDistanceM], a permitted reply that has **not started**
 * is HELD until the manoeuvre is passed (the distance jumps up by more than [passJumpM]) or
 * [holdMaxMs] has gone since the zone was entered, whichever is first. A reply already playing
 * ([onReplyAudio] seen, [onReplyEnded] not yet) is never cut. The P1 window does not expire
 * while a reply is being workload-held, so the held answer plays rather than being dropped.
 */
class SpeechArbiter(
    private val clock: () -> Long,
    private val tailMs: Long = GuidanceMicGate.DEFAULT_TAIL_MS,
    private val maxClosedMs: Long = GuidanceMicGate.DEFAULT_MAX_CLOSED_MS,
    private val windowMs: Long = WINDOW_MS,
    private val holdDistanceM: Int = WORKLOAD_HOLD_DISTANCE_M,
    private val holdMaxMs: Long = WORKLOAD_HOLD_MAX_MS,
    private val passJumpM: Int = MANEUVER_PASSED_JUMP_M,
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
    private var replyPlaying = false
    private var lastDistanceM: Int? = null
    /** When the current manoeuvre's hold zone was entered; null outside the zone or once released. */
    private var zoneEnteredMs: Long? = null
    /** The current manoeuvre's hold reached its cap; do not re-hold until it is passed. */
    private var zoneSpent = false

    fun onNavigating(value: Boolean) = synchronized(lock) {
        navigating = value
        if (!value) {
            windowUntilMs = 0L
            clearManeuver()
        }
    }

    /**
     * Distance in metres to the next manoeuvre (`NaviInfo.curStepRetainDistance`), or null when
     * unknown. A jump up of more than [passJumpM] means the manoeuvre was passed.
     */
    fun onManeuverDistance(meters: Int?) = synchronized(lock) {
        val previous = lastDistanceM
        lastDistanceM = meters
        if (meters == null) {
            zoneEnteredMs = null
            zoneSpent = false
            return@synchronized
        }
        if (previous != null && meters > previous + passJumpM) {
            zoneEnteredMs = null
            zoneSpent = false
        }
        if (meters < holdDistanceM) {
            if (zoneEnteredMs == null && !zoneSpent) zoneEnteredMs = clock()
        } else {
            zoneEnteredMs = null
            zoneSpent = false
        }
    }

    /** The reply that was playing has finished (turn complete or flushed). */
    fun onReplyEnded() = synchronized(lock) { replyPlaying = false }

    /** The driver asked something, or a tool asked for confirmation: its answer may be spoken. */
    fun onDriverRequest() = synchronized(lock) { windowUntilMs = clock() + windowMs }

    fun onConfirmation() = onDriverRequest()

    /** A permitted reply is playing: keep the window open until it finishes; never reopen it. */
    fun onReplyAudio() = synchronized(lock) {
        val now = clock()
        if (now <= windowUntilMs) windowUntilMs = maxOf(windowUntilMs, now + windowMs)
        replyPlaying = true
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
        replyPlaying = false
        clearManeuver()
    }

    /** A workload hold (R6a) keeps the P1 window open, so a held answer is not later dropped. */
    fun reply(): Reply = synchronized(lock) {
        when {
            focus == Focus.PERMANENT_LOSS -> Reply.DROP
            muted() -> Reply.DROP
            guidanceSpeaking -> Reply.HOLD
            focus == Focus.TRANSIENT_LOSS -> Reply.HOLD
            workloadHolding() -> {
                windowUntilMs = maxOf(windowUntilMs, clock() + windowMs)
                Reply.HOLD
            }
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

    /** R6a. Only for a reply not yet started; the cap is enforced here so no timer is needed. */
    private fun workloadHolding(): Boolean {
        if (!navigating || replyPlaying) return false
        val entered = zoneEnteredMs ?: return false
        if (clock() - entered >= holdMaxMs) {
            zoneEnteredMs = null
            zoneSpent = true
            return false
        }
        return true
    }

    private fun clearManeuver() {
        lastDistanceM = null
        zoneEnteredMs = null
        zoneSpent = false
    }

    private fun muted(): Boolean = navigating && clock() > windowUntilMs

    companion object {
        const val WINDOW_MS = 10_000L
        const val WORKLOAD_HOLD_DISTANCE_M = 150
        const val WORKLOAD_HOLD_MAX_MS = 8_000L
        const val MANEUVER_PASSED_JUMP_M = 20
    }
}
