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
}
