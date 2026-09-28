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
        val choice = ordinal(utterance) ?: preference(utterance) ?: NavigationChoice.Name(utterance)
        return when (phase) {
            NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                intercept(destinations, choice, NavigationChoiceResolver::pickDestination)
            NavigationPhase.AWAITING_ROUTE_SELECTION ->
                intercept(routes, choice, NavigationChoiceResolver::pickRoute)
            else -> null
        }
    }

    private fun <T> intercept(
        items: List<T>,
        choice: NavigationChoice,
        pick: (List<T>, NavigationChoice) -> ChoiceMatch<T>,
    ): NavigationChoice? =
        when (val match = pick(items, choice)) {
            is ChoiceMatch.Picked -> choice
            is ChoiceMatch.Rejected ->
                when {
                    choice is NavigationChoice.Preference -> choice
                    choice is NavigationChoice.Index -> null
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

    /**
     * Whole-utterance route/destination preferences: 「最快的」「选最近的」「红绿灯少的」.
     * A sentence that merely mentions a preference («前面最快的路口») is not a pick.
     */
    fun preference(utterance: String): NavigationChoice.Preference? {
        val text = utterance.filterNot { it.isWhitespace() || it in PUNCTUATION }
        val match = PREFERENCE.matchEntire(text) ?: return null
        return when (match.groupValues[1]) {
            "最快", "时间最短", "用时最短" -> NavigationChoice.Preference(NavigationChoice.Kind.FASTEST)
            "最短", "距离最短" -> NavigationChoice.Preference(NavigationChoice.Kind.SHORTEST)
            "推荐", "开始导航", "就这条", "好的" -> NavigationChoice.Preference(NavigationChoice.Kind.RECOMMENDED)
            "免费", "不要收费", "不走高速", "少收费" -> NavigationChoice.Preference(NavigationChoice.Kind.NO_TOLL)
            "红绿灯少", "红灯少" -> NavigationChoice.Preference(NavigationChoice.Kind.FEWEST_LIGHTS)
            "最近" -> NavigationChoice.Preference(NavigationChoice.Kind.NEAREST)
            else -> null
        }
    }

    private const val PUNCTUATION = "。，,.!！?？、~～"

    private val ORDINAL = Regex(
        "^(?:我要|我选|就选|就要|就去|就|选择|选|要|去)?第([一二三四五六七八九十]|[0-9]{1,2})(?:个|条|项|家|行)?(?:吧|啊|呀|哈|啦|就行|就好)?$",
    )

    private val PREFERENCE = Regex(
        "^(?:我要|我选|就选|就要|就|选择|选|走)?(最快|时间最短|用时最短|最短|距离最短|推荐|开始导航|就这条|好的|免费|不要收费|不走高速|少收费|红绿灯少|红灯少|最近)(?:的|那条|那条路|那条路线|路线|那个地点|那个|吧|啊|呀|哈|啦|就行|就好)?$",
    )

    private fun number(raw: String): Int? =
        raw.toIntOrNull() ?: when (raw) {
            "十" -> 10
            else -> "一二三四五六七八九".indexOf(raw).takeIf { it >= 0 }?.plus(1)
        }
}
