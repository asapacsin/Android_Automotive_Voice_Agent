package com.novadrive.app.voice

import com.novadrive.ingress.realtime.ResponseOutcome

/**
 * Decides when to start a fresh Baidu Flex conversation.
 *
 * Measured on device 2026-09-17 with the speech harness (same 8 spoken commands each run):
 * in one long session the model fails from about the third tool turn on — empty replies, and
 * actions executed one turn late (「关闭空调」 raised the temperature instead). Sampling
 * temperature, client-created replies, a compact prompt and an anchored text turn did not help.
 * One fresh conversation per command: 8/8 correct. So the conversation is kept short:
 *
 * - reset after every completed turn that involved a tool call (call -> result -> spoken reply);
 * - reset after [maxPlainTurns] replies without a tool call;
 * - never while a tool result is still owed to the model (its call_id belongs to this conversation).
 */
class ConversationResetPolicy(private val maxPlainTurns: Int = 3) {
    private var pendingToolResults = 0
    private var toolTurnSinceReset = false
    private var plainTurns = 0

    /**
     * Results are matched by call id when the response names them: a fast tool's result can be
     * sent before the response.done that announces its call (found by the simulation benchmark,
     * 2026-09-17), which with plain counting left a result "owed" forever and stopped resets.
     */
    private val owed = mutableSetOf<String>()
    private val answeredEarly = mutableSetOf<String>()

    /**
     * A response finished. Returns true to reset now.
     *
     * Takes a [ResponseOutcome] rather than the provider's own `output[].type` strings: which wire
     * format said "function_call" is the adapter's business, and this policy's rules are not
     * Baidu's (ADR-009).
     */
    @Synchronized
    fun onResponseDone(outcome: ResponseOutcome, superseded: Boolean = false): Boolean {
        if (outcome.requestedTool) {
            outcome.toolCallIds.forEach { id -> if (!answeredEarly.remove(id)) owed += id }
            pendingToolResults += outcome.unidentifiedToolCalls
            toolTurnSinceReset = true
            return false
        }
        // [superseded]: the driver started speaking over this reply and it was discarded. It is
        // not a finished turn, and the driver is mid-utterance: a reset now opens a new socket while
        // their sentence is still being streamed, so the new conversation hears only its tail.
        // Measured 2026-09-28 08:32:57 and 08:36:31 (owner demo): flex_context_reset 100 ms and
        // 45 ms after speech_started, then transcripts 「这个。」 and replies 「没听清，再说一遍。」.
        // The reset still happens after the next completed reply.
        if (superseded) return false
        if (!outcome.spoke) return false
        if (pendingToolResults > 0 || owed.isNotEmpty()) return false
        if (toolTurnSinceReset) return true
        plainTurns += 1
        return plainTurns >= maxPlainTurns
    }

    @Synchronized
    fun onToolResultSent(callId: String? = null) {
        when {
            callId != null && owed.remove(callId) -> Unit
            pendingToolResults > 0 -> pendingToolResults -= 1
            callId != null -> answeredEarly += callId
        }
    }

    /**
     * A call from a response the client cancelled (a local pick already did the job): it is never
     * executed and never answered, so nothing is owed for it. Measured 2026-09-28 on the emulator
     * replay of the owner's demo: 「最快的」 was picked locally, the model's `choose_navigation_option`
     * was dropped, its call id stayed owed, and no reset ever happened again. The long conversation
     * then answered every later command with `output=[]` (有点热 x5, 停止说话, 看看前面有什么) —
     * exactly the failure this policy exists to prevent.
     */
    @Synchronized
    fun onCallDropped(callId: String) = onToolResultSent(callId)

    @Synchronized
    fun reset() {
        pendingToolResults = 0
        toolTurnSinceReset = false
        plainTurns = 0
        owed.clear()
        answeredEarly.clear()
    }
}
