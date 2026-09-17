package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationChoiceResolverTest {
    private fun dest(id: String, name: String, meters: Int?) =
        DestinationCandidate(id, name, "地址", "香洲区", 22.2, 113.5, distanceMeters = meters)

    private val destinations = listOf(
        dest("a", "珠海站", 12_900),
        dest("b", "珠海站(南广场)", 13_100),
        dest("c", "拱北口岸", 24_400),
        dest("d", "珠海北站", 8_000),
    )

    // Real labels and sizes from the device, 2026-09-16.
    private val routes = listOf(
        RouteCandidate(12, 46_000, 44 * 60, labels = "推荐", trafficLightCount = 20),
        RouteCandidate(13, 42_600, 47 * 60, labels = "常规", trafficLightCount = 12),
        RouteCandidate(14, 42_000, 59 * 60, labels = "免费", trafficLightCount = 30),
    )

    private fun <T> picked(match: ChoiceMatch<T>): T = (match as ChoiceMatch.Picked<T>).item
    private fun code(match: ChoiceMatch<*>) = (match as ChoiceMatch.Rejected).code

    @Test
    fun ordinalPicksInScreenOrder() {
        assertEquals("c", picked(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Index(3))).id)
        assertEquals(13, picked(NavigationChoiceResolver.pickRoute(routes, NavigationChoice.Index(2))).routeId)
    }

    @Test
    fun ordinalOutsideTheListIsRefused() {
        assertEquals("OUT_OF_RANGE", code(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Index(5))))
        assertEquals("OUT_OF_RANGE", code(NavigationChoiceResolver.pickRoute(routes, NavigationChoice.Index(0))))
    }

    @Test
    fun routePreferencesPickByTheRightMeasure() {
        fun pick(kind: NavigationChoice.Kind) = picked(NavigationChoiceResolver.pickRoute(routes, NavigationChoice.Preference(kind))).routeId
        assertEquals(12, pick(NavigationChoice.Kind.FASTEST))
        assertEquals(14, pick(NavigationChoice.Kind.SHORTEST))
        assertEquals(13, pick(NavigationChoice.Kind.FEWEST_LIGHTS))
        assertEquals(12, pick(NavigationChoice.Kind.RECOMMENDED))
        assertEquals(14, pick(NavigationChoice.Kind.NO_TOLL))
    }

    @Test
    fun recommendedFallsBackToTheFirstRouteAndNoTollNeedsALabel() {
        val unlabelled = routes.map { it.copy(labels = null) }
        assertEquals(12, picked(NavigationChoiceResolver.pickRoute(unlabelled, NavigationChoice.Preference(NavigationChoice.Kind.RECOMMENDED))).routeId)
        assertEquals("NO_MATCH", code(NavigationChoiceResolver.pickRoute(unlabelled, NavigationChoice.Preference(NavigationChoice.Kind.NO_TOLL))))
    }

    @Test
    fun nearestDestinationUsesDistance() {
        assertEquals("d", picked(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Preference(NavigationChoice.Kind.NEAREST))).id)
        val unknown = destinations.map { it.copy(distanceMeters = null) }
        assertEquals("DISTANCE_UNKNOWN", code(NavigationChoiceResolver.pickDestination(unknown, NavigationChoice.Preference(NavigationChoice.Kind.NEAREST))))
    }

    @Test
    fun routeOnlyPreferencesAreRefusedForDestinations() {
        assertEquals(
            "PREFERENCE_NOT_FOR_DESTINATIONS",
            code(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Preference(NavigationChoice.Kind.FASTEST))),
        )
    }

    @Test
    fun nameMatchesPartOfTheName() {
        assertEquals("c", picked(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Name("拱北"))).id)
        assertEquals("c", picked(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Name("就去拱北口岸那个"))).id)
    }

    @Test
    fun anExactNameWinsOverLongerOnesAndTrueAmbiguityIsRefused() {
        assertEquals("a", picked(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Name("珠海站"))).id)
        assertEquals("AMBIGUOUS", code(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Name("珠海"))))
        assertEquals("NO_MATCH", code(NavigationChoiceResolver.pickDestination(destinations, NavigationChoice.Name("机场"))))
    }

    @Test
    fun routeNamesMatchTheirLabels() {
        assertEquals(14, picked(NavigationChoiceResolver.pickRoute(routes, NavigationChoice.Name("免费"))).routeId)
    }

    @Test
    fun emptyListsAreRefused() {
        assertEquals("NO_OPTIONS", code(NavigationChoiceResolver.pickRoute(emptyList(), NavigationChoice.Index(1))))
    }

    @Test
    fun summariesAreNumberedInScreenOrder() {
        val d = NavigationChoiceResolver.describeDestinations(destinations)
        assertTrue(d.startsWith("1. 珠海站，12.9公里"), d)
        assertTrue(d.contains("3. 拱北口岸，24.4公里"), d)
        val r = NavigationChoiceResolver.describeRoutes(routes)
        assertEquals("1. 46.0公里，约44分钟，推荐；2. 42.6公里，约47分钟，常规；3. 42.0公里，约59分钟，免费", r)
    }
}
