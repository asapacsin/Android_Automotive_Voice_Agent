package com.novadrive.app.nav.amap

import com.novadrive.app.nav.NavigationPhase.AWAITING_ROUTE_SELECTION
import com.novadrive.app.nav.NavigationPhase.CALCULATING_ROUTE
import com.novadrive.app.nav.NavigationPhase.IDLE
import com.novadrive.app.nav.NavigationPhase.NAVIGATING
import com.novadrive.app.nav.amap.MapPresentationPolicy.Presentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapPresentationPolicyTest {
    @Test
    fun lateSuccessAfterCancelGetsNoPreviewAndNoCurrentIds() {
        // Driver cancelled: phase is already IDLE when the SDK reports success.
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onRouteReady(hostNavigating = false))
        assertFalse(MapPresentationPolicy.keepsRouteIds(IDLE, hostNavigating = false))
        // The controller dropped it, so the phase never changes: nothing is drawn.
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(IDLE, IDLE, hostNavigating = false))
    }

    @Test
    fun normalSuccessPreviewsOnceTheControllerAccepts() {
        assertTrue(MapPresentationPolicy.keepsRouteIds(CALCULATING_ROUTE, hostNavigating = false))
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onRouteReady(hostNavigating = false))
        assertEquals(
            Presentation.PREVIEW,
            MapPresentationPolicy.onPhase(CALCULATING_ROUTE, AWAITING_ROUTE_SELECTION, hostNavigating = false),
        )
    }

    @Test
    fun cancelReturnsToIdle() {
        assertEquals(Presentation.IDLE, MapPresentationPolicy.onPhase(AWAITING_ROUTE_SELECTION, IDLE, false))
        assertEquals(Presentation.IDLE, MapPresentationPolicy.onPhase(CALCULATING_ROUTE, IDLE, false))
    }

    @Test
    fun cancelWithKeptGuidanceRestoresDriving() {
        assertEquals(Presentation.DRIVING, MapPresentationPolicy.onPhase(AWAITING_ROUTE_SELECTION, NAVIGATING, true))
        assertEquals(Presentation.DRIVING, MapPresentationPolicy.onPhase(CALCULATING_ROUTE, NAVIGATING, true))
    }

    @Test
    fun rerouteWhileNavigatingIsUnchanged() {
        assertEquals(Presentation.PREVIEW, MapPresentationPolicy.onRouteReady(hostNavigating = true))
        assertTrue(MapPresentationPolicy.keepsRouteIds(NAVIGATING, hostNavigating = true))
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(NAVIGATING, NAVIGATING, true))
    }
}
