package com.novadrive.app.nav.amap

import android.content.Context
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.novadrive.app.nav.LocationFix

/**
 * The one WGS-84 → GCJ-02 conversion for every Amap surface (map, route, `place/around`).
 *
 * Android reports WGS-84; Amap expects GCJ-02 (618 m apart at the demo origin, measured
 * 2026-10-12). Uses the SDK's offline `CoordinateConverter` (lbs.amap.com Android 坐标转换).
 * Stateless; never logs a coordinate (I-8). On failure the fix is returned unchanged.
 */
object AmapCoordinates {
    fun toGcj02(context: Context, fix: LocationFix): LocationFix = runCatching {
        AmapPrivacyCompliance.ensure(context)
        val converted: LatLng? = CoordinateConverter(context.applicationContext)
            .from(CoordinateConverter.CoordType.GPS)
            .coord(LatLng(fix.latitude, fix.longitude))
            .convert()
        if (converted == null) fix else fix.copy(latitude = converted.latitude, longitude = converted.longitude)
    }.getOrElse { fix }
}
