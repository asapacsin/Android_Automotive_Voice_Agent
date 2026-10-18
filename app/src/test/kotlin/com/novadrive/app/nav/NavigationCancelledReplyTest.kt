package com.novadrive.app.nav

import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.CoreActionExecutor
import com.novadrive.app.noCamera
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** D-CANCEL-STALE-REPLY, SPEC-006 CVC-36: the pending navigate_to result after 「算了，不去了」 is no stale 「请选择」. */
class NavigationCancelledReplyTest {
    private fun candidate(id: String, name: String) = DestinationCandidate(
        id = id, name = name, address = "地址", district = "香洲区",
        latitude = 22.2, longitude = 113.5, distanceMeters = 100, poiId = id,
    )

    private val routes = listOf(RouteCandidate(10, 18_200, 29 * 60, labels = "推荐"))

    /** Process-wide: a suppression left by another test would swallow navigate_to before the executor. */
    @BeforeEach
    fun clearLocalPick() = NavigationLocalPickGuard.invalidate()

    private fun reply(snapshot: EmbeddedNavigationController.OptionsSnapshot) =
        JSONObject(NavigationVoiceOutput.build("navigate_to", "awaiting_route_selection_on_screen", snapshot))

    private fun assertCancelledReply(out: JSONObject) {
        assertTrue(out.getBoolean("ok"))
        assertEquals("cancelled", out.getString("screen"), out.toString())
        assertEquals("cancelled_by_driver", out.getString("status"))
        val next = out.getString("next")
        assertFalse(next.contains("请选择") || next.contains("第几") || next.contains("请说"), next)
        assertFalse(out.has("options_on_screen"))
    }

    // ---- Through the real dispatch path: AndroidToolDispatcher -> NavigationServer -> CoreActionExecutor ----

    private fun dispatcher(nav: EmbeddedNavigationController) = AndroidToolDispatcher(
        CoreActionExecutor(navigationFlow = nav, music = { error("unused") }),
        ClimateToolHandler(SimulatedVehicleControl()),
        noCamera(),
    ) { null }

    private fun navigateTo(destination: String) =
        DomainVoiceEvent.ToolCall("call_nav_$destination", "navigate_to", mapOf("destination" to destination))

    private val exit = DomainVoiceEvent.ToolCall("call_exit", "exit_navigation_mode", emptyMap())

    @Test
    fun cancelDuringTheRequestWaitOfADispatchedNavigateToIsReportedCancelled() = runBlocking {
        val gate = CompletableDeferred<List<DestinationCandidate>>()
        val started = CompletableDeferred<Unit>()
        val nav = EmbeddedNavigationController(resolver = { started.complete(Unit); gate.await() }, engine = FakeNaviEngine())
        val tools = dispatcher(nav)
        val deferred = tools.dispatch(navigateTo("万达")).deferredOutput!!
        val output = async(Dispatchers.Default) { deferred() }
        withTimeout(5_000) { started.await() }
        tools.dispatch(exit)
        gate.complete(listOf(candidate("a", "万达广场"), candidate("b", "万达影城")))
        assertCancelledReply(JSONObject(withTimeout(10_000) { output.await() }))
    }

    @Test
    fun aCancelledFirstRequestDoesNotReportTheSecondRequestsList(): Unit = runBlocking {
        val gate = CompletableDeferred<List<DestinationCandidate>>()
        val started = CompletableDeferred<Unit>()
        val second = listOf(candidate("c", "珠海站北"), candidate("d", "珠海站南"))
        val nav = EmbeddedNavigationController(
            resolver = { query -> if (query == "万达") { started.complete(Unit); gate.await() } else second },
            engine = FakeNaviEngine(),
        )
        val tools = dispatcher(nav)
        val first = tools.dispatch(navigateTo("万达")).deferredOutput!!
        val firstOutput = async(Dispatchers.Default) { first() }
        withTimeout(5_000) { started.await() }
        tools.dispatch(exit)
        val secondOutput = JSONObject(withTimeout(10_000) { tools.dispatch(navigateTo("珠海站")).deferredOutput!!() })
        gate.complete(emptyList()) // A's search returns late; A's output may be waiting on it
        assertCancelledReply(JSONObject(withTimeout(10_000) { firstOutput.await() }))
        assertEquals("destination_list", secondOutput.getString("screen"), secondOutput.toString())
        assertTrue(secondOutput.getString("options_on_screen").contains("珠海站北"))
    }

