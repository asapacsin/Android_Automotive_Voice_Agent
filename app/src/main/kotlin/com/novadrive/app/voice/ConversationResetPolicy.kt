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

    /** A response finished; [outputKinds] are its `output[].type` values. Returns true to reset now. */
    @Synchronized
    fun onResponseDone(outputKinds: List<String>): Boolean {
        val calls = outputKinds.count { it == "function_call" }
        if (calls > 0) {
            pendingToolResults += calls
            toolTurnSinceReset = true
            return false
        }
        if ("message" !in outputKinds) return false
        if (pendingToolResults > 0) return false
        if (toolTurnSinceReset) return true
        plainTurns += 1
        return plainTurns >= maxPlainTurns
    }

    @Synchronized
    fun onToolResultSent() {
        if (pendingToolResults > 0) pendingToolResults -= 1
    }

    @Synchronized
    fun reset() {
        pendingToolResults = 0
        toolTurnSinceReset = false
        plainTurns = 0
    }
}
