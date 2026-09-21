package com.novadrive.app.nav.amap

/**
 * Pure progress rules for [NavigationTraceListener].
 *
 * P31 (2026-09-21): emulator runs reached ~15 m remaining, then jumped to ~3.7 km without
 * `nav_emulator_end`. These helpers log regressions for measurement and apply a narrow
 * emulator-only proximity completion when the SDK never fires [onEndEmulatorNavi].
 */
internal object NavigationProgressTrace {
    /** Amap [NaviType.EMULATOR] — observed as `nav_started type=2` on device. */
    const val NAVI_TYPE_EMULATOR = 2

    /** Complete when this close or closer for [proximitySamplesRequired] consecutive updates. */
    const val PROXIMITY_ARRIVAL_METERS = 35

    const val PROXIMITY_SAMPLES_REQUIRED = 2

    /** Remaining distance increase that counts as a terminal regression (P31 used ~15 -> ~3700). */
    const val REGRESSION_MIN_INCREASE_METERS = 500

    /** Log when remaining distance moves by at least this many metres. */
    const val REMAIN_LOG_DELTA_METERS = 50

    fun isEmulatorNav(naviType: Int?): Boolean = naviType == NAVI_TYPE_EMULATOR

    fun isRemainRegression(previousMeters: Int, currentMeters: Int): Boolean {
        if (previousMeters <= 0 || currentMeters <= 0) return false
        return currentMeters - previousMeters >= REGRESSION_MIN_INCREASE_METERS
    }

    fun shouldLogRemainUpdate(previousMeters: Int?, currentMeters: Int): Boolean {
        if (currentMeters < 0) return false
        if (previousMeters == null) return true
        return kotlin.math.abs(currentMeters - previousMeters) >= REMAIN_LOG_DELTA_METERS
    }

    fun proximityStreak(previousStreak: Int, naviType: Int?, remainMeters: Int): Int {
        if (!isEmulatorNav(naviType)) return 0
        if (remainMeters < 0 || remainMeters > PROXIMITY_ARRIVAL_METERS) return 0
        return previousStreak + 1
    }

    fun shouldCompleteByProximity(naviType: Int?, streak: Int): Boolean =
        isEmulatorNav(naviType) && streak >= PROXIMITY_SAMPLES_REQUIRED
}
