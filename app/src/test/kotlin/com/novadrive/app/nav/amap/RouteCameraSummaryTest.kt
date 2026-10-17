package com.novadrive.app.nav.amap

import com.novadrive.app.nav.amap.RouteCameraSummary.RouteInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RouteCameraSummaryTest {
    @Test
    fun `formats routes in id order with speed cameras only`() {
        val out = RouteCameraSummary.format(
            listOf(
                RouteInfo(2, 7100, 600, emptyList()),
                RouteInfo(1, 6800, 540, listOf(60 to 2100, 0 to 3000, 40 to 5200)),
            ),
        )
        assertEquals("routes=2 r1:len=6800,time=540,cams=60@2100;40@5200 r2:len=7100,time=600,cams=", out)
    }

    @Test
    fun `no routes`() {
        assertEquals("routes=0", RouteCameraSummary.format(emptyList()))
    }
}
