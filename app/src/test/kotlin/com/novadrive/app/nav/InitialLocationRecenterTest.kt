package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The startup-recentre rules. Every case here is one of the product requirements behind the
 * "map opens at an old position after a restart" report.
 */
class InitialLocationRecenterTest {
    private val now = 1_700_000_000_000L
    private val zhuhai = 22.2769 to 113.5678

    private fun fix(
        ageMs: Long = 0L,
        accuracyMeters: Float = 8f,
        latitude: Double = zhuhai.first,
        longitude: Double = zhuhai.second,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        timeMs = now - ageMs,
        accuracyMeters = accuracyMeters,
    )

    @Test
    fun freshFixRecentresOnce() {
        val policy = InitialLocationRecenter()
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 2_000), now))
        assertTrue(policy.settled)
    }

    @Test
    fun secondFreshFixDoesNotRecentreAgain() {
        val policy = InitialLocationRecenter()
        policy.decide(fix(ageMs = 2_000), now)
        // Requirement 4: the automatic recentre happens once per app start, so a moving driver
        // is not dragged back to the centre every second.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 0), now + 1_000))
    }

    @Test
    fun cachedFixCentresProvisionallyAndAFreshFixReplacesIt() {
        val policy = InitialLocationRecenter()
        // Two minutes old: worth showing while GNSS warms up.
        assertEquals(RecenterDecision.RECENTER_PROVISIONAL, policy.decide(fix(ageMs = 120_000), now))
        assertFalse(policy.settled)
        // A second cached fix must not move the camera again — only a fresh one may.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 90_000), now))
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 1_000), now))
        assertTrue(policy.settled)
    }

    @Test
    fun staleFixFromAPreviousSessionIsNeverShownAsCurrent() {
        val policy = InitialLocationRecenter()
        // Requirement 7: yesterday's position is not where the driver is.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 26 * 60 * 60 * 1000L), now))
        assertFalse(policy.settled)
        // ...and the map still recentres when a real fix finally arrives.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 500), now))
    }

    @Test
    fun manualPanEndsAutomaticRecentring() {
        val policy = InitialLocationRecenter()
        policy.onUserMovedCamera()
        assertTrue(policy.stopped)
        // Requirement 5: the driver's chosen viewport is never yanked back.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 0), now))
    }

    @Test
    fun panAfterAProvisionalCentreStopsTheFreshOneToo() {
        val policy = InitialLocationRecenter()
        policy.decide(fix(ageMs = 120_000), now)
        policy.onUserMovedCamera()
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 0), now))
    }

    @Test
    fun aFixWithNoTimestampIsTreatedAsLive() {
        val policy = InitialLocationRecenter()
        // Only the SDK callback omits a time, and it fires as the position arrives.
        val untimed = LocationFix(latitude = zhuhai.first, longitude = zhuhai.second, timeMs = 0L)
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(untimed, now))
    }

    @Test
    fun nonsenseCoordinatesAreIgnored() {
        val policy = InitialLocationRecenter()
        listOf(
            LocationFix(Double.NaN, 113.5, now),
            LocationFix(22.2, Double.POSITIVE_INFINITY, now),
            LocationFix(91.0, 113.5, now),
            LocationFix(22.2, 181.0, now),
            // Null Island: an uninitialised fix, not a position off West Africa.
            LocationFix(0.0, 0.0, now),
        ).forEach { bad ->
            assertEquals(RecenterDecision.IGNORE, policy.decide(bad, now), "should ignore $bad")
        }
        assertFalse(policy.settled)
    }

    @Test
    fun wildlyInaccurateFixIsIgnored() {
        val policy = InitialLocationRecenter()
        // A 5 km cell-tower fix would centre the map on the wrong district.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(accuracyMeters = 5_000f), now))
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(accuracyMeters = 20f), now))
    }

    @Test
    fun unknownAccuracyIsAccepted() {
        val policy = InitialLocationRecenter()
        // 0 means "not reported", not "perfect"; rejecting it would drop usable SDK fixes.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(accuracyMeters = 0f), now))
    }

    @Test
    fun aFixTimestampedSlightlyInTheFutureStillCounts() {
        val policy = InitialLocationRecenter()
        // GNSS time versus system clock: a few seconds of skew must not look stale.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = -3_000), now))
    }
}
