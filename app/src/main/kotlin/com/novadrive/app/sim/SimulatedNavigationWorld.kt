package com.novadrive.app.sim

import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.NavigationBackend
import com.novadrive.app.nav.RouteCandidate
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A deterministic navigation world for benchmarks: fixed places, three fixed routes per place,
 * programmable progress, arrival and faults. No road geometry — only what the app logic needs.
 *
 * Data (kept in sync with `ScenarioCatalog`):
 * - 「澳门大学」 → 澳门大学 / 澳门大学图书馆 / 澳门大学运动场
 * - 「万达」 → 万达广场 / 万达影城 / 万达酒店
 * - 「珠海站」 → 珠海站 / 珠海站(进站口) / 珠海站地下停车场
 * - 「横琴口岸」「公司」「家」 → exactly one place
 * - routes, in order: 推荐 (fastest) · 距离最短 (shortest) · 免费 (no toll, fewest lights)
 *
 * Route callbacks arrive on a worker thread after [calcDelayMs], like the SDK's asynchronous ones.
 */
class SimulatedNavigationWorld(
    @Volatile var calcDelayMs: Long = 5,
) : NavigationBackend {
    data class Place(val name: String, val lat: Double, val lon: Double, val meters: Int)

    companion object {
        private val PLACES: List<Pair<String, List<Place>>> = listOf(
            "澳门大学" to listOf(
                Place("澳门大学", 22.1265, 113.5446, 9_800),
                Place("澳门大学图书馆", 22.1270, 113.5451, 10_100),
                Place("澳门大学运动场", 22.1251, 113.5432, 10_600),
            ),
            "万达" to listOf(
                Place("万达广场", 22.2710, 113.5670, 6_200),
                Place("万达影城", 22.2715, 113.5675, 6_300),
                Place("万达酒店", 22.2720, 113.5680, 6_900),
            ),
            "珠海站" to listOf(
                Place("珠海站", 22.2155, 113.5480, 9_200),
                Place("珠海站(进站口)", 22.2157, 113.5482, 9_200),
                Place("珠海站地下停车场", 22.2150, 113.5478, 9_300),
            ),
            "横琴口岸" to listOf(Place("横琴口岸", 22.1395, 113.5520, 4_500)),
            "公司" to listOf(Place("公司", 22.2600, 113.5800, 12_000)),
            "家" to listOf(Place("家", 22.2400, 113.5300, 7_500)),
        )

        const val LABEL_RECOMMENDED = "推荐"
        const val LABEL_SHORTEST = "距离最短"
        const val LABEL_NO_TOLL = "免费"
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sim-navi").apply { isDaemon = true } }

    @Volatile private var onSuccess: ((IntArray) -> Unit)? = null
    @Volatile private var onFailure: ((Int) -> Unit)? = null
    @Volatile private var onEnded: ((String) -> Unit)? = null

    private var nextRouteId = 12
    private var latestCalcBase = -1
    private var routes: List<RouteCandidate> = emptyList()
    private var selectedRouteId: Int? = null
    private var activeRoute: RouteCandidate? = null

    @Volatile var navigating = false
        private set

    @Volatile var progress = 0.0
        private set

    // ---- faults -----------------------------------------------------------------------------

    private val resolveDelays = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val inFlight = java.util.concurrent.atomic.AtomicInteger()
    private val delaySlept = java.util.concurrent.atomic.AtomicLong()

    /** Destination searches still running (a delayed one may return after a newer request). */
    val searchesInFlight: Int get() = inFlight.get()

    /** Total deliberate search delay applied since the last [reset]. */
    val delaySleptMs: Long get() = delaySlept.get()
    @Volatile var failNextResolve = false
    @Volatile var failNextCalcWith: Int? = null
    @Volatile var failNextStart = false

    fun delayResolve(query: String, ms: Long) {
        resolveDelays[query] = ms
    }

    fun reset() {
        synchronized(lock) {
            routes = emptyList()
            selectedRouteId = null
            activeRoute = null
            navigating = false
            progress = 0.0
            latestCalcBase = -1
        }
        resolveDelays.clear()
        delaySlept.set(0)
        failNextResolve = false
        failNextCalcWith = null
        failNextStart = false
    }

    // ---- DestinationCandidateSource ----------------------------------------------------------

    override suspend fun resolve(query: String): List<DestinationCandidate> {
        inFlight.incrementAndGet()
        try {
            return resolveNow(query)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun resolveNow(query: String): List<DestinationCandidate> {
        val key = PLACES.map { it.first }.filter { query.contains(it) }.maxByOrNull { it.length }
        resolveDelays.entries.firstOrNull { query.contains(it.key) }?.let {
            delaySlept.addAndGet(it.value)
            delay(it.value)
        }
        if (failNextResolve) {
            failNextResolve = false
            return emptyList()
        }
        val places = PLACES.firstOrNull { it.first == key }?.second ?: return emptyList()
        return places.mapIndexed { i, p ->
            DestinationCandidate(
                id = "sim-${key}-$i", name = p.name, address = "模拟地址${i + 1}", district = "模拟区",
                latitude = p.lat, longitude = p.lon, distanceMeters = p.meters, poiId = "sim-$key-$i",
            )
        }
    }

    // ---- NaviEngine --------------------------------------------------------------------------

    override fun calculateDriveRoute(endLat: Double, endLon: Double, endName: String, strategy: Int): Boolean {
        val failure = failNextCalcWith?.also { failNextCalcWith = null }
        val meters = PLACES.flatMap { it.second }.firstOrNull { it.name == endName }?.meters ?: 10_000
        val built = synchronized(lock) {
            val base = nextRouteId
            nextRouteId += 3
            latestCalcBase = base
            listOf(
                RouteCandidate(base, (meters * 1.40).toInt(), 25 * 60, LABEL_RECOMMENDED, 12),
                RouteCandidate(base + 1, (meters * 1.20).toInt(), 29 * 60, LABEL_SHORTEST, 18),
                RouteCandidate(base + 2, (meters * 1.60).toInt(), 33 * 60, LABEL_NO_TOLL, 8),
            )
        }
        val base = built.first().routeId
        worker.schedule({
            // A newer request supersedes this one, as in the SDK.
            if (synchronized(lock) { latestCalcBase != base }) return@schedule
            if (failure != null) {
                onFailure?.invoke(failure)
            } else {
                synchronized(lock) {
                    routes = built
                    selectedRouteId = null
                }
                onSuccess?.invoke(built.map { it.routeId }.toIntArray())
            }
        }, calcDelayMs, TimeUnit.MILLISECONDS)
        return true
    }

    override fun selectRoute(routeId: Int): Boolean = synchronized(lock) {
        if (routes.none { it.routeId == routeId }) return false
        selectedRouteId = routeId
        true
    }

    override fun startNavigation(emulator: Boolean): Boolean {
        if (failNextStart) {
            failNextStart = false
            return false
        }
        synchronized(lock) {
            val route = routes.firstOrNull { it.routeId == selectedRouteId } ?: routes.firstOrNull() ?: return false
            activeRoute = route
            navigating = true
            progress = 0.0
        }
        return true
    }

    /** Like the SDK host: stopping an active session reports the end exactly once. */
    override fun stopNavigation(reason: String): Boolean {
        synchronized(lock) {
            if (!navigating) return false
            navigating = false
        }
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

    // ---- driving --------------------------------------------------------------------------------

    fun advance(fraction: Double) {
        if (navigating) progress = fraction.coerceIn(0.0, 1.0)
    }

    /** The vehicle reaches the destination: the SDK's arrival path. */
    fun arrive() {
        if (!navigating) return
        progress = 1.0
        stopNavigation("arrived")
    }

    /** Label of the route being driven, or "" when not navigating. */
    val activeRouteLabel: String
        get() = synchronized(lock) { if (navigating) activeRoute?.labels.orEmpty() else "" }
}
