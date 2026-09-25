package com.novadrive.app

import com.novadrive.app.nav.AlongRouteCategory
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.FakeNaviEngine
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.nav.RouteCandidate
import com.novadrive.app.nav.RouteLiveInfoSource
import com.novadrive.app.nav.RouteTraffic
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.DriverContext
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-011 A2: every row of the failure table yields its code, through the production dispatcher,
 * and a success carries only what the source returned. REST paths use the real [AmapPoiClient]
 * against a local server replaying the recorded fixtures.
 */
class LiveInfoToolTest {
    private val server = MockWebServer()
    private val logs = mutableListOf<String>()

    @AfterEach
    fun close() = server.close()

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/amap/$name.json")).readText()

    private class Executor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Accepted()
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted()
        override fun playMusic() = AndroidActionResult.Accepted()
        override fun stopMusic() = AndroidActionResult.Accepted()
        override fun exitNavigationMode() = AndroidActionResult.Accepted()
    }

    private class FakeRoute(
        var traffic: RouteTraffic? = null,
        var along: List<DestinationCandidate>? = emptyList(),
    ) : RouteLiveInfoSource {
        override fun traffic() = traffic
        override suspend fun alongRoute(category: AlongRouteCategory) = along
    }

    private var clock = 0L
    private val route = FakeRoute()
    private val engine = FakeNaviEngine()
    private val navigation = EmbeddedNavigationController(resolver = engine, engine = engine)

    private fun tool(
        key: String? = "test-key",
        fix: Pair<Double, Double>? = 22.2 to 113.5,
        rest: AmapLiveInfoRest = AmapPoiClient(liveBaseUrl = server.url("/v3").toString().trimEnd('/')),
        timeoutMs: Long = LiveInfoTool.TIMEOUT_MS,
    ) = LiveInfoTool(
        webKey = { key },
        rest = rest,
        location = { fix },
        navigation = { navigation },
        route = route,
        nowMs = { clock },
        clockText = { "15:00" },
        log = { logs += it },
        timeoutMs = timeoutMs,
    )

    private fun dispatcher(tool: LiveInfoTool, context: DriverContext? = null) = AndroidToolDispatcher(
        Executor(),
        ClimateToolHandler(SimulatedVehicleControl()),
        noCamera(),
        liveInfo = tool,
    ) { context }

    private fun call(vararg args: Pair<String, String>) =
        DomainVoiceEvent.ToolCall("call_1", LiveInfoTool.TOOL, mapOf(*args))

    /** The JSON the model receives, whether the tool refused at once or after the lookup. */
    private fun output(result: ToolDispatchResult): JSONObject =
        JSONObject(result.output ?: runBlocking { result.deferredOutput!!() })

    private fun run(tool: LiveInfoTool, vararg args: Pair<String, String>) = output(dispatcher(tool).dispatch(call(*args)))

    private fun assertFails(code: String, result: JSONObject) {
        assertFalse(result.getBoolean("ok"), result.toString())
        assertEquals(code, result.getString("error"), result.toString())
        assertTrue(result.optString("next").isNotBlank(), "every code needs ToolFailureAdvice: $code")
        assertEquals(LiveInfoTool.TOOL, result.getString("tool"))
    }

    private fun startNavigation(destination: DestinationCandidate = candidate("d", "北京南站", poiId = "B000A83AJN")) = runBlocking {
        engine.results = listOf(destination)
        engine.routes = listOf(RouteCandidate(10, 18_000, 1_800))
        navigation.requestDestination("x")
        engine.emitSuccess(intArrayOf(10))
        navigation.selectRoute(10)
        assertEquals(NavigationPhase.NAVIGATING, navigation.state().value)
    }

    // ---- the failure table -----------------------------------------------------

    @Test
    fun noWebKey() {
        assertFails("AMAP_WEB_KEY_MISSING", run(tool(key = null), "kind" to "weather"))
        assertFails("AMAP_WEB_KEY_MISSING", run(tool(key = " "), "kind" to "place_details"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noLocationForHere() {
        assertFails("NO_LOCATION", run(tool(fix = null), "kind" to "weather", "where" to "here"))
        assertFails("NO_LOCATION", run(tool(fix = null), "kind" to "weather"))
    }

    @Test
    fun destinationWeatherWithNoDestination() {
        assertFails("NO_DESTINATION", run(tool(), "kind" to "weather", "where" to "destination"))
        assertFails("NO_DESTINATION", run(tool(), "kind" to "place_details", "target" to "destination"))
    }

    @Test
    fun routeKindsWithoutARoute() {
        assertFails("NOT_NAVIGATING", run(tool(), "kind" to "route_traffic"))
        assertFails("NOT_NAVIGATING", run(tool(), "kind" to "along_route", "category" to "fuel"))
    }

    @Test
    fun timeoutIsUnavailableNeverAGuess() {
        val slow = object : AmapLiveInfoRest by LiveInfoTool.noRest() {
            override fun weatherNow(city: String, key: String): LiveInfoFetch<WeatherNow> {
                Thread.sleep(2_000)
                return LiveInfoFetch.Ok(WeatherNow("珠海市", "晴", 30, null, null, null, "15:00"))
            }
        }
        val started = System.nanoTime()
        assertFails("LIVE_INFO_UNAVAILABLE", run(tool(rest = slow, timeoutMs = 200), "kind" to "weather", "where" to "珠海"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "the deadline must not wait for the blocking read")
    }

    @Test
    fun httpErrorAndBadStatusAreUnavailable() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertFails("LIVE_INFO_UNAVAILABLE", run(tool(), "kind" to "weather", "where" to "珠海"))
        server.enqueue(MockResponse().setBody(fixture("error_invalid_key")))
        assertFails("LIVE_INFO_UNAVAILABLE", run(tool(), "kind" to "weather", "where" to "上海"))
    }

    @Test
    fun dailyQuota() {
        server.enqueue(MockResponse().setBody(fixture("error_quota_synthetic")))
        assertFails("LIVE_INFO_QUOTA", run(tool(), "kind" to "weather", "where" to "珠海"))
    }

    @Test
    fun zeroResults() {
        server.enqueue(MockResponse().setBody(fixture("weather_empty")))
        assertFails("NO_RESULTS", run(tool(), "kind" to "weather", "where" to "不存在"))
        startNavigation()
        route.along = emptyList()
        val none = run(tool(), "kind" to "along_route", "category" to "fuel")
        assertFails("NO_RESULTS", none)
        assertEquals("加油站", none.getString("category"), "the driver hears 沿途没有找到加油站")
    }

    @Test
    fun calledTwiceInOneTurn() {
        server.enqueue(MockResponse().setBody(fixture("weather_base")))
        val context = DriverContext()
        context.onDriverUtterance("北京天气怎么样", epoch = 1)
        val dispatcher = dispatcher(tool(), context)
        assertTrue(output(dispatcher.dispatch(call("kind" to "weather", "where" to "北京"))).getBoolean("ok"))
        assertFails("DUPLICATE_IN_TURN", output(dispatcher.dispatch(call("kind" to "weather", "where" to "北京"))))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun invalidKindAndArguments() {
        assertFails("INVALID_KIND", run(tool(), "kind" to "news"))
        assertFails("INVALID_ARGUMENT", run(tool(), "kind" to "weather", "day" to "yesterday"))
        assertFails("INVALID_ARGUMENT", run(tool(), "kind" to "along_route", "category" to "hotel"))
    }

    // ---- successes carry only what the source returned ---------------------------

    @Test
    fun weatherHereReverseGeocodesThenReportsTheSourcesFields() {
        server.enqueue(MockResponse().setBody(fixture("regeo")))
        server.enqueue(MockResponse().setBody(fixture("weather_base")))
        val result = run(tool(), "kind" to "weather")
        assertTrue(result.getBoolean("ok"))
        assertEquals("weather", result.getString("kind"))
        assertEquals("这里", result.getString("where"))
        assertEquals("多云", result.getString("now"))
        assertEquals(24, result.getInt("temp_c"))
        assertEquals("南风≤3级", result.getString("wind"))
        assertEquals("14:39", result.getString("reported_at"))
        assertFalse(result.getBoolean("cached"))
        assertEquals("/v3/geocode/regeo", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("110101", server.takeRequest().requestUrl!!.queryParameter("city"))
    }

    @Test
    fun aMissingFieldIsLeftOutNotFilledIn() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"1","infocode":"10000","lives":[{"city":"珠海市","weather":"晴","temperature":"","winddirection":[]}]}""",
            ),
        )
        val result = run(tool(), "kind" to "weather", "where" to "珠海")
        assertTrue(result.getBoolean("ok"))
        listOf("temp_c", "wind", "humidity_pct", "reported_at").forEach { assertFalse(result.has(it), "$it was invented") }
    }

    @Test
    fun weatherIsCachedTenMinutesPerCity() {
        server.enqueue(MockResponse().setBody(fixture("weather_base")))
        server.enqueue(MockResponse().setBody(fixture("weather_base")))
        val tool = tool()
        assertFalse(run(tool, "kind" to "weather", "where" to "北京").getBoolean("cached"))
        clock += 9 * 60_000
        assertTrue(run(tool, "kind" to "weather", "where" to "北京").getBoolean("cached"))
        assertEquals(1, server.requestCount)
        clock += 2 * 60_000
        assertFalse(run(tool, "kind" to "weather", "where" to "北京").getBoolean("cached"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun tomorrowUsesTheForecastAndDestinationUsesItsAdcode() {
        startNavigation(candidate("d", "珠海站", adcode = "440402"))
        server.enqueue(MockResponse().setBody(fixture("weather_all")))
        val result = run(tool(), "kind" to "weather", "where" to "destination", "day" to "tomorrow")
        assertEquals("目的地", result.getString("where"))
        assertEquals("小雨", result.getString("day_weather"))
        assertEquals(24, result.getInt("high_c"))
        assertEquals(17, result.getInt("low_c"))
        val request = server.takeRequest().requestUrl!!
        assertEquals("440402", request.queryParameter("city"), "no reverse-geocode when the POI had an adcode")
        assertEquals("all", request.queryParameter("extensions"))
    }

    @Test
    fun routeTrafficReportsCountsAndDistanceNeverCoordinates() {
        startNavigation()
        route.traffic = RouteTraffic.summarize(listOf(1 to 1_000, 2 to 300, 3 to 400, 1 to 2_000, 4 to 100))
        val result = run(tool(), "kind" to "route_traffic")
        assertTrue(result.getBoolean("ok"))
        assertEquals(2, result.getInt("congested_segments"))
        assertEquals(1, result.getInt("slow_segments"))
        assertEquals(1_300, result.getInt("first_congestion_m"))
        assertEquals("15:00", result.getString("reported_at"))
        assertFalse(result.toString().contains("lat") || result.toString().contains("location"))
        route.traffic = null
        assertFails("LIVE_INFO_UNAVAILABLE", run(tool(), "kind" to "route_traffic"))
    }

    @Test
    fun placeDetailsOfTheDestination() {
        startNavigation()
        server.enqueue(MockResponse().setBody(fixture("place_detail")))
        val result = run(tool(), "kind" to "place_details", "target" to "destination")
        assertEquals("周一至周日 05:30-24:00", result.getString("opening_hours"))
        assertFalse(result.has("rating"))
        assertEquals("B000A83AJN", server.takeRequest().requestUrl!!.queryParameter("id"))
    }

    @Test
    fun logsCarryOnlyKindOkCodeMsAndCached() {
        server.enqueue(MockResponse().setBody(fixture("weather_base")))
        run(tool(), "kind" to "weather", "where" to "北京")
        run(tool(), "kind" to "上海的天气")
        assertEquals(
            listOf("live_info kind=weather ok=true code=none ms=0 cached=false", "live_info kind=invalid ok=false code=INVALID_KIND ms=0 cached=false"),
            logs,
        )
        assertNull(logs.firstOrNull { it.contains("北京") || it.contains("上海") || it.contains("110101") })
    }

    companion object {
        fun candidate(id: String, name: String, poiId: String? = null, adcode: String? = null) = DestinationCandidate(
            id = id,
            name = name,
            address = "",
            district = "",
            latitude = 22.2,
            longitude = 113.5,
            poiId = poiId,
            adcode = adcode,
        )
    }
}
