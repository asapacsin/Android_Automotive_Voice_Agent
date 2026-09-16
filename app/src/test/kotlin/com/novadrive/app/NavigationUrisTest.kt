package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationUrisTest {
    @Test
    fun amapKeywordNaviEncodesTiananmenSquare() {
        assertEquals(
            "androidamap://keywordNavi?sourceApplication=NovaDrive&keyword=%E5%A4%A9%E5%AE%89%E9%97%A8%E5%B9%BF%E5%9C%BA&style=2",
            NavigationUris.amapKeywordNavi("天安门广场"),
        )
    }

    @Test
    fun amapKeywordNaviEncodesSpaceAndAmpersandSoTheyCannotInjectQueryParams() {
        val uri = NavigationUris.amapKeywordNavi("Park & Ride")
        assertEquals(
            "androidamap://keywordNavi?sourceApplication=NovaDrive&keyword=Park%20%26%20Ride&style=2",
            uri,
        )
        assertTrue(uri.contains("%20"))
        assertTrue(uri.contains("%26"))
        assertFalse(uri.contains("keyword=Park & Ride"))
        assertFalse(uri.contains("&Ride"))
    }

    @Test
    fun amapNaviEncodesNameAndFormatsCoordinates() {
        assertEquals(
            "androidamap://navi?sourceApplication=NovaDrive&poiname=%E5%A4%A9%E5%AE%89%E9%97%A8%E5%B9%BF%E5%9C%BA&lat=39.908740&lon=116.397500&dev=0&style=2",
            NavigationUris.amapNavi("天安门广场", 39.90874, 116.3975),
        )
    }

    @Test
    fun geoSearchUsesTheSameEncodedKeyword() {
        val encoded = "%E5%A4%A9%E5%AE%89%E9%97%A8%E5%B9%BF%E5%9C%BA"
        val uri = NavigationUris.geoSearch("天安门广场")
        assertTrue(uri.startsWith("geo:0,0?q="))
        assertTrue(uri.contains(encoded))
    }
}
