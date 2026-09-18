package com.novadrive.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.novadrive.app.nav.LocationAge
import com.novadrive.app.nav.LocationFix
import java.util.concurrent.atomic.AtomicBoolean

class CoarseLocationProvider(context: Context) {
    private val context = context.applicationContext

    /** Last known coarse location as Pair(latitude, longitude), or null when unavailable or not permitted. Never throws. */
    fun lastKnown(): Pair<Double, Double>? = lastKnownFix()?.let { it.latitude to it.longitude }

    /**
     * The newest platform last-known fix, **WGS-84** — the coordinate system Android reports and
     * *not* the GCJ-02 the Amap surfaces use. Converting is the caller's job, and only the Amap
     * host may do it (it owns the SDK import).
     *
     * Carries the fix's age so a caller can tell "a minute ago" from "last week": without it a
     * cached position from a previous session is indistinguishable from the driver's current one.
     * Never throws.
     */
    fun lastKnownFix(): LocationFix? = runCatching {
        if (!hasLocationPermission()) return@runCatching null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.takeIf { location ->
                location.latitude.isFinite() && location.longitude.isFinite()
            }
            // Newest by the monotonic clock, for the same reason the age is: a provider that
            // re-stamps its cached fixes would otherwise always win this comparison.
        }.maxByOrNull { it.elapsedRealtimeNanos }
        best?.toFix()
    }.getOrNull()

    /**
     * Age comes from the monotonic clock, never from [Location.getTime]: a wall-clock stamp can
     * be moved by a clock correction between the fix and the reading, and a provider may stamp a
     * cached fix with the moment it handed it over. See [LocationAge].
     */
    private fun Location.toFix(): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        ageMs = LocationAge.fromElapsedRealtime(elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()),
        accuracyMeters = if (hasAccuracy()) accuracy else 0f,
    )

    /**
     * Asks the platform for one fresh fix, delivered on the main looper, then unregisters itself.
     *
     * The Amap SDK has its own position stream, but it has twice been assumed to do something it
     * does not (P5), and a map that waits forever for a callback that never comes looks exactly
     * like the defect this exists to fix. This is a second, independent source. Fixes are
     * **WGS-84** like [lastKnownFix].
     *
     * Returns a cancel handle, or null when no provider could be asked. Never throws.
     */
    fun requestSingleFix(onFix: (LocationFix) -> Unit): (() -> Unit)? = runCatching {
        if (!hasLocationPermission()) return@runCatching null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return@runCatching null

        val delivered = AtomicBoolean(false)
        lateinit var listener: LocationListener
        val cancel = {
            if (delivered.compareAndSet(false, true)) {
                runCatching { manager.removeUpdates(listener) }
                Unit
            }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
                if (!delivered.compareAndSet(false, true)) return
                runCatching { manager.removeUpdates(this) }
                // Age-stamped like any other fix: requestLocationUpdates can deliver the
                // provider's cached position first, which is not a live one.
                onFix(location.toFix())
            }

            // Overridden explicitly: these are default methods only in recent SDK stubs, and
            // minSdk here is 28, where the framework interface still declares them abstract.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }
        cancel
    }.getOrNull()

    /**
     * Whether the device's location services are switched on at all. A denied permission and a
     * disabled GPS switch look identical from the map's side — no position ever arrives — so the
     * two are reported apart. Never throws.
     */
    fun locationServicesEnabled(): Boolean = runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager.isLocationEnabled
    }.getOrDefault(false)

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
