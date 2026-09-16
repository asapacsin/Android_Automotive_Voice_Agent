package com.novadrive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager

class CoarseLocationProvider(context: Context) {
    private val context = context.applicationContext

    /** Last known coarse location as Pair(latitude, longitude), or null when unavailable or not permitted. Never throws. */
    fun lastKnown(): Pair<Double, Double>? = runCatching {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@runCatching null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.takeIf { location ->
                location.latitude.isFinite() && location.longitude.isFinite()
            }
        }.maxByOrNull { it.time }
        best?.let { it.latitude to it.longitude }
    }.getOrNull()
}
