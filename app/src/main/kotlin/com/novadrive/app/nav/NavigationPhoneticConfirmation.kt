package com.novadrive.app.nav

import android.os.Build
import android.icu.text.Transliterator

/**
 * Phonetic recovery proposes a confirmation candidate; it never auto-navigates.
 * Exact-name matching in [NavigationChoiceResolver] stays first.
 */
object NavigationPhoneticConfirmation {
    sealed interface Proposal {
        data class Confirm(val position: Int, val name: String) : Proposal
        data class AskOrdinal(val reason: String) : Proposal
    }

    fun proposeDestination(
        candidates: List<DestinationCandidate>,
        utterance: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Proposal? {
        if (candidates.isEmpty() || utterance.isBlank()) return null
        if (sdkInt < Build.VERSION_CODES.Q) {
            return Proposal.AskOrdinal("API_28_ORDINAL_REQUIRED")
        }
        val transliterator =
            runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull()
                ?: return Proposal.AskOrdinal("ICU_UNAVAILABLE")
        val wanted = latinKey(transliterator, utterance)
        if (wanted.isBlank()) return Proposal.AskOrdinal("NO_PHONETIC_SIGNAL")
        val scored =
            candidates.withIndex().map { (index, candidate) ->
                val latin = latinKey(transliterator, candidate.name)
                index + 1 to similarity(wanted, latin)
            }
        val leaders = scored.filter { it.second >= MIN_SIMILARITY }
        if (leaders.isEmpty()) return null
        val best = leaders.maxBy { it.second }
        val ties = leaders.count { it.second == best.second }
        if (ties > 1) return Proposal.AskOrdinal("PHONETIC_AMBIGUOUS")
        val candidate = candidates[best.first - 1]
        return Proposal.Confirm(best.first, candidate.name)
    }

    private fun latinKey(transliterator: Transliterator, text: String): String =
        transliterator.transliterate(text)
            .lowercase()
            .replace(Regex("[^a-z]"), "")

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shorter = minOf(a.length, b.length)
        val longer = maxOf(a.length, b.length)
        var common = 0
        for (i in 0 until shorter) {
            if (a[i] == b[i]) common++
        }
        return common.toDouble() / longer
    }

    private const val MIN_SIMILARITY = 0.55
}
