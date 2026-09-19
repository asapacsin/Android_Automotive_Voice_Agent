package com.novadrive.ingress.realtime

/**
 * What a provider's completed response actually contained, in neutral terms.
 *
 * ## Why this exists
 *
 * `ActionClaimGuard` and `ConversationResetPolicy` are per-turn **policy** — they decide whether a
 * claim may be spoken and when the conversation is reset. They used to take `outputKinds:
 * List<String>` and ask questions like `"function_call" in outputKinds`, where those strings came
 * straight out of Baidu's `output[].type` field. That is a vendor wire format deciding product
 * behaviour: a second provider would have had to fabricate the literal string `"function_call"` to
 * reuse policy that has nothing to do with Baidu.
 *
 * The adapter knows the wire format. The policy knows the rules. This is the value that passes
 * between them, and it is deliberately small: two facts, because two facts are what the policies
 * actually asked of the list.
 *
 * See [ADR-009](../../../../../../../DECISIONS/ADR-009-provider-neutral-realtime-contract.md).
 */
data class ResponseOutcome(
    /** The response carried something for the driver to hear or read. */
    val spoke: Boolean,
    /** Ids of the tool calls the response requested, in the order the provider reported them. */
    val toolCallIds: List<String> = emptyList(),
    /** Tool calls whose id the provider did not give us; they still count as calls. */
    val unidentifiedToolCalls: Int = 0,
) {
    val toolCalls: Int get() = toolCallIds.size + unidentifiedToolCalls

    val requestedTool: Boolean get() = toolCalls > 0

    companion object {
        /** A response that only spoke — no tool was requested. */
        fun spokenOnly(): ResponseOutcome = ResponseOutcome(spoke = true)

        /** A response that only requested tools. */
        fun toolsOnly(vararg callIds: String): ResponseOutcome =
            ResponseOutcome(spoke = false, toolCallIds = callIds.toList())
    }
}
