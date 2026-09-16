package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AmapPoiParserTest {
    @Test
    fun validResponseUsesLonLatOrder() {
        val result = AmapPoiParser.parse(
            """{"status":"1","pois":[{"name":"天安门广场","location":"116.397500,39.908740"}]}""",
        )
        assertEquals(PoiResult("天安门广场", 39.908740, 116.397500), result)
    }

    @Test
    fun statusZeroReturnsNull() {
        assertNull(AmapPoiParser.parse("""{"status":"0","pois":[{"name":"天安门广场","location":"116.397500,39.908740"}]}"""))
    }

    @Test
    fun emptyPoisReturnsNull() {
        assertNull(AmapPoiParser.parse("""{"status":"1","pois":[]}"""))
    }

    @Test
    fun invalidLocationReturnsNull() {
        assertNull(AmapPoiParser.parse("""{"status":"1","pois":[{"name":"天安门广场","location":"abc"}]}"""))
    }

    @Test
    fun latitudeOutOfRangeReturnsNull() {
        assertNull(AmapPoiParser.parse("""{"status":"1","pois":[{"name":"天安门广场","location":"116.397500,99.000000"}]}"""))
    }

    @Test
    fun parseCandidatesReadsWholeArrayAndSkipsMalformed() {
        val result = AmapPoiParser.parseCandidates(
            """{"status":"1","pois":[""" +
                """{"name":"珠海站","address":"香洲区","location":"113.543200,22.202400","id":"B001","adname":"香洲区","distance":"850"},""" +
                """{"name":"bad","location":"abc"},""" +
                """{"name":"横琴口岸","address":"横琴","location":"113.544000,22.140000","id":"B002","adname":"横琴区","distance":250}""" +
                """]}""",
        )
        assertEquals(2, result.size)
        assertEquals("B001", result[0].id)
        assertEquals("珠海站", result[0].name)
        assertEquals("香洲区", result[0].address)
        assertEquals("香洲区", result[0].district)
        assertEquals(22.202400, result[0].latitude)
        assertEquals(113.543200, result[0].longitude)
        assertEquals(850, result[0].distanceMeters)
        assertEquals("B001", result[0].poiId)
        assertEquals("B002", result[1].id)
        assertEquals(250, result[1].distanceMeters)
    }

    @Test
    fun parseCandidatesEmptyOrBadStatusIsEmptyList() {
        assertEquals(emptyList<com.novadrive.app.nav.DestinationCandidate>(), AmapPoiParser.parseCandidates("""{"status":"0","pois":[{"name":"x","location":"116.0,39.0"}]}"""))
        assertEquals(emptyList<com.novadrive.app.nav.DestinationCandidate>(), AmapPoiParser.parseCandidates("""{"status":"1","pois":[]}"""))
        assertEquals(emptyList<com.novadrive.app.nav.DestinationCandidate>(), AmapPoiParser.parseCandidates("not-json"))
    }
}
