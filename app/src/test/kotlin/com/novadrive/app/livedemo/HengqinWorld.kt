package com.novadrive.app.livedemo

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.nav.AlongRouteCategory
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.NavigationBackend
import com.novadrive.app.nav.RouteCandidate
import com.novadrive.app.nav.RouteLiveInfoSource
import com.novadrive.app.nav.RouteTraffic
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The commute demo's simulated map (docs/DEMO_COMMUTE.md): 横琴·澳门青年创业谷 → 横琴镇, driven in real
 * time. Stands in for the Amap SDK behind [NavigationBackend]; the navigation state machine on top
 * of it is the shipped [com.novadrive.app.nav.EmbeddedNavigationController]. Places are approximate
 * and simulated; only 横琴 queries return anything.
 *
 * Route traffic comes from here, not from Amap: real traffic needs an SDK route. It reports one slow
 * segment and no congestion, with the remaining distance from the simulated progress.
 */
class HengqinWorld : NavigationBackend, RouteLiveInfoSource {
    data class Place(val name: String, val lat: Double, val lon: Double, val meters: Int, val address: String)

    companion object {
        /** 横琴·澳门青年创业谷, WGS-84 (OpenStreetMap), as in record_demo.py. */
        const val ORIGIN_LAT = 22.1340339
        const val ORIGIN_LON = 113.5369122

        private val PLACES: List<Pair<String, List<Place>>> = listOf(
            "横琴镇" to listOf(
                Place("横琴镇", 22.1420, 113.5173, 2_600, "珠海市香洲区横琴镇"),
                Place("横琴镇政务服务中心", 22.1412, 113.5190, 2_450, "珠海市香洲区横琴镇（模拟）"),
                Place("横琴镇小横琴社区", 22.1305, 113.5262, 1_500, "珠海市香洲区横琴镇（模拟）"),
            ),
            "横琴口岸" to listOf(Place("横琴口岸", 22.1395, 113.5520, 2_100, "珠海市香洲区横琴口岸（模拟）")),
            "长隆" to listOf(Place("长隆海洋王国", 22.1010, 113.5310, 5_200, "珠海市香洲区横琴（模拟）")),
        )
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "livedemo-navi").apply { isDaemon = true } }

    @Volatile private var onSuccess: ((IntArray) -> Unit)? = null
    @Volatile private var onFailure: ((Int) -> Unit)? = null
    @Volatile private var onEnded: ((String) -> Unit)? = null

    private var nextRouteId = 12
    private var latestCalcBase = -1
    private var routes: List<RouteCandidate> = emptyList()
    private var selectedRouteId: Int? = null

    @Volatile var activeRoute: RouteCandidate? = null
        private set
    @Volatile var destinationName: String? = null
        private set
    @Volatile var navigating = false
        private set
    @Volatile var arrived = false
        private set
    @Volatile var progress = 0.0
        private set

    /** Simulated speed; 40 km/h is the slowest the Amap emulator allows, as in the demo. */
    @Volatile var speedKmh = 40.0

    private val tickMs = 200L

    init {
        worker.scheduleAtFixedRate({ tick() }, tickMs, tickMs, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val route = activeRoute ?: return
        if (!navigating) return
        progress = (progress + speedKmh / 3.6 * tickMs / 1000.0 / route.distanceMeters).coerceAtMost(1.0)
        if (progress >= 1.0) {
            arrived = true
            DebugVoiceLog.log("nav_arrived")
            stopNavigation("arrived")
        }
    }

    val remainingMeters: Int get() = activeRoute?.let { (it.distanceMeters * (1 - progress)).toInt() } ?: 0

    override suspend fun resolve(query: String): List<DestinationCandidate> {
        val key = PLACES.map { it.first }.filter { query.contains(it) }.maxByOrNull { it.length } ?: return emptyList()
        val places = PLACES.first { it.first == key }.second
        return places.mapIndexed { i, p ->
            DestinationCandidate(
                id = "live-$key-$i", name = p.name, address = p.address, district = "香洲区",
                latitude = p.lat, longitude = p.lon, distanceMeters = p.meters, poiId = null,
            )
        }
    }

    override fun calculateDriveRoute(endLat: Double, endLon: Double, endName: String, strategy: Int): Boolean {
        val meters = PLACES.flatMap { it.second }.firstOrNull { it.name == endName }?.meters ?: 3_000
        val built = synchronized(lock) {
            val base = nextRouteId
            nextRouteId += 3
            latestCalcBase = base
            destinationName = endName
            // ~40 km/h urban average; the labels match SimulatedNavigationWorld's.
            listOf(
                RouteCandidate(base, meters, meters * 9 / 100, "推荐", 4),
                RouteCandidate(base + 1, (meters * 0.95).toInt(), meters * 10 / 100, "距离最短", 6),
                RouteCandidate(base + 2, (meters * 1.15).toInt(), meters * 11 / 100, "免费", 3),
            )
        }
        val base = built.first().routeId
        worker.schedule({
            if (synchronized(lock) { latestCalcBase != base }) return@schedule
            synchronized(lock) {
                routes = built
                selectedRouteId = null
            }
            onSuccess?.invoke(built.map { it.routeId }.toIntArray())
        }, 300, TimeUnit.MILLISECONDS)
        return true
    }

    override fun selectRoute(routeId: Int): Boolean = synchronized(lock) {
        if (routes.none { it.routeId == routeId }) return false
        selectedRouteId = routeId
        true
    }

    override fun startNavigation(emulator: Boolean): Boolean {
        synchronized(lock) {
            val route = routes.firstOrNull { it.routeId == selectedRouteId } ?: routes.firstOrNull() ?: return false
            activeRoute = route
            navigating = true
            arrived = false
            progress = 0.0
        }
        DebugVoiceLog.log("nav_started")
        return true
    }

    override fun showOverview(): Boolean = navigating
    override fun resumeTracking(): Boolean = navigating

    override fun stopNavigation(reason: String): Boolean {
        synchronized(lock) {
            if (!navigating) return false
            navigating = false
        }
        DebugVoiceLog.log("nav_ended reason=$reason")
        onEnded?.invoke(reason)
        return true
    }

    override fun routeCandidates(): List<RouteCandidate> = synchronized(lock) { routes }

    override fun attachRouteCallbacks(onSuccess: (IntArray) -> Unit, onFailure: (Int) -> Unit) {
        this.onSuccess = onSuccess
        this.onFailure = onFailure
    }

    override fun attachNavigationEndedCallback(onEnded: (reason: String) -> Unit) {
        this.onEnded = onEnded
    }

    override fun traffic(): RouteTraffic? =
        if (!navigating) null else RouteTraffic(remainingMeters, slowSegments = 1, congestedSegments = 0, metersToFirstCongestion = null)

    override suspend fun alongRoute(category: AlongRouteCategory): List<DestinationCandidate>? = emptyList()

    fun close() = worker.shutdownNow()
}
