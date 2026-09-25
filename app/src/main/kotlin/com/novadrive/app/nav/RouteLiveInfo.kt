package com.novadrive.app.nav

/**
 * SPEC-011: what the Navi and Search SDKs know about the route on screen, behind an SDK-free port.
 * The implementation ([com.novadrive.app.nav.amap.AmapRouteLiveInfo]) is the only code that touches
 * `getTrafficStatuses` / `RoutePOISearch`, because only `app/nav/amap` may import `com.amap` (I-9).
 */
interface RouteLiveInfoSource {
    /** Traffic on the route being driven, or null when the SDK has no route. */
    fun traffic(): RouteTraffic?

    /** POIs of [category] along the calculated route; null when the search itself failed. */
    suspend fun alongRoute(category: AlongRouteCategory): List<DestinationCandidate>?

    companion object {
        /** No SDK (JVM tests, simulations): there is never a route to read. */
        val NONE: RouteLiveInfoSource = object : RouteLiveInfoSource {
            override fun traffic(): RouteTraffic? = null
            override suspend fun alongRoute(category: AlongRouteCategory): List<DestinationCandidate>? = null
        }
    }
}

enum class AlongRouteCategory(val wire: String, val spoken: String) {
    FUEL("fuel", "加油站"),
    CHARGING("charging", "充电站"),
    SERVICE_AREA("service_area", "服务区"),
    TOILET("toilet", "厕所"),
    ;

    companion object {
        fun fromWire(raw: String?): AlongRouteCategory? = entries.firstOrNull { it.wire == raw }
    }
}

/** Counts and distances only — never a coordinate (SPEC-011 B2, I-8). */
data class RouteTraffic(
    val remainingMeters: Int,
    val slowSegments: Int,
    val congestedSegments: Int,
    /** Distance along the route to the first congested segment, or null when there is none. */
    val metersToFirstCongestion: Int?,
) {
    companion object {
        /** Amap `AMapTrafficStatus.status`: 0 unknown, 1 smooth, 2 slow, 3 congested, 4 severely congested. */
        const val SLOW = 2
        const val CONGESTED = 3

        /** [segments] are (status, length in metres) in driving order from the car. */
        fun summarize(segments: List<Pair<Int, Int>>): RouteTraffic {
            var travelled = 0
            var firstJam: Int? = null
            var slow = 0
            var jams = 0
            segments.forEach { (status, length) ->
                when {
                    status >= CONGESTED -> {
                        jams++
                        if (firstJam == null) firstJam = travelled
                    }
                    status == SLOW -> slow++
                }
                travelled += length.coerceAtLeast(0)
            }
            return RouteTraffic(travelled, slow, jams, firstJam)
        }
    }
}
