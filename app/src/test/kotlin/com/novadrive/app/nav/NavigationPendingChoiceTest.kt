package com.novadrive.app.nav

import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.CoreActionExecutor
import com.novadrive.app.noCamera
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Astra P5: a spoken choice refers to the list the driver has in mind. An ordinal on a list that
 * has been on screen too long, or that survived a sleep, is re-presented before it is accepted; a
 * name that matches nothing but sounds like one row becomes a question, never a selection.
 */
class NavigationPendingChoiceTest {
    private var now = 1_000L
    private var proposal: NavigationPhoneticConfirmation.Proposal? = null

    private fun controller(engine: FakeNaviEngine, results: List<DestinationCandidate>): EmbeddedNavigationController {
        engine.results = results
        return EmbeddedNavigationController(
            resolver = engine,
            engine = engine,
            nowMs = { now },
            phoneticProposer = { _, _ -> proposal },
        )
    }

    @Test
    fun anOrdinalOnAnExpiredListIsReReadOnceThenAccepted() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        nav.requestDestination("万达")
        now += NavigationChoiceAuthority.CHOICE_EXPIRY_MS + 1
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected(NavigationChoiceAuthority.OPTIONS_STALE),
            nav.chooseByVoice(NavigationChoice.Index(2)),
        )
        assertTrue(engine.calculateCalls.isEmpty(), "a stale ordinal selects nothing")
        assertEquals(NavigationPhase.AWAITING_DESTINATION_SELECTION, nav.state().value, "the list stays on screen")
        // The rejection re-read the list: the next ordinal refers to what the driver just heard.
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("万达影城", 2),
            nav.chooseByVoice(NavigationChoice.Index(2)),
        )
    }

    @Test
    fun anOrdinalWithinTheExpiryIsAcceptedFirstTime() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        nav.requestDestination("万达")
        now += NavigationChoiceAuthority.CHOICE_EXPIRY_MS
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("万达影城", 2),
            nav.chooseByVoice(NavigationChoice.Index(2)),
        )
    }

    @Test
    fun aNameIsExplicitAndIsNotRefusedOnAStaleList() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        nav.requestDestination("万达")
        nav.onListeningSuspended()
        now += NavigationChoiceAuthority.CHOICE_EXPIRY_MS * 3
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("万达酒店", 3),
            nav.chooseByVoice(NavigationChoice.Name("万达酒店")),
        )
    }

    @Test
    fun afterSleepAnOrdinalIsReconfirmedEvenIfTheListIsRecent() = runBlocking {
        val engine = FakeNaviEngine()
        engine.routes = threeRoutes()
        val nav = controller(engine, listOf(candidate("a", name = "珠海站")))
        nav.requestDestination("珠海站")
        engine.emitSuccess(intArrayOf(10, 20, 30))
        assertEquals(NavigationPhase.AWAITING_ROUTE_SELECTION, nav.state().value)
        nav.onListeningSuspended()
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected(NavigationChoiceAuthority.OPTIONS_STALE),
            nav.chooseByVoice(NavigationChoice.Preference(NavigationChoice.Kind.FASTEST)),
        )
        assertEquals(0, engine.startNaviCount, "no route is driven on a pre-sleep preference")
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.RouteChosen(3, true),
            nav.chooseByVoice(NavigationChoice.Preference(NavigationChoice.Kind.FASTEST)),
        )
    }

    @Test
    fun aNewListClearsTheStaleMark() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        nav.requestDestination("万达")
        nav.onListeningSuspended()
        nav.requestDestination("万达")
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("万达广场", 1),
            nav.chooseByVoice(NavigationChoice.Index(1)),
        )
    }

    @Test
    fun aPhoneticLeadBecomesAQuestionAndSelectsNothing() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        nav.requestDestination("万达")
        proposal = NavigationPhoneticConfirmation.Proposal.Confirm(2, "万达影城")
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.ConfirmNeeded(2, "万达影城"),
            nav.chooseByVoice(NavigationChoice.Name("万大影成")),
        )
        assertTrue(engine.calculateCalls.isEmpty(), "a phonetic lead never navigates")
        assertEquals(2, nav.activeConfirmation()?.position)
        // The driver's 「对」 arrives as the confirmed ordinal and is accepted.
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("万达影城", 2),
            nav.chooseByVoice(NavigationChoice.Index(2)),
        )
        assertNull(nav.activeConfirmation(), "answered")
    }

    @Test
    fun anAmbiguousOrUnavailablePhoneticResultStaysAPlainNoMatch() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        nav.requestDestination("万达")
        proposal = NavigationPhoneticConfirmation.Proposal.AskOrdinal("PHONETIC_AMBIGUOUS")
        assertEquals(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected("NO_MATCH"),
            nav.chooseByVoice(NavigationChoice.Name("随便哪个")),
        )
        assertNull(nav.activeConfirmation())
    }

    @Test
    fun aPendingConfirmationDiesWithSleepExpiryWithdrawalAndANewList() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        proposal = NavigationPhoneticConfirmation.Proposal.Confirm(2, "万达影城")

        nav.requestDestination("万达")
        nav.chooseByVoice(NavigationChoice.Name("万大影成"))
        nav.onListeningSuspended()
        assertNull(nav.activeConfirmation(), "「对」 after waking up answers nothing")

        nav.requestDestination("万达")
        nav.chooseByVoice(NavigationChoice.Name("万大影成"))
        now += NavigationChoiceAuthority.CHOICE_EXPIRY_MS + 1
        assertNull(nav.activeConfirmation(), "expired")

        nav.requestDestination("万达")
        nav.chooseByVoice(NavigationChoice.Name("万大影成"))
        nav.withdrawConfirmation()
        assertNull(nav.activeConfirmation(), "the driver said something else")

        nav.chooseByVoice(NavigationChoice.Name("万大影成"))
        nav.requestDestination("万达")
        assertNull(nav.activeConfirmation(), "a new list replaces the question")
    }

    @Test
    fun onlyAWholeShortYesAnswersTheQuestion() {
        listOf("对", "对的。", "是的", "嗯", "好的！", " 没错 ", "就是这个").forEach {
            assertTrue(NavigationPhoneticConfirmation.isAffirmative(it), it)
        }
        listOf("对了我想去别处", "不是", "不对", "第二个", "", "好的换一个").forEach {
            assertFalse(NavigationPhoneticConfirmation.isAffirmative(it), it)
        }
    }

    @Test
    fun theModelIsToldWhichRowToAskAboutAndWhatToReRead() = runBlocking {
        val engine = FakeNaviEngine()
        val nav = controller(engine, threeCandidates())
        val dispatcher = AndroidToolDispatcher(
            CoreActionExecutor(navigationFlow = nav, music = { error("unused") }),
            ClimateToolHandler(SimulatedVehicleControl()),
            noCamera(),
        )
        nav.requestDestination("万达")
        proposal = NavigationPhoneticConfirmation.Proposal.Confirm(2, "万达影城")
        val asked = JSONObject(dispatcher.dispatch(choose(mapOf("name" to "万大影成"))).output!!)
        assertFalse(asked.getBoolean("ok"))
        assertEquals(AndroidToolDispatcher.CONFIRM_CANDIDATE, asked.getString("error"))
        assertEquals(2, asked.getInt("candidate_position"))
        assertEquals("万达影城", asked.getString("candidate_name"))
        assertTrue(asked.has("next"), "every refusal carries its wording")

        now += NavigationChoiceAuthority.CHOICE_EXPIRY_MS + 1
        val stale = JSONObject(dispatcher.dispatch(choose(mapOf("index" to "1"))).output!!)
        assertEquals(NavigationChoiceAuthority.OPTIONS_STALE, stale.getString("error"))
        assertTrue(stale.getString("options_on_screen").startsWith("1. 万达广场"), stale.toString())
        assertTrue(stale.has("next"))
        assertTrue(engine.calculateCalls.isEmpty())
    }

    private fun choose(args: Map<String, String>) =
        DomainVoiceEvent.ToolCall("call_1", AndroidToolDispatcher.CHOOSE_NAVIGATION_OPTION, args)

    private fun candidate(id: String, name: String) = DestinationCandidate(
        id = id,
        name = name,
        address = "地址",
        district = "香洲区",
        latitude = 22.27,
        longitude = 113.57,
        distanceMeters = 100,
        poiId = id,
    )

    private fun threeCandidates() = listOf(
        candidate("a", "万达广场"),
        candidate("b", "万达影城"),
        candidate("c", "万达酒店"),
    )

    private fun threeRoutes() = listOf(
        RouteCandidate(10, 18_200, 29 * 60, labels = "高速优先", trafficLightCount = 3),
        RouteCandidate(20, 16_400, 32 * 60, labels = "", trafficLightCount = 8),
        RouteCandidate(30, 21_000, 27 * 60, labels = "", trafficLightCount = 5),
    )
}
