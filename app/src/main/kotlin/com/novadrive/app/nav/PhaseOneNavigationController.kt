package com.novadrive.app.nav

import com.novadrive.app.AndroidActionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 1 binding for [NavigationController].
 * Records the request and returns an honest not-implemented outcome.
 * Does not launch any external map application — routing is Phase 2.
 */
class PhaseOneNavigationController : NavigationController {
    private val phase = MutableStateFlow(NavigationPhase.IDLE)

    @Volatile
    var lastDestination: String? = null
        private set

    fun navigate(destination: String): AndroidActionResult {
        lastDestination = destination
        return AndroidActionResult.Rejected("EMBEDDED_ROUTING_NOT_IMPLEMENTED")
    }

    override suspend fun resolveDestination(query: String): DestinationResult {
        lastDestination = query
        return DestinationResult.Failed("EMBEDDED_ROUTING_NOT_IMPLEMENTED")
    }

    override suspend fun planRoute(destination: Destination): RoutePlanResult =
        RoutePlanResult.Failed("EMBEDDED_ROUTING_NOT_IMPLEMENTED")

    override suspend fun startNavigation(routeId: Int?): NavigationResult =
        NavigationResult.Failed("EMBEDDED_ROUTING_NOT_IMPLEMENTED")

    override suspend fun stopNavigation() = Unit

    override suspend fun cancelRoute() = Unit

    override suspend fun reroute(): NavigationResult =
        NavigationResult.Failed("EMBEDDED_ROUTING_NOT_IMPLEMENTED")

    override fun state(): StateFlow<NavigationPhase> = phase.asStateFlow()
}
