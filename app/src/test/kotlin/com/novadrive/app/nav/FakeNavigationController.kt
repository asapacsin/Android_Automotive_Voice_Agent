package com.novadrive.app.nav

import kotlinx.coroutines.flow.StateFlow

class FakeNavigationController(
    val store: NavigationStateStore = NavigationStateStore(),
) : NavigationController {
    var resolveOutcome: DestinationResult = DestinationResult.Resolved(DEFAULT_DESTINATION)
    var planOutcome: RoutePlanResult = RoutePlanResult.Success(routeId = 1)
    var startOutcome: NavigationResult = NavigationResult.Started
    var rerouteOutcome: NavigationResult = NavigationResult.Started

    private val _phaseTrace = mutableListOf(store.phase.value)
    val phaseTrace: List<NavigationPhase> get() = _phaseTrace.toList()

    override suspend fun resolveDestination(query: String): DestinationResult {
        val result = resolveOutcome
        val resolved = (result as? DestinationResult.Resolved)?.destination
        advance(NavigationPhase.RESOLVING_DESTINATION, resolved ?: store.destination.value)
        if (result is DestinationResult.Failed) {
            advance(NavigationPhase.ERROR)
        }
        return result
    }

    override suspend fun planRoute(destination: Destination): RoutePlanResult {
        advance(NavigationPhase.PLANNING_ROUTE, destination)
        return when (val result = planOutcome) {
            is RoutePlanResult.Success -> {
                advance(NavigationPhase.ROUTE_READY, destination)
                result
            }
            is RoutePlanResult.Failed -> {
                advance(NavigationPhase.ERROR, destination)
                result
            }
        }
    }

    override suspend fun startNavigation(routeId: Int?): NavigationResult {
        return when (val result = startOutcome) {
            NavigationResult.Started -> {
                advance(NavigationPhase.NAVIGATING)
                result
            }
            is NavigationResult.Failed -> {
                advance(NavigationPhase.ERROR)
                result
            }
        }
    }

    override suspend fun stopNavigation() {
        resetAndTrace()
    }

    override suspend fun cancelRoute() {
        resetAndTrace()
    }

    override suspend fun reroute(): NavigationResult {
        advance(NavigationPhase.PLANNING_ROUTE)
        return when (val result = rerouteOutcome) {
            NavigationResult.Started -> {
                advance(NavigationPhase.NAVIGATING)
                result
            }
            is NavigationResult.Failed -> {
                advance(NavigationPhase.ERROR)
                result
            }
        }
    }

    override fun state(): StateFlow<NavigationPhase> = store.phase

    private fun advance(phase: NavigationPhase, destination: Destination? = store.destination.value) {
        store.update(phase, destination)
        _phaseTrace += phase
    }

    private fun resetAndTrace() {
        store.reset()
        _phaseTrace += NavigationPhase.IDLE
    }

    companion object {
        val DEFAULT_DESTINATION = Destination(
            name = "珠海站",
            latitude = 22.2024,
            longitude = 113.5432,
        )
    }
}
