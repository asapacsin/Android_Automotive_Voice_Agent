package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextNavigationPanelModelTest {
    private fun render(
        phase: NavigationPhase,
        routes: List<RouteCandidate> = emptyList(),
        progress: TextNavigationPanelModel.Progress? = null,
        limit: Int = 0,
        text: String? = null,
    ) = TextNavigationPanelModel.render(phase, emptyList(), routes, "珠海站", progress, limit, text)

    @Test
    fun routePreviewListsIndexDistanceAndMinutes() {
        val lines = render(NavigationPhase.AWAITING_ROUTE_SELECTION, routes = listOf(RouteCandidate(7, 12_345, 1_500)))
        assertEquals("1. 12.3 km  25 分钟", lines.body.single())
    }

    @Test
    fun navigatingShowsManeuverRemainingLimitAndGuidance() {
        val p = TextNavigationPanelModel.Progress(2, 300, 5_000, 600, "情侣路")
        val lines = render(NavigationPhase.NAVIGATING, progress = p, limit = 60, text = "前方左转")
        assertEquals("← 左转  300 m  进入 情侣路", lines.body[0])
        assertEquals("剩余 5.0 km · 10 分钟", lines.body[1])
        assertEquals("限速 60 km/h", lines.body[2])
        assertTrue(lines.body[3].endsWith("前方左转"))
    }

    @Test
    fun navigatingWithoutProgressWaits() {
        assertEquals(1, render(NavigationPhase.NAVIGATING).body.size)
    }

    @Test
    fun arrivedHasItsOwnTitle() {
        assertEquals("已到达", render(NavigationPhase.ARRIVED).title)
    }
}
