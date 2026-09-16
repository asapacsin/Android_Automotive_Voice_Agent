package com.novadrive.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Phone navigation handoff seam. It does not claim route activation or arrival. */
class NavigationAdapter(
    private val context: Context,
    private val poiResolver: ((String) -> PoiResult?)? = null,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * Hands the destination to a maps app.
     * When a POI resolver is configured and coordinates are not supplied, [NavigationHandoffResult.HandedOff]
     * means the handoff started; resolution and the Amap/geo launch continue off the main thread.
     */
    fun openDestination(label: String, latitude: Double? = null, longitude: Double? = null): NavigationHandoffResult {
        val safeLabel = label.trim()
        if (safeLabel.isEmpty() || safeLabel.length > 120) return NavigationHandoffResult.Unavailable
        if (latitude != null && longitude != null) {
            val navi = Intent(Intent.ACTION_VIEW, Uri.parse(NavigationUris.amapNavi(safeLabel, latitude, longitude)))
                .setPackage("com.autonavi.minimap")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(navi)) return NavigationHandoffResult.HandedOff
            val query = Uri.encode(safeLabel)
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($query)"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return if (tryStart(geo)) NavigationHandoffResult.HandedOff else NavigationHandoffResult.Unavailable
        }
        if (poiResolver != null) {
            ioScope.launch {
                val poi = runCatching { poiResolver.invoke(safeLabel) }.getOrNull()
                val started = if (poi != null) {
                    tryStart(
                        Intent(Intent.ACTION_VIEW, Uri.parse(NavigationUris.amapNavi(poi.name, poi.latitude, poi.longitude)))
                            .setPackage("com.autonavi.minimap")
                            .addCategory(Intent.CATEGORY_DEFAULT)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    false
                }
                if (!started) {
                    NavigationAutoPick.arm(safeLabel, System.currentTimeMillis())
                    tryStart(keywordNaviIntent(safeLabel)) || tryStart(geoSearchIntent(safeLabel))
                }
            }
            return NavigationHandoffResult.HandedOff
        }
        NavigationAutoPick.arm(safeLabel, System.currentTimeMillis())
        if (tryStart(keywordNaviIntent(safeLabel))) return NavigationHandoffResult.HandedOff
        return if (tryStart(geoSearchIntent(safeLabel))) NavigationHandoffResult.HandedOff else NavigationHandoffResult.Unavailable
    }

    private fun keywordNaviIntent(label: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(NavigationUris.amapKeywordNavi(label)))
            .setPackage("com.autonavi.minimap")
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun geoSearchIntent(label: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(NavigationUris.geoSearch(label)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun tryStart(intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
}

sealed interface NavigationHandoffResult {
    data object HandedOff : NavigationHandoffResult
    data object Unavailable : NavigationHandoffResult
}

internal object NavigationUris {
    fun amapKeywordNavi(label: String): String {
        val keyword = encode(label)
        return "androidamap://keywordNavi?sourceApplication=NovaDrive&keyword=$keyword&style=2"
    }

    fun amapNavi(name: String, latitude: Double, longitude: Double): String {
        val poiname = encode(name)
        val lat = String.format(java.util.Locale.US, "%.6f", latitude)
        val lon = String.format(java.util.Locale.US, "%.6f", longitude)
        return "androidamap://navi?sourceApplication=NovaDrive&poiname=$poiname&lat=$lat&lon=$lon&dev=0&style=2"
    }

    fun geoSearch(label: String): String = "geo:0,0?q=${encode(label)}"

    private fun encode(label: String): String =
        java.net.URLEncoder.encode(label, "UTF-8").replace("+", "%20")
}
