package com.novadrive.app.nav

import com.novadrive.app.nav.StaticMapModel.GeoPoint
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * P68 (state/decisions/P68-along-route-source.md): the search area for one along_route question.
 * The next [lookaheadMeters] of the remaining route, from the car, buffered and wrapped in a convex
 * polygon for one Web `v3/place/polygon` call; [keep] then cuts the answer back to the true corridor
 * (within [HALF_WIDTH_M] of the unsimplified route, ahead of the car, inside the lookahead).
 *
 * Pure and SDK-free. Coordinates are GCJ-02 as the route gives them; nothing here is ever logged
 * except through [searchLine], which carries counts only (I-8).
 */
class AlongRouteCorridor private constructor(
    private val projection: Projection,
    /** The unsimplified route in local metres, with the along-route distance of each point. */
    private val xy: List<Pair<Double, Double>>,
    private val cumulative: DoubleArray,
    private val carMeters: Double,
    /** `lon,lat|...`, closed, at most [MAX_VERTICES] points, six decimals. Never logged. */
    val polygon: String,
    val vertices: Int,
    /** How far ahead of the car was searched: min(remaining, L). */
    val lookaheadMeters: Int,
    /** True when the lookahead covered the whole remaining route. */
    val reachesDestination: Boolean,
) {
    /**
     * The POIs inside the true corridor, with distanceMeters set to the along-route distance from the
     * car (the earliest pass when the route passes twice), nearest first, de-duplicated by POI id.
     */
    fun keep(pois: List<DestinationCandidate>): List<DestinationCandidate> {
        val end = carMeters + lookaheadMeters
        val seen = mutableSetOf<String>()
        return pois.mapNotNull { poi ->
            val (px, py) = projection.toXy(GeoPoint(poi.latitude, poi.longitude))
            // A pass is a run of consecutive segments within w; its point is the closest one in the
            // run (not a clamped segment end, which would pull the distance back). Earliest pass ahead wins.
            var best: Double? = null
            var runDistance = Double.MAX_VALUE
            var runS = 0.0
            fun closeRun() {
                if (runDistance <= HALF_WIDTH_M && runS >= carMeters && runS <= end && best == null) best = runS
                runDistance = Double.MAX_VALUE
            }
            for (i in 0 until xy.size - 1) {
                val (ax, ay) = xy[i]
                val (bx, by) = xy[i + 1]
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val distance = hypot(ax + t * dx - px, ay + t * dy - py)
                if (distance > HALF_WIDTH_M) {
                    closeRun()
                    continue
                }
                if (distance < runDistance) {
                    runDistance = distance
                    runS = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
                }
            }
            closeRun()
            best?.let { poi.copy(distanceMeters = (it - carMeters).toInt()) }
        }.sortedBy { it.distanceMeters }
            .filter { seen.add(it.poiId ?: it.id) }
    }

    /** Equirectangular metres around a reference point; ample for a few tens of kilometres. */
    private class Projection(origin: GeoPoint) {
        private val lat0 = origin.latitude
        private val lon0 = origin.longitude
        private val mPerDegLat = PI / 180 * EARTH_RADIUS_M
        private val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180)

        fun toXy(p: GeoPoint) = (p.longitude - lon0) * mPerDegLon to (p.latitude - lat0) * mPerDegLat
        fun toGeo(x: Double, y: Double) = GeoPoint(lat0 + y / mPerDegLat, lon0 + x / mPerDegLon)
    }

    companion object {
        /** w: how far off the route a POI may be. */
        const val HALF_WIDTH_M = 500.0
        /** The buffer circle radius: w + 50 m (the simplification tolerance). */
        const val BUFFER_M = HALF_WIDTH_M + 50.0
        const val SIMPLIFY_M = 50.0
        const val MAX_VERTICES = 64
        private const val CIRCLE_POINTS = 8
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun lookaheadFor(category: AlongRouteCategory): Int = when (category) {
            AlongRouteCategory.SERVICE_AREA -> 50_000
            AlongRouteCategory.FUEL, AlongRouteCategory.CHARGING, AlongRouteCategory.TOILET -> 10_000
        }

        /** Counts only: never the polygon, a URL, a name, an id or a coordinate (I-8). */
        fun searchLine(code: String, total: Int, returned: Int, kept: Int, lookaheadM: Int, vertices: Int, ms: Long): String =
            "along_route_search code=$code total=$total returned=$returned kept=$kept lookahead_m=$lookaheadM " +
                "vertices=$vertices ms=$ms" + if (total > returned) " truncated=true" else ""

        /**
         * The corridor over [route] from [carMeters] (allLength - pathRetainDistance; 0 before the drive)
         * for at most [lookaheadMeters]; null when no route is left ahead of the car.
         */
        fun plan(route: List<GeoPoint>, carMeters: Int, lookaheadMeters: Int): AlongRouteCorridor? {
            if (route.size < 2) return null
            val projection = Projection(route.first())
            val xy = route.map(projection::toXy)
            val cumulative = DoubleArray(xy.size)
            for (i in 1 until xy.size) {
                cumulative[i] = cumulative[i - 1] + hypot(xy[i].first - xy[i - 1].first, xy[i].second - xy[i - 1].second)
            }
            val total = cumulative.last()
            val car = carMeters.toDouble().coerceAtLeast(0.0)
            if (car >= total) return null
            val end = minOf(total, car + lookaheadMeters)
            val segment = cut(xy, cumulative, car, end)
            if (segment.size < 2) return null

            var tolerance = SIMPLIFY_M
            var hull: List<Pair<Double, Double>>
            while (true) {
                val simplified = douglasPeucker(segment, tolerance)
                val buffered = simplified.flatMap { (x, y) ->
                    (0 until CIRCLE_POINTS).map { k ->
                        val a = 2 * PI * k / CIRCLE_POINTS
                        x + BUFFER_M * cos(a) to y + BUFFER_M * sin(a)
                    }
                }
                hull = convexHull(buffered)
                if (hull.size + 1 <= MAX_VERTICES) break
                tolerance *= 2
            }
            val ring = (hull + hull.first()).map { (x, y) ->
                val p = projection.toGeo(x, y)
                String.format(Locale.US, "%.6f,%.6f", p.longitude, p.latitude)
            }
            return AlongRouteCorridor(
                projection = projection,
                xy = xy,
                cumulative = cumulative,
                carMeters = car,
                polygon = ring.joinToString("|"),
                vertices = ring.size,
                lookaheadMeters = (end - car).toInt(),
                reachesDestination = car + lookaheadMeters >= total,
            )
        }

        /** The route between along-route distances [from] and [to], interpolating both ends. */
        private fun cut(xy: List<Pair<Double, Double>>, cumulative: DoubleArray, from: Double, to: Double): List<Pair<Double, Double>> {
            fun at(s: Double): Pair<Double, Double> {
                val i = (1 until xy.size).firstOrNull { cumulative[it] >= s } ?: return xy.last()
                val span = cumulative[i] - cumulative[i - 1]
                val t = if (span == 0.0) 0.0 else (s - cumulative[i - 1]) / span
                return xy[i - 1].first + t * (xy[i].first - xy[i - 1].first) to xy[i - 1].second + t * (xy[i].second - xy[i - 1].second)
            }
            val inside = xy.indices.filter { cumulative[it] > from && cumulative[it] < to }.map { xy[it] }
            return listOf(at(from)) + inside + at(to)
        }

        private fun douglasPeucker(points: List<Pair<Double, Double>>, tolerance: Double): List<Pair<Double, Double>> {
            if (points.size < 3) return points
            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.size - 1) }
            while (stack.isNotEmpty()) {
                val (first, last) = stack.removeLast()
                var index = -1
                var farthest = 0.0
                for (i in first + 1 until last) {
                    val d = segmentDistance(points[i], points[first], points[last])
                    if (d > farthest) {
                        farthest = d
                        index = i
                    }
                }
                if (index >= 0 && farthest > tolerance) {
                    keep[index] = true
                    stack.add(first to index)
                    stack.add(index to last)
                }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }

        private fun segmentDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(a.first + t * dx - p.first, a.second + t * dy - p.second)
        }

        /** Andrew's monotone chain; counter-clockwise, no repeated first point. */
        private fun convexHull(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val sorted = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            if (sorted.size < 3) return sorted
            fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
                (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
            val lower = mutableListOf<Pair<Double, Double>>()
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1)
                lower.add(p)
            }
            val upper = mutableListOf<Pair<Double, Double>>()
            for (p in sorted.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1)
                upper.add(p)
            }
            return lower.dropLast(1) + upper.dropLast(1)
        }
    }
}
