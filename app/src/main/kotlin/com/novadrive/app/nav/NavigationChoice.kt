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

    /**
     * Name matching, in order of confidence. Checklist row T06: 「选择麦当劳珠海站店」 must reach the
     * candidate the driver means, and must **never** silently pick when several are plausible.
     *
     * 1. exact, after normalising away punctuation, spaces and brackets;
     * 2. one candidate that contains the spoken name, or is contained by it — the spoken form drops
     *    a suffix (「麦当劳珠海站店」 for 「麦当劳(珠海站店)」) or adds a spoken 「那个」/「店」;
     * 3. one candidate sharing a long enough distinctive run of characters, for a half-heard name.
     *
     * Every step requires exactly one survivor. Two plausible candidates return `AMBIGUOUS`, and
     * the model is told to ask which one — a wrong destination is worse than a question.
     */
    private fun <T> byName(items: List<T>, raw: String, nameOf: (T) -> String): ChoiceMatch<T> {
        val wanted = normalise(stripChoiceWords(raw))
        if (wanted.isEmpty()) return ChoiceMatch.Rejected("NO_MATCH")
        val named = items.withIndex().map { it to normalise(nameOf(it.value)) }.filter { it.second.isNotEmpty() }

        val exact = named.filter { it.second == wanted }
        if (exact.size == 1) return ChoiceMatch.Picked(exact[0].first.value, exact[0].first.index + 1)
        if (exact.size > 1) return ChoiceMatch.Rejected("AMBIGUOUS")

        val containment = named.filter { it.second.contains(wanted) || wanted.contains(it.second) }
        if (containment.size == 1) return ChoiceMatch.Picked(containment[0].first.value, containment[0].first.index + 1)
        if (containment.size > 1) return ChoiceMatch.Rejected("AMBIGUOUS")

        // Conservative fuzzy step, for a half-heard name. The winner must beat the runner-up:
        // a run every candidate shares is the brand, not a choice.
        //
        // Measured on device 2026-09-18: 「选择麦当劳珠海站店」 against five 麦当劳 branches, none of
        // them the one named. Every candidate shared the run 「麦当劳」, so a plain threshold called
        // it AMBIGUOUS — as if the driver had nearly picked something. NO_MATCH is the truth.
        val runs = named.map { it to longestCommonRun(it.second, wanted) }
        val best = runs.maxOf { it.second }
        if (best < MIN_FUZZY_RUN) return ChoiceMatch.Rejected("NO_MATCH")
        // How much of what the driver said the run accounts for. 「麦当劳」 out of 「麦当劳珠海站店」 is
        // the brand and decides nothing; 「麦当劳珠海站」 out of the same is nearly all of it.
        val coverage = best.toDouble() / wanted.length
        val leaders = runs.filter { it.second == best }
        if (leaders.size == 1) {
            return if (coverage >= MIN_FUZZY_COVERAGE) {
                ChoiceMatch.Picked(leaders[0].first.first.value, leaders[0].first.first.index + 1)
            } else {
                ChoiceMatch.Rejected("NO_MATCH")
            }
        }
        // Several tie. If the shared run is most of the request they are genuinely rival
        // candidates and the driver must choose; if it is a fragment, nothing was matched at all.
        return if (coverage >= MIN_FUZZY_COVERAGE) ChoiceMatch.Rejected("AMBIGUOUS") else ChoiceMatch.Rejected("NO_MATCH")
    }

    /** A spoken pick is wrapped in words that are not part of any name. */
    private fun stripChoiceWords(raw: String): String {
        var text = raw.trim()
        for (word in CHOICE_WORDS) {
            if (text.startsWith(word) && text.length > word.length) text = text.removePrefix(word).trim()
        }
        for (word in TRAILING_CHOICE_WORDS) {
            if (text.endsWith(word) && text.length > word.length) text = text.removeSuffix(word).trim()
        }
        return text
    }

    private val CHOICE_WORDS = listOf("就去", "就到", "我要去", "我想去", "选择", "选", "去", "到", "第一个", "那个")
        .sortedByDescending { it.length }
    private val TRAILING_CHOICE_WORDS = listOf("那个", "这个", "吧", "好了", "就行", "谢谢")
        .sortedByDescending { it.length }

    /** Shortest run that is distinctive enough to act on; two Han characters are not. */
    const val MIN_FUZZY_RUN = 3

    /**
     * How much of the spoken name a run must account for before it means anything. Below this the
     * match is a shared fragment — a brand, a district — and selects nothing.
     */
    const val MIN_FUZZY_COVERAGE = 0.6

    private fun longestCommonRun(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var best = 0
        val previous = IntArray(b.length + 1)
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else 0
                if (current[j] > best) best = current[j]
            }
            System.arraycopy(current, 0, previous, 0, current.size)
            current.fill(0)
        }
        return best
    }

    private fun normalise(text: String): String =
        text.lowercase().filterNot { it.isWhitespace() || it in "。，,.!！?？、“”\"'「」（）()·-—_" }

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
