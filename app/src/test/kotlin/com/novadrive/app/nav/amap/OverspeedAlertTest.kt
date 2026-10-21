package com.novadrive.app.nav.amap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** P63 (owner test 10-16): a spoken warning when the HUD turns overspeed on, never without a limit. */
class OverspeedAlertTest {
    private fun hud(speed: Int, limit: Int, navigating: Boolean = true) =
        DrivingSpeedHud.snapshot(navigating = navigating, reportedSpeedKmh = speed, fallbackSpeedKmh = 0, limitKmh = limit)

    @Test
    fun warnsOnceWhenTheCarGoesOverAKnownLimit() {
        val alert = OverspeedAlert()
        assertNull(alert.onSnapshot(hud(30, 40), 0))
        assertEquals("您已超速，限速40公里", alert.onSnapshot(hud(90, 40), 1_000))
        assertNull(alert.onSnapshot(hud(95, 40), 2_000), "no repeat while still over the same limit")
        assertNull(alert.onSnapshot(hud(90, 40), 60_000))
    }

    @Test
    fun noLimitKnownMeansNoWarning() {
        // The demo's 珠海站 route: 90 km/h, 「限速 –」, violation cameras only.
        val alert = OverspeedAlert()
        assertNull(alert.onSnapshot(hud(90, 0), 0))
        assertNull(alert.onSnapshot(hud(120, 0), 20_000))
    }

    @Test
    fun warnsAgainAfterSlowingDownAndGoingOverAgain() {
        val alert = OverspeedAlert()
        assertEquals(OverspeedAlert.sentence(60), alert.onSnapshot(hud(90, 60), 0))
        assertNull(alert.onSnapshot(hud(50, 60), 15_000))
        assertEquals(OverspeedAlert.sentence(60), alert.onSnapshot(hud(90, 60), 30_000))
    }

    @Test
    fun aNewLowerLimitWhileOverIsAnnounced() {
        val alert = OverspeedAlert()
        assertEquals(OverspeedAlert.sentence(60), alert.onSnapshot(hud(90, 60), 0))
        assertEquals(OverspeedAlert.sentence(40), alert.onSnapshot(hud(90, 40), 20_000))
    }

    @Test
    fun aFlickeringLimitDoesNotRepeatWithinTheGap() {
        val alert = OverspeedAlert()
        assertEquals(OverspeedAlert.sentence(60), alert.onSnapshot(hud(90, 60), 0))
        assertNull(alert.onSnapshot(hud(90, 0), 2_000))
        assertNull(alert.onSnapshot(hud(90, 60), 4_000), "within ${OverspeedAlert.MIN_GAP_MS} ms")
        assertNull(alert.onSnapshot(hud(90, 0), 6_000))
        assertEquals(OverspeedAlert.sentence(60), alert.onSnapshot(hud(90, 60), 12_000))
    }

    @Test
    fun notNavigatingOrResetStartsFresh() {
        val alert = OverspeedAlert()
        assertEquals(OverspeedAlert.sentence(40), alert.onSnapshot(hud(90, 40), 0))
        assertNull(alert.onSnapshot(hud(90, 40, navigating = false), 1_000))
        alert.reset()
        assertEquals(OverspeedAlert.sentence(40), alert.onSnapshot(hud(90, 40), 2_000))
    }

    @Test
    fun theHostsNavigationStateDecidesNotTheLegacyFlag() {
        // The host navigates though a session restart cleared NavigationState.navigating: still queued.
        assertEquals("amap", OverspeedAlert.target(hostNavigating = true, relayOn = false, alertQueueOn = true))
        assertEquals("relay", OverspeedAlert.target(hostNavigating = true, relayOn = true, alertQueueOn = false))
        assertEquals("dropped:not_navigating", OverspeedAlert.target(hostNavigating = false, relayOn = false, alertQueueOn = true))
        assertEquals("dropped:no_queue", OverspeedAlert.target(hostNavigating = true, relayOn = false, alertQueueOn = false))
    }

    @Test
    fun atTheLimitIsNotOver() {
        assertNull(OverspeedAlert().onSnapshot(hud(40, 40), 0))
    }
}
