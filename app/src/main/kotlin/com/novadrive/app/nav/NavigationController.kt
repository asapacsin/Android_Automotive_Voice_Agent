package com.novadrive.app.nav

import kotlinx.coroutines.flow.StateFlow

interface NavigationController {
    suspend fun resolveDestination(query: String): DestinationResult
    suspend fun planRoute(destination: Destination): RoutePlanResult
    suspend fun startNavigation(routeId: Int? = null): NavigationResult
    suspend fun stopNavigation()
    fun showOverview(): Boolean = false
    fun resumeTracking(): Boolean = false
    suspend fun cancelRoute()
    suspend fun reroute(): NavigationResult
    fun state(): StateFlow<NavigationPhase>
}

interface DestinationResolver {
    suspend fun resolve(query: String): DestinationResult
}
