package com.novadrive.app

import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.EmbeddedNavigationController.VoiceChoiceResult
import com.novadrive.app.nav.NavigationChoiceAuthority
import com.novadrive.app.nav.NavigationChoiceResolver
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.nav.NavigationPickSession

/**
 * What a spoken navigation choice did, as the executor reports it to the model. Records the
 * executor's result for the pick session first, so a duplicate call cannot claim a selection the
 * executor did not make. A refusal carries the facts the reply needs: the rows to re-read for a
 * stale list, and the row to ask about for a phonetic lead.
 */
internal fun navigationChoiceAction(
    navigation: EmbeddedNavigationController,
    outcome: VoiceChoiceResult,
): AndroidActionResult {
    NavigationPickSession.recordExecutorResult(outcome)
    return when (outcome) {
        is VoiceChoiceResult.DestinationChosen -> AndroidActionResult.Accepted("destination_selected")
        is VoiceChoiceResult.RouteChosen -> AndroidActionResult.Accepted("navigation_started")
        is VoiceChoiceResult.Rejected -> {
            val details = if (outcome.code == NavigationChoiceAuthority.OPTIONS_STALE) {
                optionsOnScreen(navigation)?.let { mapOf("options_on_screen" to it) }.orEmpty()
            } else {
                emptyMap()
            }
            AndroidActionResult.Rejected(outcome.code, details)
        }
        is VoiceChoiceResult.ConfirmNeeded -> AndroidActionResult.Rejected(
            AndroidToolDispatcher.CONFIRM_CANDIDATE,
            mapOf("candidate_position" to outcome.position, "candidate_name" to outcome.name),
        )
    }
}

/** The rows on screen as the model reads them out, for a list that must be re-presented. */
private fun optionsOnScreen(navigation: EmbeddedNavigationController): String? =
    when (navigation.state().value) {
        NavigationPhase.AWAITING_DESTINATION_SELECTION ->
            NavigationChoiceResolver.describeDestinations(navigation.destinationCandidates.value)
        NavigationPhase.AWAITING_ROUTE_SELECTION ->
            NavigationChoiceResolver.describeRoutes(navigation.routeCandidates.value)
        else -> null
    }
