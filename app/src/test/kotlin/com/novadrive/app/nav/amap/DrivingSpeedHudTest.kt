package com.novadrive.app.nav.amap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AMapNaviView has no speedometer widget. Numbers come from Amap location + cameras.
 */
class DrivingSpeedHudTest {
    @Test
    fun hiddenWhenNotNavigating() {
        val snap = DrivingSpeedHud.snapshot(
            navigating = false,
            reportedSpeedKmh = 50,
            fallbackSpeedKmh = 50,
            limitKmh = 40,
        )
        assertFalse(snap.visible)
    }

    @Test
    fun fiftyAgainstFortyIsOverspeed() {
        val snap = DrivingSpeedHud.snapshot(
            navigating = true,
            reportedSpeedKmh = 50,
            fallbackSpeedKmh = 0,
            limitKmh = 40,
        )
        assertTrue(snap.visible)
        assertEquals(50, snap.speedKmh)
        assertEquals(40, snap.limitKmh)
        assertTrue(snap.overspeed)
    }

    @Test
    fun fiftyAgainstEightyIsLegal() {
        val snap = DrivingSpeedHud.snapshot(
            navigating = true,
            reportedSpeedKmh = 50,
            fallbackSpeedKmh = 0,
            limitKmh = 80,
        )
        assertFalse(snap.overspeed)
    }

    @Test
    fun emulatorFallbackWhenLocationSpeedIsZero() {
        val snap = DrivingSpeedHud.snapshot(
            navigating = true,
            reportedSpeedKmh = 0,
            fallbackSpeedKmh = 50,
            limitKmh = 30,
        )
        assertEquals(50, snap.speedKmh)
        assertTrue(snap.overspeed)
    }

    @Test
    fun nearestPositiveCameraIsThePostedLimit() {
        assertEquals(
            30,
            DrivingSpeedHud.postedLimitKmh(
                listOf(
                    DrivingSpeedHud.CameraLimit(limitKmh = 0, distM = 10),
                    DrivingSpeedHud.CameraLimit(limitKmh = 80, distM = 400),
                    DrivingSpeedHud.CameraLimit(limitKmh = 30, distM = 120),
                ),
            ),
        )
        assertEquals(0, DrivingSpeedHud.postedLimitKmh(emptyList()))
    }

    @Test
    fun cameraLimitBeatsFacilityWhenBothPresent() {
        assertEquals(40, DrivingSpeedHud.mergeLimit(cameraLimitKmh = 40, facilityLimitKmh = 80))
        assertEquals(80, DrivingSpeedHud.mergeLimit(cameraLimitKmh = 0, facilityLimitKmh = 80))
        assertEquals(0, DrivingSpeedHud.mergeLimit(cameraLimitKmh = 0, facilityLimitKmh = 0))
    }
}
