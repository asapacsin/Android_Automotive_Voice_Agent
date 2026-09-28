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
        val choice = ordinal(utterance) ?: NavigationChoice.Name(utterance)
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

    /**
     * 「第二个」「选第2条」「就第三个吧」 as an [NavigationChoice.Index]. Only a whole utterance that is
     * nothing but an ordinal: 「第二个路口右转」 or 「两个」 are not picks. Measured 2026-09-28 08:36
     * (owner demo): 「第二个」 with five destinations on screen reached the model, which called
     * choose_navigation_option with a *preference* and got PREFERENCE_NOT_FOR_DESTINATIONS; the
     * driver had to say it twice. An ordinal is deterministic - the model gets no say in it.
     */
    fun ordinal(utterance: String): NavigationChoice.Index? {
        val text = utterance.filterNot { it.isWhitespace() || it in PUNCTUATION }
        val match = ORDINAL.matchEntire(text) ?: return null
        return number(match.groupValues[1])?.let { NavigationChoice.Index(it) }
    }

    private const val PUNCTUATION = "。，,.!！?？、~～"

    private val ORDINAL = Regex(
        "^(?:我要|我选|就选|就要|就去|就|选择|选|要|去)?第([一二三四五六七八九十]|[0-9]{1,2})(?:个|条|项|家|行)?(?:吧|啊|呀|哈|啦|就行|就好)?$",
    )

    private fun number(raw: String): Int? =
        raw.toIntOrNull() ?: when (raw) {
            "十" -> 10
            else -> "一二三四五六七八九".indexOf(raw).takeIf { it >= 0 }?.plus(1)
        }
}
