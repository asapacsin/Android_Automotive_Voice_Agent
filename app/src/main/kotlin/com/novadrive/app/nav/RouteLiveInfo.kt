package com.novadrive.app.nav

import com.novadrive.app.nav.StaticMapModel.GeoPoint

/**
 * SPEC-011: what the Navi SDK knows about the route on screen, behind an SDK-free port.
 * The implementation ([com.novadrive.app.nav.amap.AmapRouteLiveInfo]) is the only code that touches
 * `getTrafficStatuses` and the route geometry, because only `app/nav/amap` may import `com.amap` (I-9).
 * P68: POIs along the route come from one Web place/polygon search ([AlongRouteCorridor]), not the SDK.
 */
interface RouteLiveInfoSource {
    /** Traffic on the route being driven, or null when the SDK has no route. */
    fun traffic(): RouteTraffic?

    /** The calculated route's shape and how far along it the car is; null when there is no route. */
    fun routeProgress(): RouteProgress?

    companion object {
        /** No SDK (JVM tests, simulations): there is never a route to read. */
        val NONE: RouteLiveInfoSource = object : RouteLiveInfoSource {
            override fun traffic(): RouteTraffic? = null
            override fun routeProgress(): RouteProgress? = null
        }
    }
}

/**
 * The route on screen as plain values: GCJ-02 points in driving order (never logged, I-8) and the
 * metres already driven (allLength - pathRetainDistance; 0 while the route is only on screen).
 */
data class RouteProgress(val points: List<GeoPoint>, val travelledMeters: Int)

/** [amapTypes] is the Amap POI type code sent as `types=` (P68). */
enum class AlongRouteCategory(val wire: String, val spoken: String, val amapTypes: String) {
    FUEL("fuel", "加油站", "010100"),
    CHARGING("charging", "充电站", "011100"),
    SERVICE_AREA("service_area", "服务区", "180300"),
    TOILET("toilet", "厕所", "200300"),
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

/** SDK-free decisions behind [RouteLiveInfoSource] implementations. Never logs (I-8). */
object RouteLiveInfoMapping {
    /** Remaining-route statuses when non-empty, otherwise the path's own statuses. */
    fun <T> chooseTrafficStatuses(remaining: List<T>?, path: List<T>?): List<T>? =
        remaining?.takeIf { it.isNotEmpty() } ?: path
}
