package com.novadrive.app.nav.amap

import com.novadrive.app.nav.amap.NavigationTraceListener.CameraSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** `nav_camera_ahead` was logged about once a second on the 2026-09-28 demo; it now changes only with the bucket. */
class CameraLogLineTest {
    @Test
    fun approachingACameraKeepsTheSameLineWithinABucket() {
        val lines = (480 downTo 210 step 15).map { d ->
            NavigationTraceListener.cameraLogLine(listOf(CameraSample(type = 0, limitKmh = 60, distM = d)))
        }.distinct()
        assertEquals(1, lines.size, "one line for 480 m .. 210 m")
    }

    @Test
    fun bucketsAndNoRawDistance() {
        val line = NavigationTraceListener.cameraLogLine(
            listOf(CameraSample(0, 60, 123), CameraSample(5, 0, 1_234)),
        )
        assertEquals("nav_camera_ahead count=2 type=0 limitKmh=60 dist=<200,type=5 limitKmh=0 dist=>=1000", line)
        assertFalse(line.contains("123"))
    }

    @Test
    fun broadcastModeNamesAreStable() {
        assertEquals("concise", AmapGuidanceVoice.broadcastModeName(com.amap.api.navi.enums.BroadcastMode.CONCISE))
        assertEquals("unknown", AmapGuidanceVoice.broadcastModeName(-1))
    }
}
