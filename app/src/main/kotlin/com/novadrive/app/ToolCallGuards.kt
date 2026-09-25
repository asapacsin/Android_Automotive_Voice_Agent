package com.novadrive.app

import com.novadrive.app.nav.PlaceSlot
import com.novadrive.app.nav.SavedPlace
import com.novadrive.app.nav.SavedPlaces
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.ContextResolver
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.json.JSONObject

/**
 * Whether a validated tool call may actually run, given what the driver said and what already
 * happened this turn.
 *
 * Separate from [AndroidToolDispatcher] because these are three answers to one question that the
 * call's own arguments cannot answer:
 *
 * - `control_music{play}` looks identical whether the driver said 「放首歌」 or named a song this
 *   product has no way to play;
 * - the same `adjust_temperature{-2}` twice is −4 °C, not −2;
 * - 「再低一点」 does not say *what* to lower, and sometimes the history cannot say either.
 *
 * Each returns an error code, or null to let the call through. Building the failure result stays
 * with the dispatcher: guards decide, the dispatcher formats. The wording the model is given lives
 * in [ToolFailureAdvice], so there is one place to read what the driver will hear.
 */
object ToolCallGuards {

    /** Tools whose effect accumulates, so running one twice is not the same as running it once. */
    private val REPEAT_SENSITIVE = setOf(ClimateToolHandler.TOOL, "control_music")

    private val TEMPERATURE_OR_FAN = setOf(
        ClimateToolActions.ADJUST_TEMPERATURE,
        ClimateToolActions.SET_TEMPERATURE,
        ClimateToolActions.ADJUST_FAN,
        ClimateToolActions.SET_FAN,
    )

    const val MEDIA_LIBRARY_UNSUPPORTED = "MEDIA_LIBRARY_UNSUPPORTED"
    const val HOME_NOT_SET = "HOME_NOT_SET"
    const val WORK_NOT_SET = "WORK_NOT_SET"
    const val DUPLICATE_IN_TURN = "DUPLICATE_IN_TURN"
    const val AMBIGUOUS_REFERENT = "AMBIGUOUS_REFERENT"

    /**
     * The same call, with the same arguments, twice in one driver turn.
     *
     * A driver who really does ask twice speaks twice, which is two turns and two epochs, so a
     * genuine second request cannot be swallowed here. With no transcribed utterance there is no
     * turn to be "within", and two identical calls are two separate requests — dropping a real
     * action is the more expensive mistake of the two.
     */
    fun repeatedInTurn(call: DomainVoiceEvent.ToolCall, context: DriverContext?): String? {
        if (call.name !in REPEAT_SENSITIVE) return null
        if (context == null) return null
        val arguments = call.arguments.filterKeys { it != "_validation_error" }
        // SPEC-010 B4: the on-screen matcher may already have run this capability for this
        // utterance. Keyed on the utterance that started, since the call can precede its transcript.
        val capabilityEpoch = context.capabilityEpoch()
        if (capabilityEpoch > 0 &&
            !context.claimCapability(capabilityEpoch, call.name, arguments["action"], DriverContext.ClaimSource.MODEL)
        ) {
            return DUPLICATE_IN_TURN
        }
        val epoch = context.currentEpoch()
        if (epoch <= 0) return null
        return if (context.claimDispatch(epoch, call.name, arguments)) null else DUPLICATE_IN_TURN
    }

    /**
     * A request for *particular* music. There is one bundled track and no library, so starting it
     * would make `ok=true` mean "you got what you asked for"
     * ([I-2](../../../../../../docs/INVARIANTS.md)).
     */
    fun unsupportedMedia(call: DomainVoiceEvent.ToolCall, context: DriverContext?): String? {
        if (call.name != "control_music" || call.arguments["action"] != "play") return null
        val said = context?.currentRequestText().orEmpty()
        // The model already decided this is a request to play music, so the driver's words only
        // have to answer one question: did they name something in particular?
        return if (ActionClaimGuard.isSpecificMediaRequest(said, mediaIntentKnown = true)) {
            MEDIA_LIBRARY_UNSUPPORTED
        } else {
            null
        }
    }

    /**
     * A relative climate change whose target the driver did not state and the history cannot
     * supply — 「再低一点」 after both the temperature and the fan were adjusted, or after neither was.
     *
     * The hint asks the model to ask; this makes it hold, because a prompt rule is not an
     * enforcement mechanism ([I-11](../../../../../../docs/INVARIANTS.md)). Guessing right is still
     * wrong: it is the same coin toss next time. Recording the question is what lets the driver's
     * one-word answer resolve on the following turn.
     */
    fun ambiguousReferent(call: DomainVoiceEvent.ToolCall, context: DriverContext?): String? {
        val action = call.arguments["action"] ?: return null
        if (action != ClimateToolActions.ADJUST_TEMPERATURE && action != ClimateToolActions.ADJUST_FAN) return null
        if (context == null) return null
        val epoch = context.currentEpoch()
        if (epoch <= 0) return null
        val resolution = ContextResolver.resolve(context.currentRequestText(), context, epoch)
        if (resolution !is ContextResolver.Resolution.Clarify) return null
        if (resolution.reason == ContextResolver.REASON_NOTHING_TO_REVERSE) return null
        context.recordClarification(resolution.options, resolution.delta, epoch)
        return AMBIGUOUS_REFERENT
    }

    /**
     * A temperature or fan change that succeeded while the climate is **off**.
     *
     * The backend stores a new target happily with the system off, so the result is `ok=true` and
     * the driver feels nothing — a true statement that leaves a false impression (SPEC-006 D1).
     * The advice rides on the result rather than the dispatcher switching the climate on itself:
     * the driver asked for a temperature, not for the system to be started, and inventing the
     * second action is how an assistant stops being predictable.
     */
    fun withPowerAdvice(output: String?): String? {
        if (output == null) return output
        if (!output.contains("\"ok\":true") || !output.contains("\"power_on\":false")) return output
        val action = Regex("\"action\":\"([a-z_]+)\"").find(output)?.groupValues?.get(1)
        if (action !in TEMPERATURE_OR_FAN) return output
        return JSONObject(output).put("next", ToolFailureAdvice.CLIMATE_OFF).toString()
    }

    /**
     * 「回家」 when we do not know where home is.
     *
     * The failure this prevents is not a crash: it is a *plausible* wrong answer. Sent to a POI
     * search, 家 matches shops and other people's addresses, and the driver would be routed
     * somewhere confidently wrong. Measured on device 2026-09-19, the search returned nothing at
     * all - which is luckier than the alternative, and not something to rely on.
     *
     * A set slot returns null: the resolver answers it from the store, with no search.
     */
    fun savedPlaceMissing(
        destination: String,
        savedPlace: (PlaceSlot) -> SavedPlace?,
    ): String? {
        val slot = SavedPlaces.slotFor(destination) ?: return null
        if (savedPlace(slot) != null) return null
        return when (slot) {
            PlaceSlot.HOME -> HOME_NOT_SET
            PlaceSlot.WORK -> WORK_NOT_SET
        }
    }
}
