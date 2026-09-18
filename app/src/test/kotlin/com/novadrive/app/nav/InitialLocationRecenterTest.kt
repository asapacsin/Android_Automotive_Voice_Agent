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
    private val zhuhai = 22.2769 to 113.5678

    private fun fix(
        ageMs: Long = 0L,
        accuracyMeters: Float = 8f,
        latitude: Double = zhuhai.first,
        longitude: Double = zhuhai.second,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        ageMs = ageMs,
        accuracyMeters = accuracyMeters,
    )

    @Test
    fun freshFixRecentresOnce() {
        val policy = InitialLocationRecenter()
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 2_000)))
        assertTrue(policy.settled)
    }

    @Test
    fun secondFreshFixDoesNotRecentreAgain() {
        val policy = InitialLocationRecenter()
        policy.decide(fix(ageMs = 2_000))
        // Requirement: the automatic recentre happens once per app start, so a moving driver
        // is not dragged back to the centre every second.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 0)))
    }

    @Test
    fun cachedFixCentresProvisionallyAndAFreshFixReplacesIt() {
        val policy = InitialLocationRecenter()
        // Two minutes old: worth showing while GNSS warms up.
        assertEquals(RecenterDecision.RECENTER_PROVISIONAL, policy.decide(fix(ageMs = 120_000)))
        assertFalse(policy.settled)
        // A second cached fix must not move the camera again — only a fresh one may.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 90_000)))
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 1_000)))
        assertTrue(policy.settled)
    }

    @Test
    fun staleFixFromAPreviousSessionIsNeverShownAsCurrent() {
        val policy = InitialLocationRecenter()
        // Yesterday's position is not where the driver is.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 26 * 60 * 60 * 1000L)))
        assertFalse(policy.settled)
        // ...and the map still recentres when a real fix finally arrives.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 500)))
    }

    /**
     * The case the whole feature exists for: the app was last used days ago, somewhere else.
     * That position must never be presented as where the driver is now, however the clock
     * reports it — which is why ages come from [LocationAge] rather than a wall-clock stamp.
     */
    @Test
    fun aPositionFromDaysAgoIsRefusedAndDoesNotBlockTheLiveFix() {
        val policy = InitialLocationRecenter()
        val threeDaysSeventeenHours = (3 * 24 + 17) * 60 * 60 * 1000L + 30 * 60 * 1000L
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = threeDaysSeventeenHours)))
        assertFalse(policy.settled)
        // Crucially, the policy stays open, so the live fix that follows still lands.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = 120)))
    }

    @Test
    fun manualPanEndsAutomaticRecentring() {
        val policy = InitialLocationRecenter()
        policy.onUserMovedCamera()
        assertTrue(policy.stopped)
        // The driver's chosen viewport is never yanked back.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 0)))
    }

    @Test
    fun panAfterAProvisionalCentreStopsTheFreshOneToo() {
        val policy = InitialLocationRecenter()
        policy.decide(fix(ageMs = 120_000))
        policy.onUserMovedCamera()
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(ageMs = 0)))
    }

    @Test
    fun nonsenseCoordinatesAreIgnored() {
        val policy = InitialLocationRecenter()
        listOf(
            LocationFix(Double.NaN, 113.5),
            LocationFix(22.2, Double.POSITIVE_INFINITY),
            LocationFix(91.0, 113.5),
            LocationFix(22.2, 181.0),
            // Null Island: an uninitialised fix, not a position off West Africa.
            LocationFix(0.0, 0.0),
        ).forEach { bad ->
            assertEquals(RecenterDecision.IGNORE, policy.decide(bad), "should ignore $bad")
        }
        assertFalse(policy.settled)
    }

    @Test
    fun wildlyInaccurateFixIsIgnored() {
        val policy = InitialLocationRecenter()
        // A 5 km cell-tower fix would centre the map on the wrong district.
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix(accuracyMeters = 5_000f)))
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(accuracyMeters = 20f)))
    }

    @Test
    fun unknownAccuracyIsAccepted() {
        val policy = InitialLocationRecenter()
        // 0 means "not reported", not "perfect"; rejecting it would drop usable SDK fixes.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(accuracyMeters = 0f)))
    }

    @Test
    fun aFixTimestampedSlightlyInTheFutureStillCounts() {
        val policy = InitialLocationRecenter()
        // GNSS time versus system clock: a few seconds of skew must not look stale.
        assertEquals(RecenterDecision.RECENTER_FRESH, policy.decide(fix(ageMs = -3_000)))
    }
}

/** Age arithmetic: the rule that keeps a previous session's position out of the camera. */
class LocationAgeTest {
    private val now = 900_000_000_000L // ~15 min of uptime, in nanoseconds

    @Test
    fun ageIsTheMonotonicDifferenceInMilliseconds() {
        assertEquals(5_000L, LocationAge.fromElapsedRealtime(now - 5_000_000_000L, now))
    }

    @Test
    fun aLiveFixIsZeroAge() {
        assertEquals(0L, LocationAge.fromElapsedRealtime(now, now))
    }

    @Test
    fun anUnstampedFixIsNotTreatedAsNew() {
        // 0 means the platform never stamped it; calling that "brand new" would be a guess.
        assertEquals(LocationAge.UNKNOWN_AGE_MS, LocationAge.fromElapsedRealtime(0L, now))
        assertEquals(LocationAge.UNKNOWN_AGE_MS, LocationAge.fromElapsedRealtime(-1L, now))
    }

    @Test
    fun aFixFromBeforeARebootIsNotCurrent() {
        // Elapsed realtime restarts at boot, so a stamp ahead of now cannot be from this boot.
        assertEquals(LocationAge.UNKNOWN_AGE_MS, LocationAge.fromElapsedRealtime(now + 1_000_000L, now))
    }

    @Test
    fun anUnknownAgeIsRefusedByThePolicy() {
        val policy = InitialLocationRecenter()
        val fix = LocationFix(22.2769, 113.5678, ageMs = LocationAge.UNKNOWN_AGE_MS)
        assertEquals(RecenterDecision.IGNORE, policy.decide(fix))
    }
}
