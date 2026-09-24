package com.novadrive.app.nav

/**
 * Binds a local pick attempt to list/turn identity until the executor reports its result.
 */
object NavigationPickSession {
    data class Pending(val listKey: String, val turnKey: Long)

    @Volatile
    private var pending: Pending? = null

    fun begin(listKey: String, turnKey: Long) {
        pending = Pending(listKey, turnKey)
    }

    fun recordExecutorResult(outcome: EmbeddedNavigationController.VoiceChoiceResult) {
        val current = pending ?: return
        pending = null
        val recorded =
            when (outcome) {
                is EmbeddedNavigationController.VoiceChoiceResult.DestinationChosen,
                is EmbeddedNavigationController.VoiceChoiceResult.RouteChosen,
                -> NavigationLocalPickGuard.Outcome.SELECTED
                is EmbeddedNavigationController.VoiceChoiceResult.Rejected ->
                    when (outcome.code) {
                        "AMBIGUOUS" -> NavigationLocalPickGuard.Outcome.AMBIGUOUS
                        "NO_MATCH", "OUT_OF_RANGE" -> NavigationLocalPickGuard.Outcome.NO_MATCH
                        else -> NavigationLocalPickGuard.Outcome.REFINE
                    }
                // A question back to the driver: nothing was selected, so nothing may be claimed.
                is EmbeddedNavigationController.VoiceChoiceResult.ConfirmNeeded ->
                    NavigationLocalPickGuard.Outcome.AMBIGUOUS
            }
        NavigationLocalPickGuard.record(current.listKey, current.turnKey, recorded)
    }

    fun clear() {
        pending = null
    }
}
