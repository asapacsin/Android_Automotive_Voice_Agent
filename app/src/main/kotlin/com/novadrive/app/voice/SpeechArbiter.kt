package com.novadrive.app.voice

/**
 * SPEC-012: the one owner of whether 小诺 may speak and whether the microphone may reach Baidu.
 *
 * Before step 3 the answer was split across `NavigationState` (P1 mute window), the guidance
 * listener, `GuidanceMicGate` (P3) and `AndroidPlaybackPort.applyFocusChange`. This class holds the same
 * rules as one table, driven by events and an injected clock so every row is testable on the JVM.
 * Wired in SPEC-012 step 3 through [SpeechAuthority]; `SpeechRulesCharacterizationTest` pins every
 * combination of inputs to the behaviour those owners had.
 *
 * Rows, highest first, for a new chunk of reply audio:
 * R4 permanent focus loss → DROP · R6 navigating outside the permitted window → DROP (P1) ·
 * R1 guidance speaking → HOLD · R5 transient focus loss → HOLD · otherwise PLAY.
 * Volume: a duck request is honoured unless a permitted reply is playing during navigation (R7/R8).
 * Uplink: closed while guidance speaks and for [tailMs] after (R1/R2), reopened after
 * [maxClosedMs] if the end is never reported (R3).
 *
 * R0 (driver utterance protection, 2026-09-28): guidance that starts while the server reports the
 * driver mid-utterance (`speech_started` without `speech_stopped`, fed by [onDriverSpeaking]) does
 * not close the uplink until that utterance ends or [protectMaxMs] has gone since the guidance
 * began, whichever is first. Closing it cut the driver off and the turn was lost (owner demo: the
 * mic closed 1.9 s into 「有点热」, no transcript ever arrived). The guidance voice is Amap's own and
 * cannot be deferred; the cost is that its first seconds may reach Baidu mixed with the driver.
 *
 * R6a (SPEC-012 B5, workload hold): while navigating, once the distance to the next manoeuvre
 * ([onManeuverDistance]) falls under [holdDistanceM], a permitted reply that has **not started**
 * is HELD until the manoeuvre is passed (the distance jumps up by more than [passJumpM]) or
 * [holdMaxMs] has gone since the zone was entered, whichever is first. A reply already playing
 * ([onReplyAudio] seen, [onReplyEnded] not yet) is never cut. The P1 window does not expire
 * while a reply is being workload-held, so the held answer plays rather than being dropped.
 *
 * SPEC-018 (ADR-014): assistant guidance — a model turn answering an app prompt — is decided by
 * [guidanceChunk], not [reply]: exempt from P1 (R6) and R6a, DROP on R4 or once [abandon]ed, HOLD
 * under Amap guidance (R1) or R5. While it is open or playing an ordinary reply is HELD, and its
 * playout closes the uplink exactly like Amap guidance (R0–R3 unchanged).
 */
