package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationLocalPickGuardTest {
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
