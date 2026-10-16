package com.novadrive.app

import com.novadrive.app.nav.PlaceSlot
import com.novadrive.app.nav.SavedPlace
import com.novadrive.app.nav.SavedPlaces
import com.novadrive.app.tools.ToolRegistry
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.ContextResolver
import com.novadrive.app.voice.DriverContext
import com.novadrive.app.voice.TranscriptText
import com.novadrive.app.voice.UtteranceIntentResolver
import com.novadrive.contracts.CapabilityIds
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
    const val UNSUPPORTED_BODY_PART = "UNSUPPORTED_BODY_PART"
    const val NEARBY_QUESTION_WHILE_NAVIGATING = "NEARBY_QUESTION_WHILE_NAVIGATING"

    /** Words that do ask to go somewhere (else) - only these may replace an active route. */
    private val GO_INTENT = Regex("导航|带我去|带我到|开过去|开去|前往|就去|改去|换(个|成)?(目的地|地方)|去(最近|附近|那|这)")

    /** 「路上有加油站吗」「前面有服务区吗」: along the route, which is query_live_info's along_route. */
    private val ON_THE_WAY = Regex("路上|沿途|沿路|前面|前方")

    /** A question, not a command: 吗 / 有没有 / 哪里有 / 在哪 / a question mark. */
    private val QUESTION = Regex("吗|有没有|哪里有|哪儿有|在哪|多远|[?？]")

    /** Body parts this product cannot move, from their owner, [UtteranceIntentResolver]. */
    private val unsupportedPartWords: List<String> by lazy {
        UtteranceIntentResolver.product().cuesFor(CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS)
    }
    private val windowWords: List<String> by lazy { UtteranceIntentResolver.product().cuesFor(CapabilityIds.BODY_WINDOW) }

    /** Longer first so 「我这一侧」 is the phrase, not a shorter neighbour. */
    private val DRIVER_SIDE = listOf("我这一侧", "我这半边", "我这边", "我这侧")

    /** A second window the driver named. 「都」 alone is not one: 「我这边的窗户都开一点」 is still theirs. */
    private val OTHER_WINDOW = listOf(
        "副驾驶", "副驾", "乘客", "右前", "左后", "右后", "后排", "后窗", "全部", "所有", "四个", "两边", "两侧",
        // Review 2026-10-15: 「我这边和后面的都开一点」 named the rear windows too.
        "后面", "后边", "后头", "后座", "前排", "右边", "右侧", "别的", "其他", "其它", "大家", "每扇", "每个窗",
    )

    /**
     * The same call, with the same arguments, twice in one driver turn.
     *
     * A driver who really does ask twice speaks twice, which is two turns and two epochs, so a
     * genuine second request cannot be swallowed here. With no transcribed utterance there is no
     * turn to be "within", and two identical calls are two separate requests — dropping a real
     * action is the more expensive mistake of the two.
     */
    fun repeatedInTurn(call: DomainVoiceEvent.ToolCall, context: DriverContext?): String? {
        // Repeat-sensitive tools (ToolSpec.repeatSensitive) act on the world: an identical second call in
        // one driver turn is a protocol retry, never a second request. Climate and music accumulate,
        // place_call dials twice, navigate_to / open_app / save_place / exit_navigation_mode redo a
        // visible action, and query_live_info costs daily quota (SPEC-011: DUPLICATE_IN_TURN). Pure
        // reads such as describe_camera_view are left out.
        if (call.name !in ToolRegistry.PRODUCT.repeatSensitiveNames) return null
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
        // Keyed on speech onset, which is the driver turn: the transcript epoch arrives after the
        // call under Gemini ordering, and until then still names the *previous* turn.
        if (capabilityEpoch <= 0) return null
        return if (context.claimDispatch(capabilityEpoch, call.name, arguments)) null else DUPLICATE_IN_TURN
    }

    /**
     * A request for *particular* music on `control_music{play}`. That tool is the one bundled
     * track, so starting it would make `ok=true` mean "you got what you asked for"
     * ([I-2](../../../../../../docs/INVARIANTS.md)); the refusal's advice redirects the model to
     * `play_music` (SPEC-017).
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
     * A sunroof request answered with `control_window` (P56, live tryout 2026-10-13: 「帮我把天窗打开」
     * opened all four windows to 100 %). SPEC-006 F-7: refuse, do not run a *nearby* supported
     * action. The driver's words decide, and only when they belong to the utterance being answered:
     * a transcript older than the speech onset is the previous turn's, so a 天窗 said then must not
     * block a window asked for now (the guard lets the call through rather than guess). A request
     * that also names an ordinary window (「天窗和车窗都打开」) is not blocked. The vocabulary is
     * [UtteranceIntentResolver]'s, so 车门/后备箱/车灯/雨刷 on control_window are refused too. The text
     * is normalised by the one shared [TranscriptText] in case a spaced transcript reaches here raw.
     */
    fun sunroofOnWindowTool(call: DomainVoiceEvent.ToolCall, context: DriverContext?): String? {
        if (call.name != com.novadrive.app.voice.BodyToolActions.WINDOW_TOOL || context == null) return null
        if (context.currentEpoch() <= 0 || context.currentEpoch() < context.capabilityEpoch()) return null
        val said = TranscriptText.normalize(context.currentRequestText())
        if (unsupportedPartWords.none { it in said }) return null
        val rest = unsupportedPartWords.fold(said) { text, word -> text.replace(word, "") }
        return if (windowWords.any { it in rest } || "窗" in rest) null else UNSUPPORTED_BODY_PART
    }

    /**
     * P61 (GitHub emulator navigation demo, 2026-10-16): mid-drive 「附近有加油站吗？」 was answered
     * with `navigate_to`, which replaced the active route with a list of gas stations. P54 already
     * decided the split: with no route a nearby question is a nearby search (navigate_to), with a
     * route it is `query_live_info` along_route. While navigating, a nearby *question* with no word
     * that asks to go there is refused here; 「带我去附近的加油站」 still goes through. A transcript
     * older than the speech onset belongs to the previous turn and is not used.
     */
    /**
     * Whether a route is being driven: the map host's own state, or the legacy flag. The legacy flag
     * alone is not enough - a voice-session stop or restart clears it while the car keeps
     * navigating (nav demo run 38855381048: after Gemini reconnects 「附近有加油站吗」 replaced the
     * route again, P61's regression).
     */
    internal fun navigatingNow(): Boolean =
        NavigationState.navigating || com.novadrive.app.nav.NavigationHostGateway.current()?.isNavigating == true

    fun nearbyQuestionWhileNavigating(
        call: DomainVoiceEvent.ToolCall,
        context: DriverContext?,
        navigating: Boolean = navigatingNow(),
    ): String? {
        if (call.name != "navigate_to" || !navigating || context == null) return null
        if (context.currentEpoch() <= 0 || context.currentEpoch() < context.capabilityEpoch()) return null
        val said = TranscriptText.normalize(context.currentRequestText())
        val destination = call.arguments["destination"].orEmpty()
        val nearby = com.novadrive.app.nav.DestinationQuery.isNearbyRequest(said) ||
            com.novadrive.app.nav.DestinationQuery.isNearbyRequest(destination) ||
            ON_THE_WAY.containsMatchIn(said)
        if (!nearby || !QUESTION.containsMatchIn(said) || GO_INTENT.containsMatchIn(said)) return null
        return NEARBY_QUESTION_WHILE_NAVIGATING
    }

    /**
     * 「把我这边的窗户开一点」 answered with `window=all` (P57, tryout run 4: all four windows
     * opened 20 %). The driver's side is the driver window. Returns `driver` when this turn's
     * words name only that side and the call would move a different one; null leaves the argument
     * alone. Only `window=all` (or no window) is narrowed. A transcript older than the speech
     * onset is the previous turn's, so it is not used
     * (same rule as [sunroofOnWindowTool]). Another named window (副驾, 后排, 全部, …) is left to
     * the call. The tool description is only a hint.
     */
    fun driverSideWindow(call: DomainVoiceEvent.ToolCall, context: DriverContext?): String? {
        if (call.name != com.novadrive.app.voice.BodyToolActions.WINDOW_TOOL || context == null) return null
        if (context.currentEpoch() <= 0 || context.currentEpoch() < context.capabilityEpoch()) return null
        // Only an unspecified target is narrowed. A window the model named (rear, passenger, …)
        // came from other words in the line, e.g. 「我这边冷，把后面的窗户关上」 (review 2026-10-15).
        val window = call.arguments["window"]
        if (window != null && window != com.novadrive.app.vehicle.WindowToolHandler.WINDOW_ALL) return null
        val said = TranscriptText.normalize(context.currentRequestText())
        if (DRIVER_SIDE.none { it in said }) return null
        if (OTHER_WINDOW.any { it in said }) return null
        return "driver"
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
        val relative = when (call.name) {
            com.novadrive.app.voice.BodyToolActions.SEAT_TOOL -> action == com.novadrive.app.voice.BodyToolActions.SEAT_ADJUST_HEIGHT
            com.novadrive.app.voice.BodyToolActions.WINDOW_TOOL -> action == com.novadrive.app.voice.BodyToolActions.WINDOW_ADJUST
            else -> action == ClimateToolActions.ADJUST_TEMPERATURE || action == ClimateToolActions.ADJUST_FAN
        }
        if (!relative) return null
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
     * What the refusal must name so the model asks the right question: 「再低一点」 after the seat
     * and the temperature both changed is 「是座椅还是温度？」, not a climate-only question
     * (SPEC-015 FZ-14). Empty for every other code.
     */
    fun refusalDetails(code: String, context: DriverContext?): Map<String, Any> {
        if (code != AMBIGUOUS_REFERENT) return emptyMap()
        val options = context?.lastClarification()?.options.orEmpty()
        if (options.isEmpty()) return emptyMap()
        return mapOf("options" to options.joinToString("还是") { it.spoken })
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
