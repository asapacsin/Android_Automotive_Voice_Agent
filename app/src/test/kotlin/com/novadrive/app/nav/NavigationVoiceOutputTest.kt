package com.novadrive.app.nav

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Owner feedback 2026-09-17: reading every option aloud was far too long. The list is on screen;
 * the spoken reply is one sentence.
 */
class NavigationVoiceOutputTest {
    private fun dest(i: Int) = DestinationCandidate("d$i", "珠海站$i", "", "", 22.2, 113.5, distanceMeters = 9_200)
    private val routes = listOf(
        RouteCandidate(12, 19_480, 1_740, labels = "推荐"),
        RouteCandidate(13, 19_960, 1_800, labels = "常规"),
    )

    private fun build(phase: NavigationPhase, destinations: List<DestinationCandidate> = emptyList(), routes: List<RouteCandidate> = emptyList()) =
        JSONObject(
            NavigationVoiceOutput.build(
                "navigate_to",
                "accepted",
                EmbeddedNavigationController.OptionsSnapshot(phase, destinations, routes, "珠海站"),
            ),
        )

    @Test
    fun destinationListAsksForOneShortSentence() {
        val out = build(NavigationPhase.AWAITING_DESTINATION_SELECTION, destinations = (1..5).map(::dest))
        assertEquals(5, out.getInt("count"))
        assertTrue(out.getString("next").contains("不要念出列表"))
        assertTrue(out.getString("next").contains("找到5个地点"))
        assertFalse(out.has("options"), "nothing labelled as something to read out")
        assertTrue(out.getString("options_on_screen").contains("5. 珠海站5"), "still available for name matching")
    }

    @Test
    fun routeListMentionsOnlyTheRecommendedTimeAndThatNothingStartedYet() {
        val out = build(NavigationPhase.AWAITING_ROUTE_SELECTION, routes = routes)
        val next = out.getString("next")
        assertTrue(next.contains("不要逐条念路线"))
        assertTrue(next.contains("有2条路线，推荐的约29分钟"))
        assertTrue(next.contains("导航还没有开始"))
        assertTrue(next.contains("开始导航"))
    }

    @Test
    fun navigatingAndFailureAreReportedPlainly() {
        assertEquals("navigating", build(NavigationPhase.NAVIGATING).getString("screen"))
        val failed = build(NavigationPhase.ERROR)
        assertFalse(failed.getBoolean("ok"))
        assertEquals("NAVIGATION_FAILED", failed.getString("error"))
    }
}
