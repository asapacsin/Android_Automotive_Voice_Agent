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
) : NavigationController {
    private val lock = Any()

    @Volatile
    private var generation = 0L

    @Volatile
    private var selectedDestination: DestinationCandidate? = null

    @Volatile
    private var startNaviIssuedForSelection = false

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

    suspend fun requestDestination(query: String) {
        // Changing destination while driving: end the current guidance first, otherwise the
        // old route keeps navigating underneath the new picker. Done outside the lock because
        // the host's ended-callback re-enters onNavigationEnded.
        if (store.phase.value == NavigationPhase.NAVIGATING) {
            engine.stopNavigation("replaced")
        }
        val seq = synchronized(lock) {
            generation += 1
            clearCandidatesLocked()
            selectedDestination = null
            startNaviIssuedForSelection = false
            store.update(NavigationPhase.RESOLVING_DESTINATION)
            generation
        }
        DebugVoiceLog.log("nav_resolve_start")
        val resolved = resolver.resolve(query)
        val autoCalculate = synchronized(lock) {
            if (seq != generation) return
            DebugVoiceLog.log("nav_resolve_candidates count=${resolved.size}")
            when {
                resolved.isEmpty() -> {
                    failLocked("no_candidates")
                    null
                }
                resolved.size == 1 -> {
                    _destinationCandidates.value = emptyList()
                    prepareCalculationLocked(resolved.first())
                    resolved.first()
                }
                else -> {
                    _destinationCandidates.value = resolved
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
        // seq is captured under the lock: reading `generation` after releasing it would
        // hand requestCalculation a newer flow's sequence and mis-attribute a failure.
        var seq = 0L
        val candidate = synchronized(lock) {
            if (store.phase.value != NavigationPhase.AWAITING_DESTINATION_SELECTION) return
            val match = _destinationCandidates.value.firstOrNull { it.id == candidateId }
            if (match == null) {
                failLocked("unknown_candidate")
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

    sealed interface VoiceChoiceResult {
        data class DestinationChosen(val name: String, val position: Int) : VoiceChoiceResult
        data class RouteChosen(val position: Int, val started: Boolean) : VoiceChoiceResult
        data class Rejected(val code: String) : VoiceChoiceResult
    }

    /**
     * 「第二个」「选最快的」「就去拱北口岸」: picks from whichever list is on screen, through the same
     * [selectDestination] / [selectRoute] paths a tap uses.
     */
    fun chooseByVoice(choice: NavigationChoice): VoiceChoiceResult =
        when (store.phase.value) {
            NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                when (val match = NavigationChoiceResolver.pickDestination(_destinationCandidates.value, choice)) {
                    is ChoiceMatch.Rejected -> VoiceChoiceResult.Rejected(match.code)
                    is ChoiceMatch.Picked -> {
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
            else -> VoiceChoiceResult.Rejected("NO_OPTIONS_ON_SCREEN")
        }

    data class OptionsSnapshot(
        val phase: NavigationPhase,
        val destinations: List<DestinationCandidate>,
        val routes: List<RouteCandidate>,
        val destinationName: String?,
    )

    /** Waits (bounded) until destinations or routes are no longer being computed, then reports them. */
    suspend fun awaitOptions(timeoutMs: Long): OptionsSnapshot {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            store.phase.first { it != NavigationPhase.RESOLVING_DESTINATION && it != NavigationPhase.CALCULATING_ROUTE }
        }
        return synchronized(lock) {
            OptionsSnapshot(
                phase = store.phase.value,
                destinations = _destinationCandidates.value,
                routes = _routeCandidates.value,
                destinationName = selectedDestination?.name,
            )
        }
    }

    enum class VoiceEndResult { STOPPED_NAVIGATION, CANCELLED_SELECTION, NOTHING_ACTIVE }

    /**
     * 「结束导航」/「算了」 by voice: stops embedded guidance if it is running, otherwise closes an
     * open destination/route picker. Reports what actually happened so the reply can be truthful.
     */
    suspend fun endByVoice(): VoiceEndResult =
        when (store.phase.value) {
            NavigationPhase.NAVIGATING -> {
                stopNavigation()
                VoiceEndResult.STOPPED_NAVIGATION
            }
            NavigationPhase.AWAITING_DESTINATION_SELECTION,
            NavigationPhase.AWAITING_ROUTE_SELECTION,
            -> {
                cancel()
                VoiceEndResult.CANCELLED_SELECTION
            }
            else -> VoiceEndResult.NOTHING_ACTIVE
        }

    fun cancel() {
        synchronized(lock) {
            val phase = store.phase.value
            if (phase != NavigationPhase.AWAITING_DESTINATION_SELECTION &&
                phase != NavigationPhase.AWAITING_ROUTE_SELECTION
            ) {
                return
            }
            generation += 1
            clearCandidatesLocked()
            selectedDestination = null
            startNaviIssuedForSelection = false
            store.reset()
            DebugVoiceLog.log("nav_flow_cancelled")
            onFlowEnded()
        }
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
     * VoicePolicy long after the drive was over.
     *
     * Ignored unless we are actually navigating, so a stale stop cannot clobber a picker
     * the driver is in the middle of using.
     */
    private fun onNavigationEnded(reason: String) {
        synchronized(lock) {
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
            if (store.phase.value != NavigationPhase.CALCULATING_ROUTE) return
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
            store.update(NavigationPhase.AWAITING_ROUTE_SELECTION, selectedDestination?.toDestination())
        }
    }

    private fun onCalculateRouteFailure(errorCode: Int) {
        synchronized(lock) {
            if (store.phase.value != NavigationPhase.CALCULATING_ROUTE) return
            failLocked("calc_failure_$errorCode")
        }
    }

    private fun failLocked(reason: String) {
        DebugVoiceLog.log("nav_flow_error reason=$reason")
        clearCandidatesLocked()
        store.update(NavigationPhase.ERROR, selectedDestination?.toDestination())
        onFlowEnded()
    }

    private fun clearCandidatesLocked() {
        _destinationCandidates.value = emptyList()
        _routeCandidates.value = emptyList()
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
