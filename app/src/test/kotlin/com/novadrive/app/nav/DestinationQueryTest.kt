package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checklist row T04. Measured on device 2026-09-18: 「导航去附近的麦当劳」 searched for the literal
 * string 「附近的麦当劳」 and Amap returned zero results, so the driver was told navigation failed
 * for a brand with branches minutes away.
 */
class DestinationQueryTest {
    @Test
    fun aNearbyBrandBecomesABrandSearch() {
        assertEquals("麦当劳", DestinationQuery.searchKeyword("附近的麦当劳"))
        assertEquals("麦当劳", DestinationQuery.searchKeyword("最近的麦当劳"))
        assertEquals("麦当劳", DestinationQuery.searchKeyword("离我最近的麦当劳"))
        assertEquals("星巴克", DestinationQuery.searchKeyword("我附近的星巴克"))
        assertEquals("加油站", DestinationQuery.searchKeyword("周边加油站"))
    }

    @Test
    fun politenessAndVerbsAreNotPartOfTheName() {
        assertEquals("麦当劳", DestinationQuery.searchKeyword("帮我找附近的麦当劳"))
        assertEquals("珠海站", DestinationQuery.searchKeyword("去珠海站"))
        assertEquals("拱北口岸", DestinationQuery.searchKeyword("带我到拱北口岸吧"))
    }

    @Test
    fun anOrdinaryPlaceNameIsLeftAlone() {
        assertEquals("珠海站", DestinationQuery.searchKeyword("珠海站"))
        assertEquals("横琴口岸", DestinationQuery.searchKeyword("横琴口岸"))
        // Not a nearby request, so nothing is stripped from the middle of a name.
        assertEquals("中山大学", DestinationQuery.searchKeyword("中山大学"))
    }

    @Test
    fun aQualifierWithNoPlaceLeftKeepsTheOriginal() {
        // 「导航去附近」 names nothing; an empty keyword would search for everything.
        assertEquals("附近", DestinationQuery.searchKeyword("附近"))
        assertEquals("最近", DestinationQuery.searchKeyword("最近"))
    }

    @Test
    fun nearbyRequestsAreRecognised() {
        assertTrue(DestinationQuery.isNearbyRequest("附近的麦当劳"))
        assertTrue(DestinationQuery.isNearbyRequest("离我最近的加油站"))
        assertFalse(DestinationQuery.isNearbyRequest("珠海站"))
    }
}
