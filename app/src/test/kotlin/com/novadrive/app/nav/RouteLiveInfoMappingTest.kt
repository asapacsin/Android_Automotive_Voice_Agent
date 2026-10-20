package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RouteLiveInfoMappingTest {
    @Test
    fun alongRouteSearchLineCountsRawMappedAndDrops() {
        val raw = listOf(
            RoutePoi("B0SECRETID", "中石化加油站", 22.123456, 113.654321, 120),
            RoutePoi(null, "无编号站", 22.2, 113.7, 300),
            RoutePoi("B0NOPOINT", "无坐标站", null, 113.7, 50),
        )
        val mapped = RouteLiveInfoMapping.toCandidates(raw)
        val line = RouteLiveInfoMapping.alongRouteSearchLine(1000, raw, mapped.size, 250, 18342)
        assertEquals(
            "along_route_search code=1000 raw=3 mapped=2 dropped_no_point=1 dropped_other=0 range_m=250 route_m=18342",
            line,
        )
        listOf("B0SECRETID", "B0NOPOINT", "中石化", "无", "22.1", "113.6", "22.2", "113.7").forEach {
            assertFalse(line.contains(it), "leaked $it")
        }
    }

    @Test
    fun alongRouteSearchLineEmptyAndMissingLists() {
        assertEquals(
            "along_route_search code=1000 raw=0 mapped=0 dropped_no_point=0 dropped_other=0 range_m=250 route_m=-1",
            RouteLiveInfoMapping.alongRouteSearchLine(1000, emptyList(), 0, 250, -1),
        )
        assertEquals(
            "along_route_search code=1802 raw=-1 mapped=0 dropped_no_point=0 dropped_other=0 range_m=250 route_m=900",
            RouteLiveInfoMapping.alongRouteSearchLine(1802, null, 0, 250, 900),
        )
        assertEquals(
            "along_route_search code=1802 raw=2 mapped=0 dropped_no_point=0 dropped_other=0 range_m=250 route_m=900",
            RouteLiveInfoMapping.alongRouteSearchLine(1802, listOf(RoutePoi("a", "t", 1.0, 2.0, 5), RoutePoi("b", "u", 1.0, 2.0, 6)), 0, 250, 900),
        )
    }
}
