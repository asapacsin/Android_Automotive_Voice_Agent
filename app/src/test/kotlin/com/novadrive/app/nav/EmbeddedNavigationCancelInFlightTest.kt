package com.novadrive.app.nav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D-CANCEL-SEARCH, SPEC-006 CVC-36 / CVC-46: 「算了，不去了」 while a search or calculation is in flight. */
class EmbeddedNavigationCancelInFlightTest {
    private fun candidate(id: String, name: String) = DestinationCandidate(
        id = id, name = name, address = "地址", district = "香洲区",
        latitude = 22.2, longitude = 113.5, distanceMeters = 100, poiId = id,
    )

    private val three = listOf(candidate("a", "万达广场"), candidate("b", "万达影城"), candidate("c", "万达酒店"))
    private val routes = listOf(RouteCandidate(10, 18_200, 29 * 60, labels = "高速优先", trafficLightCount = 3))

    @Test
    fun cancelWhileResolvingDropsTheLateSearchResult() = runBlocking {
        val engine = FakeNaviEngine()
        val gate = CompletableDeferred<List<DestinationCandidate>>()
        val started = CompletableDeferred<Unit>()
        val controller = EmbeddedNavigationController(
            resolver = { started.complete(Unit); gate.await() },
            engine = engine,
        )
        val search = async(Dispatchers.Default) { controller.requestDestination("万达") }
        withTimeout(5_000) { started.await() }
        assertEquals(NavigationPhase.RESOLVING_DESTINATION, controller.state().value)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice())
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        gate.complete(three)
        withTimeout(5_000) { search.await() }
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        assertTrue(controller.destinationCandidates.value.isEmpty())
        assertTrue(engine.calculateCalls.isEmpty())
        // CVC-46: an ordinal after the cancel picks nothing.
        val pick = controller.chooseByVoice(NavigationChoice.Index(1))
        assertTrue(pick is EmbeddedNavigationController.VoiceChoiceResult.Rejected, "got $pick")
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        assertTrue(engine.calculateCalls.isEmpty())
    }

    @Test
    fun cancelWhileCalculatingDropsTheLateRouteSuccessAndFailure() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = routes
        engine.results = listOf(candidate("a", "珠海站"))
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice())
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        engine.emitSuccess(intArrayOf(10))
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        assertTrue(controller.routeCandidates.value.isEmpty())
        engine.emitFailure(3)
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        val pick = controller.chooseByVoice(NavigationChoice.Index(1))
        assertTrue(pick is EmbeddedNavigationController.VoiceChoiceResult.Rejected, "got $pick")
        assertEquals(0, engine.startNaviCount)
    }

    @Test
    fun aNewCalculationAfterCancelStillReceivesItsRoutes() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = routes
        engine.results = listOf(candidate("a", "珠海站"))
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        controller.endByVoice()
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10))
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, controller.state().value)
        assertEquals(1, controller.routeCandidates.value.size)
    }

    @Test
    fun planningRouteAndRouteReadyAreCancelledToIdle() = runBlocking {
        for (phase in listOf(NavigationPhase.PLANNING_ROUTE, NavigationPhase.ROUTE_READY)) {
            val store = NavigationStateStore()
            val controller = EmbeddedNavigationController(store = store, resolver = FakeNaviEngine(), engine = FakeNaviEngine())
            store.update(phase)
            assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice(), "$phase")
            assertEquals(NavigationPhase.IDLE, controller.state().value, "$phase")
        }
    }

    @Test
    fun uiCancelWhileCalculatingAlsoEndsTheFlow() = runBlocking {
        val engine = FakeNaviEngine()
        engine.results = listOf(candidate("a", "珠海站"))
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        controller.cancel()
        assertEquals(NavigationPhase.IDLE, controller.state().value)
    }

    @Test
    fun searchInFlightUnderKeptGuidanceCancelsBackToTheDrive() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = routes
        engine.results = listOf(candidate("a", "十字门"))
        val store = NavigationStateStore()
        val controller = EmbeddedNavigationController(store = store, resolver = engine, engine = engine)
        controller.requestDestination("十字门")
        engine.emitSuccess(intArrayOf(10))
        controller.selectRoute(10)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        controller.requestCandidates(listOf(candidate("s1", "加油站一"), candidate("s2", "加油站二")))
        // requestCandidates resolves synchronously; stand in for the in-flight moment of the kept flow.
        store.update(NavigationPhase.RESOLVING_DESTINATION)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice())
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        assertEquals(0, engine.stopNaviCount)
        assertTrue(controller.destinationCandidates.value.isEmpty())
    }

    @Test
    fun idleAndPlainNavigatingAreUnchanged() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = routes
        engine.results = listOf(candidate("a", "十字门"))
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.NOTHING_ACTIVE, controller.endByVoice())
        controller.requestDestination("十字门")
        engine.emitSuccess(intArrayOf(10))
        controller.selectRoute(10)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.STOPPED_NAVIGATION, controller.endByVoice())
    }
}
