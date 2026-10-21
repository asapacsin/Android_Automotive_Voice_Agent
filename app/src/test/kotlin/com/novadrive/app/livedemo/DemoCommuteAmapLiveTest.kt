package com.novadrive.app.livedemo

import com.novadrive.app.AmapPoiClient
import com.novadrive.app.LiveInfoTool
import com.novadrive.app.nav.RouteLiveInfoSource
import com.novadrive.app.nav.LiveDestinationCandidateSource
import com.novadrive.app.nav.LocationFix
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
            platformFix = { LocationFix(HengqinWorld.ORIGIN_LAT, HengqinWorld.ORIGIN_LON) },
            toGcj02 = { it.copy(latitude = HengqinWorld.ORIGIN_GCJ_LAT, longitude = HengqinWorld.ORIGIN_GCJ_LON) },
            webKey = { key },
            savedPlaces = { null },
        )
        val candidates = source.resolve("横琴镇")
        assertTrue(candidates.isNotEmpty(), "导航去横琴镇 found no place")
        val first = candidates.first() // 第一个
        println("c_pick -> ${first.name} ${first.distanceMeters} m (of ${candidates.size})")
        assertTrue("横琴" in first.name, "第一个 is not a 横琴 place: ${first.name}")
        // c3-c5 are six driver turns (about 15 s each with the take overheads): about 150 s, 1.7 km at
        // the emulator's 40 km/h. The route is never shorter than the straight line, so a destination
        // under 1.7 km away ends the drive first. Measured 2026-10-12 from the GCJ origin: 2045 m in a
        // straight line, a 2855 m route (257 s).
        val metres = requireNotNull(first.distanceMeters) { "the around search gave no distance" }
        assertTrue(metres >= 1_700, "第一个 is only $metres m away: the drive ends before 前面堵不堵")
    }

    @Test
    fun theWeatherLineHasItsWarmUpAnswerAtTheOrigin() {
        assumeLive()
        // The app's own session-start warm-up (district, then today's weather, each retried), as the
        // emulator runs it at the origin, so 今天天气怎么样 is answered from the cache.
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        LiveInfoTool(
            webKey = { key },
            rest = AmapPoiClient(),
            location = { HengqinWorld.ORIGIN_LAT to HengqinWorld.ORIGIN_LON },
            navigation = { null },
            route = RouteLiveInfoSource.NONE,
            log = { logs += it },
        ).warmHere()
        val deadline = System.currentTimeMillis() + 30_000
        while (logs.none { it.startsWith("live_info_warm kind=weather") || it == "live_info_warm kind=district ok=false" } &&
            System.currentTimeMillis() < deadline) Thread.sleep(50)
        println("warm: " + logs.filter { it.startsWith("live_info_warm") })
        assertTrue(logs.any { it.startsWith("live_info_warm kind=weather ok=true") }, "no warm weather at the origin: $logs")
    }
}
