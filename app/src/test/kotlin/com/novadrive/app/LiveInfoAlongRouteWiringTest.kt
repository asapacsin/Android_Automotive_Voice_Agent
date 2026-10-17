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
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-011 A6: `along_route` results open the **existing** picker, and 「第一个」 then takes the
 * existing, device-verified destination → route path. Production dispatcher, production executor,
 * production controller; only the SDK edges (engine, route search) are fakes.
 */
class LiveInfoAlongRouteWiringTest {
    private val engine = FakeNaviEngine()
    private val navigation = EmbeddedNavigationController(resolver = engine, engine = engine)
    private val searched = mutableListOf<AlongRouteCategory>()

    private val stations = listOf(
        station("s1", "中石化新港加油站", 1_200),
        station("s2", "中石油唐家加油站", 5_400),
        station("s3", "壳牌金鼎加油站", 9_800),
    )

    private val route = object : RouteLiveInfoSource {
        override fun traffic(): RouteTraffic? = null
        override suspend fun alongRoute(category: AlongRouteCategory): List<DestinationCandidate> {
            searched += category
            return stations
        }
    }

    private val dispatcher = AndroidToolDispatcher(
        CoreActionExecutor(navigationFlow = navigation, music = { error("unused") }),
        ClimateToolHandler(SimulatedVehicleControl()),
        noCamera(),
        liveInfo = LiveInfoTool(
            webKey = { "k" },
            rest = LiveInfoTool.noRest(),
            location = { null },
            navigation = { navigation },
            route = route,
            log = {},
        ),
    ) { null }

    private fun call(name: String, args: Map<String, String>) = DomainVoiceEvent.ToolCall("c", name, args)

    @Test
    fun alongRouteResultsOpenThePickerAndTheFirstOneIsNavigable() = runBlocking {
        engine.results = listOf(station("d", "珠海站", 30_000))
        engine.routes = listOf(RouteCandidate(10, 30_000, 2_400))
        navigation.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10))
        navigation.selectRoute(10)
        assertEquals(NavigationPhase.NAVIGATING, navigation.state().value)

        val lookup = dispatcher.dispatch(call(LiveInfoTool.TOOL, mapOf("kind" to "along_route", "category" to "fuel")))
        val output = JSONObject(lookup.deferredOutput!!())
        assertTrue(output.getBoolean("ok"), output.toString())
        assertEquals("shown_on_screen", output.getString("status"))
        assertEquals(listOf(AlongRouteCategory.FUEL), searched)
        assertEquals(3, output.getJSONArray("options_on_screen").length())

        // The same picker a spoken search fills.
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, navigation.state().value)
        assertEquals(stations.map { it.id }, navigation.destinationCandidates.value.map { it.id })
        // Owner test 10-16: showing the list is a question about the route, not a new destination -
        // the drive keeps going until a station is picked (nav demo runs lost the route here).
        assertEquals(0, engine.stopNaviCount, "the list alone must not end the drive")

        // 「第一个」 arrives as choose_navigation_option{index=1}: the existing path.
        val pick = dispatcher.dispatch(call(AndroidToolDispatcher.CHOOSE_NAVIGATION_OPTION, mapOf("index" to "1")))
        assertEquals(null, pick.blockedReason, pick.output)
        assertEquals(1, engine.stopNaviCount, "picking a station ends the old guidance, as navigate_to does")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, navigation.state().value)
        assertEquals("中石化新港加油站", engine.calculateCalls.last().endName)
    }

    private fun drive() = runBlocking {
        engine.results = listOf(station("d", "珠海站", 30_000))
        engine.routes = listOf(RouteCandidate(10, 30_000, 2_400))
        navigation.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10))
        navigation.selectRoute(10)
        val lookup = dispatcher.dispatch(call(LiveInfoTool.TOOL, mapOf("kind" to "along_route", "category" to "fuel")))
        lookup.deferredOutput!!()
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, navigation.state().value)
    }

    @Test
    fun withTheListOpenTheRouteToolsStillSeeTheDrive() = runBlocking {
        drive()
        val traffic = dispatcher.dispatch(call(LiveInfoTool.TOOL, mapOf("kind" to "route_traffic")))
        val trafficOut = traffic.output ?: traffic.deferredOutput!!()
        assertTrue(!trafficOut.contains("NOT_NAVIGATING"), trafficOut)
        val again = dispatcher.dispatch(call(LiveInfoTool.TOOL, mapOf("kind" to "along_route", "category" to "fuel")))
        val againOut = again.output ?: again.deferredOutput!!()
        assertTrue(JSONObject(againOut).getBoolean("ok"), againOut)
        assertEquals(0, engine.stopNaviCount, "a second along-route list keeps the drive")
        assertTrue(navigation.isGuiding())
    }

    @Test
    fun exitNavigationModeWithTheListOpenKeepsTheSpeechMute() = runBlocking {
        try {
            drive()
            assertTrue(NavigationState.navigating)
            dispatcher.dispatch(call("exit_navigation_mode", emptyMap()))
            assertEquals(NavigationPhase.NAVIGATING, navigation.state().value)
            assertTrue(NavigationState.navigating, "the drive continues, so the mute stays")
            assertEquals(0, engine.stopNaviCount)
        } finally {
            NavigationState.reset()
        }
    }

    @Test
    fun withoutARouteThePickerIsLeftAlone() {
        val result = dispatcher.dispatch(call(LiveInfoTool.TOOL, mapOf("kind" to "along_route", "category" to "fuel")))
        assertEquals("NOT_NAVIGATING", JSONObject(result.output!!).getString("error"))
        assertTrue(searched.isEmpty())
        assertEquals(NavigationPhase.IDLE, navigation.state().value)
    }

    private fun station(id: String, name: String, meters: Int) = DestinationCandidate(
        id = id,
        name = name,
        address = "",
        district = "",
        latitude = 22.25,
        longitude = 113.55,
        distanceMeters = meters,
        poiId = id,
    )
}
