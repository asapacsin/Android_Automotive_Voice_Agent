package com.novadrive.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-011 A1: each REST response parses from a fixture recorded live from restapi.amap.com on
 * 2026-09-25 (coordinates replaced with fixed values, no key), and a field the source did not send
 * stays missing. `error_quota_synthetic.json` is the one hand-written fixture: a quota response
 * cannot be provoked on purpose without exhausting the owner's key.
 */
class AmapLiveInfoParserTest {
    private val server = MockWebServer()

    @AfterEach
    fun close() = server.close()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/amap/$name.json")) { "missing fixture $name" }.readText()

    @Test
    fun liveWeatherParsesEveryFieldTheSourceSent() {
        val now = (AmapLiveInfoParser.weatherNow(fixture("weather_base")) as LiveInfoFetch.Ok).value
        assertEquals(
            WeatherNow(
                city = "东城区",
                weather = "多云",
                temperatureC = 24,
                windDirection = "南",
                windPower = "≤3",
                humidity = 66,
                reportedAt = "14:39",
            ),
            now,
        )
    }

    @Test
    fun forecastParsesEachDay() {
        val forecast = (AmapLiveInfoParser.weatherForecast(fixture("weather_all")) as LiveInfoFetch.Ok).value
        assertEquals("东城区", forecast.city)
        assertEquals("14:39", forecast.reportedAt)
        assertEquals(4, forecast.days.size)
        assertEquals(WeatherDay("2026-09-26", "小雨", "小雨", 24, 17), forecast.days[1])
    }

    @Test
    fun anUnknownCityIsNoResultsNotAnEmptyForecast() {
        // Recorded with city=999999: status=1, count=1, and `lives: [[]]`.
        assertEquals(LiveInfoFetch.Failed("NO_RESULTS"), AmapLiveInfoParser.weatherNow(fixture("weather_empty")))
    }

    @Test
    fun missingFieldsStayMissing() {
        val body = """{"status":"1","infocode":"10000","lives":[{"city":"珠海市","weather":"晴","temperature":"",""" +
            """"winddirection":[],"reporttime":"2026-09-25 15:00:19"}]}"""
        val now = (AmapLiveInfoParser.weatherNow(body) as LiveInfoFetch.Ok).value
        assertEquals("晴", now.weather)
        assertNull(now.temperatureC, "an empty temperature must not become 0")
        assertNull(now.windDirection, "Amap's [] for an absent string must not become \"[]\"")
        assertNull(now.windPower)
        assertNull(now.humidity)
    }

    @Test
    fun regeoYieldsOnlyTheAdcode() {
        assertEquals(LiveInfoFetch.Ok("110101"), AmapLiveInfoParser.regeoAdcode(fixture("regeo")))
    }

    @Test
    fun placeDetailKeepsWhatADriverAsksAbout() {
        val detail = (AmapLiveInfoParser.placeDetail(fixture("place_detail")) as LiveInfoFetch.Ok).value
        assertEquals("北京南站", detail.name)
        assertEquals("永外大街车站路12号", detail.address)
        assertEquals("010-51867182", detail.phone)
        assertEquals("周一至周日 05:30-24:00", detail.openingHours)
        assertNull(detail.rating, "rating was [] in the recording")
        assertEquals("火车站", detail.category)
    }

    @Test
    fun anInvalidKeyIsUnavailableAndQuotaIsItsOwnCode() {
        assertEquals(LiveInfoFetch.Failed("LIVE_INFO_UNAVAILABLE"), AmapLiveInfoParser.weatherNow(fixture("error_invalid_key")))
        assertEquals(LiveInfoFetch.Failed("LIVE_INFO_QUOTA"), AmapLiveInfoParser.weatherNow(fixture("error_quota_synthetic")))
        assertEquals(
            LiveInfoFetch.Failed("LIVE_INFO_QUOTA"),
            AmapLiveInfoParser.placeDetail("""{"status":"0","info":"USER_DAILY_QUERY_OVER_LIMIT","infocode":"10044"}"""),
        )
        assertEquals(LiveInfoFetch.Failed("LIVE_INFO_UNAVAILABLE"), AmapLiveInfoParser.weatherNow("<html>"))
    }

    @Test
    fun noFixtureCarriesARealCoordinate() {
        listOf("weather_base", "weather_all", "weather_empty", "regeo", "place_detail").forEach { name ->
            val coordinates = Regex("\\d{2,3}\\.\\d+,\\d{1,2}\\.\\d+").findAll(fixture(name)).map { it.value }.toSet()
            assertTrue(coordinates.all { it == "116.000000,39.000000" }, "$name: $coordinates")
        }
    }

    // ---- the client sends what the endpoint needs and maps transport failures ----

    private fun client() = AmapPoiClient(liveBaseUrl = server.url("/v3").toString().trimEnd('/'))

    @Test
    fun clientRequestsWeatherAndDetailWithTheRightParameters() {
        server.enqueue(MockResponse().setBody(fixture("weather_all")))
        server.enqueue(MockResponse().setBody(fixture("place_detail")))
        assertTrue(client().weatherForecast("440400", "test-key") is LiveInfoFetch.Ok)
        assertTrue(client().placeDetail("B000A83AJN", "test-key") is LiveInfoFetch.Ok)
        val weather = server.takeRequest().requestUrl!!
        assertEquals("/v3/weather/weatherInfo", weather.encodedPath)
        assertEquals("440400", weather.queryParameter("city"))
        assertEquals("all", weather.queryParameter("extensions"))
        assertEquals("test-key", weather.queryParameter("key"))
        val detail = server.takeRequest().requestUrl!!
        assertEquals("/v3/place/detail", detail.encodedPath)
        assertEquals("B000A83AJN", detail.queryParameter("id"))
    }

    @Test
    fun clientMapsHttpErrorsToUnavailable() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(LiveInfoFetch.Failed("LIVE_INFO_UNAVAILABLE"), client().weatherNow("110101", "k"))
    }

    @Test
    fun searchResultsCarryTheirAdcode() {
        val body = """{"status":"1","pois":[{"id":"B1","name":"珠海站","location":"113.550000,22.210000","adcode":"440402"},""" +
            """{"id":"B2","name":"无码","location":"113.550000,22.210000","adcode":[]}]}"""
        val candidates = AmapPoiParser.parseCandidates(body)
        assertEquals("440402", candidates[0].adcode)
        assertEquals("440402", candidates[0].toDestination().adcode)
        assertFalse(candidates[1].adcode != null, "[] is not an adcode")
    }
}
