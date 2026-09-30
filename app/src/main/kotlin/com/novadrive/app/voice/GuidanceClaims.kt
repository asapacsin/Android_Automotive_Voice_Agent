package com.novadrive.app.voice

/**
 * SPEC-018 (ADR-014) hooks between the playback port and the guidance relay (next step). The
 * defaults are neutral: every prompt is claimed by the assistant and a cut is ignored, so nothing
 * changes until the relay installs its own.
 */
object GuidanceClaims {
    /** Before the first chunk of [promptId] plays: true lets 小诺 speak it; false abandons it. */
    @Volatile var claimAssistant: (promptId: String) -> Boolean = { true }

    /** An open prompt's audio was flushed or cut before COMPLETED (B2a: the relay may re-speak it). */
    @Volatile var onGuidanceCut: (promptId: String) -> Unit = {}

    /** [promptId]'s audio finished playing out (B3: the next guidance may speak only now). */
    @Volatile var onGuidanceDrained: (promptId: String) -> Unit = {}
}

/**
 * The playback port's per-prompt bookkeeping, kept free of Android so it is testable on the JVM.
 * [open] routes chunks (OPENED → COMPLETED/VOIDED); [playing] is the prompt whose audio reached the
 * player and has not drained yet.
 */
internal class GuidancePromptTracker(
    private val abandon: (String) -> Unit,
    private val claim: (String) -> Boolean = { GuidanceClaims.claimAssistant(it) },
    private val cut: (String) -> Unit = { GuidanceClaims.onGuidanceCut(it) },
) {
    enum class Route { ORDINARY, DROP, QUEUE }

    @Volatile var open: String? = null
        private set
    @Volatile var playing: String? = null
        private set
    private var claimed = false

    /** An open or still-playing prompt: cancel paths must spare it. */
    val active: Boolean get() = open != null || playing != null

    fun opened(promptId: String) = synchronized(this) {
        open = promptId
        claimed = false
    }

    fun completed(promptId: String) = synchronized(this) {
        if (open == promptId) open = null
    }

    /** VOIDED: stop routing; true when its audio had started, so the caller drops it and signals the cut. */
    fun voided(promptId: String): Boolean = synchronized(this) {
        if (open == promptId) open = null
        val started = playing == promptId
        if (started) {
            playing = null
            cut(promptId)
        }
        started
    }

    /**
     * Where a chunk goes. ORDINARY: no prompt open — today's reply path. The listening-state gate
     * is never consulted for guidance (B6).
     */
    fun route(decision: (String) -> SpeechArbiter.Reply): Route = synchronized(this) {
        val id = open ?: return@synchronized Route.ORDINARY
        if (decision(id) == SpeechArbiter.Reply.DROP) return@synchronized Route.DROP
        if (!claimed) {
            claimed = true
            if (!claim(id)) {
                abandon(id)
                return@synchronized Route.DROP
            }
        }
        playing = id
        Route.QUEUE
    }

    /** The player went quiet; returns the prompt that was playing, if any. */
    fun drained(): String? = synchronized(this) { playing.also { playing = null } }

    /** Its audio was flushed before completion: signal the cut (B2a). Returns whether one was playing. */
    fun flushed(): Boolean = synchronized(this) {
        val id = playing ?: return@synchronized false
        playing = null
        cut(id)
        true
    }
}
