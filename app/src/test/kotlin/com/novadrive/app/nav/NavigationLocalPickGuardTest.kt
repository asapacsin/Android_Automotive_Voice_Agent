package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavigationLocalPickGuardTest {
    @Test
    fun suppressionIsOneShotUntilTheNextUserTranscript() {
        NavigationLocalPickGuard.onUserTranscript()
        NavigationLocalPickGuard.onLocalPickSucceeded()
        assertTrue(NavigationLocalPickGuard.consumeNavigateToSuppression())
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression())
        NavigationLocalPickGuard.onUserTranscript()
        assertFalse(NavigationLocalPickGuard.consumeNavigateToSuppression())
    }
}
