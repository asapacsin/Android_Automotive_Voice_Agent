package com.novadrive.app.voice

import com.novadrive.app.voice.DriverContext.Dimension

/**
 * Turns what the driver said into a concrete climate action, or into a decision to ask.
 *
 * Implements the ambiguity policy and the implicit-intent table of
 * [SPEC-006](../../../../../../../SPECS/SPEC-006-complex-voice-commands.md). Pure functions over a
 * transcript and a [DriverContext] snapshot, so every rule below is unit-testable with no model, no
 * network and no device.
 *
 * The result is **advice for the conversation**, not an execution: [VoiceContextHints] turns it
 * into one line the fresh conversation can act on, and the model still issues the tool call that
 * the dispatcher validates. Nothing here claims anything happened
 * ([I-1](../../../../../../../docs/INVARIANTS.md)).
 *
 * The bounded list is the point. An implicit intent that is not in the table below is not an
 * implicit intent — it is an ordinary request, or it is refused. This is what keeps a contextual
 * assistant from becoming one that guesses.
 */
object ContextResolver {

    sealed interface Resolution {
        /**
         * One concrete adjustment. [powerOnFirst] means the climate is off, so the driver would
         * feel nothing: power must be proven on before the adjustment is issued (SPEC-006 D1).
         */
        data class Adjust(
            val dimension: Dimension,
            val delta: Double,
            val powerOnFirst: Boolean,
            val atLimit: Boolean = false,
        ) : Resolution

        /** The referent is missing or ambiguous. Asking is mandatory; guessing right still fails. */
        data class Clarify(val options: List<Dimension>, val delta: Double, val reason: String) : Resolution

        /** Nothing in this utterance depends on context. */
        data object NotContextual : Resolution
    }

    /** Why a clarification was needed, for the diagnostic line and for tests. */
    const val REASON_NO_REFERENT = "no_referent"
    const val REASON_AMBIGUOUS = "ambiguous"
    const val REASON_NOTHING_TO_REVERSE = "nothing_to_reverse"

    /** Frozen product defaults (SPEC-006 open decision 1). One constant each, not per phrase. */
    const val IMPLICIT_STEP_C: Double = 2.0
    const val RELATIVE_STEP_C: Double = 1.0
    const val FAN_STEP: Double = 1.0

    private val TEMPERATURE_WORDS = listOf("温度", "度数", "凉", "暖", "冷", "热")
    private val FAN_WORDS = listOf("风量", "风速", "风")

    private val HOT = listOf("热")
    private val COLD = listOf("冷")
    private val FAN_TOO_MUCH = listOf("风太大", "风太吵", "风大了", "风太强")
    private val FAN_TOO_LITTLE = listOf("风太小", "不够风", "风小了", "闷")

    private val COOLER = listOf("凉", "冷一点", "低")
    private val WARMER = listOf("暖", "热一点", "高")

    private val NEUTRAL_UP = listOf("高", "大", "强")
    private val NEUTRAL_DOWN = listOf("低", "小", "弱")

    private val WINDOW_WORDS = listOf("窗")
    private val SEAT_WORDS = listOf("座椅", "座位", "座")
    private val SEAT_UP = listOf("高", "升")
    private val SEAT_DOWN = listOf("低", "降")
    private val WINDOW_OPEN = listOf("开", "大", "多")
    private val WINDOW_CLOSE = listOf("关", "小", "少")

    /** Dimensions a neutral 高/低/大/小 can mean. A raised window is a closed one, so not WINDOW. */
    private val NEUTRAL_CANDIDATES = setOf(Dimension.TEMPERATURE, Dimension.FAN, Dimension.SEAT_HEIGHT)

    private val REVERSAL = listOf("刚才那个", "刚才的", "调回来", "回来一点", "复原", "还原")
    private val RELATIVE_MARKERS = listOf("再", "更", "还是", "还要", "多一点", "一点", "一些")

