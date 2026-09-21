package com.novadrive.app.nav.amap

/**
 * Emulator vehicle speed is how fast the fake car moves, not a posted limit.
 *
 * Amap's [com.amap.api.navi.AMapNavi.setEmulatorNaviSpeed] range is 40–120 km/h.
 * Default 50 sits above typical 30 cameras and below 80 arterials so HUD overspeed
 * is per-road. 120 made every camera fire on a short urban trip.
 */
object EmulatorNaviSpeed {
    const val DEFAULT_KMH = 50
    const val MIN_KMH = 40
    const val MAX_KMH = 120

    data class Request(val emulator: Boolean, val speedKmh: Int)

    fun clamp(speedKmh: Int): Int = speedKmh.coerceIn(MIN_KMH, MAX_KMH)

    fun parseNavStart(arg: String): Request {
        val trimmed = arg.trim()
        if (trimmed.equals("gps", ignoreCase = true)) {
            return Request(emulator = false, speedKmh = DEFAULT_KMH)
        }
        if (trimmed.isEmpty() || trimmed.equals("emulator", ignoreCase = true)) {
            return Request(emulator = true, speedKmh = DEFAULT_KMH)
        }
        if (trimmed.startsWith("emulator:", ignoreCase = true)) {
            val parsed = trimmed.substringAfter(':').toIntOrNull()
            return Request(emulator = true, speedKmh = clamp(parsed ?: DEFAULT_KMH))
        }
        return Request(emulator = false, speedKmh = DEFAULT_KMH)
    }

    /**
     * HUD overspeed (Amap) compares current speed to that camera/road's limit.
     * [limitKmh] 0 means no speed-limit camera / no data — not an overspeed.
     */
    fun exceedsPostedLimit(speedKmh: Int, limitKmh: Int): Boolean =
        limitKmh > 0 && speedKmh > limitKmh
}
