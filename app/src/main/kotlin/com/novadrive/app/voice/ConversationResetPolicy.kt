package com.novadrive.app.voice

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
     * A response finished; [outputKinds] are its `output[].type` values and [callIds] the call ids
     * of its function calls, where known. Returns true to reset now.
     */
    @Synchronized
    fun onResponseDone(outputKinds: List<String>, callIds: List<String> = emptyList()): Boolean {
        val calls = outputKinds.count { it == "function_call" }
        if (calls > 0) {
            callIds.forEach { id -> if (!answeredEarly.remove(id)) owed += id }
            pendingToolResults += (calls - callIds.size).coerceAtLeast(0)
            toolTurnSinceReset = true
            return false
        }
        if ("message" !in outputKinds) return false
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

    @Synchronized
    fun reset() {
        pendingToolResults = 0
        toolTurnSinceReset = false
        plainTurns = 0
        owed.clear()
        answeredEarly.clear()
    }
}
