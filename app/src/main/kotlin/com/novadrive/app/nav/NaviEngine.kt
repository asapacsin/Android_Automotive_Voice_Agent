package com.novadrive.app.nav

/**
 * SDK-free seam over the live [com.novadrive.app.nav.amap.AmapNaviViewHost].
 * AMapNavi cannot run on the JVM, so tests inject a fake.
 */
interface NaviEngine {
    fun calculateDriveRoute(
        endLat: Double,
        endLon: Double,
        endName: String,
        strategy: Int = DRIVING_MULTIPLE_ROUTES_DEFAULT,
    ): Boolean

    fun selectRoute(routeId: Int): Boolean

    fun startNavigation(emulator: Boolean = false): Boolean

    fun stopNavigation(reason: String = "manual"): Boolean

    fun routeCandidates(): List<RouteCandidate>

    fun attachRouteCallbacks(
        onSuccess: (IntArray) -> Unit,
        onFailure: (Int) -> Unit,
    )

    /** Fires when the SDK session ends: arrival, emulator end, or a manual stop. */
    fun attachNavigationEndedCallback(onEnded: (reason: String) -> Unit)
}

fun interface DestinationCandidateSource {
    suspend fun resolve(query: String): List<DestinationCandidate>
}
