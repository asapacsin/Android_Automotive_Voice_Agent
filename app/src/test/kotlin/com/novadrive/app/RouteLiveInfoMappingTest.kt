package com.novadrive.app

import com.novadrive.app.nav.RouteLiveInfoMapping
import com.novadrive.app.nav.RoutePoi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RouteLiveInfoMappingTest {
    @Test
    fun nonEmptyRemainingTrafficWinsOverPath() {
        assertEquals(listOf(1), RouteLiveInfoMapping.chooseTrafficStatuses(listOf(1), listOf(2, 3)))
    }

    @Test
    fun emptyOrNullRemainingTrafficFallsBackToPath() {
        assertEquals(listOf(2), RouteLiveInfoMapping.chooseTrafficStatuses(emptyList(), listOf(2)))
        assertEquals(listOf(2), RouteLiveInfoMapping.chooseTrafficStatuses(null, listOf(2)))
    }

    @Test
    fun bothEmptyYieldsPathValue() {
        assertEquals(emptyList<Int>(), RouteLiveInfoMapping.chooseTrafficStatuses(emptyList<Int>(), emptyList()))
        assertNull(RouteLiveInfoMapping.chooseTrafficStatuses<Int>(null, null))
    }

    @Test
    fun blankOrNullIdFallsBackToIndex() {
        val out = RouteLiveInfoMapping.toCandidates(
            listOf(
                RoutePoi(null, "A", 1.0, 2.0, 10),
                RoutePoi(" ", "B", 1.0, 2.0, 20),
                RoutePoi("x9", null, 1.0, 2.0, 30),
            ),
        )
        assertEquals(listOf("r0", "r1", "x9"), out.map { it.id })
        assertEquals(listOf(null, null, "x9"), out.map { it.poiId })
        assertEquals(listOf("A", "B", ""), out.map { it.name })
    }

    @Test
    fun poiWithoutPointIsDroppedAndIndexKept() {
        val out = RouteLiveInfoMapping.toCandidates(
            listOf(RoutePoi(null, "gone", null, null, 5), RoutePoi(null, "kept", 3.0, 4.0, 6)),
        )
        assertEquals(1, out.size)
        assertEquals("r1", out[0].id)
        assertEquals(3.0, out[0].latitude, 0.0)
        assertEquals(4.0, out[0].longitude, 0.0)
    }

    @Test
    fun candidatesSortedByDistance() {
        val out = RouteLiveInfoMapping.toCandidates(
            listOf(RoutePoi("a", "a", 1.0, 1.0, 300), RoutePoi("b", "b", 1.0, 1.0, 100), RoutePoi("c", "c", 1.0, 1.0, 200)),
        )
        assertEquals(listOf("b", "c", "a"), out.map { it.id })
        assertEquals(listOf(100, 200, 300), out.map { it.distanceMeters })
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertTrue(RouteLiveInfoMapping.toCandidates(emptyList()).isEmpty())
    }
}
