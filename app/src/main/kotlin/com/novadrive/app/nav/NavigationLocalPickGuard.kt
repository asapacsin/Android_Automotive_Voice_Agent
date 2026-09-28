package com.novadrive.app.nav

/**
 * Turn-and-list scoped authority for local navigation picks. Replaces the old one-shot boolean
 * with the executor outcome so duplicate `navigate_to` calls cannot claim success without evidence.
 */
object NavigationLocalPickGuard {
    enum class Outcome {
        SELECTED,
        REFINE,
        NEW_SEARCH,
        AMBIGUOUS,
        NO_MATCH,
    }

    data class Authority(
        val listKey: String,
        val turnKey: Long,
        val outcome: Outcome,
        /** For [Outcome.SELECTED]: what the local pick did, as the executor reported it. */
        val selectedStatus: String = DESTINATION_SELECTED,
        val atMs: Long = monotonicMs(),
    )

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    /**
     * How long after a local pick the model's own call still counts as its duplicate. The call
     * follows the transcript by about 200 ms (08:36:30.711 -> 30.879 on 2026-09-28); a call
     * seconds later belongs to a later utterance whose transcript has not arrived yet.
     */
    const val DUPLICATE_CALL_WINDOW_MS = 5_000L

    const val DESTINATION_SELECTED = "destination_selected"
    const val NAVIGATION_STARTED = "navigation_started"

    @Volatile
    private var authority: Authority? = null

    @Volatile
    private var turnSeq: Long = 0L

    fun nextTurnKey(): Long = ++turnSeq

    fun onUserTranscript() {
        authority = null
    }

    fun onListReplaced() {
        authority = null
    }

    fun record(
        listKey: String,
        turnKey: Long,
        outcome: Outcome,
        selectedStatus: String = DESTINATION_SELECTED,
    ) {
        authority = Authority(listKey, turnKey, outcome, selectedStatus)
    }

    fun current(): Authority? = authority

    /** Records a confirmed local selection for the active list/turn. */
    fun onLocalPickSucceeded(listKey: String, turnKey: Long) {
        record(listKey, turnKey, Outcome.SELECTED)
    }

    /**
     * The model's own `choose_navigation_option` for an utterance the app already picked locally:
     * the executor's status of that pick (once), or null to execute the call. Measured 2026-09-28:
     * a duplicate call for 「第二个」 carried a *preference* and would have been rejected, or - with
     * the destination already chosen - applied to the route list that replaced it.
     */
    fun consumeChoiceSuppression(nowMs: Long = monotonicMs()): String? {
        val current = authority ?: return null
        if (current.outcome != Outcome.SELECTED) return null
        if (nowMs - current.atMs > DUPLICATE_CALL_WINDOW_MS) return null
        authority = null
        return current.selectedStatus
    }

    /**
     * True once: suppress a duplicate `navigate_to` for the same list/turn after a local pick.
     */
    fun consumeNavigateToSuppression(listKey: String, turnKey: Long): Boolean {
        val current = authority ?: return false
        if (current.listKey != listKey || current.turnKey != turnKey) return false
        if (current.outcome != Outcome.SELECTED && current.outcome != Outcome.REFINE) return false
        authority = null
        return true
    }

    fun invalidate() {
        authority = null
    }
}
