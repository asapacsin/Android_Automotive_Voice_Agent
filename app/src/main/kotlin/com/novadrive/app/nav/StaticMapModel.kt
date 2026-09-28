package com.novadrive.app.nav

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The map picture on a build that cannot draw the Amap GL map (x86 emulator, `TranslatedAbi`):
 * which overlays the Amap Web Service static map (`/v3/staticmap`) is asked for, and when it is
 * worth asking again. Pure Kotlin so it is unit-tested; the fetch lives in `nav/amap`.
 *
 * The URL it builds carries the Web key and coordinates, so it must never be logged (I-8).
 */
object StaticMapModel {
    data class GeoPoint(val latitude: Double, val longitude: Double)

    /** What the picture shows. [routes] first entry is drawn as the active/first route. */
    data class Scene(
        val car: GeoPoint?,
        val destination: GeoPoint?,
        val candidates: List<GeoPoint> = emptyList(),
        val routes: List<List<GeoPoint>> = emptyList(),
        /** Navigating: centred on the car at street zoom, route cut to the stretch around it. */
        val followCar: Boolean = false,
    ) {
        val empty: Boolean get() = car == null && destination == null && candidates.isEmpty() && routes.isEmpty()
    }

    const val ENDPOINT = "https://restapi.amap.com/v3/staticmap"
    const val FOLLOW_ZOOM = 15
    const val MAX_ROUTES = 3
    const val MAX_POINTS_PER_ROUTE = 80
    const val MAX_CANDIDATES = 9
    private const val AHEAD_METERS = 3_000.0
    private const val BEHIND_METERS = 300.0

    fun url(scene: Scene, key: String, widthPx: Int, heightPx: Int): String {
        val w = (widthPx / 2).coerceIn(64, 1024)
        val h = (heightPx / 2).coerceIn(64, 1024)
        val params = mutableListOf("key=$key", "size=${w}*$h", "scale=2")
        val car = scene.car
        if (scene.followCar && car != null) {
            params += "location=${fmt(car)}"
            params += "zoom=$FOLLOW_ZOOM"
        }
        val markers = mutableListOf<String>()
        scene.destination?.let { markers += "mid,0xE53935,D:${fmt(it)}" }
        scene.candidates.take(MAX_CANDIDATES).forEachIndexed { i, p -> markers += "mid,0xFB8C00,${i + 1}:${fmt(p)}" }
        car?.let { markers += "mid,0x1E88E5,C:${fmt(it)}" }
        if (markers.isNotEmpty()) params += "markers=" + markers.joinToString("|")
        val paths = scene.routes.take(MAX_ROUTES).mapIndexedNotNull { i, route ->
            val pts = if (scene.followCar && car != null) aroundCar(route, car) else route
            val simple = simplify(pts, MAX_POINTS_PER_ROUTE)
            if (simple.size < 2) return@mapIndexedNotNull null
            val style = if (i == 0) "8,0x1976D2,1,," else "6,0x78909C,0.8,,"
            style + ":" + simple.joinToString(";") { fmt(it) }
        }.reversed() // the active route is drawn last, on top
        if (paths.isNotEmpty()) params += "paths=" + paths.joinToString("|")
        return ENDPOINT + "?" + params.joinToString("&")
    }

    /** The part of [route] from a little behind the car's nearest vertex to a few km ahead. */
    fun aroundCar(route: List<GeoPoint>, car: GeoPoint): List<GeoPoint> {
        if (route.size < 2) return route
        val nearest = route.indices.minBy { meters(route[it], car) }
        var start = nearest
        var back = 0.0
        while (start > 0 && back < BEHIND_METERS) { back += meters(route[start - 1], route[start]); start-- }
        var end = nearest
        var ahead = 0.0
        while (end < route.lastIndex && ahead < AHEAD_METERS) { ahead += meters(route[end], route[end + 1]); end++ }
        return route.subList(start, end + 1)
    }

    /** Douglas–Peucker with a growing tolerance until at most [maxPoints] remain; ends kept. */
    fun simplify(points: List<GeoPoint>, maxPoints: Int): List<GeoPoint> {
        if (points.size <= maxPoints) return points
        var tolerance = 5.0
        var out = points
        while (out.size > maxPoints) {
            out = douglasPeucker(points, tolerance)
            tolerance *= 1.6
        }
        return out
    }

    private fun douglasPeucker(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var worst = -1
            var worstD = toleranceM
            for (i in a + 1 until b) {
                val d = offsetMeters(points[i], points[a], points[b])
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(a to worst)
                stack.addLast(worst to b)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Local equirectangular metres; fine at city scale. */
    fun meters(a: GeoPoint, b: GeoPoint): Double {
        val (x, y) = xy(b, a)
        return sqrt(x * x + y * y)
    }

    private fun xy(p: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(origin.latitude))
        return (p.longitude - origin.longitude) * mPerDegLon to (p.latitude - origin.latitude) * mPerDegLat
    }

    private fun offsetMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val (bx, by) = xy(b, a)
        val (px, py) = xy(p, a)
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return sqrt(px * px + py * py)
        val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    private fun fmt(p: GeoPoint) = String.format(Locale.US, "%.6f,%.6f", p.longitude, p.latitude)

    /**
     * Whether to fetch again. Anything but the car changing (phase, candidates, routes, view
     * size) refreshes at once; car movement alone refreshes only after [MIN_CAR_INTERVAL_MS] and
     * once it has moved [MIN_CAR_MOVE_M] — a street-zoom picture is ~1.5 km across.
     */
    class Throttle {
        private var lastLayout: Any? = null
        private var lastCar: GeoPoint? = null
        private var lastAtMs = Long.MIN_VALUE / 2

        fun shouldFetch(scene: Scene, widthPx: Int, heightPx: Int, nowMs: Long): Boolean {
            val layout = listOf(scene.destination, scene.candidates, scene.routes.map { it.size to it.firstOrNull() }, scene.followCar, widthPx, heightPx)
            val car = scene.car
            val fetch = when {
                layout != lastLayout -> true
                car == null || car == lastCar -> false
                lastCar == null -> true
                nowMs - lastAtMs < MIN_CAR_INTERVAL_MS -> false
                else -> meters(car, lastCar!!) >= (if (scene.followCar) MIN_CAR_MOVE_M else IDLE_CAR_MOVE_M)
            }
            if (fetch) {
                lastLayout = layout
                lastCar = car
                lastAtMs = nowMs
            }
            return fetch
        }

        fun reset() { lastLayout = null; lastCar = null }
    }

    const val MIN_CAR_INTERVAL_MS = 4_000L
    const val MIN_CAR_MOVE_M = 40.0
    const val IDLE_CAR_MOVE_M = 200.0
}
