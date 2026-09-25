package com.novadrive.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The outcome of one Amap web-service call made for `query_live_info` (SPEC-011).
 *
 * [Failed.code] is already the tool's failure code, so nothing downstream has to know Amap's
 * `status` / `infocode` conventions.
 */
sealed interface LiveInfoFetch<out T> {
    data class Ok<T>(val value: T) : LiveInfoFetch<T>
    data class Failed(val code: String) : LiveInfoFetch<Nothing>
}

/** `v3/weather/weatherInfo?extensions=base`. Every field is only what the source returned. */
data class WeatherNow(
    val city: String?,
    val weather: String?,
    val temperatureC: Int?,
    val windDirection: String?,
    val windPower: String?,
    val humidity: Int?,
    /** "HH:mm" of `reporttime`. */
    val reportedAt: String?,
)

/** One day of `v3/weather/weatherInfo?extensions=all`. */
data class WeatherDay(
    val date: String?,
    val dayWeather: String?,
    val nightWeather: String?,
    val highC: Int?,
    val lowC: Int?,
)

data class WeatherForecast(val city: String?, val reportedAt: String?, val days: List<WeatherDay>)

/** `v3/place/detail?extensions=all`, reduced to what a driver would ask about. */
data class PlaceDetail(
    val name: String?,
    val address: String?,
    val phone: String?,
    val openingHours: String?,
    val rating: String?,
    val category: String?,
)

/**
 * Parsers for the Amap REST responses `query_live_info` reads. Fixtures recorded 2026-09-25 live
 * from restapi.amap.com are in `app/src/test/resources/amap/`.
 *
 * Amap encodes an absent string as `[]` and an absent number as `""`; both become null here. A
 * missing field stays missing — it is never filled in (SPEC-011 B2).
 */
object AmapLiveInfoParser {
    const val UNAVAILABLE = "LIVE_INFO_UNAVAILABLE"
    const val QUOTA = "LIVE_INFO_QUOTA"
    const val NO_RESULTS = "NO_RESULTS"

    /** Daily / per-key quota exhausted (Amap infocode table). */
    private val QUOTA_INFOCODES = setOf("10003", "10044")

    fun weatherNow(json: String): LiveInfoFetch<WeatherNow> = parse(json) { root ->
        val live = root.optJSONArray("lives")?.firstObject() ?: return@parse LiveInfoFetch.Failed(NO_RESULTS)
        val weather = WeatherNow(
            city = live.text("city"),
            weather = live.text("weather"),
            temperatureC = live.text("temperature")?.toIntOrNull(),
            windDirection = live.text("winddirection"),
            windPower = live.text("windpower"),
            humidity = live.text("humidity")?.toIntOrNull(),
            reportedAt = clock(live.text("reporttime")),
        )
        if (weather.weather == null && weather.temperatureC == null) LiveInfoFetch.Failed(NO_RESULTS) else LiveInfoFetch.Ok(weather)
    }

    fun weatherForecast(json: String): LiveInfoFetch<WeatherForecast> = parse(json) { root ->
        val forecast = root.optJSONArray("forecasts")?.firstObject() ?: return@parse LiveInfoFetch.Failed(NO_RESULTS)
        val casts = forecast.optJSONArray("casts") ?: JSONArray()
        val days = (0 until casts.length()).mapNotNull { index ->
            val cast = casts.optJSONObject(index) ?: return@mapNotNull null
            WeatherDay(
                date = cast.text("date"),
                dayWeather = cast.text("dayweather"),
                nightWeather = cast.text("nightweather"),
                highC = cast.text("daytemp")?.toIntOrNull(),
                lowC = cast.text("nighttemp")?.toIntOrNull(),
            )
        }
        if (days.isEmpty()) {
            LiveInfoFetch.Failed(NO_RESULTS)
        } else {
            LiveInfoFetch.Ok(WeatherForecast(forecast.text("city"), clock(forecast.text("reporttime")), days))
        }
    }

    /** `v3/geocode/regeo`: only the adcode leaves this function, never the address. */
    fun regeoAdcode(json: String): LiveInfoFetch<String> = parse(json) { root ->
        root.optJSONObject("regeocode")?.optJSONObject("addressComponent")?.text("adcode")
            ?.let { LiveInfoFetch.Ok(it) } ?: LiveInfoFetch.Failed(NO_RESULTS)
    }

    fun placeDetail(json: String): LiveInfoFetch<PlaceDetail> = parse(json) { root ->
        val poi = root.optJSONArray("pois")?.firstObject() ?: return@parse LiveInfoFetch.Failed(NO_RESULTS)
        val business = poi.optJSONObject("biz_ext")
        LiveInfoFetch.Ok(
            PlaceDetail(
                name = poi.text("name"),
                address = poi.text("address"),
                phone = poi.text("tel"),
                openingHours = business?.text("opentime2") ?: business?.text("open_time"),
                rating = business?.text("rating"),
                category = poi.text("type")?.split(';')?.lastOrNull()?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /** Status and quota handling shared by every endpoint; a body that is not JSON is unavailable. */
    private fun <T> parse(json: String, body: (JSONObject) -> LiveInfoFetch<T>): LiveInfoFetch<T> =
        try {
            val root = JSONObject(json)
            when {
                root.optString("infocode") in QUOTA_INFOCODES -> LiveInfoFetch.Failed(QUOTA)
                root.optString("status") != "1" -> LiveInfoFetch.Failed(UNAVAILABLE)
                else -> body(root)
            }
        } catch (_: Exception) {
            LiveInfoFetch.Failed(UNAVAILABLE)
        }

    /** The first element when it is an object with at least one field; Amap sends `[[]]` for none. */
    private fun JSONArray.firstObject(): JSONObject? = optJSONObject(0)?.takeIf { it.length() > 0 }

    private fun JSONObject.text(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** "2026-09-25 14:39:40" → "14:39". */
    private fun clock(reportTime: String?): String? =
        reportTime?.substringAfter(' ', "")?.take(5)?.takeIf { Regex("\\d{2}:\\d{2}").matches(it) }
}
