package com.novadrive.app.nav

import com.novadrive.app.nav.StaticMapModel.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos

/** P68: the along_route corridor is pure geometry; fixed fake coordinates only. */
class AlongRouteCorridorTest {
    private val lat0 = 22.0
    private val lon0 = 113.0
    private val mPerDegLat = PI / 180 * 6_371_000.0
    private val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180)

    /** A point [east]/[north] metres from the fake origin. */
    private fun at(east: Double, north: Double) = GeoPoint(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    private fun straight(lengthM: Int, stepM: Int = 100) = (0..lengthM step stepM).map { at(it.toDouble(), 0.0) }

    /** East 3 km, a 400 m-wide U-turn, back west 3 km. */
    private fun uTurn(): List<GeoPoint> =
        (0..3_000 step 100).map { at(it.toDouble(), 0.0) } + (3_000 downTo 0 step 100).map { at(it.toDouble(), 400.0) }

    private fun poi(id: String, east: Double, north: Double): DestinationCandidate {
        val p = at(east, north)
        return DestinationCandidate(id, "fake-$id", "", "", p.latitude, p.longitude, poiId = id)
    }

    private fun ring(corridor: AlongRouteCorridor): List<Pair<Double, Double>> =
        corridor.polygon.split('|').map { pair -> pair.split(',').let { it[0].toDouble() to it[1].toDouble() } }

    /** Ray casting on (lon, lat). */
    private fun inside(ring: List<Pair<Double, Double>>, p: GeoPoint): Boolean {
        var hit = false
        for (i in 0 until ring.size - 1) {
            val (x1, y1) = ring[i]
            val (x2, y2) = ring[i + 1]
            if ((y1 > p.latitude) != (y2 > p.latitude) &&
                p.longitude < (x2 - x1) * (p.latitude - y1) / (y2 - y1) + x1
            ) hit = !hit
        }
        return hit
    }

    @Test
    fun polygonIsAClosedConvexRingOfAtMost64VerticesWithSixDecimals() {
        val corridor = requireNotNull(AlongRouteCorridor.plan(uTurn(), 0, 10_000))
        val pairs = corridor.polygon.split('|')
        assertTrue(pairs.size in 4..AlongRouteCorridor.MAX_VERTICES, "vertices=${pairs.size}")
        assertEquals(pairs.size, corridor.vertices)
        assertEquals(pairs.first(), pairs.last(), "closed ring")
        pairs.flatMap { it.split(',') }.forEach { number ->
            assertTrue((number.substringAfter('.', "").length) <= 6, number)
        }
        val r = ring(corridor)
        // Convex: every turn has the same sign.
        val signs = (0 until r.size - 1).map { i ->
            val a = r[i]
            val b = r[i + 1]
            val c = r[(i + 2) % (r.size - 1)]
            Math.signum((b.first - a.first) * (c.second - b.second) - (b.second - a.second) * (c.first - b.first))
        }.filter { it != 0.0 }
        assertTrue(signs.all { it == signs.first() }, "convex")
    }

    @Test
    fun manyBendsStillFitIn64Vertices() {
        val zigzag = (0..200).map { at(it * 100.0, if (it % 2 == 0) 0.0 else 300.0 + (it % 7) * 40) }
        val corridor = AlongRouteCorridor.plan(zigzag, 0, 50_000)!!
        assertTrue(corridor.vertices <= AlongRouteCorridor.MAX_VERTICES, "vertices=${corridor.vertices}")
    }

    @Test
    fun everyPointWithinWOfTheRouteIsCoveredIncludingAUTurn() {
        val route = uTurn()
        val r = ring(AlongRouteCorridor.plan(route, 0, 10_000)!!)
        route.forEach { assertTrue(inside(r, it)) }
        val w = AlongRouteCorridor.HALF_WIDTH_M - 1.0
        (0..3_000 step 250).forEach { x ->
            assertTrue(inside(r, at(x.toDouble(), -w)), "south of the outbound leg at $x")
            assertTrue(inside(r, at(x.toDouble(), 400.0 + w)), "north of the return leg at $x")
        }
        assertTrue(inside(r, at(3_000.0 + w, 200.0)), "beyond the turn")
        assertFalse(inside(r, at(1_500.0, 400.0 + 2_000)), "far away is outside")
    }

    @Test
    fun poisBehindTheCarOrOffTheCorridorAreDroppedAndTheRestOrderedAlongTheRoute() {
        val corridor = AlongRouteCorridor.plan(straight(20_000), 2_000, 10_000)!!
        val kept = corridor.keep(
            listOf(
                poi("far", 9_000.0, 50.0),
                poi("behind", 1_500.0, 0.0),
                poi("off", 5_000.0, 900.0),
                poi("near", 2_600.0, -300.0),
                poi("beyond", 12_500.0, 0.0),
                poi("mid", 6_000.0, 450.0),
            ),
        )
        assertEquals(listOf("near", "mid", "far"), kept.map { it.id })
        listOf(600, 4_000, 7_000).zip(kept).forEach { (expected, poi) ->
            assertTrue(kotlin.math.abs(poi.distanceMeters!! - expected) <= 20, "${poi.id} ${poi.distanceMeters}")
        }
    }

    @Test
    fun aPoiPassedTwiceTakesTheEarliestDistanceAndDuplicatesCollapse() {
        val corridor = AlongRouteCorridor.plan(uTurn(), 0, 10_000)!!
        val kept = corridor.keep(listOf(poi("a", 1_000.0, 200.0), poi("a", 1_000.0, 200.0), poi("b", 2_000.0, 0.0)))
        assertEquals(listOf("a", "b"), kept.map { it.id })
        assertTrue(kept[0].distanceMeters!! in 990..1_010, "first pass, not the return leg: ${kept[0].distanceMeters}")
    }

    @Test
    fun lookaheadStopsShortOfOrReachesTheDestination() {
        val short = AlongRouteCorridor.plan(straight(30_000), 1_000, 10_000)!!
        assertFalse(short.reachesDestination)
        assertEquals(10_000, short.lookaheadMeters)
        val whole = AlongRouteCorridor.plan(straight(8_000), 1_000, 10_000)!!
        assertTrue(whole.reachesDestination)
        assertTrue(whole.lookaheadMeters in 6_900..7_100, "${whole.lookaheadMeters}")
        assertTrue(whole.keep(listOf(poi("x", 7_500.0, 0.0))).isNotEmpty())
    }

    @Test
    fun lookaheadPerCategory() {
        assertEquals(10_000, AlongRouteCorridor.lookaheadFor(AlongRouteCategory.FUEL))
        assertEquals(10_000, AlongRouteCorridor.lookaheadFor(AlongRouteCategory.CHARGING))
        assertEquals(10_000, AlongRouteCorridor.lookaheadFor(AlongRouteCategory.TOILET))
        assertEquals(50_000, AlongRouteCorridor.lookaheadFor(AlongRouteCategory.SERVICE_AREA))
    }

    @Test
    fun searchLineIsCountsOnlyAndFlagsTruncation() {
        assertEquals(
            "along_route_search code=10000 total=3 returned=3 kept=2 lookahead_m=10000 vertices=12 ms=140",
            AlongRouteCorridor.searchLine("10000", 3, 3, 2, 10_000, 12, 140),
        )
        assertEquals(
            "along_route_search code=10000 total=40 returned=25 kept=5 lookahead_m=50000 vertices=30 ms=900 truncated=true",
            AlongRouteCorridor.searchLine("10000", 40, 25, 5, 50_000, 30, 900),
        )
    }

    @Test
    fun noRouteLeftIsNoCorridor() {
        assertNull(AlongRouteCorridor.plan(listOf(at(0.0, 0.0)), 0, 10_000))
        assertNull(AlongRouteCorridor.plan(straight(1_000), 1_000, 10_000))
    }

    /** Reviewer case: a 22.5° road with a 45 m bump the simplification removes; a POI 499 m off the bump peak. */
    @Test
    fun aPoiJustInsideWOffASimplifiedAwayBumpIsKeptAndInsideThePolygon() {
        val angle = Math.toRadians(22.5)
        val ux = kotlin.math.sin(angle)
        val uy = kotlin.math.cos(angle)
        // Unit normal to the road.
        val nx = uy
        val ny = -ux
        val route = (0..100).map { i ->
            val s = i * 100.0
            val bump = if (i == 50) 45.0 else 0.0
            at(s * ux + bump * nx, s * uy + bump * ny)
        }
        val corridor = AlongRouteCorridor.plan(route, 0, 10_000)!!
        val peak = 5_000.0
        val poi = poi("p", peak * ux + (45.0 + 499.0) * nx, peak * uy + (45.0 + 499.0) * ny)
        assertEquals(listOf("p"), corridor.keep(listOf(poi)).map { it.id })
        assertTrue(inside(ring(corridor), GeoPoint(poi.latitude, poi.longitude)), "the polygon must cover what keep() keeps")
    }

    /** A 40 km-radius loop needs more than 64 vertices at 50 m, so the tolerance is raised; w still covered. */
    @Test
    fun aRaisedToleranceStillCoversW() {
        val radius = 40_000.0
        val route = (0..500).map { i ->
            val a = 2 * PI * i / 500
            at(radius * kotlin.math.sin(a), radius * cos(a))
        }
        val corridor = AlongRouteCorridor.plan(route, 0, 300_000)!!
        assertTrue(corridor.toleranceMeters > AlongRouteCorridor.SIMPLIFY_M, "tolerance=${corridor.toleranceMeters}")
        assertTrue(corridor.vertices <= AlongRouteCorridor.MAX_VERTICES)
        val r = ring(corridor)
        listOf(37, 101, 163, 222, 311, 433).forEach { i ->
            val a = 2 * PI * (i + 0.5) / 500
            // Midpoint of a chord, 499 m further out along its outward normal.
            val mid = radius * cos(PI / 500)
            val out = mid + 499.0
            val p = poi("x$i", out * kotlin.math.sin(a), out * cos(a))
            assertTrue(inside(r, GeoPoint(p.latitude, p.longitude)), "leg $i")
            assertEquals(1, corridor.keep(listOf(p)).size, "leg $i")
        }
    }
}
