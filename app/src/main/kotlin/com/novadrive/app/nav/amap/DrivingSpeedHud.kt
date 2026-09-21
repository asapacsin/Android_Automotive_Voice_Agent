package com.novadrive.app.nav.amap

/**
 * Current speed / posted-limit numbers for the driving HUD.
 *
 * AMapNaviView has no speedometer widget. Values come from Amap's location
 * callback and camera/facility limits — not a second speed source.
 */
object DrivingSpeedHud {
    data class CameraLimit(val limitKmh: Int, val distM: Int)

    data class Snapshot(
        val visible: Boolean,
        val speedKmh: Int,
        val limitKmh: Int,
        val overspeed: Boolean,
    )

    fun snapshot(
        navigating: Boolean,
        reportedSpeedKmh: Int,
        fallbackSpeedKmh: Int,
        limitKmh: Int,
    ): Snapshot {
        if (!navigating) return Snapshot(visible = false, speedKmh = 0, limitKmh = 0, overspeed = false)
        val speed = if (reportedSpeedKmh > 0) reportedSpeedKmh else fallbackSpeedKmh.coerceAtLeast(0)
        val limit = limitKmh.coerceAtLeast(0)
        return Snapshot(
            visible = true,
            speedKmh = speed,
            limitKmh = limit,
            overspeed = EmulatorNaviSpeed.exceedsPostedLimit(speed, limit),
        )
    }

    fun postedLimitKmh(cameras: List<CameraLimit>): Int =
        cameras.filter { it.limitKmh > 0 }.minByOrNull { it.distM }?.limitKmh ?: 0

    fun mergeLimit(cameraLimitKmh: Int, facilityLimitKmh: Int): Int = when {
        cameraLimitKmh > 0 -> cameraLimitKmh
        facilityLimitKmh > 0 -> facilityLimitKmh
        else -> 0
    }
}