    /**
     * @param text the driver's transcript for this turn
     * @param context the app's record; navigation state is deliberately not consulted here
     * @param epoch the current turn, used to find a clarification asked in the previous one
     */
    fun resolve(text: String, context: DriverContext, epoch: Long): Resolution {
        val said = text.trim()
        if (said.isEmpty()) return Resolution.NotContextual

        // An answer to a question the app itself asked, before anything else: 「温度。」 is not a
        // request on its own, and would otherwise look like an utterance with no referent at all.
        context.pendingClarification(epoch)?.let { pending ->
            namedDimension(said)?.let { chosen ->
                if (chosen in pending.options) {
                    val delta = if (chosen.isClimate || pending.delta == 0.0) {
                        pending.delta
                    } else {
                        Math.signum(pending.delta) * step(chosen)
                    }
                    return adjustment(chosen, delta, context, implicit = false)
                }
            }
        }

        if (REVERSAL.any { it in said }) return reverse(context)

        // Discomfort stated rather than an action asked for. Checked before the directive forms
        // because 「还是有点热」 is feedback about a state, not an instruction to go one step cooler.
        implicitIntent(said)?.let { (dimension, delta) ->
            return adjustment(dimension, delta, context, implicit = true)
        }

        if (RELATIVE_MARKERS.none { it in said }) return Resolution.NotContextual

        // Lexically bound: the dimension is in the words, so no history is needed at all.
        lexicalDimension(said)?.let { dimension ->
            val delta = directionalDelta(said, dimension) ?: return Resolution.NotContextual
            return adjustment(dimension, delta, context, implicit = false)
        }

        // 「再开一点」「再关一点」: only a window opens, and only when it is the one thing adjusted.
        // A neutral 大/小 alongside (「再开大一点」) keeps its existing neutral meaning below.
        val openClose = when {
            neutralDirection(said) != null -> null
            "开" in said -> 1.0
            "关" in said -> -1.0
            else -> null
        }
        if (openClose != null) {
            val referents = context.validReferents().map { it.dimension }
            if (referents == listOf(Dimension.WINDOW)) {
                return adjustment(Dimension.WINDOW, openClose * step(Dimension.WINDOW), context, implicit = false)
            }
            return if (referents.isEmpty()) {
                Resolution.Clarify(Dimension.entries.toList(), openClose * RELATIVE_STEP_C, REASON_NO_REFERENT)
            } else {
                Resolution.Clarify(referents, openClose * RELATIVE_STEP_C, REASON_AMBIGUOUS)
            }
        }

        // Dimension-neutral: 「再低一点」. Only the history can say what is being adjusted.
        val direction = neutralDirection(said) ?: return Resolution.NotContextual
        val referents = context.validReferents().filter { it.dimension in NEUTRAL_CANDIDATES }
        return when (referents.size) {
            1 -> {
                val dimension = referents.first().dimension
                adjustment(dimension, direction * step(dimension), context, implicit = false)
            }
            0 -> Resolution.Clarify(
                Dimension.entries.toList(),
                direction * RELATIVE_STEP_C,
                REASON_NO_REFERENT,
            )
            else -> Resolution.Clarify(
                referents.map { it.dimension },
                direction * RELATIVE_STEP_C,
                REASON_AMBIGUOUS,
            )
        }
    }

    /** 「刚才那个调回来一点」 — one step back against the last proven adjustment, not a full undo. */
    private fun reverse(context: DriverContext): Resolution {
        val last = context.validReferents().firstOrNull()
            ?: return Resolution.Clarify(Dimension.entries.toList(), 0.0, REASON_NOTHING_TO_REVERSE)
        if (last.delta == 0.0) {
            return Resolution.Clarify(listOf(last.dimension), 0.0, REASON_NOTHING_TO_REVERSE)
        }
        val back = if (last.delta > 0) -step(last.dimension) else step(last.dimension)
        return adjustment(last.dimension, back, context, implicit = false)
    }

    /**
     * Is this a request for a climate change that never names an action?
     *
     * Measured on device 2026-09-19: 「还是有点热。」 was answered 「再调高两度。」 with no tool call, and
     * 「有点热。」 produced 「空调还没开呢，我给你打开。」 without ever calling `power_on`. Both were
     * released unheld because [DriverTurn.classify] saw no control word in them and called the turn
     * conversation — so [I-1](../../../../../../../docs/INVARIANTS.md) never applied to a request that
     * plainly asks for an action.
     *
     * Classification reads the implicit-intent table rather than keeping a second copy of it.
     */
    fun isImplicitComfortRequest(text: String): Boolean = implicitIntent(text.trim()) != null

    /**
     * Does this sentence ask for a concrete climate change — whether it names one or only implies
     * one? The single answer to that question, so classification, the nudge and the hint cannot
     * drift apart on three copies of the same word list.
     *
     * Falls back to the implicit table when no context is installed, which is the case in tests and
     * before the first session.
     */
    /**
     * Does this sentence ask for a concrete change whose *target* cannot be worked out?
     *
     * 「再低一点」 with nothing adjusted yet: 低 could be temperature or fan, and no history says
     * which. SPEC-006 requires asking. Measured on device 2026-09-20 without this, the driver got
     * 「刚才没有听清」 for a sentence that was heard perfectly - the app simply had no way to say
     * that the *referent*, not the audio, was the problem.
     */
    fun needsClarification(text: String): Boolean {
        val context = DriverContext.currentOrNull() ?: return resolveWithoutContext(text)
        return resolve(text, context, context.currentEpoch()) is Resolution.Clarify
    }

