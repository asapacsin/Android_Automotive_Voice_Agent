package com.novadrive.app.nav

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeNavigationControllerTest {
    @Test
    fun resolvePlanStartWalksIdleToNavigating() = runBlocking {
        val controller = FakeNavigationController()
        assertEquals(NavigationPhase.IDLE, controller.store.phase.value)

        val resolved = controller.resolveDestination("珠海站")
        assertTrue(resolved is DestinationResult.Resolved)
        assertEquals(NavigationPhase.RESOLVING_DESTINATION, controller.store.phase.value)

        val destination = (resolved as DestinationResult.Resolved).destination
        val planned = controller.planRoute(destination)
        assertTrue(planned is RoutePlanResult.Success)
        assertEquals(NavigationPhase.ROUTE_READY, controller.store.phase.value)

        val started = controller.startNavigation()
        assertEquals(NavigationResult.Started, started)
        assertEquals(NavigationPhase.NAVIGATING, controller.store.phase.value)

        assertEquals(
            listOf(
                NavigationPhase.IDLE,
                NavigationPhase.RESOLVING_DESTINATION,
                NavigationPhase.PLANNING_ROUTE,
                NavigationPhase.ROUTE_READY,
                NavigationPhase.NAVIGATING,
            ),
            controller.phaseTrace,
        )
    }

    @Test
    fun ambiguousResolveDoesNotAdvanceToPlanningRoute() = runBlocking {
        val controller = FakeNavigationController()
        val candidates = listOf(
            Destination("万达广场", 22.27, 113.57),
            Destination("万达影城", 22.28, 113.58),
        )
        controller.resolveOutcome = DestinationResult.Ambiguous(candidates)

        val result = controller.resolveDestination("万达")
        assertTrue(result is DestinationResult.Ambiguous)
        assertEquals(NavigationPhase.RESOLVING_DESTINATION, controller.store.phase.value)
        assertFalse(controller.phaseTrace.contains(NavigationPhase.PLANNING_ROUTE))
        assertEquals(NavigationPhase.IDLE, controller.phaseTrace.first())
    }
}
