package com.novadrive.app.nav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D-CANCEL-STALE-REPLY, SPEC-006 CVC-36: the pending navigate_to result after 「算了，不去了」 is no stale 「请选择」. */
class NavigationCancelledReplyTest {
    private fun candidate(id: String, name: String) = DestinationCandidate(
        id = id, name = name, address = "地址", district = "香洲区",
        latitude = 22.2, longitude = 113.5, distanceMeters = 100, poiId = id,
    )

    private val routes = listOf(RouteCandidate(10, 18_200, 29 * 60, labels = "推荐"))

    private fun reply(snapshot: EmbeddedNavigationController.OptionsSnapshot) =
        JSONObject(NavigationVoiceOutput.build("navigate_to", "accepted", snapshot))

    private fun assertNoChoicePrompt(out: JSONObject) {
        assertTrue(out.getBoolean("ok"))
        assertEquals("cancelled", out.getString("screen"))
        val next = out.getString("next")
        assertFalse(next.contains("请选择") || next.contains("第几") || next.contains("请说"), next)
        assertFalse(out.has("options_on_screen"))
    }

    /** Starts awaitOptions, lets it block on the in-flight phase, then cancels by voice. */
    private suspend fun awaitThenCancel(controller: EmbeddedNavigationController) = kotlinx.coroutines.coroutineScope {
        val pending = async(Dispatchers.Default) { controller.awaitOptions(3_000) }
        Thread.sleep(100)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice())
        withTimeout(5_000) { pending.await() }
    }

    @Test
    fun cancelDuringResolvingGivesCancelledNotAChoice() = runBlocking {
        val gate = CompletableDeferred<List<DestinationCandidate>>()
        val started = CompletableDeferred<Unit>()
        val controller = EmbeddedNavigationController(resolver = { started.complete(Unit); gate.await() }, engine = FakeNaviEngine())
        val search = async(Dispatchers.Default) { controller.requestDestination("万达") }
        withTimeout(5_000) { started.await() }
        assertEquals(NavigationPhase.RESOLVING_DESTINATION, controller.state().value)
        val snapshot = awaitThenCancel(controller)
        assertTrue(snapshot.cancelled)
        assertNoChoicePrompt(reply(snapshot))
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
        val snapshot = awaitThenCancel(controller)
        assertEquals(NavigationPhase.IDLE, snapshot.phase)
        assertTrue(snapshot.cancelled)
        assertNoChoicePrompt(reply(snapshot))
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
        val snapshot = awaitThenCancel(controller)
        assertEquals(NavigationPhase.NAVIGATING, snapshot.phase)
        val out = reply(snapshot)
        assertNoChoicePrompt(out)
        assertEquals("十字门", out.getString("destination"))
        assertTrue(out.getString("next").contains("原来的导航继续"))
    }

    @Test
    fun aSlowLoadIsStillLoadingNotCancelled() = runBlocking {
        val engine = FakeNaviEngine().apply { results = listOf(candidate("a", "珠海站")) }
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        val snapshot = controller.awaitOptions(50)
        assertFalse(snapshot.cancelled)
        assertEquals("still_loading", reply(snapshot).getString("screen"))
    }

    @Test
    fun aCompletedLoadAfterAnEarlierCancelIsNotCancelled() = runBlocking {
        val engine = FakeNaviEngine().apply { this.routes = this@NavigationCancelledReplyTest.routes; results = listOf(candidate("a", "珠海站")) }
        val controller = EmbeddedNavigationController(resolver = engine, engine = engine)
        controller.requestDestination("珠海站")
        controller.cancel()
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10))
        val snapshot = controller.awaitOptions(1_000)
        assertFalse(snapshot.cancelled)
        assertEquals("route_list", reply(snapshot).getString("screen"))
    }
}
