package com.novadrive.app

import com.novadrive.app.nav.DestinationCandidate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PoiResult(val name: String, val latitude: Double, val longitude: Double)

object AmapPoiParser {
    fun parse(json: String): PoiResult? {
        return try {
            val root = JSONObject(json)
            if (root.optString("status") != "1") return null
            val pois = root.optJSONArray("pois") ?: return null
            if (pois.length() == 0) return null
            val first = pois.optJSONObject(0) ?: return null
            val location = first.optString("location")
            val parts = location.split(',')
            if (parts.size != 2) return null
            val longitude = parts[0].trim().toDoubleOrNull() ?: return null
            val latitude = parts[1].trim().toDoubleOrNull() ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            PoiResult(name = first.optString("name"), latitude = latitude, longitude = longitude)
        } catch (_: Exception) {
            null
        }
    }

    fun parseCandidates(json: String): List<DestinationCandidate> {
        return try {
            val root = JSONObject(json)
            if (root.optString("status") != "1") return emptyList()
            val pois = root.optJSONArray("pois") ?: return emptyList()
            buildList {
                for (index in 0 until pois.length()) {
                    val obj = pois.optJSONObject(index) ?: continue
                    val candidate = parseCandidate(obj, index) ?: continue
                    add(candidate)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseCandidate(obj: JSONObject, index: Int): DestinationCandidate? {
        val location = obj.optString("location")
        val parts = location.split(',')
        if (parts.size != 2) return null
        val longitude = parts[0].trim().toDoubleOrNull() ?: return null
        val latitude = parts[1].trim().toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val poiId = obj.optString("id").takeIf { it.isNotBlank() }
        return try {
            DestinationCandidate(
                id = poiId ?: "i$index",
                name = obj.optString("name"),
                address = obj.optString("address"),
                district = obj.optString("adname"),
                latitude = latitude,
                longitude = longitude,
                distanceMeters = parseDistance(obj),
                poiId = poiId,
                adcode = obj.optString("adcode").takeIf { it.isNotBlank() && it != "[]" },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDistance(obj: JSONObject): Int? {
        if (!obj.has("distance") || obj.isNull("distance")) return null
        return when (val raw = obj.opt("distance")) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> obj.optString("distance").toIntOrNull()
        }
    }
}

class AmapPoiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = "https://restapi.amap.com/v3/place/text",
    private val aroundUrl: String = "https://restapi.amap.com/v3/place/around",
    /** Root of the web-service endpoints `query_live_info` reads (SPEC-011). */
    private val liveBaseUrl: String = "https://restapi.amap.com/v3",
) : AmapLiveInfoRest {
    // ---- query_live_info (SPEC-011). No URL here is ever logged: regeo carries `location=`. ----

    /** [city] is an adcode or a city name; the weather endpoint accepts both. */
    override fun weatherNow(city: String, key: String): LiveInfoFetch<WeatherNow> =
        live("weather/weatherInfo", key, mapOf("city" to city, "extensions" to "base"), AmapLiveInfoParser::weatherNow)

    override fun weatherForecast(city: String, key: String): LiveInfoFetch<WeatherForecast> =
        live("weather/weatherInfo", key, mapOf("city" to city, "extensions" to "all"), AmapLiveInfoParser::weatherForecast)

    override fun regeoAdcode(latitude: Double, longitude: Double, key: String): LiveInfoFetch<String> =
        live(
            "geocode/regeo",
            key,
            mapOf("location" to String.format(Locale.US, "%.6f,%.6f", longitude, latitude)),
            AmapLiveInfoParser::regeoAdcode,
        )

    override fun placeDetail(poiId: String, key: String): LiveInfoFetch<PlaceDetail> =
        live("place/detail", key, mapOf("id" to poiId, "extensions" to "all"), AmapLiveInfoParser::placeDetail)

    private fun <T> live(
        path: String,
        key: String,
        params: Map<String, String>,
        parse: (String) -> LiveInfoFetch<T>,
    ): LiveInfoFetch<T> = try {
        val url = "$liveBaseUrl/$path".toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
            addQueryParameter("key", key)
            addQueryParameter("output", "JSON")
        }?.build()
        if (url == null) {
            LiveInfoFetch.Failed(AmapLiveInfoParser.UNAVAILABLE)
        } else {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) LiveInfoFetch.Failed(AmapLiveInfoParser.UNAVAILABLE) else parse(body)
            }
        }
    } catch (_: Exception) {
        // Timeout (4 s connect / 4 s read), DNS, TLS: all "cannot tell", never a guess.
        LiveInfoFetch.Failed(AmapLiveInfoParser.UNAVAILABLE)
    }

    fun resolve(keyword: String, key: String): PoiResult? {
        return try {
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keyword)
                ?.addQueryParameter("key", key)
                ?.addQueryParameter("offset", "1")
                ?.addQueryParameter("page", "1")
                ?.addQueryParameter("extensions", "base")
                ?.addQueryParameter("output", "JSON")
                ?.build() ?: return null
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                AmapPoiParser.parse(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveNearby(
        keyword: String,
        key: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 50_000,
    ): PoiResult? {
        return try {
            val url = aroundUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keyword)
                ?.addQueryParameter("key", key)
                ?.addQueryParameter("location", String.format(Locale.US, "%.6f,%.6f", longitude, latitude))
                ?.addQueryParameter("radius", radiusMeters.toString())
                ?.addQueryParameter("sortrule", "distance")
                ?.addQueryParameter("offset", "1")
                ?.addQueryParameter("page", "1")
                ?.addQueryParameter("extensions", "base")
                ?.addQueryParameter("output", "JSON")
                ?.build() ?: return null
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                AmapPoiParser.parse(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun resolveCandidates(keyword: String, key: String, limit: Int = 5): List<DestinationCandidate> {
        return try {
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keyword)
                ?.addQueryParameter("key", key)
                ?.addQueryParameter("offset", limit.toString())
                ?.addQueryParameter("page", "1")
                ?.addQueryParameter("extensions", "base")
                ?.addQueryParameter("output", "JSON")
                ?.build() ?: return emptyList()
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                AmapPoiParser.parseCandidates(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun resolveNearbyCandidates(
        keyword: String,
        key: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 50_000,
        limit: Int = 5,
    ): List<DestinationCandidate> {
        return try {
            val url = aroundUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("keywords", keyword)
                ?.addQueryParameter("key", key)
                ?.addQueryParameter("location", String.format(Locale.US, "%.6f,%.6f", longitude, latitude))
                ?.addQueryParameter("radius", radiusMeters.toString())
                ?.addQueryParameter("sortrule", "distance")
                ?.addQueryParameter("offset", limit.toString())
                ?.addQueryParameter("page", "1")
                ?.addQueryParameter("extensions", "base")
                ?.addQueryParameter("output", "JSON")
                ?.build() ?: return emptyList()
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                AmapPoiParser.parseCandidates(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
