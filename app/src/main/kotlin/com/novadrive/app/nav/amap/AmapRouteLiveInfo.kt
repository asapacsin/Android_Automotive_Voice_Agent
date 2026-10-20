package com.novadrive.app.nav.amap

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.novadrive.app.nav.RouteLiveInfoMapping
import com.novadrive.app.nav.RouteLiveInfoSource
import com.novadrive.app.nav.RouteProgress
import com.novadrive.app.nav.RouteTraffic
import com.novadrive.app.nav.StaticMapModel.GeoPoint

/**
 * SPEC-011's SDK reads: traffic on the driven route (`AMapNavi.getTrafficStatuses`) and, for
 * along_route (P68), the route's shape and the car's progress on it. The POI search itself is one
 * Web place/polygon call made by LiveInfoTool through AmapPoiClient; the SDK's route-POI search is not
 * used (device run 20261020: code=1000 raw=0 at every range).
 *
 * AMapNavi is a process singleton, so this reads the same route [AmapNaviViewHost] calculated
 * without reaching into the host. Nothing here logs: a route is a trail of coordinates (I-8).
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
        val statuses = RouteLiveInfoMapping.chooseTrafficStatuses(navi.getTrafficStatuses(0, 0), path.trafficStatuses)
        statuses?.takeIf { it.isNotEmpty() }?.let { list -> RouteTraffic.summarize(list.map { it.status to it.length }) }
    }.getOrNull()

    /** GCJ-02 already; s_car = allLength - pathRetainDistance during guidance, else 0. */
    override fun routeProgress(): RouteProgress? = runCatching {
        val path = navi()?.naviPath ?: return@runCatching null
        val points = path.coordList?.map { GeoPoint(it.latitude, it.longitude) }?.takeIf { it.size >= 2 }
            ?: return@runCatching null
        val retain = NavigationTraceListener.latestRetainMeters
        val travelled = if (retain == null) 0 else (path.allLength - retain).coerceAtLeast(0)
        RouteProgress(points, travelled)
    }.getOrNull()
}

/**
 * Route shapes for the emulator's static map picture (GCJ-02, as the Web API expects), read from
 * the same AMapNavi singleton. Active route first while navigating; otherwise every calculated
 * route in id order. Never logged.
 */
internal object AmapRouteGeometry {
    fun routes(context: Context, navigating: Boolean): List<List<GeoPoint>> = runCatching {
        val navi = AMapNavi.getInstance(context.applicationContext)
        val paths = if (navigating) listOfNotNull(navi.naviPath) else navi.naviPaths?.toSortedMap()?.values?.toList().orEmpty()
        paths.mapNotNull { path -> path.coordList?.map { GeoPoint(it.latitude, it.longitude) }?.takeIf { it.size >= 2 } }
    }.getOrDefault(emptyList())
}
