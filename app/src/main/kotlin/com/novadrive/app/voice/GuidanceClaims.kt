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

    /** R1/R2: the session's playback stopped or the session ended; nothing open survives it. */
    @Volatile var onSessionStopped: () -> Unit = {}
}

/**
 * The playback port's per-prompt bookkeeping, kept free of Android so it is testable on the JVM.
 * [open] routes chunks (OPENED → COMPLETED/VOIDED); [playing] is the prompt whose audio reached the
 * player and has not drained yet. R8: the claim is made at playout ([startPlayout]), not at enqueue;
 * a drain needs COMPLETED seen and the player idle, so an underrun before COMPLETED is not one.
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
    private var completedId: String? = null

    /** An open or still-playing prompt: cancel paths must spare it. */
    val active: Boolean get() = open != null || playing != null

    fun opened(promptId: String) = synchronized(this) {
        open = promptId
        claimed = false
        completedId = null
    }

    fun completed(promptId: String) = synchronized(this) {
        if (open == promptId) open = null
        completedId = promptId
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
     * is never consulted for guidance (B6). QUEUE does not claim: see [startPlayout].
     */
    fun route(decision: (String) -> SpeechArbiter.Reply): Route = synchronized(this) {
        val id = open ?: return@synchronized Route.ORDINARY
        if (decision(id) == SpeechArbiter.Reply.DROP) return@synchronized Route.DROP
        playing = id
        Route.QUEUE
    }

    /**
     * R8a: the player is actually playing the queued guidance (not HOLD-paused). The first call
     * per prompt claims it; false = the relay gave it to Amap, the caller flushes what is queued.
     */
    fun startPlayout(): Boolean = synchronized(this) {
        val id = playing ?: return@synchronized true
        if (claimed) return@synchronized true
        claimed = true
        if (claim(id)) return@synchronized true
        abandon(id)
        playing = null
        false
    }

    /** R8b: the player went quiet; the drained prompt only once its COMPLETED was seen, else null. */
    fun drained(): String? = synchronized(this) {
        val id = playing ?: return@synchronized null
        if (completedId != id) return@synchronized null
        playing = null
        id
    }

    /** Its audio was flushed before completion: signal the cut (B2a). Returns whether one was playing. */
    fun flushed(): Boolean = synchronized(this) {
        val id = playing ?: return@synchronized false
        playing = null
        cut(id)
        true
    }

    /** R2: session stopped — nothing routes as guidance any more. */
    fun reset() = synchronized(this) {
        open = null
        playing = null
        claimed = false
        completedId = null
    }
}