    // ---- Controller level, deterministic: the mark is taken before the cancel ----

    @Test
    fun cancelDuringResolvingGivesCancelledNotAChoice() = runBlocking {
        val gate = CompletableDeferred<List<DestinationCandidate>>()
        val started = CompletableDeferred<Unit>()
        val controller = EmbeddedNavigationController(resolver = { started.complete(Unit); gate.await() }, engine = FakeNaviEngine())
        val search = async(Dispatchers.Default) { controller.requestDestination("万达") }
        withTimeout(5_000) { started.await() }
        val mark = controller.cancelMark()
        assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice())
        val snapshot = controller.awaitOptions(1_000, mark)
        assertTrue(snapshot.cancelled)
        assertCancelledReply(reply(snapshot))
        assertTrue(reply(snapshot).getString("next").contains("好的，已取消"))
        gate.complete(listOf(candidate("a", "万达广场"), candidate("b", "万达影城")))
        withTimeout(5_000) { search.await() }
    }

    @Test
    fun cancelDuringCalculatingGivesCancelledNotAChoice() = runBlocking {
        val engine = FakeNaviEngine().apply { this.routes = this@NavigationCancelledReplyTest.routes; results = listOf(candidate("a", "珠海站")) }
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
        val mark = controller.cancelMark()
        controller.endByVoice()
        val snapshot = controller.awaitOptions(1_000, mark)
        assertEquals(NavigationPhase.IDLE, snapshot.phase)
        assertTrue(snapshot.cancelled)
        assertCancelledReply(reply(snapshot))
    }

    @Test
    fun cancelWithKeptGuidanceSaysTheEarlierNavigationContinues() = runBlocking {
        val engine = FakeNaviEngine().apply { this.routes = this@NavigationCancelledReplyTest.routes; results = listOf(candidate("a", "十字门")) }
        val store = NavigationStateStore()
        val controller = EmbeddedNavigationController(store = store, resolver = engine, engine = engine)
        controller.requestDestination("十字门")
        engine.emitSuccess(intArrayOf(10))
        controller.selectRoute(10)
        controller.requestCandidates(listOf(candidate("s1", "加油站一"), candidate("s2", "加油站二")))
        store.update(NavigationPhase.RESOLVING_DESTINATION) // stand in for the in-flight moment of the kept flow
        val mark = controller.cancelMark()
        controller.endByVoice()
        val snapshot = controller.awaitOptions(1_000, mark)
        assertEquals(NavigationPhase.NAVIGATING, snapshot.phase)
        val out = reply(snapshot)
        assertCancelledReply(out)
        assertEquals("十字门", out.getString("destination"))
        assertTrue(out.getString("next").contains("原来的导航继续"))
    }

    @Test
    fun aSlowLoadIsStillLoadingNotCancelled() = runBlocking {
        val engine = FakeNaviEngine().apply { results = listOf(candidate("a", "珠海站")) }
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        val snapshot = controller.awaitOptions(50, controller.cancelMark())
        assertFalse(snapshot.cancelled)
        assertEquals("still_loading", reply(snapshot).getString("screen"))
    }

    @Test
    fun aCompletedLoadAfterAnEarlierCancelIsNotCancelled() = runBlocking {
        val engine = FakeNaviEngine().apply { this.routes = this@NavigationCancelledReplyTest.routes; results = listOf(candidate("a", "珠海站")) }
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        controller.cancel()
        val mark = controller.cancelMark()
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10))
        val snapshot = controller.awaitOptions(1_000, mark)
        assertFalse(snapshot.cancelled)
        assertEquals("route_list", reply(snapshot).getString("screen"))
    }
}
