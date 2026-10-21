package com.novadrive.app.nav.amap

import com.novadrive.app.nav.NavigationPhase

/**
 * Which map presentation follows the controller's decision (TECH_DEBT D-CANCEL-HOST-OVERVIEW).
 * Pure, so the rule is JVM-tested; [AmapNaviViewHost] applies the result on the main thread.
 * The host never draws a route overview on the SDK callback alone: a result the controller
 * dropped after a cancel never reaches a route-selection phase, so it never gets a preview.
 */
internal object MapPresentationPolicy {
    enum class Presentation { PREVIEW, IDLE, DRIVING, UNCHANGED }

    private val routeShown = setOf(NavigationPhase.AWAITING_ROUTE_SELECTION, NavigationPhase.ROUTE_READY)
    private val flowOver = setOf(NavigationPhase.IDLE, NavigationPhase.STOPPED, NavigationPhase.ERROR)

    /**
     * A calculation succeeded. While guidance runs this is a reroute and keeps today's
     * behaviour; otherwise the preview waits for the controller to accept the result.
     */
    fun previewsOnRouteReady(hostNavigating: Boolean): Boolean = hostNavigating

    /** Route ids count as current only for a live calculation or a reroute, never a cancelled flow's. */
    fun keepsRouteIds(shownPhase: NavigationPhase, hostNavigating: Boolean): Boolean =
        hostNavigating || shownPhase == NavigationPhase.CALCULATING_ROUTE

    /**
     * Whether the SDK may draw the calculated route (`AMapNaviViewOptions.setAutoDrawRoute`).
     * P59: after a cancel the SDK keeps the route line drawn until auto-draw is switched off
     * (device run 2026-10-19), so only the preview and guidance draw it; idle never does.
     */
    fun drawsRoute(driving: Boolean, preview: Boolean): Boolean = driving || preview

    /** The controller's phase moved from [previous] to [phase]. */
    fun onPhase(previous: NavigationPhase, phase: NavigationPhase, hostNavigating: Boolean): Presentation = when {
        phase == previous -> Presentation.UNCHANGED
        phase in routeShown -> Presentation.PREVIEW
        // Cancel with kept guidance: the drive goes on, so the driving HUD comes back.
        phase == NavigationPhase.NAVIGATING && hostNavigating && previous != NavigationPhase.NAVIGATING ->
            Presentation.DRIVING
        phase in flowOver && !hostNavigating -> Presentation.IDLE
        else -> Presentation.UNCHANGED
    }
}
