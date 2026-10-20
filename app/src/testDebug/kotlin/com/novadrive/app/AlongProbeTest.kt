package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlongProbeTest {
    @Test
    fun lineHoldsRangeCodeRawAndMsOnly() {
        assertEquals("along_probe range_m=250 code=1000 raw=0 ms=412", AlongProbe.line(250, 1000, 0, 412))
    }

    @Test
    fun missingResultIsMinusOne() {
        assertEquals("along_probe range_m=2000 code=-1 raw=-1 ms=0", AlongProbe.line(2000, -1, null, 0))
    }

    @Test
    fun rangesAreTheFourProbeRanges() {
        assertEquals(listOf(250, 500, 1000, 2000), AlongProbe.RANGES)
    }

    @Test
    fun polylineLineHoldsCodeRawAndPointCountOnly() {
        assertEquals("along_probe form=polyline range_m=1000 code=1000 raw=3 points=100", AlongProbe.polylineLine(1000, 1000, 3, 100))
    }

    @Test
    fun modeLineHoldsModeRangeCodeRaw() {
        assertEquals("along_probe form=startend mode=6 range_m=1000 code=1000 raw=-1", AlongProbe.modeLine(6, 1000, 1000, null))
    }

    @Test
    fun webAroundLineHoldsRadiusAndCount() {
        assertEquals("along_probe form=web_around radius_m=3000 count=-1", AlongProbe.webAroundLine(3000, null))
        assertEquals("along_probe form=web_around radius_m=3000 count=5", AlongProbe.webAroundLine(3000, 5))
    }

    @Test
    fun downsampleKeepsAtMostMaxEvenlyWithEnds() {
        val pts = (0 until 1000).toList()
        val d = AlongProbe.downsample(pts, 100)
        assertEquals(100, d.size)
        assertEquals(0, d.first())
        assertEquals(999, d.last())
        assertEquals(listOf(1, 2, 3), AlongProbe.downsample(listOf(1, 2, 3), 100))
    }
}
