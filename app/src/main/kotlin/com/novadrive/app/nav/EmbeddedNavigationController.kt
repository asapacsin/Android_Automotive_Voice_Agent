package com.novadrive.app.nav

import android.content.Context
import com.novadrive.app.DebugVoiceLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Live [NavigationController] binding. Voice and UI both call [requestDestination] /
 * [selectDestination] / [selectRoute] / [cancel]; selection state does not live in a View.
 */
class EmbeddedNavigationController(
    private val store: NavigationStateStore = NavigationStateStore(),
    private val resolver: DestinationCandidateSource,
    private val engine: NaviEngine,
    /**
     * Called whenever a flow ends (arrived, stopped, cancelled, failed). Clears the legacy
     * navigation speech mute: found by the simulation benchmark 2026-09-17 — after arrival or a
     * failed search the mute stayed on and 小诺's replies were dropped 10 s later.
     */
    private val onFlowEnded: () -> Unit = { com.novadrive.app.NavigationState.reset() },
    /** Guidance started (by voice or tap): the speech mute applies from here. */
    private val onGuidanceStarted: () -> Unit = { com.novadrive.app.NavigationState.begin() },
    /** Monotonic milliseconds; decides when a list on screen is too old for an ordinal. */
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Phonetic recovery for a spoken name that matches nothing (API 29+ ICU; see its guard). */
    phoneticProposer: (List<DestinationCandidate>, String) -> NavigationPhoneticConfirmation.Proposal? =
        { candidates, utterance ->
            runCatching { NavigationPhoneticConfirmation.proposeDestination(candidates, utterance) }.getOrNull()
        },
) : NavigationController {
    private val lock = Any()

    /** Guarded by [lock]. */
    private val authority = NavigationChoiceAuthority(nowMs, phoneticProposer)

    @Volatile private var generation = 0L
    @Volatile private var cancels = 0L // counts cancel(): lets awaitOptions tell a cancelled flow from a slow one
    @Volatile private var calcGeneration = -1L // generation of the pending calculation; older callbacks drop
    @Volatile private var selectedDestination: DestinationCandidate? = null
    @Volatile private var startNaviIssuedForSelection = false

    /**
     * Owner test 10-16 (nav demo runs 38871517346, worker 6c00cfa): an along-route list shown while
     * driving keeps the current guidance until a candidate is picked. Non-null = the route being
     * driven (its destination and selection), restored on cancel. Guarded by [lock].
     */
    private var keptGuidance: Pair<Destination?, DestinationCandidate?>? = null

    private val _destinationCandidates = MutableStateFlow<List<DestinationCandidate>>(emptyList())
    val destinationCandidates: StateFlow<List<DestinationCandidate>> = _destinationCandidates.asStateFlow()

    /** The destination the flow is working on (selected, calculated, driven or just reached). */
    val destination: StateFlow<Destination?> get() = store.destination

    private val _routeCandidates = MutableStateFlow<List<RouteCandidate>>(emptyList())
    val routeCandidates: StateFlow<List<RouteCandidate>> = _routeCandidates.asStateFlow()

    init {
        engine.attachRouteCallbacks(::onCalculateRouteSuccess, ::onCalculateRouteFailure)
        engine.attachNavigationEndedCallback(::onNavigationEnded)
    }

    suspend fun requestDestination(query: String) = request(keepGuidance = false) { resolver.resolve(query) }

    /**
     * SPEC-011 `along_route`: POIs found along the route go into the same picker as a search, so
     * choosing one is the existing destination → route → guidance path, not a second one.
     */
    suspend fun requestCandidates(candidates: List<DestinationCandidate>) = request(keepGuidance = true) { candidates }

    private suspend fun request(keepGuidance: Boolean, resolve: suspend () -> List<DestinationCandidate>) {
        // Changing destination while driving: end the current guidance first, otherwise the
        // old route keeps navigating underneath the new picker. Done outside the lock because
        // the host's ended-callback re-enters onNavigationEnded. An along-route list (a question,
        // not a new destination) keeps the guidance until a candidate is picked (keptGuidance).
        // Phase read and keptGuidance written in one block: an arrival cannot slip in between.
        val stop = synchronized(lock) {
            val driving = store.phase.value == NavigationPhase.NAVIGATING
            if (keepGuidance && driving) keptGuidance = store.destination.value to selectedDestination
            (!keepGuidance && keptGuidance != null || driving && !keepGuidance).also { if (it) keptGuidance = null }
        }
        if (stop) engine.stopNavigation("replaced")
        NavigationLocalPickGuard.onListReplaced()
        val seq = synchronized(lock) {
            generation += 1
            clearCandidatesLocked()
            selectedDestination = null
            startNaviIssuedForSelection = false
            store.update(NavigationPhase.RESOLVING_DESTINATION)
            generation
        }
        DebugVoiceLog.log("nav_resolve_start")
        val resolveStarted = System.nanoTime()
        val resolved = resolve()
        val resolveMs = (System.nanoTime() - resolveStarted) / 1_000_000
        val autoCalculate = synchronized(lock) {
            if (seq != generation) return DebugVoiceLog.log("nav_flow_late_result_dropped kind=search")
            DebugVoiceLog.log("nav_resolve_candidates count=${resolved.size} ms=$resolveMs")
            when {
                resolved.isEmpty() && restoreKeptLocked() -> null
                resolved.isEmpty() -> {
                    failLocked("no_candidates")
                    null
                }
                resolved.size == 1 && keptGuidance == null -> {
                    _destinationCandidates.value = emptyList()
                    prepareCalculationLocked(resolved.first())
                    resolved.first()
                }
                else -> {
                    _destinationCandidates.value = resolved
                    authority.onListPresented()
                    store.update(NavigationPhase.AWAITING_DESTINATION_SELECTION)
                    null
                }
            }
        }
        if (autoCalculate != null) {
            requestCalculation(autoCalculate, seq)
        }
    }

    fun selectDestination(candidateId: String) {
        // A pick from an along-route list replaces the route still being driven (keptGuidance).
        val replacing = synchronized(lock) {
            store.phase.value == NavigationPhase.AWAITING_DESTINATION_SELECTION && keptGuidance != null &&
                _destinationCandidates.value.any { it.id == candidateId }
        }
        if (replacing) {
            synchronized(lock) { keptGuidance = null }
            engine.stopNavigation("replaced")
        }
        // seq is captured under the lock: reading `generation` after releasing it would
        // hand requestCalculation a newer flow's sequence and mis-attribute a failure.
        var seq = 0L
        val candidate = synchronized(lock) {
            if (store.phase.value != NavigationPhase.AWAITING_DESTINATION_SELECTION) return
            val match = _destinationCandidates.value.firstOrNull { it.id == candidateId }
            if (match == null) {
                if (!restoreKeptLocked()) failLocked("unknown_candidate")
                return
            }
            DebugVoiceLog.log("nav_destination_selected candidateId=$candidateId")
            _destinationCandidates.value = emptyList()
            prepareCalculationLocked(match)
            seq = generation
            match
        }
        requestCalculation(candidate, seq)
    }

    fun selectRoute(routeId: Int) {
        val accepted = synchronized(lock) {
            if (store.phase.value != NavigationPhase.AWAITING_ROUTE_SELECTION) return
            if (startNaviIssuedForSelection) return
            if (_routeCandidates.value.none { it.routeId == routeId }) {
                failLocked("unknown_route")
                return
            }
            startNaviIssuedForSelection = true
            DebugVoiceLog.log("nav_route_selected routeId=$routeId")
            true
        }
        if (!accepted) return
        val selected = engine.selectRoute(routeId)
        if (!selected) {
            synchronized(lock) {
                startNaviIssuedForSelection = false
                failLocked("select_route_rejected")
            }
            return
        }
        val started = engine.startNavigation(emulator = false)
        synchronized(lock) {
            if (started) {
                _routeCandidates.value = emptyList()
                store.update(NavigationPhase.NAVIGATING, selectedDestination?.toDestination())
                DebugVoiceLog.log("nav_navigation_started routeId=$routeId")
                onGuidanceStarted()
            } else {
                startNaviIssuedForSelection = false
                failLocked("start_navi_rejected")
            }
        }
    }

    /** Guidance is running, even under an along-route list (keptGuidance). */
    fun isGuiding(): Boolean = synchronized(lock) { store.phase.value == NavigationPhase.NAVIGATING || keptGuidance != null }

    override fun showOverview(): Boolean {
        if (!isGuiding()) return false
        return engine.showOverview()
    }

    override fun resumeTracking(): Boolean {
        if (!isGuiding()) return false
        return engine.resumeTracking()
    }

    sealed interface VoiceChoiceResult {
        data class DestinationChosen(val name: String, val position: Int) : VoiceChoiceResult
        data class RouteChosen(val position: Int, val started: Boolean) : VoiceChoiceResult
        data class Rejected(val code: String) : VoiceChoiceResult

        /** Nothing was selected; the driver is asked whether they meant this row. */
        data class ConfirmNeeded(val position: Int, val name: String) : VoiceChoiceResult

        /** 「开始导航」 while guidance is already running: true, and nothing to do. */
        data object AlreadyNavigating : VoiceChoiceResult
    }

    /**
     * 「第二个」「选最快的」「就去拱北口岸」: picks from whichever list is on screen, through the same
     * [selectDestination] / [selectRoute] paths a tap uses.
     */
    fun chooseByVoice(choice: NavigationChoice): VoiceChoiceResult {
        val stale = synchronized(lock) { store.phase.value.hasChoices() && authority.refuseStale(choice) }
        if (stale) return VoiceChoiceResult.Rejected(NavigationChoiceAuthority.OPTIONS_STALE)
        return when (store.phase.value) {
            NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                when (val match = NavigationChoiceResolver.pickDestination(_destinationCandidates.value, choice)) {
                    is ChoiceMatch.Rejected ->
                        synchronized(lock) { authority.propose(generation, _destinationCandidates.value, choice, match.code) }
                            ?.let { VoiceChoiceResult.ConfirmNeeded(it.position, it.name) }
                            ?: VoiceChoiceResult.Rejected(match.code)
                    is ChoiceMatch.Picked -> {
                        synchronized(lock) { authority.withdraw() }
                        DebugVoiceLog.log("nav_voice_choice kind=destination position=${match.position}")
                        selectDestination(match.item.id)
                        if (store.phase.value == NavigationPhase.ERROR) {
                            VoiceChoiceResult.Rejected("ROUTE_CALCULATION_FAILED")
                        } else {
                            VoiceChoiceResult.DestinationChosen(match.item.name, match.position)
                        }
                    }
                }
            NavigationPhase.AWAITING_ROUTE_SELECTION ->
                when (val match = NavigationChoiceResolver.pickRoute(_routeCandidates.value, choice)) {
                    is ChoiceMatch.Rejected -> VoiceChoiceResult.Rejected(match.code)
                    is ChoiceMatch.Picked -> {
                        DebugVoiceLog.log("nav_voice_choice kind=route position=${match.position} routeId=${match.item.routeId}")
                        selectRoute(match.item.routeId)
                        val started = store.phase.value == NavigationPhase.NAVIGATING
                        if (started) VoiceChoiceResult.RouteChosen(match.position, true)
                        else VoiceChoiceResult.Rejected("START_FAILED")
                    }
                }
            NavigationPhase.RESOLVING_DESTINATION,
            NavigationPhase.CALCULATING_ROUTE,
            -> VoiceChoiceResult.Rejected("OPTIONS_NOT_READY")
            NavigationPhase.NAVIGATING ->
                if (choice == NavigationChoice.Preference(NavigationChoice.Kind.RECOMMENDED)) VoiceChoiceResult.AlreadyNavigating
                else VoiceChoiceResult.Rejected("NO_OPTIONS_ON_SCREEN")
            else -> VoiceChoiceResult.Rejected("NO_OPTIONS_ON_SCREEN")
        }
    }

    private fun NavigationPhase.hasChoices() =
        this == NavigationPhase.AWAITING_DESTINATION_SELECTION || this == NavigationPhase.AWAITING_ROUTE_SELECTION

    /** Listening slept: see [NavigationChoiceAuthority.onListeningSuspended]. */
    fun onListeningSuspended() = synchronized(lock) { authority.onListeningSuspended(store.phase.value.hasChoices()) }

    /** The phonetic question still awaiting the driver's answer for the list on screen, if any. */
    fun activeConfirmation(): NavigationChoiceAuthority.PendingConfirmation? = synchronized(lock) {
        authority.active(generation, store.phase.value == NavigationPhase.AWAITING_DESTINATION_SELECTION)
    }

    /** The driver answered something other than yes. */
    fun withdrawConfirmation() = synchronized(lock) { authority.withdraw() }

    /** [cancelled]: the driver cancelled after the tool call was dispatched (D-CANCEL-STALE-REPLY). */
    data class OptionsSnapshot(
        val phase: NavigationPhase,
        val destinations: List<DestinationCandidate>,
        val routes: List<RouteCandidate>,
        val destinationName: String?,
        val cancelled: Boolean = false,
    )

    /** Stable identity for the list currently on screen. */
    fun currentListKey(): String = synchronized(lock) {
        "${generation}_${store.phase.value}_${_destinationCandidates.value.size}_${_routeCandidates.value.size}"
    }

    fun cancelMark(): Long = cancels // taken when a navigation tool call is dispatched; see [awaitOptions]
    /** Waits (bounded) until destinations or routes are no longer being computed, then reports them. */
    suspend fun awaitOptions(timeoutMs: Long, cancelsAtDispatch: Long): OptionsSnapshot {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            store.phase.first { it != NavigationPhase.RESOLVING_DESTINATION && it != NavigationPhase.CALCULATING_ROUTE }
        }
        return synchronized(lock) {
            OptionsSnapshot(
                phase = store.phase.value,
                destinations = _destinationCandidates.value,
                routes = _routeCandidates.value,
                destinationName = selectedDestination?.name,
                cancelled = cancels != cancelsAtDispatch,
            )
        }
    }

    enum class VoiceEndResult { STOPPED_NAVIGATION, CANCELLED_SELECTION, NOTHING_ACTIVE }

    /**
     * 「结束导航」/「算了」 by voice: stops embedded guidance if it is running, otherwise closes an
     * open destination/route picker. Reports what actually happened so the reply can be truthful.
     */
    suspend fun endByVoice(): VoiceEndResult {
        if (synchronized(lock) { keptGuidance != null }) {
            cancel() // closes the list only: back to the route being driven; a second 「结束导航」 ends it
            return VoiceEndResult.CANCELLED_SELECTION
        }
        return when (store.phase.value) {
            NavigationPhase.NAVIGATING -> {
                stopNavigation()
                VoiceEndResult.STOPPED_NAVIGATION
            }
            in CANCELLABLE_PHASES -> {
                cancel()
                VoiceEndResult.CANCELLED_SELECTION
            }
            else -> VoiceEndResult.NOTHING_ACTIVE
        }
    }

    fun cancel() {
        synchronized(lock) {
            val phase = store.phase.value
            if (phase !in CANCELLABLE_PHASES) return
            NavigationLocalPickGuard.invalidate()
            generation += 1
            cancels += 1
            clearCandidatesLocked()
            startNaviIssuedForSelection = false
            if (restoreKeptLocked()) {
                DebugVoiceLog.log("nav_flow_cancelled kept_guidance=true")
                return
            }
            selectedDestination = null
            store.reset()
            DebugVoiceLog.log("nav_flow_cancelled")
            onFlowEnded()
        }
    }

    private companion object { // a picker is open or a search / calculation is in flight (D-CANCEL-SEARCH)
        val CANCELLABLE_PHASES = setOf(NavigationPhase.AWAITING_DESTINATION_SELECTION, NavigationPhase.AWAITING_ROUTE_SELECTION,
            NavigationPhase.RESOLVING_DESTINATION, NavigationPhase.PLANNING_ROUTE, NavigationPhase.CALCULATING_ROUTE,
            NavigationPhase.ROUTE_READY)
    }

    override suspend fun resolveDestination(query: String): DestinationResult {
        requestDestination(query)
        val dests = _destinationCandidates.value
        return when (store.phase.value) {
            NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                DestinationResult.Ambiguous(dests.map { it.toDestination() })
            NavigationPhase.CALCULATING_ROUTE,
            NavigationPhase.AWAITING_ROUTE_SELECTION,
            NavigationPhase.NAVIGATING,
            -> selectedDestination?.let { DestinationResult.Resolved(it.toDestination()) }
                ?: DestinationResult.Failed("NO_DESTINATION")
            NavigationPhase.ERROR -> DestinationResult.Failed("NO_CANDIDATES")
            else -> DestinationResult.Failed("NO_DESTINATION")
        }
    }

    override suspend fun planRoute(destination: Destination): RoutePlanResult =
        RoutePlanResult.Failed("USE_SELECT_DESTINATION")

    override suspend fun startNavigation(routeId: Int?): NavigationResult {
        if (routeId == null) return NavigationResult.Failed("ROUTE_REQUIRED")
        selectRoute(routeId)
        return if (store.phase.value == NavigationPhase.NAVIGATING) {
            NavigationResult.Started
        } else {
            NavigationResult.Failed("START_FAILED")
        }
    }

    override suspend fun stopNavigation() {
        engine.stopNavigation("manual")
        synchronized(lock) {
            generation += 1
            clearCandidatesLocked()
            selectedDestination = null
            keptGuidance = null
            startNaviIssuedForSelection = false
            store.update(NavigationPhase.STOPPED)
            onFlowEnded()
        }
    }

    override suspend fun cancelRoute() {
        cancel()
    }

    override suspend fun reroute(): NavigationResult = NavigationResult.Failed("REROUTE_NOT_IMPLEMENTED")

    override fun state(): StateFlow<NavigationPhase> = store.phase

    /**
     * The SDK session ended. Arrival, emulator end and the manual `nav_stop` fallback all
     * stop the host directly and never call [stopNavigation], so without this the phase
     * stayed NAVIGATING for the life of the process and
     * [NavigationPhase.isNavigationSessionActive] kept reporting a live session to
     * the speech arbiter long after the drive was over.
     *
     * Ignored unless we are actually navigating, so a stale stop cannot clobber a picker
     * the driver is in the middle of using.
     */
    private fun onNavigationEnded(reason: String) {
        synchronized(lock) {
            // The drive kept under an along-route list ended on its own (arrival): close the list too.
            if (keptGuidance != null && reason != "replaced") {
                NavigationLocalPickGuard.invalidate()
                restoreKeptLocked() // so ARRIVED below carries the kept destination
            }
            if (store.phase.value != NavigationPhase.NAVIGATING) return
            val ended = if (reason == "arrived" || reason == "emulator_end") {
                NavigationPhase.ARRIVED
            } else {
                NavigationPhase.STOPPED
            }
            generation += 1
            clearCandidatesLocked()
            startNaviIssuedForSelection = false
            store.update(ended, selectedDestination?.toDestination())
            selectedDestination = null
            DebugVoiceLog.log("nav_flow_ended reason=$reason phase=$ended")
            // A replaced destination ends the old guidance only; the new flow is already running.
            if (reason != "replaced") onFlowEnded()
        }
    }

    private fun prepareCalculationLocked(candidate: DestinationCandidate) {
        selectedDestination = candidate
        startNaviIssuedForSelection = false
        calcGeneration = generation
        store.update(NavigationPhase.CALCULATING_ROUTE, candidate.toDestination())
        DebugVoiceLog.log("nav_route_calc_start")
    }

    private fun requestCalculation(candidate: DestinationCandidate, seq: Long) {
        val accepted = engine.calculateDriveRoute(
            candidate.latitude,
            candidate.longitude,
            candidate.name,
            DRIVING_MULTIPLE_ROUTES_DEFAULT,
        )
        if (!accepted) {
            synchronized(lock) {
                if (seq == generation) failLocked("calc_not_accepted")
            }
        }
    }

    private fun onCalculateRouteSuccess(routeIds: IntArray) {
        val built: List<RouteCandidate> =
            if (routeIds.isEmpty()) {
                emptyList()
            } else {
                val byId = engine.routeCandidates().associateBy { candidate -> candidate.routeId }
                buildList {
                    for (id in routeIds) {
                        byId[id]?.let { add(it) }
                    }
                }
            }
        synchronized(lock) {
            if (lateRouteResultLocked()) return
            if (built.isEmpty()) {
                failLocked("no_routes")
                return
            }
            DebugVoiceLog.log("nav_route_candidates count=${built.size}")
            built.forEach { route ->
                DebugVoiceLog.log(
                    "nav_route_candidate routeId=${route.routeId} meters=${route.distanceMeters} seconds=${route.durationSeconds}",
                )
            }
            _routeCandidates.value = built
            authority.onListPresented()
            store.update(NavigationPhase.AWAITING_ROUTE_SELECTION, selectedDestination?.toDestination())
        }
    }

    private fun onCalculateRouteFailure(errorCode: Int) {
        synchronized(lock) {
            if (lateRouteResultLocked()) return
            failLocked("calc_failure_$errorCode")
        }
    }

    /** True when a calculation callback must be ignored; one from a cancelled flow is logged. */
    private fun lateRouteResultLocked(): Boolean = if (calcGeneration != generation) {
        DebugVoiceLog.log("nav_flow_late_result_dropped kind=route"); true
    } else store.phase.value != NavigationPhase.CALCULATING_ROUTE

    private fun failLocked(reason: String) {
        DebugVoiceLog.log("nav_flow_error reason=$reason")
        clearCandidatesLocked()
        store.update(NavigationPhase.ERROR, selectedDestination?.toDestination())
        onFlowEnded()
    }

    /** The along-route list closes unpicked: the drive that never stopped carries on. */
    private fun restoreKeptLocked(): Boolean {
        val kept = keptGuidance ?: return false
        keptGuidance = null
        clearCandidatesLocked()
        selectedDestination = kept.second
        store.update(NavigationPhase.NAVIGATING, kept.first)
        return true
    }

    private fun clearCandidatesLocked() {
        _destinationCandidates.value = emptyList()
        _routeCandidates.value = emptyList()
        authority.onListCleared()
    }
}

object EmbeddedNavigation {
    private val lock = Any()

    @Volatile
    private var instance: EmbeddedNavigationController? = null

    /** The shared controller if it exists yet; never creates one. */
    fun currentOrNull(): EmbeddedNavigationController? = instance

    fun shared(context: Context): EmbeddedNavigationController = synchronized(lock) {
        instance ?: SwitchingNavigationBackend(
            liveSource = LiveDestinationCandidateSource(context.applicationContext),
            liveEngine = GatewayNaviEngine(),
        ).let { backend ->
            EmbeddedNavigationController(resolver = backend, engine = backend)
        }.also { instance = it }
    }
}
