package com.novadrive.app.nav

import com.novadrive.app.DebugVoiceLog

/**
 * Whether a spoken choice may still be taken at face value for the list on screen (Astra P5).
 *
 * Owned by [EmbeddedNavigationController] and only called under its lock; split out so the
 * controller keeps to one job, the flow. An ordinal is a reference to what the driver saw. After
 * [CHOICE_EXPIRY_MS], or once listening has slept, 「第二个」 may mean a list they no longer have in
 * mind, so it is refused once and the list is re-read. A name that matches nothing but sounds like
 * one row becomes a question, and only the driver's 「对」 to that question selects it.
 */
class NavigationChoiceAuthority(
    private val nowMs: () -> Long,
    private val phoneticProposer: (List<DestinationCandidate>, String) -> NavigationPhoneticConfirmation.Proposal?,
) {
    /** A phonetic match waiting for the driver's 「对」, bound to the list it was made for. */
    data class PendingConfirmation(val generation: Long, val position: Int, val name: String, val atMs: Long)

    private var presentedAtMs = 0L
    private var needsReconfirm = false
    private var pending: PendingConfirmation? = null

    /** A list was shown, or re-read to the driver: ordinals refer to it from now. */
    fun onListPresented() {
        presentedAtMs = nowMs()
        needsReconfirm = false
        pending = null
    }

    fun onListCleared() {
        needsReconfirm = false
        pending = null
    }

    /**
     * Listening went to SLEEP or DEEP_IDLE. A list still on screen keeps its rows (a tap stays
     * valid), but a spoken ordinal is re-confirmed first, and a pending question is void: 「对」
     * after waking up is not an answer to a question asked before.
     */
    fun onListeningSuspended(listOnScreen: Boolean) {
        pending = null
        if (listOnScreen) needsReconfirm = true
    }

    /**
     * True when [choice] must be refused as stale; the list then counts as re-presented, because
     * the reply re-reads it. Names are explicit and never refused; a tap never comes here.
     */
    fun refuseStale(choice: NavigationChoice): Boolean {
        if (choice is NavigationChoice.Name) return false
        val reason = when {
            needsReconfirm -> "after_sleep"
            nowMs() - presentedAtMs > CHOICE_EXPIRY_MS -> "expired"
            else -> return false
        }
        DebugVoiceLog.log("nav_voice_choice_stale reason=$reason")
        onListPresented()
        return true
    }

    /**
     * A name matched no row: a single phonetic lead becomes a pending question. Ties, weak matches
     * and API 28 return null and stay an ordinary rejection (「请说第几个」).
     */
    fun propose(
        generation: Long,
        candidates: List<DestinationCandidate>,
        choice: NavigationChoice,
        code: String,
    ): PendingConfirmation? {
        if (choice !is NavigationChoice.Name || code != "NO_MATCH") return null
        val proposal = phoneticProposer(candidates, choice.text)
        if (proposal !is NavigationPhoneticConfirmation.Proposal.Confirm) return null
        // The question names the row, so the list counts as presented: 「对」 answers it now.
        presentedAtMs = nowMs()
        return PendingConfirmation(generation, proposal.position, proposal.name, presentedAtMs)
            .also { pending = it }
            .also { DebugVoiceLog.log("nav_voice_choice_confirm position=${it.position}") }
    }

    /** The question still awaiting an answer for [generation]'s destination list, if any. */
    fun active(generation: Long, destinationListOnScreen: Boolean): PendingConfirmation? {
        val current = pending ?: return null
        val live = current.generation == generation && destinationListOnScreen &&
            nowMs() - current.atMs <= CHOICE_EXPIRY_MS
        if (!live) pending = null
        return pending
    }

    /** Answered, or the driver said something other than yes. */
    fun withdraw() {
        pending = null
    }

    companion object {
        /**
         * How long a spoken ordinal may refer to a list on screen. Two minutes covers reading a
         * list and deciding at the wheel; beyond it the reply re-reads the options (one sentence)
         * rather than guess which list 「第二个」 meant. Not measured in a cabin: a starting value.
         */
        const val CHOICE_EXPIRY_MS = 120_000L

        const val OPTIONS_STALE = "OPTIONS_STALE"
    }
}
