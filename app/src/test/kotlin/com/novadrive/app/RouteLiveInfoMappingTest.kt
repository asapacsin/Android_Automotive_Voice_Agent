package com.novadrive.app

import com.novadrive.app.nav.RouteLiveInfoMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
