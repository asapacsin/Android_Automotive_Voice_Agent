package com.novadrive.app

import com.novadrive.app.nav.AlongRouteCategory
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.nav.RouteLiveInfoSource
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Locale.US

/** The REST lookups [LiveInfoTool] needs; [AmapPoiClient] is the one implementation (SPEC-011 non-goals). */
interface AmapLiveInfoRest {
    fun weatherNow(city: String, key: String): LiveInfoFetch<WeatherNow>
    fun weatherForecast(city: String, key: String): LiveInfoFetch<WeatherForecast>
    fun regeoAdcode(latitude: Double, longitude: Double, key: String): LiveInfoFetch<String>
    fun placeDetail(poiId: String, key: String): LiveInfoFetch<PlaceDetail>
}

/**
 * `query_live_info` (SPEC-011): weather, traffic on the route, POIs along it, and a place's details,
 * each answered from a real Amap lookup or refused with a code — never from the model's imagination.
 *
 * Preconditions that need no network are checked at once; the lookup itself is [ToolDispatchResult.deferredOutput]
 * (the camera pattern, B1), so a slow network never blocks the event loop. Every field in a result is
 * one the source returned; nothing is filled in (B2).
 */
class LiveInfoTool(
    private val webKey: () -> String?,
    private val rest: AmapLiveInfoRest,
    /** WGS-84 last fix (lat, lon), or null. District-level adcode tolerates the GCJ-02 offset. */
    private val location: () -> Pair<Double, Double>?,
    private val navigation: () -> EmbeddedNavigationController?,
    private val route: RouteLiveInfoSource,
    /** Monotonic milliseconds, for the weather cache and the `ms=` log field. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Wall-clock "HH:mm" for results the source does not timestamp (B4). */
    private val clockText: () -> String = { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()) },
    private val log: (String) -> Unit = { DebugVoiceLog.log(it) },
    /** SPEC-011 failure table: 4 s, then LIVE_INFO_UNAVAILABLE. */
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private data class Cached(val atMs: Long, val output: JSONObject)

    /** Weather only, keyed by adcode or city name and day (B4). Traffic and POIs are never cached. */
    private val weatherCache = mutableMapOf<String, Cached>()

    fun dispatch(
        call: DomainVoiceEvent.ToolCall,
        failed: (DomainVoiceEvent.ToolCall, String) -> ToolDispatchResult,
    ): ToolDispatchResult {
        val kind = call.arguments["kind"]
        // The model's own string never reaches the log: it could be a city or an address.
        val kindTag = kind?.takeIf { it in KINDS } ?: "invalid"
        val lookup: (suspend () -> JSONObject) = when (kind) {
            WEATHER -> weather(call.arguments)
            ROUTE_TRAFFIC -> routeTraffic()
            ALONG_ROUTE -> alongRoute(call.arguments["category"])
            PLACE_DETAILS -> placeDetails(call.arguments["target"])
            else -> Precondition(INVALID_KIND)
        }.let { outcome ->
            when (outcome) {
                is Precondition -> {
                    log("live_info kind=$kindTag ok=false code=${outcome.code} ms=0 cached=false")
                    return failed(call, outcome.code)
                }
                is Ready -> outcome.lookup
            }
        }
        return ToolDispatchResult(
            null,
            null,
            successChip = "🔎 正在查",
            deferredOutput = {
                val started = nowMs()
                // Detached, so the deadline holds even while a blocking HTTP read is in progress;
                // cancelling it stops a late along-route result from opening the picker afterwards.
                val work = CoroutineScope(Dispatchers.IO).async { lookup() }
                val result = withTimeoutOrNull(timeoutMs) { work.await() }
                    ?: failure(AmapLiveInfoParser.UNAVAILABLE).also { work.cancel() }
                val ok = result.optBoolean("ok")
                val code = result.optString("error").ifEmpty { "none" }
                val ms = nowMs() - started
                val cached = result.optBoolean("cached")
                log("live_info kind=$kindTag ok=$ok code=$code ms=$ms cached=$cached")
                com.novadrive.app.voice.SpeechAuthority.arbiter.onConfirmation()
                result.put("tool", TOOL).toString()
            },
        )
    }

    private sealed interface Checked
    private data class Precondition(val code: String) : Checked
    private class Ready(val lookup: suspend () -> JSONObject) : Checked

    // ---- weather ------------------------------------------------------------

    private fun weather(args: Map<String, String>): Checked {
        val where = args["where"]?.trim().takeUnless { it.isNullOrEmpty() } ?: HERE
        val tomorrow = when (args["day"]) {
            null, "", "today" -> false
            "tomorrow" -> true
            else -> return Precondition(INVALID_ARGUMENT)
        }
        if (where != HERE && where != DESTINATION && where.length > MAX_CITY) return Precondition(INVALID_ARGUMENT)
        val key = webKey()?.takeIf { it.isNotBlank() } ?: return Precondition(AMAP_WEB_KEY_MISSING)
        val (label, resolveCity) = when (where) {
            HERE -> {
                val fix = location() ?: return Precondition(NO_LOCATION)
                "这里" to { rest.regeoAdcode(fix.first, fix.second, key) }
            }
            DESTINATION -> {
                val destination = navigation()?.destination?.value ?: return Precondition(NO_DESTINATION)
                val adcode = destination.adcode
                "目的地" to {
                    if (adcode != null) LiveInfoFetch.Ok(adcode) else rest.regeoAdcode(destination.latitude, destination.longitude, key)
                }
            }
            else -> where to { LiveInfoFetch.Ok(where) }
        }
        return Ready {
            when (val city = resolveCity()) {
                is LiveInfoFetch.Failed -> failure(city.code)
                is LiveInfoFetch.Ok -> cachedWeather(city.value, tomorrow, key).put("where", label)
            }
        }
    }

    private fun cachedWeather(city: String, tomorrow: Boolean, key: String): JSONObject {
        val cacheKey = "$city|$tomorrow"
        val now = nowMs()
        synchronized(weatherCache) {
            weatherCache[cacheKey]?.takeIf { now - it.atMs < WEATHER_TTL_MS }?.let {
                return JSONObject(it.output.toString()).put("cached", true)
            }
        }
        val fresh = if (tomorrow) forecastJson(rest.weatherForecast(city, key)) else nowJson(rest.weatherNow(city, key))
        if (fresh.optBoolean("ok")) synchronized(weatherCache) { weatherCache[cacheKey] = Cached(now, fresh) }
        return JSONObject(fresh.toString()).put("cached", false)
    }

    private fun nowJson(fetch: LiveInfoFetch<WeatherNow>): JSONObject = when (fetch) {
        is LiveInfoFetch.Failed -> failure(fetch.code)
        is LiveInfoFetch.Ok -> fetch.value.let { weather ->
            success(WEATHER)
                .putOpt("city", weather.city)
                .putOpt("now", weather.weather)
                .putOpt("temp_c", weather.temperatureC)
                .putOpt("wind", wind(weather.windDirection, weather.windPower))
                .putOpt("humidity_pct", weather.humidity)
                .putOpt("reported_at", weather.reportedAt)
        }
    }

    private fun forecastJson(fetch: LiveInfoFetch<WeatherForecast>): JSONObject = when (fetch) {
        is LiveInfoFetch.Failed -> failure(fetch.code)
        is LiveInfoFetch.Ok -> {
            // casts[0] is today; tomorrow is the second day the source returned, or nothing.
            val day = fetch.value.days.getOrNull(1)
            if (day == null) {
                failure(AmapLiveInfoParser.NO_RESULTS)
            } else {
                success(WEATHER)
                    .put("day", "明天")
                    .putOpt("city", fetch.value.city)
                    .putOpt("date", day.date)
                    .putOpt("day_weather", day.dayWeather)
                    .putOpt("night_weather", day.nightWeather)
                    .putOpt("high_c", day.highC)
                    .putOpt("low_c", day.lowC)
                    .putOpt("reported_at", fetch.value.reportedAt)
            }
        }
    }

    private fun wind(direction: String?, power: String?): String? {
        val named = direction?.let { if (it.endsWith("风") || it == "旋转不定" || it.contains("风向")) it else it + "风" }
        return listOfNotNull(named, power?.let { it + "级" }).joinToString("").ifEmpty { null }
    }

    // ---- the route ------------------------------------------------------------

    private fun routeTraffic(): Checked {
        if (navigation()?.state()?.value != NavigationPhase.NAVIGATING) return Precondition(NOT_NAVIGATING)
        return Ready {
            val traffic = route.traffic() ?: return@Ready failure(AmapLiveInfoParser.UNAVAILABLE)
            success(ROUTE_TRAFFIC)
                .put("remaining_km", String.format(US, "%.1f", traffic.remainingMeters / 1000.0).toDouble())
                .put("congested_segments", traffic.congestedSegments)
                .put("slow_segments", traffic.slowSegments)
                .putOpt("first_congestion_m", traffic.metersToFirstCongestion)
                .put("reported_at", clockText())
        }
    }

    private fun alongRoute(rawCategory: String?): Checked {
        val category = AlongRouteCategory.fromWire(rawCategory) ?: return Precondition(INVALID_ARGUMENT)
        val controller = navigation()
        if (controller == null || controller.state().value !in ROUTE_ON_SCREEN) return Precondition(NOT_NAVIGATING)
        return Ready {
            val found = route.alongRoute(category) ?: return@Ready failure(AmapLiveInfoParser.UNAVAILABLE)
            if (found.isEmpty()) return@Ready failure(AmapLiveInfoParser.NO_RESULTS).put("category", category.spoken)
            val shown = found.take(MAX_ALONG_ROUTE)
            // The existing picker, so 「第一个」 is the existing, device-verified navigation path (A6).
            controller.requestCandidates(shown)
            val options = JSONArray()
            shown.forEachIndexed { index, candidate ->
                options.put(
                    JSONObject().put("position", index + 1).put("name", candidate.name)
                        .putOpt("distance_m", candidate.distanceMeters),
                )
            }
            success(ALONG_ROUTE)
                .put("category", category.spoken)
                .put("count", shown.size)
                .put("status", "shown_on_screen")
                .put("options_on_screen", options)
                .put("reported_at", clockText())
                .put("next", "候选已显示在屏幕上。只说找到几个${category.spoken}和最近的一个，请用户说第几个；选中之前没有开始导航。")
        }
    }

    // ---- place details ----------------------------------------------------------

    private fun placeDetails(target: String?): Checked {
        val key = webKey()?.takeIf { it.isNotBlank() } ?: return Precondition(AMAP_WEB_KEY_MISSING)
        val controller = navigation()
        val poiId = when (val wanted = target?.trim().orEmpty().ifEmpty { DESTINATION }) {
            DESTINATION -> {
                val destination = controller?.destination?.value ?: return Precondition(NO_DESTINATION)
                destination.poiId ?: return Precondition(AmapLiveInfoParser.NO_RESULTS)
            }
            else -> {
                val position = wanted.toDoubleOrNull()?.toInt() ?: return Precondition(INVALID_ARGUMENT)
                val candidates = controller?.destinationCandidates?.value.orEmpty()
                if (candidates.isEmpty()) return Precondition("NO_OPTIONS_ON_SCREEN")
                val candidate = candidates.getOrNull(position - 1) ?: return Precondition("OUT_OF_RANGE")
                candidate.poiId ?: return Precondition(AmapLiveInfoParser.NO_RESULTS)
            }
        }
        return Ready {
            when (val detail = rest.placeDetail(poiId, key)) {
                is LiveInfoFetch.Failed -> failure(detail.code)
                is LiveInfoFetch.Ok -> success(PLACE_DETAILS)
                    .putOpt("name", detail.value.name)
                    .putOpt("address", detail.value.address)
                    .putOpt("phone", detail.value.phone)
                    .putOpt("opening_hours", detail.value.openingHours)
                    .putOpt("rating", detail.value.rating)
                    .putOpt("category", detail.value.category)
                    .put("reported_at", clockText())
            }
        }
    }

    private fun success(kind: String) = JSONObject().put("ok", true).put("kind", kind)

    /** Same shape as the dispatcher's own failures, so the guards read it the same way. */
    private fun failure(code: String) = JSONObject().put("ok", false).put("error", code)
        .apply { ToolFailureAdvice.forCode(code)?.let { put("next", it) } }

    companion object {
        const val TOOL = "query_live_info"
        const val WEATHER = "weather"
        const val ROUTE_TRAFFIC = "route_traffic"
        const val ALONG_ROUTE = "along_route"
        const val PLACE_DETAILS = "place_details"
        val KINDS = listOf(WEATHER, ROUTE_TRAFFIC, ALONG_ROUTE, PLACE_DETAILS)

        const val HERE = "here"
        const val DESTINATION = "destination"

        const val AMAP_WEB_KEY_MISSING = "AMAP_WEB_KEY_MISSING"
        const val NO_LOCATION = "NO_LOCATION"
        const val NO_DESTINATION = "NO_DESTINATION"
        const val NOT_NAVIGATING = "NOT_NAVIGATING"
        const val INVALID_KIND = "INVALID_KIND"
        const val INVALID_ARGUMENT = "INVALID_ARGUMENT"

        const val TIMEOUT_MS = 4_000L
        const val WEATHER_TTL_MS = 10 * 60 * 1000L
        private const val MAX_CITY = 20
        private const val MAX_ALONG_ROUTE = 5

        /** A calculated route exists in the SDK: the route picker, or guidance. */
        private val ROUTE_ON_SCREEN = setOf(
            NavigationPhase.AWAITING_ROUTE_SELECTION,
            NavigationPhase.ROUTE_READY,
            NavigationPhase.NAVIGATING,
        )

        /** The production wiring; MainActivity and the debug receiver build the same one. */
        fun live(context: android.content.Context): LiveInfoTool {
            val app = context.applicationContext
            return LiveInfoTool(
                webKey = { AmapSettingsRepository(app).loadWebKey() },
                rest = AmapPoiClient(),
                location = { CoarseLocationProvider(app).lastKnown() },
                navigation = { com.novadrive.app.nav.EmbeddedNavigation.shared(app) },
                route = com.novadrive.app.nav.amap.AmapRouteLiveInfo(app),
            )
        }

        /** A REST source that is never reachable. */
        fun noRest(): AmapLiveInfoRest = object : AmapLiveInfoRest {
            private val nothing = LiveInfoFetch.Failed(AmapLiveInfoParser.UNAVAILABLE)
            override fun weatherNow(city: String, key: String) = nothing
            override fun weatherForecast(city: String, key: String) = nothing
            override fun regeoAdcode(latitude: Double, longitude: Double, key: String) = nothing
            override fun placeDetail(poiId: String, key: String) = nothing
        }

        /** For call sites with no Amap at all (tests, simulations): every lookup is unavailable. */
        fun none(): LiveInfoTool = LiveInfoTool(
            webKey = { null },
            rest = noRest(),
            location = { null },
            navigation = { null },
            route = RouteLiveInfoSource.NONE,
            log = {},
        )
    }
}
