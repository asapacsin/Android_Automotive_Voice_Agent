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
}
