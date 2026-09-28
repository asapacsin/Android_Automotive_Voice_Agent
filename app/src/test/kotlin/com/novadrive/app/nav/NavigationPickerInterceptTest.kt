package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NavigationPickerInterceptTest {
    private fun dest(name: String) =
        DestinationCandidate(name, name, "地址", "香洲区", 22.2, 113.5, distanceMeters = 1_000)

    private val destinations = listOf(
        dest("珠海站"),
        dest("拱北口岸"),
    )

    @Test
    fun simplifiedHuiMatchesTraditionalOnScreenName() {
        val destinations = listOf(dest("中交滙通"))
        val choice = NavigationPickerIntercept.resolve(
            "中交汇通",
            NavigationPhase.AWAITING_DESTINATION_SELECTION,
            destinations,
            emptyList(),
        )
        assertEquals(NavigationChoice.Name("中交汇通"), choice)
    }

    @Test
    fun exactNameOnDestinationListIsPickedLocally() {
        val choice = NavigationPickerIntercept.resolve(
            "拱北口岸",
            NavigationPhase.AWAITING_DESTINATION_SELECTION,
            destinations,
            emptyList(),
        )
        assertEquals(NavigationChoice.Name("拱北口岸"), choice)
    }

    @Test
    fun ambiguousNameIsNotIntercepted() {
        val list = listOf(dest("珠海站"), dest("珠海站(南广场)"))
        assertNull(
            NavigationPickerIntercept.resolve(
                "珠海",
                NavigationPhase.AWAITING_DESTINATION_SELECTION,
                list,
                emptyList(),
            ),
        )
    }

    @Test
    fun noPickerOpenMeansNoIntercept() {
        assertNull(
            NavigationPickerIntercept.resolve(
                "拱北口岸",
                NavigationPhase.NAVIGATING,
                destinations,
                emptyList(),
            ),
        )
    }

    @Test
    fun unrelatedUtteranceIsNotIntercepted() {
        assertNull(
            NavigationPickerIntercept.resolve(
                "带我去机场",
                NavigationPhase.AWAITING_DESTINATION_SELECTION,
                destinations,
                emptyList(),
            ),
        )
    }

    /** Owner demo 2026-09-28 08:36:30: 「第二个」 went to the model, which sent a preference. */
    @Test
    fun anOrdinalPicksThatRowLocally() {
        val five = listOf(dest("拱北口岸"), dest("拱北口岸(地铁站)"), dest("拱北口岸广场"), dest("拱北口岸酒店"), dest("拱北口岸公交站"))
        for (said in listOf("第二个", "第二个。", "第2个", "选第二个", "就第二个吧", "第二")) {
            assertEquals(
                NavigationChoice.Index(2),
                NavigationPickerIntercept.resolve(said, NavigationPhase.AWAITING_DESTINATION_SELECTION, five, emptyList()),
                said,
            )
        }
        assertEquals(
            NavigationChoice.Index(10),
            NavigationPickerIntercept.ordinal("第十个"),
        )
    }

    @Test
    fun anOrdinalPicksARouteToo() {
        val routes = listOf(RouteCandidate(12, 5_827, 1_080), RouteCandidate(13, 7_078, 960))
        assertEquals(
            NavigationChoice.Index(2),
            NavigationPickerIntercept.resolve("第二条", NavigationPhase.AWAITING_ROUTE_SELECTION, emptyList(), routes),
        )
    }

    @Test
    fun onlyAWholeInRangeOrdinalIsAPick() {
        // Out of range: the model explains; a sentence containing an ordinal is not a pick.
        for (said in listOf("第三个", "第二个路口右转", "两个", "一个")) {
            assertNull(
                NavigationPickerIntercept.resolve(said, NavigationPhase.AWAITING_DESTINATION_SELECTION, destinations, emptyList()),
                said,
            )
        }
        assertNull(NavigationPickerIntercept.resolve("第二个", NavigationPhase.NAVIGATING, destinations, emptyList()))
    }
}
