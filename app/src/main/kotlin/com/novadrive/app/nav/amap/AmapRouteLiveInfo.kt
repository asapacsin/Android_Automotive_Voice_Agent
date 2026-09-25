package com.novadrive.app.nav.amap

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.routepoisearch.RoutePOISearch
import com.amap.api.services.routepoisearch.RoutePOISearchQuery
import com.novadrive.app.nav.AlongRouteCategory
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.RouteLiveInfoSource
import com.novadrive.app.nav.RouteTraffic
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * SPEC-011's two SDK reads: traffic on the driven route (`AMapNavi.getTrafficStatuses`) and POIs
 * along the calculated route (`RoutePOISearch`, search 9.8.1 in the bundled combined artifact).
 *
 * AMapNavi is a process singleton, so this reads the same route [AmapNaviViewHost] calculated
 * without reaching into the host. Nothing here logs: a route is a trail of coordinates (I-8).
 *
 * UNVERIFIED ON DEVICE (2026-09-25): written against the SDK's signatures (javap), not run. The
 * `getTrafficStatuses(0, 0)` "remaining route" reading and the start→end route-POI query are the
 * two assumptions a device run must confirm (SPEC-011 A8/A9).
 */
class AmapRouteLiveInfo(context: Context) : RouteLiveInfoSource {
    private val app = context.applicationContext

    private fun navi(): AMapNavi? = runCatching {
        AmapPrivacyCompliance.ensure(app)
        AMapNavi.getInstance(app)
    }.getOrNull()

    override fun traffic(): RouteTraffic? = runCatching {
        val navi = navi() ?: return@runCatching null
        val path = navi.naviPath ?: return@runCatching null
        val statuses = navi.getTrafficStatuses(0, 0)?.takeIf { it.isNotEmpty() } ?: path.trafficStatuses
        statuses?.takeIf { it.isNotEmpty() }?.let { list -> RouteTraffic.summarize(list.map { it.status to it.length }) }
    }.getOrNull()

    override suspend fun alongRoute(category: AlongRouteCategory): List<DestinationCandidate>? {
        val path = navi()?.naviPath ?: return null
        val start = path.startPoint ?: return null
        val end = path.endPoint ?: return null
        val query = RoutePOISearchQuery(
            LatLonPoint(start.latitude, start.longitude),
            LatLonPoint(end.latitude, end.longitude),
            RoutePOISearch.DrivingDefault,
            searchType(category),
            RANGE_METERS,
        )
        return suspendCancellableCoroutine { continuation ->
            val search = runCatching { RoutePOISearch(app, query) }.getOrNull()
            if (search == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            search.setPoiSearchListener { result, code ->
                if (!continuation.isActive) return@setPoiSearchListener
                val items = if (code == SUCCESS) result?.routePois else null
                continuation.resume(
                    items?.mapIndexedNotNull { index, item ->
                        val point = item.point ?: return@mapIndexedNotNull null
                        runCatching {
                            DestinationCandidate(
                                id = item.id?.takeIf { it.isNotBlank() } ?: "r$index",
                                name = item.title.orEmpty(),
                                address = "",
                                district = "",
                                latitude = point.latitude,
                                longitude = point.longitude,
                                distanceMeters = item.distance.toInt(),
                                poiId = item.id?.takeIf { it.isNotBlank() },
                            )
                        }.getOrNull()
                    }?.sortedBy { it.distanceMeters ?: Int.MAX_VALUE },
                )
            }
            runCatching { search.searchRoutePOIAsyn() }.onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }

    private fun searchType(category: AlongRouteCategory): RoutePOISearch.RoutePOISearchType = when (category) {
        AlongRouteCategory.FUEL -> RoutePOISearch.RoutePOISearchType.TypeGasStation
        AlongRouteCategory.CHARGING -> RoutePOISearch.RoutePOISearchType.TypeChargeStation
        AlongRouteCategory.SERVICE_AREA -> RoutePOISearch.RoutePOISearchType.TypeServiceArea
        AlongRouteCategory.TOILET -> RoutePOISearch.RoutePOISearchType.TypeToilet
    }

    private companion object {
        /** AMapException.CODE_AMAP_SUCCESS. */
        const val SUCCESS = 1000

        /** How far off the route a POI may be; the SDK's range is in metres. */
        const val RANGE_METERS = 250
    }
}
