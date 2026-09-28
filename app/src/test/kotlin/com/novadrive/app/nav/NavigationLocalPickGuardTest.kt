package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NavigationLocalPickGuardTest {
    /** Global state: never leave an authority behind for another test class to consume. */
    @AfterEach
    fun clear() {
        NavigationLocalPickGuard.invalidate()
        NavigationPickSession.clear()
    }

    /** Owner demo 2026-09-28 08:36:30: 「第二个」 picked locally, then the model's own call. */
    @Test
    fun theModelsDuplicateChoiceGetsTheLocalPicksResultOnceAndOnlySoon() {
        val turn = NavigationLocalPickGuard.nextTurnKey()
        NavigationPickSession.begin("list-a", turn)
        NavigationPickSession.recordExecutorResult(
            EmbeddedNavigationController.VoiceChoiceResult.RouteChosen(2, true),
        )
        assertEquals(NavigationLocalPickGuard.NAVIGATION_STARTED, NavigationLocalPickGuard.consumeChoiceSuppression())
        assertNull(NavigationLocalPickGuard.consumeChoiceSuppression(), "once")

        NavigationLocalPickGuard.onLocalPickSucceeded("list-a", NavigationLocalPickGuard.nextTurnKey())
        val late = NavigationLocalPickGuard.current()!!.atMs + NavigationLocalPickGuard.DUPLICATE_CALL_WINDOW_MS + 1
        assertNull(NavigationLocalPickGuard.consumeChoiceSuppression(late), "a later call is not a duplicate")
    }
    @Test
    fun selectedAuthoritySuppressesDuplicateNavigateToOnce() {
        NavigationLocalPickGuard.onUserTranscript()
        val turn = NavigationLocalPickGuard.nextTurnKey()
        NavigationLocalPickGuard.onLocalPickSucceeded("list-a", turn)
        assertTrue(NavigationLocalPickGuard.consumeNavigateToSuppression("list-a", turn))
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression("list-a", turn))
    }

    @Test
    fun staleListOrTurnDoesNotSuppress() {
        val turn = NavigationLocalPickGuard.nextTurnKey()
        NavigationLocalPickGuard.onLocalPickSucceeded("list-a", turn)
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression("list-b", turn))
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression("list-a", turn + 1))
    }

    @Test
    fun ambiguousOutcomeDoesNotSuppressNavigateTo() {
        val turn = NavigationLocalPickGuard.nextTurnKey()
        NavigationLocalPickGuard.record("list-a", turn, NavigationLocalPickGuard.Outcome.AMBIGUOUS)
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression("list-a", turn))
    }

    @Test
    fun localPreferenceRejectionSuppressesNavigateToOnce() {
        val turn = NavigationLocalPickGuard.nextTurnKey()
        NavigationPickSession.begin("list-a", turn)
        NavigationPickSession.recordExecutorResult(
            EmbeddedNavigationController.VoiceChoiceResult.Rejected("PREFERENCE_NOT_FOR_DESTINATIONS"),
        )
        assertTrue(NavigationLocalPickGuard.consumeNavigateToSuppression("list-a", turn))
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression("list-a", turn))
    }

    @Test
    fun pickSessionRecordsExecutorResult() {
        val turn = NavigationLocalPickGuard.nextTurnKey()
        NavigationPickSession.begin("list-a", turn)
        NavigationPickSession.recordExecutorResult(
            EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen("珠海站", 1),
        )
        assertEquals(
            NavigationLocalPickGuard.Outcome.SELECTED,
            NavigationLocalPickGuard.current()?.outcome,
        )
    }
}
