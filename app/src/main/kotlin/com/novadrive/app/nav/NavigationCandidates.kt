package com.novadrive.app.nav

import java.util.Locale
import kotlin.math.roundToInt

data class DestinationCandidate(
    val id: String,
    val name: String,
    val address: String,
    val district: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int? = null,
    val poiId: String? = null,
    /** Amap district code; lets 「目的地天气」 skip a reverse-geocode (SPEC-011). */
    val adcode: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude $latitude is outside -90..90" }
        require(longitude in -180.0..180.0) { "longitude $longitude is outside -180..180" }
    }

    fun toDestination(): Destination = Destination(
        name = name,
        latitude = latitude,
        longitude = longitude,
        poiId = poiId,
        address = address.takeIf { it.isNotBlank() },
        adcode = adcode,
    )
}

data class RouteCandidate(
    val routeId: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val labels: String? = null,
    val trafficLightCount: Int? = null,
)

/** PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT — kept as Int so this layer never imports the SDK. */
const val DRIVING_MULTIPLE_ROUTES_DEFAULT = 10

object NavigationFormatters {
    fun formatDistanceMeters(meters: Int): String {
        val value = meters.coerceAtLeast(0)
        return if (value < 1000) {
            "$value m"
        } else {
            String.format(Locale.US, "%.1f km", value / 1000.0)
        }
    }

    fun formatDurationSeconds(seconds: Int): String {
        val totalMinutes = (seconds.coerceAtLeast(0) / 60.0).roundToInt()
        return if (totalMinutes < 60) {
            "$totalMinutes 分钟"
        } else {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (minutes == 0) "$hours 小时" else "$hours 小时 $minutes 分钟"
        }
    }
}
