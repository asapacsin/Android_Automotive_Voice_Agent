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

/** A route POI as plain values, already lifted out of the SDK result. [lat]/[lon] null = no point. */
data class RoutePoi(
    val id: String?,
    val title: String?,
    val lat: Double?,
    val lon: Double?,
    val distanceMeters: Int,
)

/** SDK-free decisions behind [RouteLiveInfoSource] implementations. Never logs (I-8). */
object RouteLiveInfoMapping {
    /** Remaining-route statuses when non-empty, otherwise the path's own statuses. */
    fun <T> chooseTrafficStatuses(remaining: List<T>?, path: List<T>?): List<T>? =
        remaining?.takeIf { it.isNotEmpty() } ?: path

    /**
     * Text-free diagnostic for one RoutePOISearch (I-8): SDK code, raw item count (-1 when the SDK
     * gave no list), mapped candidate count and why [toCandidates] dropped items. A missing id is not
     * a drop (it falls back to r<index>); dropped_no_point counts items without lat/lon and
     * dropped_other the rest (0 unless code is 1000: nothing else is mapped). Counts and metres only, never a name, id or coordinate.
     */
    fun alongRouteSearchLine(code: Int, raw: List<RoutePoi>?, mapped: Int, rangeMeters: Int, routeMeters: Int): String {
        val noPoint = raw?.count { it.lat == null || it.lon == null } ?: 0
        val other = if (raw == null || code != 1000) 0 else (raw.size - mapped - noPoint).coerceAtLeast(0)
        return "along_route_search code=$code raw=${raw?.size ?: -1} mapped=$mapped dropped_no_point=$noPoint " +
            "dropped_other=$other range_m=$rangeMeters route_m=$routeMeters"
    }

    /** Route POIs to candidates: id falls back to r<index>, no point is dropped, nearest first. */
    fun toCandidates(pois: List<RoutePoi>): List<DestinationCandidate> =
        pois.mapIndexedNotNull { index, item ->
            val lat = item.lat ?: return@mapIndexedNotNull null
            val lon = item.lon ?: return@mapIndexedNotNull null
            runCatching {
                DestinationCandidate(
                    id = item.id?.takeIf { it.isNotBlank() } ?: "r$index",
                    name = item.title.orEmpty(),
                    address = "",
                    district = "",
                    latitude = lat,
                    longitude = lon,
                    distanceMeters = item.distanceMeters,
                    poiId = item.id?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
}
