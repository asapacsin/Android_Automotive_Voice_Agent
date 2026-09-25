package com.novadrive.app.nav

import com.novadrive.app.NavigationState
import com.novadrive.app.voice.SpeechArbiter
import com.novadrive.app.voice.SpeechAuthority
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The speech mute must follow the navigation phase at **every** way a drive can end.
 *
 * [TECH_DEBT.md](../../../../../../../docs/TECH_DEBT.md) D-4 records two answers to "are we
 * navigating": `NavigationPhase` and the legacy `NavigationState.navigating` flag that
 * [com.novadrive.app.voice.PcmAudioPlayer] reads before dropping a reply. The symptom it describes
 * — arriving with the session still open left the driver muted — was fixed when
 * `onNavigationEnded` began calling `onFlowEnded`, but nothing asserted it, so the next transition
 * added to the controller could reintroduce it silently. That is the whole risk in D-4: not that
 * the two disagree today, but that nothing notices when they start to.
 *
 * These drive the **real** `NavigationState` through the controller's default callbacks, which is
 * what production wires, rather than a fake that would prove only that the test's own fake works.
 */
class NavigationMuteFollowsPhaseTest {

    private var offsetMs = 0L

    @BeforeEach
    fun clearMute() {
        SpeechAuthority.resetForTest { System.currentTimeMillis() + offsetMs }
        NavigationState.reset()
    }

    @AfterEach
    fun leaveNothingBehind() {
        NavigationState.reset()
        SpeechAuthority.resetForTest()
    }

    /** Past the confirmation window, so the mute reflects the phase and not a recent reply. */
    private fun mutedNow(): Boolean {
        offsetMs += SpeechArbiter.WINDOW_MS + 1
        return SpeechAuthority.arbiter.navigationMuted()
    }

    private fun drivingController(engine: FakeNaviEngine): EmbeddedNavigationController {
        engine.results = listOf(
            DestinationCandidate(
                id = "a", name = "珠海站", address = "地址", district = "香洲区",
                latitude = 22.20, longitude = 113.54, distanceMeters = 100,
            ),
        )
        engine.routes = threeRoutes()
        return EmbeddedNavigationController(resolver = engine, engine = engine)
    }

    private fun startDriving(controller: EmbeddedNavigationController, engine: FakeNaviEngine) {
        runBlocking {
            controller.requestDestination("珠海站")
            engine.emitSuccess(intArrayOf(10, 20, 30))
            controller.selectRoute(10)
        }
    }

    @Test
    fun guidanceStartingMutesUnpromptedSpeech() {
        val engine = FakeNaviEngine()
        val controller = drivingController(engine)
        startDriving(controller, engine)

        assertTrue(controller.state().value == NavigationPhase.NAVIGATING)
        assertTrue(NavigationState.navigating, "the legacy flag must follow the phase")
        assertTrue(mutedNow(), "unprompted speech is muted while navigating (product rule P1)")
    }

    @Test
    fun arrivingUnmutesEvenWhenTheSessionStaysOpen() {
        val engine = FakeNaviEngine()
        val controller = drivingController(engine)
        startDriving(controller, engine)
        assertTrue(mutedNow())

        engine.emitNavigationEnded("arrived")

        assertTrue(controller.state().value == NavigationPhase.ARRIVED)
        assertFalse(NavigationState.navigating, "D-4: arriving must clear the legacy flag too")
        assertFalse(mutedNow(), "a driver who has arrived must be able to hear 小诺 again")
    }

    @Test
    fun anEmulatedDriveEndingUnmutesTheSameWay() {
        val engine = FakeNaviEngine()
        val controller = drivingController(engine)
        startDriving(controller, engine)

        engine.emitNavigationEnded("emulator_end")

        assertTrue(controller.state().value == NavigationPhase.ARRIVED)
        assertFalse(mutedNow())
    }

    @Test
    fun stoppingTheDriveUnmutes() {
        val engine = FakeNaviEngine()
        val controller = drivingController(engine)
        startDriving(controller, engine)

        runBlocking { controller.stopNavigation() }

        assertTrue(controller.state().value == NavigationPhase.STOPPED)
        assertFalse(NavigationState.navigating)
        assertFalse(mutedNow())
    }

    @Test
    fun replacingTheDestinationKeepsTheMuteOn() {
        // The one ending that must NOT unmute: the old guidance stopped, but a new drive is
        // already running, so unmuting here would let 小诺 talk over the new route's guidance.
        val engine = FakeNaviEngine()
        val controller = drivingController(engine)
        startDriving(controller, engine)

        engine.emitNavigationEnded("replaced")

        assertTrue(NavigationState.navigating, "a replaced destination is still a drive")
        assertTrue(mutedNow())
    }

    private fun threeRoutes() = listOf(
        RouteCandidate(routeId = 10, distanceMeters = 19507, durationSeconds = 1800, labels = "推荐"),
        RouteCandidate(routeId = 20, distanceMeters = 19987, durationSeconds = 1700),
        RouteCandidate(routeId = 30, distanceMeters = 21410, durationSeconds = 1600),
    )
}
