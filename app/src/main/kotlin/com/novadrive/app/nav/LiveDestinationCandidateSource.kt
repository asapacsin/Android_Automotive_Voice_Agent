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
        val fix = location()
        if (fix != null) {
            poiClient.resolveNearbyCandidates(query, key, fix.first, fix.second)
        } else {
            poiClient.resolveCandidates(query, key)
        }
    }
}
