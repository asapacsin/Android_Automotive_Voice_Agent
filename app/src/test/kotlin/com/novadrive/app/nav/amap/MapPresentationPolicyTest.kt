package com.novadrive.app.nav.amap

import com.novadrive.app.nav.NavigationPhase.AWAITING_DESTINATION_SELECTION
import com.novadrive.app.nav.NavigationPhase.AWAITING_ROUTE_SELECTION
import com.novadrive.app.nav.NavigationPhase.CALCULATING_ROUTE
import com.novadrive.app.nav.NavigationPhase.IDLE
import com.novadrive.app.nav.NavigationPhase.ERROR
import com.novadrive.app.nav.NavigationPhase.NAVIGATING
import com.novadrive.app.nav.NavigationPhase.RESOLVING_DESTINATION
import com.novadrive.app.nav.NavigationPhase.STOPPED
import com.novadrive.app.nav.amap.MapPresentationPolicy.Presentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapPresentationPolicyTest {
    @Test
    fun lateSuccessAfterCancelGetsNoPreviewAndNoCurrentIds() {
        // Driver cancelled: phase is already IDLE when the SDK reports success.
        assertFalse(MapPresentationPolicy.previewsOnRouteReady(hostNavigating = false))
        assertFalse(MapPresentationPolicy.keepsRouteIds(IDLE, hostNavigating = false))
        // The controller dropped it, so the phase never changes: nothing is drawn.
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(IDLE, IDLE, hostNavigating = false))
    }

    @Test
    fun normalSuccessPreviewsOnceTheControllerAccepts() {
        assertTrue(MapPresentationPolicy.keepsRouteIds(CALCULATING_ROUTE, hostNavigating = false))
        assertFalse(MapPresentationPolicy.previewsOnRouteReady(hostNavigating = false))
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
        assertEquals(Presentation.DRIVING, MapPresentationPolicy.onPhase(AWAITING_DESTINATION_SELECTION, NAVIGATING, true))
        assertEquals(Presentation.DRIVING, MapPresentationPolicy.onPhase(RESOLVING_DESTINATION, NAVIGATING, true))
    }

    @Test
    fun normalStartGivesDriving() {
        assertEquals(Presentation.DRIVING, MapPresentationPolicy.onPhase(AWAITING_ROUTE_SELECTION, NAVIGATING, true))
    }

    @Test
    fun searchDuringGuidanceLeavesDrivingHud() {
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(NAVIGATING, RESOLVING_DESTINATION, true))
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(NAVIGATING, AWAITING_DESTINATION_SELECTION, true))
    }

    @Test
    fun endedFlowsSettleIdleOnlyWhenNotNavigating() {
        assertEquals(Presentation.IDLE, MapPresentationPolicy.onPhase(STOPPED, IDLE, false))
        assertEquals(Presentation.IDLE, MapPresentationPolicy.onPhase(ERROR, IDLE, false))
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(NAVIGATING, STOPPED, true))
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(NAVIGATING, IDLE, true))
    }

    @Test
    fun routeIdsOutsideCalculationAreNotKept() {
        assertFalse(MapPresentationPolicy.keepsRouteIds(RESOLVING_DESTINATION, hostNavigating = false))
        assertFalse(MapPresentationPolicy.keepsRouteIds(AWAITING_ROUTE_SELECTION, hostNavigating = false))
    }

    @Test
    fun rerouteWhileNavigatingIsUnchanged() {
        assertTrue(MapPresentationPolicy.previewsOnRouteReady(hostNavigating = true))
        assertTrue(MapPresentationPolicy.keepsRouteIds(NAVIGATING, hostNavigating = true))
        assertEquals(Presentation.UNCHANGED, MapPresentationPolicy.onPhase(NAVIGATING, NAVIGATING, true))
    }

    @Test
    fun routeIsDrawnOnlyForPreviewAndDriving() {
        assertFalse(MapPresentationPolicy.drawsRoute(driving = false, preview = false))
        assertTrue(MapPresentationPolicy.drawsRoute(driving = false, preview = true))
        assertTrue(MapPresentationPolicy.drawsRoute(driving = true, preview = false))
    }

    @Test
    fun cancelFromRouteSelectionStopsDrawingTheRoute() {
        // P59: 「算了」 at route selection -> IDLE, applied as applyIdle(driving=false, preview=false).
        assertEquals(Presentation.IDLE, MapPresentationPolicy.onPhase(AWAITING_ROUTE_SELECTION, IDLE, false))
        assertFalse(MapPresentationPolicy.drawsRoute(driving = false, preview = false))
    }
}
