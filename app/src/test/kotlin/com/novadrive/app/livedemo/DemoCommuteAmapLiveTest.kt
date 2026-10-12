package com.novadrive.app.livedemo

import com.novadrive.app.AmapPoiClient
import com.novadrive.app.LiveInfoFetch
import com.novadrive.app.nav.LiveDestinationCandidateSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The commute demo's real Amap lookups, before a local recording spends any Qwen quota
 * (docs/DEMO_COMMUTE.md). The emulator take uses the real place search, so a search that answers the
 * wrong place breaks the take no matter how well she speaks; the JVM harness simulates the route and
 * never saw it. Measured 2026-10-12: with `sortrule=distance`, 「导航去横琴镇」 + 「第一个」 chose
 * 横琴澳门青年创业谷 - the origin, a 26 m route - so the drive ended before c3-c5 were spoken.
 *
 * Free Amap Web API calls only (no voice model): runs with NOVA_LIVE_AMAP=1 and AMAP_WEB_KEY set.
 */
class DemoCommuteAmapLiveTest {
    private val key: String? = System.getenv("AMAP_WEB_KEY")?.takeIf { it.isNotBlank() }

    private fun assumeLive() {
        assumeTrue(System.getenv("NOVA_LIVE_AMAP") == "1", "live Amap checks run only with NOVA_LIVE_AMAP=1")
        assumeTrue(key != null, "AMAP_WEB_KEY is not set")
    }

    @Test
    fun navigateToHengqinTownPicksAPlaceFarEnoughToDriveThroughTheMiddleTakes() = runBlocking {
        assumeLive()
        // The app's own search, with the fix the recorder sets before the app starts (WGS-84, as the
        // platform reports it).
        val source = LiveDestinationCandidateSource(
            poiClient = AmapPoiClient(),
            location = { HengqinWorld.ORIGIN_LAT to HengqinWorld.ORIGIN_LON },
            webKey = { key },
            savedPlaces = { null },
        )
        val candidates = source.resolve("横琴镇")
        assertTrue(candidates.isNotEmpty(), "导航去横琴镇 found no place")
        val first = candidates.first() // 第一个
        println("c_pick -> ${first.name} ${first.distanceMeters} m (of ${candidates.size})")
        assertTrue("横琴" in first.name, "第一个 is not a 横琴 place: ${first.name}")
        // c3-c5 are six driver turns (about 15 s each with the take overheads): about 150 s, 1.7 km at
        // the emulator's 40 km/h. A destination nearer than 2 km in a straight line ends the drive first.
        val metres = requireNotNull(first.distanceMeters) { "the around search gave no distance" }
        assertTrue(metres >= 2_000, "第一个 is only $metres m away: the drive ends before 前面堵不堵")
    }

    @Test
    fun theWeatherLineHasItsWarmUpAnswerAtTheOrigin() {
        assumeLive()
        // LiveInfoTool.warmHere: district first, then the weather it caches for 今天天气怎么样.
        val client = AmapPoiClient()
        val place = client.regeoPlace(HengqinWorld.ORIGIN_LAT, HengqinWorld.ORIGIN_LON, key!!)
        assertTrue(place is LiveInfoFetch.Ok, "no district at the origin: $place")
        val weather = client.weatherNow((place as LiveInfoFetch.Ok).value.adcode, key)
        assertTrue(weather is LiveInfoFetch.Ok && weather.value.weather != null, "no weather at the origin: $weather")
    }
}
