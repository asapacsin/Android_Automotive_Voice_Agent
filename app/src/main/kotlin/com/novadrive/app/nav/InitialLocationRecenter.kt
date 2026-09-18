package com.novadrive.app.nav

/**
 * One position fix, provider-neutral so the policy below can be unit-tested on the JVM.
 *
 * [ageMs] is how old the fix was **when it was read**, measured on a monotonic clock wherever the
 * source offers one. It is deliberately not a timestamp: see [LocationAge]. [accuracyMeters] is 0
 * when unknown. No coordinate is ever logged — this is location data.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val ageMs: Long = 0L,
    val accuracyMeters: Float = 0f,
)

/**
 * Age arithmetic, kept separate because getting it wrong decides whether a previous session's
 * position can be shown as the driver's current one.
 *
 * Ages come from `elapsedRealtimeNanos`, not `Location.getTime()`. Wall-clock timestamps are not
 * a sound basis for "how old is this fix": the clock can jump between the fix and the reading
 * (NTP correction, the driver changing the time, crossing a time zone), and a provider is free to
 * stamp a cached fix with the time it handed it over rather than the time it was taken. The
 * monotonic clock has none of those failure modes — nothing re-stamps it, and a fix cannot
 * survive the boot that would reset it.
 *
 * Not itself the cause of any observed misbehaviour: on the 2026-09-18 device run the platform
 * fix was genuinely recent and both clocks agreed. This is the difference between a rule that
 * happens to hold and one that has to.
 */
object LocationAge {
    /** Treated as "older than any threshold": used when the monotonic clock cannot be trusted. */
    const val UNKNOWN_AGE_MS = Long.MAX_VALUE / 2

    fun fromElapsedRealtime(fixElapsedNanos: Long, nowElapsedNanos: Long): Long {
        // 0 means the platform did not stamp it — never treat that as "brand new".
        if (fixElapsedNanos <= 0L) return UNKNOWN_AGE_MS
        // A fix stamped after "now" predates a reboot (or is nonsense); it is not current.
        if (fixElapsedNanos > nowElapsedNanos) return UNKNOWN_AGE_MS
        return (nowElapsedNanos - fixElapsedNanos) / 1_000_000L
    }
}

enum class RecenterDecision {
    /** A fix new enough to be the driver's real position. Recenter and stop recentering. */
    RECENTER_FRESH,

    /** A recent cached fix shown while GNSS warms up. A later fresh fix replaces it. */
    RECENTER_PROVISIONAL,
    IGNORE,
}

/** Result of the driver pressing the recenter control. */
enum class RecenterOutcome { MOVED, NO_PERMISSION, NO_LOCATION_SERVICE, NO_FIX }

/**
 * Decides whether a fix should move the map camera, once per app start.
 *
 * Before this existed nothing in the app ever moved the camera: `AmapNaviViewHost` enabled
 * `MyLocationStyle(LOCATION_TYPE_LOCATE)` on the navigation view's map and relied on the SDK to
 * centre itself, which it does not do on `AMapNaviView` without an active route. So the view
 * opened wherever it had been left and the driver had to drag the map to their own position.
 *
 * Rules, in the order they are applied:
 * 1. A manual pan ends automatic recentering for the rest of the app start — the driver's
 *    chosen viewport is never yanked away.
 * 2. Once a fresh fix has recentred, nothing recentres automatically again.
 * 3. Nonsense coordinates (non-finite, out of range, exactly 0/0) and wildly inaccurate fixes
 *    are ignored rather than shown.
 * 4. A fix younger than [freshMaxAgeMs] is the real position: recentre and settle.
 * 5. An older fix, still younger than [provisionalMaxAgeMs], may recentre **once** as a
 *    placeholder, so the map is roughly right while GNSS warms up.
 * 6. Anything older is a previous session's position and is never shown as the current one.
 *
 * Thread-safe: fixes arrive on the SDK's callback thread, the pan signal on the UI thread.
 */
class InitialLocationRecenter(
    private val freshMaxAgeMs: Long = FRESH_MAX_AGE_MS,
    private val provisionalMaxAgeMs: Long = PROVISIONAL_MAX_AGE_MS,
    private val maxAccuracyMeters: Float = MAX_ACCURACY_METERS,
) {
    private var centredOnFresh = false
    private var centredProvisionally = false
    private var userMovedCamera = false

    /** True once a fresh fix has been centred on; no further automatic move will happen. */
    val settled: Boolean
        @Synchronized get() = centredOnFresh

    /** True once the driver panned the map; automatic recentring is over for this app start. */
    val stopped: Boolean
        @Synchronized get() = userMovedCamera

    /** Called for any drag on the map surface. Idempotent. */
    @Synchronized
    fun onUserMovedCamera() {
        userMovedCamera = true
    }

    @Synchronized
    fun decide(fix: LocationFix): RecenterDecision {
        if (userMovedCamera || centredOnFresh) return RecenterDecision.IGNORE
        if (!fix.isUsable(maxAccuracyMeters)) return RecenterDecision.IGNORE
        // Clock skew can make a live fix look microscopically future-dated; that is still live.
        val age = fix.ageMs.coerceAtLeast(0L)
        if (age <= freshMaxAgeMs) {
            centredOnFresh = true
            centredProvisionally = true
            return RecenterDecision.RECENTER_FRESH
        }
        if (centredProvisionally) return RecenterDecision.IGNORE
        if (age > provisionalMaxAgeMs) return RecenterDecision.IGNORE
        centredProvisionally = true
        return RecenterDecision.RECENTER_PROVISIONAL
    }

    companion object {
        /** Younger than this and the fix is treated as where the driver is now. */
        const val FRESH_MAX_AGE_MS = 30_000L

        /** Older than this and a cached fix is a previous session's, never shown as current. */
        const val PROVISIONAL_MAX_AGE_MS = 10 * 60_000L

        /** A cell-tower fix can be kilometres wide; centring on one is worse than waiting. */
        const val MAX_ACCURACY_METERS = 2_000f

        private fun LocationFix.isUsable(maxAccuracyMeters: Float): Boolean {
            if (!latitude.isFinite() || !longitude.isFinite()) return false
            if (latitude < -90.0 || latitude > 90.0) return false
            if (longitude < -180.0 || longitude > 180.0) return false
            // Null Island: what an uninitialised or cleared fix reports.
            if (latitude == 0.0 && longitude == 0.0) return false
            if (accuracyMeters > maxAccuracyMeters) return false
            return true
        }
    }
}
