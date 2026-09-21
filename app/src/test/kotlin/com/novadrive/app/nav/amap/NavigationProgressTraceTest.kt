package com.novadrive.app.nav.amap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationProgressTraceTest {
    @Test
    fun regressionMatchesP31Shape() {
        assertTrue(NavigationProgressTrace.isRemainRegression(15, 3700))
        assertFalse(NavigationProgressTrace.isRemainRegression(500, 600))
        assertFalse(NavigationProgressTrace.isRemainRegression(15, 40))
    }

    @Test
    fun remainLoggingSkipsTinyJitter() {
        assertTrue(NavigationProgressTrace.shouldLogRemainUpdate(1000, 900))
        assertFalse(NavigationProgressTrace.shouldLogRemainUpdate(1000, 980))
        assertTrue(NavigationProgressTrace.shouldLogRemainUpdate(null, 1000))
    }

    @Test
    fun proximityCompletesOnlyInEmulatorAfterConsecutiveSamples() {
        val emulator = NavigationProgressTrace.NAVI_TYPE_EMULATOR
        var streak = 0
        streak = NavigationProgressTrace.proximityStreak(streak, emulator, 40)
        assertEquals(0, streak)
        streak = NavigationProgressTrace.proximityStreak(streak, emulator, 20)
        assertEquals(1, streak)
        streak = NavigationProgressTrace.proximityStreak(streak, emulator, 18)
        assertEquals(2, streak)
        assertTrue(NavigationProgressTrace.shouldCompleteByProximity(emulator, streak))
        assertFalse(NavigationProgressTrace.shouldCompleteByProximity(1, streak))
    }
}
