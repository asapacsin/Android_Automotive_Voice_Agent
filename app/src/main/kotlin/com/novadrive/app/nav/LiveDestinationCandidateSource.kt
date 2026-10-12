package com.novadrive.app.nav

import android.content.Context
import com.novadrive.app.AmapPoiClient
import com.novadrive.app.AmapSettingsRepository
import com.novadrive.app.CoarseLocationProvider
import com.novadrive.app.nav.amap.AmapCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiveDestinationCandidateSource(
    private val poiClient: AmapPoiClient,
    /** The platform's WGS-84 fix. */
    private val platformFix: () -> LocationFix?,
    /** `place/around` has no coordsys parameter: it takes GCJ-02 (P51, 618 m at the demo origin). */
    private val toGcj02: (LocationFix) -> LocationFix,
    private val webKey: () -> String?,
    private val savedPlaces: (PlaceSlot) -> SavedPlace?,
) : DestinationCandidateSource {
    /** The app's wiring. The primary constructor needs no Context, so the JVM can run the real search. */
    constructor(context: Context) : this(
        poiClient = AmapPoiClient(),
        platformFix = { CoarseLocationProvider(context).lastKnownFix() },
        toGcj02 = { AmapCoordinates.toGcj02(context, it) },
        webKey = { AmapSettingsRepository(context).loadWebKey() },
        savedPlaces = SavedPlaceStore(context)::get,
    )

    override suspend fun resolve(query: String): List<DestinationCandidate> = withContext(Dispatchers.IO) {
        // A saved place is answered from what the driver told us, never searched for. 「回家」 went
        // to the POI search verbatim until 2026-09-19 and returned nothing.
        SavedPlaces.slotFor(query)?.let { slot ->
            val saved = savedPlaces(slot)
            com.novadrive.app.DebugVoiceLog.log("nav_saved_place slot=${slot.name} set=${saved != null}")
            // An unset slot resolves to nothing rather than falling through to a search: searching
            // for 家 finds strangers' homes and shops, and driving somewhere wrong is worse than
            // saying we do not know. The dispatcher turns this into a spoken explanation.
            return@withContext listOfNotNull(saved?.toCandidate())
        }
        val key = webKey()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        // 「附近的麦当劳」 is not a POI name. Measured 2026-09-18: searching the spoken string
        // verbatim returned zero results for a brand with branches minutes away (row T04).
        val keyword = DestinationQuery.searchKeyword(query)
        // Saved places above are never converted; only the search origin is.
        val raw = platformFix()
        val fix = raw?.let(toGcj02)
        if (raw != null && fix != null) {
            val converted = fix.latitude != raw.latitude || fix.longitude != raw.longitude
            com.novadrive.app.DebugVoiceLog.log("nav_search_origin converted=$converted")
        }
        // A named place ranks by relevance (P51: nearest-first answered 横琴镇 with the origin); a
        // driver who asked for the nearest gets the nearest.
        val sort = if (DestinationQuery.isNearbyRequest(query)) AmapPoiClient.NEAREST_SORT_RULE else AmapPoiClient.NEARBY_SORT_RULE
        val candidates = if (fix != null) {
            poiClient.resolveNearbyCandidates(keyword, key, fix.latitude, fix.longitude, sortRule = sort)
        } else {
            poiClient.resolveCandidates(keyword, key)
        }
        if (candidates.isNotEmpty() || keyword == query) return@withContext candidates
        // The stripped keyword found nothing; the driver may genuinely have named a place that
        // contains one of those words. Try what they actually said before giving up.
        com.novadrive.app.DebugVoiceLog.log("nav_query_retry_verbatim")
        if (fix != null) {
            poiClient.resolveNearbyCandidates(query, key, fix.latitude, fix.longitude, sortRule = sort)
        } else {
            poiClient.resolveCandidates(query, key)
        }
    }
}
