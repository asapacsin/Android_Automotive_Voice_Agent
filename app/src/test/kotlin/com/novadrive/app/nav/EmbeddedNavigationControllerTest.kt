package com.novadrive.app.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        assertEquals(NaviPresentation.DRIVING, engine.presentation)
    }

    @Test
    fun selectingARouteEntersDrivingPresentationNotRoutePreview() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertEquals(NaviPresentation.IDLE, engine.presentation)
        controller.selectRoute(10)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        assertEquals(NaviPresentation.DRIVING, engine.presentation)
        assertEquals(1, engine.startNaviCount)
    }

    @Test
    fun voiceStartEntersTheSameDrivingPresentationAsATap() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        val result = controller.chooseByVoice(NavigationChoice.Preference(NavigationChoice.Kind.RECOMMENDED))
        assertEquals(EmbeddedNavigationController.VoiceChoiceResult.RouteChosen(1, true), result)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
        assertEquals(NaviPresentation.DRIVING, engine.presentation)
        assertEquals(1, engine.startNaviCount)
    }

    @Test
    fun overviewAndResumeTrackingOnlyWorkWhileNavigating() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        assertFalse(controller.showOverview())
        assertFalse(controller.resumeTracking())
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertFalse(controller.showOverview())
        controller.selectRoute(10)
        assertTrue(controller.showOverview())
        assertEquals(NaviPresentation.OVERVIEW, engine.presentation)
        assertEquals(1, engine.overviewCount)
        assertTrue(controller.resumeTracking())
        assertEquals(NaviPresentation.DRIVING, engine.presentation)
        assertEquals(1, engine.resumeTrackingCount)
        engine.emitNavigationEnded("arrived")
        assertFalse(controller.showOverview())
        assertFalse(controller.resumeTracking())
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

    @Test
    fun voiceChoiceWalksDestinationThenRouteThroughTheTapPaths() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, threeCandidates())
        controller.requestDestination("万达")
        val first = controller.chooseByVoice(NavigationChoice.Index(2))
        assertEquals(EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("万达影城", 2), first)
        assertEquals("万达影城", engine.calculateCalls.single().endName)
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected("OPTIONS_NOT_READY"),
            controller.chooseByVoice(NavigationChoice.Index(1)),
            "routes are still being calculated",
        )
        engine.emitSuccess(intArrayOf(10, 20, 30))
        val second = controller.chooseByVoice(NavigationChoice.Preference(NavigationChoice.Kind.FASTEST))
        assertEquals(EmbeddedNavigationController.VoiceChoiceResult.RouteChosen(3, true), second)
        assertEquals(listOf(30), engine.selectRouteCalls, "the fastest route (27 min) is the one driven")
        assertEquals(1, engine.startNaviCount)
        assertEquals(NavigationPhase.NAVIGATING, controller.state().value)
    }

    @Test
    fun voiceChoiceWithNothingOnScreenOrOutOfRangeChangesNothing() = runBlocking {
        val engine = FakeNaviEngine()
        val controller = controller(engine, threeCandidates())
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected("NO_OPTIONS_ON_SCREEN"),
            controller.chooseByVoice(NavigationChoice.Index(1)),
        )
        controller.requestDestination("万达")
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected("OUT_OF_RANGE"),
            controller.chooseByVoice(NavigationChoice.Index(4)),
        )
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, controller.state().value)
        assertTrue(engine.calculateCalls.isEmpty())
    }

    @Test
    fun aFailedStartIsReportedNotClaimed() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        engine.startAccepted = false
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected("START_FAILED"),
            controller.chooseByVoice(NavigationChoice.Index(1)),
        )
    }

    @Test
    fun awaitOptionsReportsRoutesOnceCalculated() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val controller = controller(engine, listOf(candidate("a", 22.20, 113.54)))
        controller.requestDestination("珠海站")
        val pending = async(Dispatchers.Default) { controller.awaitOptions(3_000) }
        Thread.sleep(100)
        engine.emitSuccess(intArrayOf(10, 20, 30))
        val snapshot = pending.await()
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, snapshot.phase)
        assertEquals(3, snapshot.routes.size)
        assertEquals("珠海站", snapshot.destinationName)
    }

    @Test
    fun everyEndOfAFlowClearsTheSpeechMuteExceptAReplacement() = runBlocking {
        // Found by the simulation benchmark: after arrival (or a failed search) the mute stayed on.
        val ended = mutableListOf<NavigationPhase>()
        var started = 0
        val engine = FakeNaviEngine().apply { routes = threeRoutes(); results = listOf(candidate("a", 22.20, 113.54)) }
        lateinit var controller: EmbeddedNavigationController
        controller = EmbeddedNavigationController(
            resolver = engine, engine = engine,
            onFlowEnded = { ended += controller.state().value },
            onGuidanceStarted = { started++ },
        )
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(10)
        assertEquals(1, started)
        controller.requestDestination("珠海站") // replacement while driving
        assertTrue(ended.isEmpty(), "replacing the destination keeps the flow (and its mute)")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(20)
        engine.emitNavigationEnded("arrived")
        assertEquals(listOf(NavigationPhase.ARRIVED), ended)

        controller.requestDestination("珠海站")
        engine.emitFailure(12)
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.cancel()
        controller.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        controller.selectRoute(30)
        controller.stopNavigation()
        assertEquals(
            listOf(NavigationPhase.ARRIVED, NavigationPhase.ERROR, NavigationPhase.IDLE, NavigationPhase.STOPPED),
            ended.distinct(),
        )
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
    var overviewCount = 0
    var resumeTrackingCount = 0
    var presentation: NaviPresentation = NaviPresentation.IDLE
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
        if (startAccepted) presentation = NaviPresentation.DRIVING
        return startAccepted
    }

    override fun stopNavigation(reason: String): Boolean {
        stopNaviCount++
        presentation = NaviPresentation.IDLE
        return true
    }

    override fun showOverview(): Boolean {
        if (presentation != NaviPresentation.DRIVING) return false
        presentation = NaviPresentation.OVERVIEW
        overviewCount++
        return true
    }

    override fun resumeTracking(): Boolean {
        if (presentation != NaviPresentation.OVERVIEW && presentation != NaviPresentation.DRIVING) {
            return false
        }
        presentation = NaviPresentation.DRIVING
        resumeTrackingCount++
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
