package com.novadrive.app.nav

/**
 * When a destination or route list is on screen, a spoken utterance that matches exactly one
 * candidate should pick that row locally instead of starting a new search.
 */
object NavigationPickerIntercept {
    fun resolve(
        utterance: String,
        phase: NavigationPhase,
        destinations: List<DestinationCandidate>,
        routes: List<RouteCandidate>,
    ): NavigationChoice? {
        if (utterance.isBlank()) return null
        val choice = NavigationChoice.Name(utterance)
        return when (phase) {
            NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                when (NavigationChoiceResolver.pickDestination(destinations, choice)) {
                    is ChoiceMatch.Picked -> choice
                    else -> null
                }
            NavigationPhase.AWAITING_ROUTE_SELECTION ->
                when (NavigationChoiceResolver.pickRoute(routes, choice)) {
                    is ChoiceMatch.Picked -> choice
                    else -> null
                }
            else -> null
        }
    }
}