class SpeechArbiter(
    private val clock: () -> Long,
    private val tailMs: Long = TAIL_MS,
    private val maxClosedMs: Long = MAX_CLOSED_MS,
    private val windowMs: Long = WINDOW_MS,
    private val holdDistanceM: Int = WORKLOAD_HOLD_DISTANCE_M,
    private val holdMaxMs: Long = WORKLOAD_HOLD_MAX_MS,
    private val passJumpM: Int = MANEUVER_PASSED_JUMP_M,
    private val protectMaxMs: Long = UTTERANCE_PROTECT_MAX_MS,
) {
    enum class Reply { PLAY, HOLD, DROP }
    enum class Volume { FULL, DUCK }
    enum class Uplink { OPEN, CLOSED }
    enum class Focus { HELD, DUCK, TRANSIENT_LOSS, PERMANENT_LOSS }

    private val lock = Any()
    private var navigating = false
    private var windowUntilMs = 0L
    private var focus = Focus.HELD
    private var amapGuidance = false
    /** SPEC-018: assistant guidance audio is actually playing ([onGuidancePlayout]). */
    private var assistantGuidance = false
    /** SPEC-018: the app prompt whose response is open (OPENED seen, COMPLETED/VOIDED not yet). */
    private var openGuidance: String? = null
    private val abandoned = ArrayDeque<String>()
    /** R1–R3 treat Amap guidance and playing assistant guidance alike. */
    private val guidanceSpeaking: Boolean get() = amapGuidance || assistantGuidance
    private var guidanceStartedMs = 0L
    private var guidanceEndedMs: Long? = null
    private var replyPlaying = false
    private var driverSpeaking = false
    /** R0: until when guidance may not close the uplink on the driver's current utterance. */
    private var protectUntilMs: Long? = null
    private var lastDistanceM: Int? = null
    /** When the current manoeuvre's hold zone was entered; null outside the zone or once released. */
    private var zoneEnteredMs: Long? = null
    /** The current manoeuvre's hold reached its cap; do not re-hold until it is passed. */
    private var zoneSpent = false

    /** Observer of the navigating input (SPEC-018 G-2: the listening lifecycle); called outside the lock. */
    @Volatile var navigatingObserver: ((Boolean) -> Unit)? = null

    fun onNavigating(value: Boolean) {
        synchronized(lock) {
            navigating = value
            if (!value) {
                windowUntilMs = 0L
                clearManeuver()
            }
        }
        navigatingObserver?.invoke(value)
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

    /**
     * A permitted reply chunk arrived: keep the window open until it finishes; never reopen it.
     * [started] is false when the chunk was only queued behind a hold — the reply has not started
     * playing, so R6a may still hold it (SPEC-012 step 4).
     */
    fun onReplyAudio(started: Boolean = true) = synchronized(lock) {
        val now = clock()
        if (now <= windowUntilMs) windowUntilMs = maxOf(windowUntilMs, now + windowMs)
        if (started) replyPlaying = true
    }

    fun onFocus(value: Focus) = synchronized(lock) { focus = value }

    /** R0 input: the server's VAD says the driver is speaking (true) or has stopped (false). */
    fun onDriverSpeaking(speaking: Boolean) = synchronized(lock) {
        driverSpeaking = speaking
        if (!speaking) protectUntilMs = null
    }

    fun onGuidanceSpeaking(speaking: Boolean) = synchronized(lock) { setGuidance(speaking, assistantGuidance, restart = speaking) }

    /** SPEC-018: assistant guidance audio started (true) or drained / was flushed (false). */
    fun onGuidancePlayout(playing: Boolean) = synchronized(lock) { setGuidance(amapGuidance, playing, restart = playing && !assistantGuidance) }

    /** SPEC-018 correlation: OPENED opens [promptId]; COMPLETED / VOIDED close it if it matches. */
    fun onAssistantGuidance(promptId: String, phase: com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase) =
        synchronized(lock) {
            if (phase == com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.OPENED) {
                openGuidance = promptId
            } else if (openGuidance == promptId) {
                openGuidance = null
            }
        }

    /** SPEC-018 B2a: [promptId] was handed elsewhere; its remaining chunks DROP. */
    fun abandon(promptId: String) = synchronized(lock) {
        if (promptId in abandoned) return@synchronized
        abandoned.addLast(promptId)
        while (abandoned.size > ABANDONED_KEPT) abandoned.removeFirst()
    }

    /** SPEC-018: the decision for a chunk of assistant guidance; independent of the listening state. */
    fun guidanceChunk(promptId: String): Reply = synchronized(lock) {
        when {
            promptId in abandoned || focus == Focus.PERMANENT_LOSS -> Reply.DROP
            amapGuidance || focus == Focus.TRANSIENT_LOSS -> Reply.HOLD
            else -> Reply.PLAY
        }
    }

    /** SPEC-018: an assistant guidance prompt is open or its audio is playing. */
    fun assistantGuidanceActive(): Boolean = synchronized(lock) { openGuidance != null || assistantGuidance }

    /** Whether the player must pause for assistant guidance now (R1 / R5). */
    fun guidanceHeld(): Boolean = synchronized(lock) { amapGuidance || focus == Focus.TRANSIENT_LOSS }

    /** [restart]: a guidance prompt began now (an Amap prompt always restarts the R3 cap, as before). */
    private fun setGuidance(amap: Boolean, assistant: Boolean, restart: Boolean) {
        val was = guidanceSpeaking
        amapGuidance = amap
        assistantGuidance = assistant
        if (guidanceSpeaking && restart) {
            val now = clock()
            guidanceStartedMs = now
            guidanceEndedMs = null
            // R0: bounded from the first prompt that met the utterance; a back-to-back prompt does not extend it.
            if (driverSpeaking && protectUntilMs == null) protectUntilMs = now + protectMaxMs
        } else if (was && !guidanceSpeaking) {
            guidanceEndedMs = clock()
        }
    }

    /** SPEC-018 R2: the session stopped — no open or playing assistant guidance survives it. */
    fun clearGuidance() = synchronized(lock) {
        openGuidance = null
        setGuidance(amapGuidance, assistant = false, restart = false)
    }

    /** Session over: nothing held survives it (SPEC-009 epoch). */
    fun reset() = synchronized(lock) {
        navigating = false
        windowUntilMs = 0L
        focus = Focus.HELD
        amapGuidance = false
        assistantGuidance = false
        openGuidance = null
        abandoned.clear()
        guidanceEndedMs = null
        replyPlaying = false
        driverSpeaking = false
        protectUntilMs = null
        clearManeuver()
    }

    /** A workload hold (R6a) keeps the P1 window open, so a held answer is not later dropped. */
    fun reply(): Reply = synchronized(lock) {
        when {
            focus == Focus.PERMANENT_LOSS -> Reply.DROP
            muted() -> Reply.DROP
            guidanceSpeaking -> Reply.HOLD
            openGuidance != null -> Reply.HOLD // SPEC-018: guidance pre-empts chatter
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
        if (closed && !protecting(now)) Uplink.CLOSED else Uplink.OPEN
    }

    /** R0 is holding the uplink open over guidance right now (for the log only). */
    fun uplinkProtected(): Boolean = synchronized(lock) {
        val now = clock()
        val guidance = guidanceSpeaking || guidanceEndedMs?.let { now - it < tailMs } == true
        guidance && protecting(now)
    }

    private fun protecting(now: Long): Boolean {
        val until = protectUntilMs ?: return false
        return driverSpeaking && now < until
    }

    /** Why a DROP: true when it is the P1 navigation mute rather than a focus loss (for the log only). */
    fun navigationMuted(): Boolean = synchronized(lock) { muted() }

    /** Whether the current HOLD is the workload hold (R6a), for the log only. No side effects. */
    fun workloadHeld(): Boolean = synchronized(lock) {
        if (guidanceSpeaking || openGuidance != null || focus == Focus.TRANSIENT_LOSS || focus == Focus.PERMANENT_LOSS) return@synchronized false
        if (!navigating || replyPlaying || muted()) return@synchronized false
        val entered = zoneEnteredMs ?: return@synchronized false
        clock() - entered < holdMaxMs
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
        const val TAIL_MS = 500L
        const val MAX_CLOSED_MS = 20_000L
        /** R0 cap: a driver command is a few seconds; past this, guidance closes the uplink as before. */
        const val UTTERANCE_PROTECT_MAX_MS = 8_000L
        const val WORKLOAD_HOLD_DISTANCE_M = 150
        const val WORKLOAD_HOLD_MAX_MS = 8_000L
        const val MANEUVER_PASSED_JUMP_M = 20
        /** SPEC-018: how many abandoned prompt ids are remembered. */
        const val ABANDONED_KEPT = 8
    }
}
