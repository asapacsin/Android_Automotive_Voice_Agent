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
) {
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
