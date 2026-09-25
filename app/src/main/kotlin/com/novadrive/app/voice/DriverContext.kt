package com.novadrive.app.voice

/**
 * What the app knows about the conversation so far — the one place cross-turn context lives.
 *
 * ## Why this exists
 *
 * The model has no cross-turn memory in this product. [ConversationResetPolicy] starts a fresh
 * conversation after every tool turn, because a long one degrades from about the third tool turn
 * (measured on device 2026-09-17: empty replies, actions a turn late). So 「再凉一点」 reaches a model
 * that does not know anything was adjusted, and the context it needs has to come from the app.
 *
 * Everything here is derived from **authoritative tool results**, never from what the model said
 * ([I-1](../../../../../../../docs/INVARIANTS.md)). A result with `ok=false` records nothing: an
 * action that did not happen is not a referent for the next sentence.
 *
 * Navigation phase and candidates are deliberately **not** copied here — [VoiceContextHints] reads
 * them live from the navigation owner. Two copies of that state is exactly the defect D-4 records.
 *
 * Staleness rules S1–S7 of [SPEC-006](../../../../../../../SPECS/SPEC-006-complex-voice-commands.md)
 * are enforced in [validReferents]; S3 (process restart) is free, because this is in-memory only.
 *
 * Pure Kotlin: no Android, no I/O, clock injected. Synchronised because the voice client, the tool
 * dispatcher and the hint composer all touch it from different threads.
 */
