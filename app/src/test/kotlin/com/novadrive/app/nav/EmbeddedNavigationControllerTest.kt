package com.novadrive.app.nav

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmbeddedNavigationControllerTest {
    @Test
    fun t1OneCandidateSkipsDestinationSelectionAndRequestsRouteCalculation() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        assertFalse(controller.state().value == NavigationPhase.AWAITING_DESTINATION_SELECTION)
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
        assertTrue(NavigationPhase.CALCULATING_ROUTE.isNavigationSessionActive)
        assertEquals(1, engine.calculateCalls.size)
        assertEquals(22.20, engine.calculateCalls[0].endLat)
        assertEquals(113.54, engine.calculateCalls[0].endLon)
        assertEquals("珠海站", engine.calculateCalls[0].endName)
        assertEquals(DRIVING_MULTIPLE_ROUTES_DEFAULT, engine.calculateCalls[0].strategy)
        assertEquals(0, engine.startNaviCount)
        assertTrue(controller.destinationCandidates.value.isEmpty())
    }

    @Test
    fun t2ThreeCandidatesAwaitSelectionAndDoNotCalculate() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, threeCandidates())
        controller.requestDestination("万达")
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, controller.state().value)
        assertFalse(NavigationPhase.AWAITING_DESTINATION_SELECTION.isNavigationSessionActive)
        assertTrue(engine.calculateCalls.isEmpty())
        assertEquals(3, controller.destinationCandidates.value.size)
        assertEquals(0, engine.startNaviCount)
    }

    @Test
    fun t3SelectingSecondCandidateCalculatesWithItsCoordinates() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, threeCandidates())
        controller.requestDestination("万达")
        controller.selectDestination("b")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
        assertEquals(1, engine.calculateCalls.size)
        assertEquals(22.28, engine.calculateCalls[0].endLat)
        assertEquals(113.58, engine.calculateCalls[0].endLon)
        assertEquals("万达影城", engine.calculateCalls[0].endName)
        assertEquals(DRIVING_MULTIPLE_ROUTES_DEFAULT, engine.calculateCalls[0].strategy)
        assertTrue(controller.destinationCandidates.value.isEmpty())
    }

    @Test
    fun t4ThreeRoutesAwaitSelectionAndDoNotStartNavi() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, controller.state().value)
        assertFalse(NavigationPhase.AWAITING_ROUTE_SELECTION.isNavigationSessionActive)
        assertEquals(0, engine.startNaviCount)
        assertEquals(0, engine.selectRouteCalls.size)
        assertEquals(listOf(10, 20, 30), controller.routeCandidates.value.map { it.routeId })
    }

    @Test
    fun t5SelectingSecondRouteCallsSelectRouteIdThenStartNaviOnce() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(20)
        assertEquals(listOf(20), engine.selectRouteCalls)
        assertEquals(1, engine.startNaviCount)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        controller.selectRoute(10)
        assertEquals(1, engine.startNaviCount)
        assertEquals(listOf(20), engine.selectRouteCalls)
    }

    @Test
    fun t7ZeroCandidatesIsRecoverableErrorWithoutCalculation() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, emptyList())
        controller.requestDestination("nowhere")
        assertEquals(NavigationPhase.ERROR, controller.state().value)
        assertFalse(NavigationPhase.ERROR.isNavigationSessionActive)
        assertTrue(engine.calculateCalls.isEmpty())
        assertEquals(0, engine.startNaviCount)
        assertTrue(controller.destinationCandidates.value.isEmpty())
        engine.results = listOf(candidate("a", 22.20, 113.54))
        controller.requestDestination("珠海站")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
    }

    @Test
    fun t8CalculationFailureNeverStartsNavi() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitFailure(12)
        assertEquals(NavigationPhase.ERROR, controller.state().value)
        assertEquals(0, engine.startNaviCount)
        assertTrue(controller.routeCandidates.value.isEmpty())
    }

    @Test
    fun t9CancelFromEachAwaitingReturnsIdleAndInactive() = runBlocking {
        val engine = FakeNaviEngine()
        val destController = controller(engine, threeCandidates())
        destController.requestDestination("万达")
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, destController.state().value)
        destController.cancel()
        assertEquals(NavigationPhase.IDLE, destController.state().value)
        assertFalse(destController.state().value.isNavigationSessionActive)
        assertTrue(destController.destinationCandidates.value.isEmpty())

        engine.routes = threeRoutes()
        val routeController = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        routeController.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, routeController.state().value)
        routeController.cancel()
        assertEquals(NavigationPhase.IDLE, routeController.state().value)
        assertFalse(routeController.state().value.isNavigationSessionActive)
        assertTrue(routeController.routeCandidates.value.isEmpty())
        assertEquals(0, engine.startNaviCount)
    }

    @Test
    fun t11ArrivalResyncsPhaseOutOfNavigating() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(20)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        assertTrue(controller.state().value.isNavigationSessionActive)
        engine.emitNavigationEnded("emulator_end")
        assertEquals(NavigationPhase.ARRIVED, controller.state().value)
        assertFalse(controller.state().value.isNavigationSessionActive)
    }

    @Test
    fun t12ManualStopResyncsPhaseAndAllowsANewSession() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(20)
        engine.emitNavigationEnded("manual")
        assertEquals(NavigationPhase.STOPPED, controller.state().value)
        assertFalse(controller.state().value.isNavigationSessionActive)

        engine.results = listOf(candidate("z", 22.14, 113.54, name = "横琴口岸"))
        engine.routes = listOf(RouteCandidate(40, 9_000, 600))
        controller.requestDestination("横琴口岸")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
        engine.emitSuccess(intArrayOf(40))
        controller.selectRoute(40)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        assertEquals(2, engine.startNaviCount)
    }

    @Test
    fun t13ControllerStopNavigationStopsHostOnceAndReportsStopped() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(20)
        controller.stopNavigation()
        assertEquals(1, engine.stopNaviCount)
        assertEquals(NavigationPhase.STOPPED, controller.state().value)
        assertFalse(controller.state().value.isNavigationSessionActive)
    }

    @Test
    fun t14NavigationEndedWhilePickingARouteIsIgnored() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, controller.state().value)
        engine.emitNavigationEnded("arrived")
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, controller.state().value)
        assertEquals(3, controller.routeCandidates.value.size)
        assertEquals(0, engine.startNaviCount)
    }

    @Test
    fun newDestinationWhileNavigatingStopsOldRouteAndShowsNewCandidates() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(10)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)

        engine.results = threeCandidates()
        controller.requestDestination("万达")
        assertEquals(1, engine.stopNaviCount)
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, controller.state().value)
        assertEquals(listOf("a", "b", "c"), controller.destinationCandidates.value.map { it.id })
    }

    @Test
    fun repeatedRequestWhilePickingReplacesTheList() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, threeCandidates())
        controller.requestDestination("万达")
        engine.results = listOf(
            candidate("x", 22.10, 113.50, name = "拱北口岸"),
            candidate("y", 22.11, 113.51, name = "拱北口岸停车场"),
        )
        controller.requestDestination("拱北口岸")
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, controller.state().value)
        assertEquals(listOf("x", "y"), controller.destinationCandidates.value.map { it.id })
        assertEquals(0, engine.stopNaviCount)
        assertTrue(engine.calculateCalls.isEmpty())
    }

    @Test
    fun endByVoiceStopsActiveNavigationOnce() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(10)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.STOPPED_NAVIGATION, controller.endByVoice())
        assertEquals(1, engine.stopNaviCount)
        assertEquals(NavigationPhase.STOPPED, controller.state().value)
        assertEquals(EmbeddedNavigationController.VoiceEndResult.NOTHING_ACTIVE, controller.endByVoice())
        assertEquals(1, engine.stopNaviCount)
    }

    @Test
    fun endByVoiceClosesAnOpenPickerWithoutTouchingTheEngine() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, threeCandidates())
        controller.requestDestination("万达")
        assertEquals(EmbeddedNavigationController.VoiceEndResult.CANCELLED_SELECTION, controller.endByVoice())
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        assertTrue(controller.destinationCandidates.value.isEmpty())
        assertEquals(0, engine.stopNaviCount)
    }

    @Test
    fun endByVoiceWithNothingActiveChangesNothing() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, emptyList())
        assertEquals(EmbeddedNavigationController.VoiceEndResult.NOTHING_ACTIVE, controller.endByVoice())
        assertEquals(NavigationPhase.IDLE, controller.state().value)
        assertEquals(0, engine.stopNaviCount)
    }

    @Test
    fun secondNavigationAfterArrivalWorks() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(10)
        engine.emitNavigationEnded("arrived")
        assertEquals(NavigationPhase.ARRIVED, controller.state().value)
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(20)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        assertEquals(2, engine.startNaviCount)
        assertEquals(0, engine.stopNaviCount, "arrival already stopped the SDK; no extra stopNavi")
    }

    @Test
    fun newRequestDestinationReplacesStaleCandidates() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, threeCandidates())
        controller.requestDestination("万达")
        assertEquals(3, controller.destinationCandidates.value.size)
        engine.results = listOf(candidate("z", 22.14, 113.54, name = "横琴口岸"))
        controller.requestDestination("横琴口岸")
        assertEquals(NavigationPhase.CALCULATING_ROUTE, controller.state().value)
        assertTrue(controller.destinationCandidates.value.none { it.id == "a" })
        assertEquals(1, engine.calculateCalls.size)
        assertEquals("横琴口岸", engine.calculateCalls[0].endName)
    }

    @Test
    fun emptyRouteSuccessIsErrorNotAwaitingRouteSelection() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf())
        assertEquals(NavigationPhase.ERROR, controller.state().value)
        assertFalse(controller.state().value == NavigationPhase.AWAITING_ROUTE_SELECTION)
        assertEquals(0, engine.startNaviCount)
    }

    private fun controller(
        engine: FakeNaviEngine,
        results: List<DestinationCandidate>,
    ): EmbeddedNavigationController {
        engine.results = results
        return EmbeddedNavigationController(
            resolver = engine,
            engine = engine,
        )
    }

    private fun candidate(
        id: String,
        lat: Double,
        lon: Double,
        name: String = if (id == "a") "珠海站" else "地点$id",
    ) = DestinationCandidate(
        id = id,
        name = name,
        address = "地址",
        district = "香洲区",
        latitude = lat,
        longitude = lon,
        distanceMeters = 100,
        poiId = id,
    )

    private fun threeCandidates() = listOf(
        candidate("a", 22.27, 113.57, name = "万达广场"),
        candidate("b", 22.28, 113.58, name = "万达影城"),
        candidate("c", 22.29, 113.59, name = "万达酒店"),
    )

    private fun threeRoutes() = listOf(
        RouteCandidate(10, 18_200, 29 * 60, labels = "高速优先", trafficLightCount = 3),
        RouteCandidate(20, 16_400, 32 * 60, labels = "", trafficLightCount = 8),
        RouteCandidate(30, 19_000, 27 * 60, labels = null, trafficLightCount = 5),
    )
}