    private fun resolveWithoutContext(text: String): Boolean =
        DriverContext().let { empty -> resolve(text, empty, empty.currentEpoch()) is Resolution.Clarify }

    fun asksForClimateChange(text: String): Boolean {
        val context = DriverContext.currentOrNull() ?: return isImplicitComfortRequest(text)
        return resolve(text, context, context.currentEpoch()) is Resolution.Adjust
    }

    private fun implicitIntent(said: String): Pair<Dimension, Double>? = when {
        FAN_TOO_MUCH.any { it in said } -> Dimension.FAN to -FAN_STEP
        FAN_TOO_LITTLE.any { it in said } && HOT.none { it in said } -> Dimension.FAN to FAN_STEP
        HOT.any { it in said } && !isDirective(said) -> Dimension.TEMPERATURE to -IMPLICIT_STEP_C
        COLD.any { it in said } && !isDirective(said) -> Dimension.TEMPERATURE to IMPLICIT_STEP_C
        else -> null
    }

    /**
     * 「有点热」 states a condition; 「热一点」 asks for one. The difference is whether a direction
     * word governs the temperature word, and it changes the step size, so it is worth separating.
     */
    private fun isDirective(said: String): Boolean =
        "热一点" in said || "冷一点" in said || "凉一点" in said || "暖一点" in said

    private fun lexicalDimension(said: String): Dimension? = when {
        WINDOW_WORDS.any { it in said } -> Dimension.WINDOW
        SEAT_WORDS.any { it in said } -> Dimension.SEAT_HEIGHT
        FAN_WORDS.any { it in said } -> Dimension.FAN
        TEMPERATURE_WORDS.any { it in said } -> Dimension.TEMPERATURE
        else -> null
    }

    /** A dimension the driver named outright, used to read an answer to a clarification. */
    private fun namedDimension(said: String): Dimension? = when {
        "窗" in said -> Dimension.WINDOW
        "座" in said -> Dimension.SEAT_HEIGHT
        "风" in said -> Dimension.FAN
        "温度" in said || "空调" in said || "度" in said -> Dimension.TEMPERATURE
        else -> null
    }

    private fun directionalDelta(said: String, dimension: Dimension): Double? {
        val magnitude = step(dimension)
        if (dimension == Dimension.WINDOW) {
            // 高/低 are not a window direction: a raised window is a closed one.
            return when {
                WINDOW_OPEN.any { it in said } -> magnitude
                WINDOW_CLOSE.any { it in said } -> -magnitude
                else -> null
            }
        }
        if (dimension == Dimension.SEAT_HEIGHT) {
            return when {
                SEAT_DOWN.any { it in said } -> -magnitude
                SEAT_UP.any { it in said } -> magnitude
                else -> null
            }
        }
        return when {
            COOLER.any { it in said } && dimension == Dimension.TEMPERATURE -> -magnitude
            WARMER.any { it in said } && dimension == Dimension.TEMPERATURE -> magnitude
            NEUTRAL_DOWN.any { it in said } -> -magnitude
            NEUTRAL_UP.any { it in said } -> magnitude
            else -> null
        }
    }

    private fun neutralDirection(said: String): Double? = when {
        NEUTRAL_DOWN.any { it in said } -> -1.0
        NEUTRAL_UP.any { it in said } -> 1.0
        else -> null
    }

    private fun step(dimension: Dimension): Double = when (dimension) {
        Dimension.FAN -> FAN_STEP
        Dimension.TEMPERATURE -> RELATIVE_STEP_C
        Dimension.WINDOW -> BodyToolActions.WINDOW_STEP
        Dimension.SEAT_HEIGHT -> BodyToolActions.SEAT_STEP
    }

    /**
     * Adds the two facts only the execution record can supply: whether the climate is off (so the
     * driver would feel nothing), and whether the last attempt already hit the limit (so saying
     * "lowered it further" would be false even though the call would succeed).
     */
    private fun adjustment(
        dimension: Dimension,
        delta: Double,
        context: DriverContext,
        implicit: Boolean,
    ): Resolution.Adjust {
        val climate = context.climateState()
        val last = context.validReferents().firstOrNull { it.dimension == dimension }
        val sameDirection = last != null && last.delta != 0.0 && (last.delta > 0) == (delta > 0)
        return Resolution.Adjust(
            dimension = dimension,
            delta = delta,
            powerOnFirst = dimension.isClimate && implicit && climate != null && !climate.powerOn,
            atLimit = last?.limitReached == true && sameDirection,
        )
    }
}
