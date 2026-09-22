package com.novadrive.app.nav

/**
 * After a local picker intercept succeeds, the model may still emit `navigate_to` for the same
 * utterance. Drop that duplicate search until the next user transcript.
 */
object NavigationLocalPickGuard {
    @Volatile private var suppressNavigateTo = false

    fun onUserTranscript() {
        suppressNavigateTo = false
    }

    fun onLocalPickSucceeded() {
        suppressNavigateTo = true
    }

    /** True once: the next `navigate_to` for this turn must not start a new search. */
    fun consumeNavigateToSuppression(): Boolean {
        if (!suppressNavigateTo) return false
        suppressNavigateTo = false
        return true
    }
}
