package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NavigationStateStoreTest {
    @Test
    fun initialPhaseIsIdleWithNullDestination() {
        val store = NavigationStateStore()
        assertEquals(NavigationPhase.IDLE, store.phase.value)
        assertNull(store.destination.value)
    }

    @Test
    fun updateChangesPhaseAndDestination() {
        val store = NavigationStateStore()
        val destination = Destination("珠海站", 22.2024, 113.5432)
        store.update(NavigationPhase.ROUTE_READY, destination)
        assertEquals(NavigationPhase.ROUTE_READY, store.phase.value)
        assertEquals(destination, store.destination.value)
    }

    @Test
    fun resetReturnsToIdleWithNullDestination() {
        val store = NavigationStateStore()
        store.update(NavigationPhase.NAVIGATING, Destination("珠海站", 22.2024, 113.5432))
        store.reset()
        assertEquals(NavigationPhase.IDLE, store.phase.value)
        assertNull(store.destination.value)
    }
}
