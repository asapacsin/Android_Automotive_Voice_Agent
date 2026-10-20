package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PreflightTimingTest {
    @Test
    fun splitLineHoldsPathOnlyAndMsFields() {
        val line = PreflightTiming.splitLine(
            call = 1,
            url = "https://restapi.amap.com/v3/place/around?key=SECRETKEY&keywords=%E9%BA%A6&location=113.5,22.1",
            timing = PreflightTiming.CallTiming(dnsMs = 3, connectMs = 40, tlsMs = 120, ttfbMs = 300, bodyMs = 5, reused = false),
        )
        assertEquals(
            "preflight_split call=1 path=/v3/place/around dns_ms=3 connect_ms=40 tls_ms=120 ttfb_ms=300 body_ms=5 reused=false",
            line,
        )
        listOf("SECRETKEY", "key=", "113.5", "22.1", "restapi", "https", "?", "keywords").forEach {
            assertFalse(line.contains(it), "leaked $it")
        }
    }

    @Test
    fun reusedConnectionReportsZeroSetupAndTrue() {
        val line = PreflightTiming.splitLine(2, "https://restapi.amap.com/v3/place/text?key=k", PreflightTiming.CallTiming(ttfbMs = 80, bodyMs = 1, reused = true))
        assertEquals("preflight_split call=2 path=/v3/place/text dns_ms=0 connect_ms=0 tls_ms=0 ttfb_ms=80 body_ms=1 reused=true", line)
    }

    @Test
    fun unparseableUrlNeverEchoed() {
        val line = PreflightTiming.splitLine(1, "not a url?key=SECRET", PreflightTiming.CallTiming())
        assertFalse(line.contains("SECRET"))
        assertFalse(line.contains("not a url"))
    }

    @Test
    fun warmLineListsRuns() {
        assertEquals("preflight_search_warm runs=3 ms=900,450,430", PreflightTiming.warmLine(listOf(900L, 450L, 430L)))
    }
}
