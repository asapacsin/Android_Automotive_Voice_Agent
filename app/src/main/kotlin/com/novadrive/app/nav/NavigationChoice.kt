package com.novadrive.app.nav

/**
 * A spoken pick from the on-screen destination or route list:
 * 「第二个」 -> [Index], 「选最快的」 -> [Preference], 「就去拱北口岸」 -> [Name].
 */
sealed interface NavigationChoice {
    /** 1-based, in the order shown on screen. */
    data class Index(val position: Int) : NavigationChoice

    data class Preference(val kind: Kind) : NavigationChoice

    data class Name(val text: String) : NavigationChoice

    enum class Kind(val wire: String) {
        FASTEST("fastest"),
        SHORTEST("shortest"),
        RECOMMENDED("recommended"),
        NO_TOLL("no_toll"),
        FEWEST_LIGHTS("fewest_lights"),
        NEAREST("nearest"),
        ;

        companion object {
            fun fromWire(value: String): Kind? = entries.firstOrNull { it.wire == value }
        }
    }
}

sealed interface ChoiceMatch<out T> {
    data class Picked<T>(val item: T, val position: Int) : ChoiceMatch<T>

    /** [code] is returned to the model so it can answer truthfully. */
    data class Rejected(val code: String) : ChoiceMatch<Nothing>
}

/** Pure matching rules; unit-tested. Positions are 1-based screen order. */
object NavigationChoiceResolver {
    fun pickDestination(candidates: List<DestinationCandidate>, choice: NavigationChoice): ChoiceMatch<DestinationCandidate> {
        if (candidates.isEmpty()) return ChoiceMatch.Rejected("NO_OPTIONS")
        return when (choice) {
            is NavigationChoice.Index -> byIndex(candidates, choice.position)
            is NavigationChoice.Preference -> when (choice.kind) {
                NavigationChoice.Kind.NEAREST -> {
                    val known = candidates.withIndex().filter { it.value.distanceMeters != null }
                    known.minByOrNull { it.value.distanceMeters!! }
                        ?.let { ChoiceMatch.Picked(it.value, it.index + 1) }
                        ?: ChoiceMatch.Rejected("DISTANCE_UNKNOWN")
                }
                else -> ChoiceMatch.Rejected("PREFERENCE_NOT_FOR_DESTINATIONS")
            }
            is NavigationChoice.Name -> byName(candidates, choice.text) { it.name }
        }
    }

    fun pickRoute(routes: List<RouteCandidate>, choice: NavigationChoice): ChoiceMatch<RouteCandidate> {
        if (routes.isEmpty()) return ChoiceMatch.Rejected("NO_OPTIONS")
        val indexed = routes.withIndex()
        fun best(selector: (RouteCandidate) -> Int?): ChoiceMatch<RouteCandidate> =
            indexed.filter { selector(it.value) != null }
                .minByOrNull { selector(it.value)!! }
                ?.let { ChoiceMatch.Picked(it.value, it.index + 1) }
                ?: ChoiceMatch.Rejected("NO_MATCH")
        fun labelled(vararg words: String): ChoiceMatch<RouteCandidate> =
            indexed.firstOrNull { route -> words.any { route.value.labels.orEmpty().contains(it) } }
                ?.let { ChoiceMatch.Picked(it.value, it.index + 1) }
                ?: ChoiceMatch.Rejected("NO_MATCH")
        return when (choice) {
            is NavigationChoice.Index -> byIndex(routes, choice.position)
            is NavigationChoice.Preference -> when (choice.kind) {
                NavigationChoice.Kind.FASTEST -> best { it.durationSeconds }
                NavigationChoice.Kind.SHORTEST -> best { it.distanceMeters }
                NavigationChoice.Kind.FEWEST_LIGHTS -> best { it.trafficLightCount }
                // The SDK lists its recommendation first when no label says so.
                NavigationChoice.Kind.RECOMMENDED -> labelled("推荐").let {
                    if (it is ChoiceMatch.Rejected) ChoiceMatch.Picked(routes.first(), 1) else it
                }
                NavigationChoice.Kind.NO_TOLL -> labelled("免费", "少收费", "不走高速")
                NavigationChoice.Kind.NEAREST -> ChoiceMatch.Rejected("PREFERENCE_NOT_FOR_ROUTES")
            }
            is NavigationChoice.Name -> byName(routes, choice.text) { it.labels.orEmpty() }
        }
    }

    private fun <T> byIndex(items: List<T>, position: Int): ChoiceMatch<T> =
        if (position in 1..items.size) ChoiceMatch.Picked(items[position - 1], position)
        else ChoiceMatch.Rejected("OUT_OF_RANGE")

    private fun <T> byName(items: List<T>, raw: String, nameOf: (T) -> String): ChoiceMatch<T> {
        val wanted = normalise(raw)
        if (wanted.isEmpty()) return ChoiceMatch.Rejected("NO_MATCH")
        val hits = items.withIndex().filter { item ->
            val name = normalise(nameOf(item.value))
            name.isNotEmpty() && (name.contains(wanted) || wanted.contains(name))
        }
        return when {
            hits.isEmpty() -> ChoiceMatch.Rejected("NO_MATCH")
            hits.size == 1 -> ChoiceMatch.Picked(hits[0].value, hits[0].index + 1)
            else -> {
                // 「珠海站」 against 「珠海站」 and 「珠海站(南广场)」: an exact name wins.
                val exact = hits.filter { normalise(nameOf(it.value)) == wanted }
                if (exact.size == 1) ChoiceMatch.Picked(exact[0].value, exact[0].index + 1)
                else ChoiceMatch.Rejected("AMBIGUOUS")
            }
        }
    }

    private fun normalise(text: String): String =
        text.lowercase().filterNot { it.isWhitespace() || it in "。，,.!！?？、“”\"'「」（）()" }

    /** What the model reads out: short, numbered, same order as the screen. */
    fun describeDestinations(candidates: List<DestinationCandidate>, limit: Int = 5): String =
        candidates.take(limit).mapIndexed { i, c ->
            val distance = c.distanceMeters?.let { "，" + NavigationFormatters.formatDistanceMeters(it).replace(" km", "公里").replace(" m", "米") }.orEmpty()
            "${i + 1}. ${c.name}$distance"
        }.joinToString("；")

    fun describeRoutes(routes: List<RouteCandidate>, limit: Int = 5): String =
        routes.take(limit).mapIndexed { i, r ->
            val km = NavigationFormatters.formatDistanceMeters(r.distanceMeters).replace(" km", "公里").replace(" m", "米")
            val time = NavigationFormatters.formatDurationSeconds(r.durationSeconds).replace(" ", "")
            val label = r.labels?.takeIf { it.isNotBlank() }?.let { "，$it" }.orEmpty()
            "${i + 1}. $km，约$time$label"
        }.joinToString("；")
}
