package com.novadrive.app.nav

import android.content.Context
import com.novadrive.app.AmapPoiClient
import com.novadrive.app.AmapSettingsRepository
import com.novadrive.app.CoarseLocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveDestinationCandidateSource(
    context: Context,
    private val poiClient: AmapPoiClient = AmapPoiClient(),
    private val location: () -> Pair<Double, Double>? = { CoarseLocationProvider(context).lastKnown() },
    private val webKey: () -> String? = { AmapSettingsRepository(context).loadWebKey() },
) : DestinationCandidateSource {
    override suspend fun resolve(query: String): List<DestinationCandidate> = withContext(Dispatchers.IO) {
        val key = webKey()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        // 「附近的麦当劳」 is not a POI name. Measured 2026-09-18: searching the spoken string
        // verbatim returned zero results for a brand with branches minutes away (row T04).
        val keyword = DestinationQuery.searchKeyword(query)
        val fix = location()
        val candidates = if (fix != null) {
            poiClient.resolveNearbyCandidates(keyword, key, fix.first, fix.second)
        } else {
            poiClient.resolveCandidates(keyword, key)
        }
        if (candidates.isNotEmpty() || keyword == query) return@withContext candidates
        // The stripped keyword found nothing; the driver may genuinely have named a place that
        // contains one of those words. Try what they actually said before giving up.
        com.novadrive.app.DebugVoiceLog.log("nav_query_retry_verbatim")
        if (fix != null) {
            poiClient.resolveNearbyCandidates(query, key, fix.first, fix.second)
        } else {
            poiClient.resolveCandidates(query, key)
        }
    }
}