class FakeNaviEngine : NaviEngine, DestinationCandidateSource {
    data class CalcCall(
        val endLat: Double,
        val endLon: Double,
        val endName: String,
        val strategy: Int,
    )

    val calculateCalls = mutableListOf<CalcCall>()
    val selectRouteCalls = mutableListOf<Int>()
    var startNaviCount = 0
    var stopNaviCount = 0
    var calculateAccepted = true
    var selectAccepted = true
    var startAccepted = true
    var routes: List<RouteCandidate> = emptyList()
    var results: List<DestinationCandidate> = emptyList()

    private var onSuccess: ((IntArray) -> Unit)? = null
    private var onFailure: ((Int) -> Unit)? = null
    private var onEnded: ((String) -> Unit)? = null

    override suspend fun resolve(query: String): List<DestinationCandidate> = results

    override fun calculateDriveRoute(
        endLat: Double,
        endLon: Double,
        endName: String,
        strategy: Int,
    ): Boolean {
        calculateCalls += CalcCall(endLat, endLon, endName, strategy)
        return calculateAccepted
    }

    override fun selectRoute(routeId: Int): Boolean {
        selectRouteCalls += routeId
        return selectAccepted
    }

    override fun startNavigation(emulator: Boolean): Boolean {
        startNaviCount++
        return startAccepted
    }

    override fun stopNavigation(reason: String): Boolean {
        stopNaviCount++
        return true
    }

    override fun routeCandidates(): List<RouteCandidate> = routes

    override fun attachRouteCallbacks(
        onSuccess: (IntArray) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        this.onSuccess = onSuccess
        this.onFailure = onFailure
    }

    override fun attachNavigationEndedCallback(onEnded: (reason: String) -> Unit) {
        this.onEnded = onEnded
    }

    /** Stands in for the host invoking its ended callback from stopNavigation(). */
    fun emitNavigationEnded(reason: String) {
        onEnded?.invoke(reason)
    }

    fun emitSuccess(ids: IntArray) {
        onSuccess?.invoke(ids)
    }

    fun emitFailure(code: Int) {
        onFailure?.invoke(code)
    }
}
