package com.novadrive.app.nav.amap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Indoor emulator speed is a test vehicle speed, not a posted limit.
 * Default 50 km/h sits above typical 30 cameras and below 80 arterials so HUD
 * overspeed is per-road, instead of firing everywhere at 120.
 */
class EmulatorNaviSpeedTest {
    @Test
    fun defaultIsUrbanNotMotorway() {
        assertEquals(50, EmulatorNaviSpeed.DEFAULT_KMH)
        assertTrue(EmulatorNaviSpeed.DEFAULT_KMH > 30)
        assertTrue(EmulatorNaviSpeed.DEFAULT_KMH < 80)
    }

    @Test
    fun blankOrEmulatorUsesDefaultUrbanSpeed() {
        assertEquals(EmulatorNaviSpeed.Request(emulator = true, speedKmh = 50), EmulatorNaviSpeed.parseNavStart(""))
        assertEquals(EmulatorNaviSpeed.Request(emulator = true, speedKmh = 50), EmulatorNaviSpeed.parseNavStart("emulator"))
    }

    @Test
    fun emulatorSpeedOverrideIsAccepted() {
        assertEquals(
            EmulatorNaviSpeed.Request(emulator = true, speedKmh = 80),
            EmulatorNaviSpeed.parseNavStart("emulator:80"),
        )
    }

    @Test
    fun gpsModeIgnoresSpeed() {
        val gps = EmulatorNaviSpeed.parseNavStart("gps")
        assertFalse(gps.emulator)
    }

    @Test
    fun speedIsClampedToAmapEmulatorRange() {
        assertEquals(40, EmulatorNaviSpeed.parseNavStart("emulator:30").speedKmh)
        assertEquals(120, EmulatorNaviSpeed.parseNavStart("emulator:200").speedKmh)
        assertEquals(50, EmulatorNaviSpeed.parseNavStart("emulator:nope").speedKmh)
    }

    @Test
    fun fiftyKmhOverspeedsThirtyButNotEighty() {
        assertTrue(EmulatorNaviSpeed.exceedsPostedLimit(speedKmh = 50, limitKmh = 30))
        assertFalse(EmulatorNaviSpeed.exceedsPostedLimit(speedKmh = 50, limitKmh = 80))
        assertFalse(EmulatorNaviSpeed.exceedsPostedLimit(speedKmh = 50, limitKmh = 0))
    }
}
