package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavigationPhaseTest {
    @Test
    fun isNavigationSessionActiveForEveryPhase() {
        for (phase in NavigationPhase.entries) {
            val expected = when (phase) {
                NavigationPhase.IDLE -> false
                NavigationPhase.RESOLVING_DESTINATION -> false
                NavigationPhase.AWAITING_DESTINATION_SELECTION -> false
                NavigationPhase.PLANNING_ROUTE -> true
                NavigationPhase.CALCULATING_ROUTE -> true
                NavigationPhase.AWAITING_ROUTE_SELECTION -> false
                NavigationPhase.ROUTE_READY -> true
                NavigationPhase.NAVIGATING -> true
                NavigationPhase.ARRIVED -> false
                NavigationPhase.STOPPED -> false
                NavigationPhase.ERROR -> false
            }
            assertEquals(expected, phase.isNavigationSessionActive, phase.name)
        }
    }
}
