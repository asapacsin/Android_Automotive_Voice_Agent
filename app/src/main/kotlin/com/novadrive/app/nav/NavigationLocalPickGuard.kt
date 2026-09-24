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
    )

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
    ) {
        authority = Authority(listKey, turnKey, outcome)
    }

    fun current(): Authority? = authority

    /** Records a confirmed local selection for the active list/turn. */
    fun onLocalPickSucceeded(listKey: String, turnKey: Long) {
        record(listKey, turnKey, Outcome.SELECTED)
    }

    /**
     * True once: suppress a duplicate `navigate_to` for the same list/turn after a local pick.
     */
    fun consumeNavigateToSuppression(listKey: String, turnKey: Long): Boolean {
        val current = authority ?: return false
        if (current.listKey != listKey || current.turnKey != turnKey) return false
        if (current.outcome != Outcome.SELECTED) return false
        authority = null
        return true
    }

    fun invalidate() {
        authority = null
    }
}
