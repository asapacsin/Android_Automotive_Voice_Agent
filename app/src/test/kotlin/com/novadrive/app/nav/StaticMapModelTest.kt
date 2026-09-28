package com.novadrive.app.nav

import com.novadrive.app.nav.StaticMapModel.GeoPoint
import com.novadrive.app.nav.StaticMapModel.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaticMapModelTest {
    private val base = GeoPoint(22.2, 113.5)
    private fun line(n: Int) = (0 until n).map { GeoPoint(base.latitude + it * 0.0005 + (it % 3) * 0.00007, base.longitude + it * 0.0005) }

    @Test fun simplifyCapsPointsAndKeepsEnds() {
        val route = line(2000)
        val out = StaticMapModel.simplify(route, StaticMapModel.MAX_POINTS_PER_ROUTE)
        assertTrue(out.size <= StaticMapModel.MAX_POINTS_PER_ROUTE)
        assertEquals(route.first(), out.first())
        assertEquals(route.last(), out.last())
    }

    @Test fun urlStaysShortWithThreeLongRoutes() {
        val url = StaticMapModel.url(Scene(base, line(3000).last(), routes = listOf(line(3000), line(2500), line(2800))), "K", 768, 720)
        assertTrue(url.length < 8000)
        assertTrue(url.contains("paths=") && url.contains("markers=") && url.contains("size=384*360"))
        assertFalse(url.contains("location="))
    }

    @Test fun followCarCentresOnCarAndCutsRoute() {
        val route = line(3000)
        val car = route[1500]
        val url = StaticMapModel.url(Scene(car, route.last(), routes = listOf(route), followCar = true), "K", 800, 600)
        assertTrue(url.contains("location=114.250000,") && url.contains("zoom=${StaticMapModel.FOLLOW_ZOOM}"))
        val cut = StaticMapModel.aroundCar(route, car)
        assertTrue(cut.size < route.size && cut.contains(car))
    }

    @Test fun candidatesAreNumberedMarkers() {
        val url = StaticMapModel.url(Scene(null, null, candidates = line(3)), "K", 400, 400)
        assertTrue(url.contains(",1:") && url.contains(",3:"))
    }

    @Test fun throttleIgnoresSmallOrFastCarMoves() {
        val t = StaticMapModel.Throttle()
        val route = listOf(line(50))
        fun s(c: GeoPoint) = Scene(c, null, routes = route, followCar = true)
        assertTrue(t.shouldFetch(s(base), 800, 600, 0))
        val far = GeoPoint(base.latitude + 0.001, base.longitude)
        assertFalse(t.shouldFetch(s(far), 800, 600, 1_000))
        val tiny = GeoPoint(base.latitude + 0.0001, base.longitude)
        assertFalse(t.shouldFetch(s(tiny), 800, 600, 10_000))
        assertTrue(t.shouldFetch(s(far), 800, 600, 10_000))
        assertTrue(t.shouldFetch(Scene(far, null, followCar = true), 800, 600, 10_001))
    }
}