class DriverContext(private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** A thing that can be adjusted relatively. Exactly the dimensions `control_climate` exposes. */
    enum class Dimension(val wire: String) {
        TEMPERATURE("temperature"),
        FAN("fan"),
    }

    /** A completed, *proven* adjustment. [delta] is signed; 0.0 for an absolute set. */
    data class Adjustment(
        val dimension: Dimension,
        val delta: Double,
        val limitReached: Boolean,
        val atMs: Long,
        val epoch: Long,
    )

    data class Climate(val powerOn: Boolean, val temperatureC: Double, val fanLevel: Int)

    /**
     * A question the **app** decided to ask — not the sentence the model produced.
     *
     * [delta] is the adjustment that was pending when the question was asked, so the driver's
     * answer (「温度。」) can be carried out without them repeating the direction.
     */
    data class Clarification(val options: List<Dimension>, val delta: Double, val askedAtEpoch: Long)

    private val lock = Any()

    private var requestText: String = ""
    private var requestEpoch: Long = 0
    private var climate: Climate? = null
    private val adjustments = mutableMapOf<Dimension, Adjustment>()
    private var clarification: Clarification? = null
    private val cancelled = mutableSetOf<Long>()
    private val dispatched = mutableSetOf<String>()
    private val capabilities = mutableMapOf<String, ClaimSource>()

    // ---- writes ------------------------------------------------------------

    /** A new driver utterance began. Anything keyed to an older turn stops being "this turn". */
    fun onDriverUtterance(text: String, epoch: Long) = synchronized(lock) {
        requestText = text.trim()
        requestEpoch = epoch
        dispatched.removeAll { it.startsWith("$epoch|") }
        capabilities.keys.removeAll { it.startsWith("$epoch|") }
    }

    /**
     * A `control_climate` result came back. Only `ok=true` is recorded — see [I-1]. [action] and
     * [value] are the call's own arguments, which is the only place the *sign* of a relative
     * change survives: the result carries the new absolute state, not the delta.
     */
    fun onClimateResult(action: String, value: Double?, output: String, epoch: Long) = synchronized(lock) {
        if (epoch in cancelled) return@synchronized
        if (!output.contains("\"ok\":true")) return@synchronized
        val now = clock()
        climate = Climate(
            powerOn = output.contains("\"power_on\":true"),
            temperatureC = number(output, "temperature_c") ?: climate?.temperatureC ?: 0.0,
            fanLevel = number(output, "fan_level")?.toInt() ?: climate?.fanLevel ?: 0,
        )
        val limitReached = output.contains("\"limit_reached\":true")
        val dimension = when (action) {
            ClimateToolActions.ADJUST_TEMPERATURE, ClimateToolActions.SET_TEMPERATURE -> Dimension.TEMPERATURE
            ClimateToolActions.ADJUST_FAN, ClimateToolActions.SET_FAN -> Dimension.FAN
            else -> return@synchronized
        }
        val delta = when (action) {
            ClimateToolActions.ADJUST_TEMPERATURE, ClimateToolActions.ADJUST_FAN -> value ?: 1.0
            else -> 0.0
        }
        adjustments[dimension] = Adjustment(dimension, delta, limitReached, now, epoch)
    }

    /**
     * The app decided to ask which dimension was meant. Recorded so the driver's *answer* can be
     * resolved next turn — a mandatory question nobody can answer is worse than a guess.
     */
    fun recordClarification(options: List<Dimension>, delta: Double, epoch: Long) = synchronized(lock) {
        clarification = Clarification(options, delta, epoch)
    }

    /** S5 — a superseded turn. Nothing it produced may be a referent, and late results are ignored. */
    fun cancel(epoch: Long) = synchronized(lock) {
        cancelled += epoch
        adjustments.entries.removeAll { it.value.epoch == epoch }
        if (clarification?.askedAtEpoch == epoch) clarification = null
    }

    /** S2 — the listening session ended or went to sleep. Context does not survive it. */
    fun onSessionEnded() = synchronized(lock) {
        adjustments.clear()
        clarification = null
        climate = null
        requestText = ""
        dispatched.clear()
        capabilities.clear()
    }

    /**
     * Records that this exact call already ran in this turn. Returns false when it is a repeat.
     *
     * Relative adjustments are not idempotent — the same `adjust_temperature{-2}` twice is −4 °C —
     * so a duplicated function call (a protocol retry, a model repeating itself) silently doubles a
     * physical change. A driver who genuinely asks twice produces two *different* utterances and
     * therefore two epochs, so this cannot swallow a real second request.
     */
    fun claimDispatch(epoch: Long, tool: String, arguments: Map<String, String>): Boolean =
        synchronized(lock) {
            val key = "$epoch|$tool|" + arguments.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
            dispatched.add(key)
        }

    /** Who ran a capability first in a turn: the on-screen matcher or the model (SPEC-010 B4). */
    enum class ClaimSource { LOCAL, MODEL }

    /**
     * SPEC-010 B4: one execution per turn per capability *across* the local affordance path and the
     * model. [claimDispatch] keys on exact arguments, so a local `value=-1` and a model `value=-1.0`
     * would both run. Returns false only when the **other** source already claimed `tool`+`action`
     * in this epoch; the same source claiming twice is left to [claimDispatch], so a model that
     * really makes two different adjustments in one turn keeps doing so.
     */
    fun claimCapability(epoch: Long, tool: String, action: String?, source: ClaimSource): Boolean =
        synchronized(lock) {
            val key = "$epoch|$tool|${action.orEmpty()}"
            val first = capabilities.getOrPut(key) { source }
            first == source
        }

    // ---- reads -------------------------------------------------------------

    fun currentRequestText(): String = synchronized(lock) { requestText }

    fun currentEpoch(): Long = synchronized(lock) { requestEpoch }

    fun climateState(): Climate? = synchronized(lock) { climate }

    /**
     * The adjustments that may still be referred to, newest first. Applies S1 (age), S4 (only the
     * newest per dimension, which the map gives for free), S5 (cancelled) and S6 (unproven results
     * were never recorded).
     */
    fun validReferents(): List<Adjustment> = synchronized(lock) {
        val now = clock()
        adjustments.values
            .filter { now - it.atMs <= REFERENT_TTL_MS && it.epoch !in cancelled }
            .sortedByDescending { it.atMs }
    }

    /** The clarification asked in the immediately preceding turn, or null. Valid one turn only. */
    fun pendingClarification(currentEpoch: Long): Clarification? = synchronized(lock) {
        clarification?.takeIf { currentEpoch - it.askedAtEpoch == 1L }
    }

    /** Called once the driver's answer has been used, or once it is clear they moved on. */
    fun clearClarification() = synchronized(lock) { clarification = null }

    fun isCancelled(epoch: Long): Boolean = synchronized(lock) { epoch in cancelled }

    private fun number(json: String, field: String): Double? =
        Regex("\"$field\":(-?\\d+(?:\\.\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    companion object {
        /**
         * S1. Long enough that a driver can pause mid-thought, short enough that a referent does
         * not survive to the next junction. A frozen product default (SPEC-006 open decision 2).
         */
        const val REFERENT_TTL_MS: Long = 180_000

        /** The live instance, written by the voice client and read by the dispatcher and hints. */
        @Volatile
        private var current: DriverContext? = null

        fun install(context: DriverContext) {
            current = context
        }

        fun currentOrNull(): DriverContext? = current

        fun clear() {
            current = null
        }
    }
}

/** The `control_climate` action names, so context and dispatch cannot drift apart on a string. */
object ClimateToolActions {
    const val SET_TEMPERATURE = "set_temperature"
    const val ADJUST_TEMPERATURE = "adjust_temperature"
    const val SET_FAN = "set_fan"
    const val ADJUST_FAN = "adjust_fan"
    const val POWER_ON = "power_on"
    const val POWER_OFF = "power_off"
}
