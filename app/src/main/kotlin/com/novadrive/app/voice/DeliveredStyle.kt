package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.SpeakingStyle
import com.novadrive.app.SpeakingStyleState

/**
 * The speaking style last delivered to the live conversation, compared with
 * [SpeakingStyleState.current] (OPEN_PROBLEMS P55; decided by the decider 2026-10-13). Not a second
 * style owner: [SpeakingStyleState] stays the only one; this only remembers what the provider was
 * last told, so a client knows when to re-send its composed instructions (SPEC-021 B10).
 */
internal class DeliveredStyle(
    private val current: () -> SpeakingStyle = { SpeakingStyleState.current },
    /** For [atIdle]: the client's name in the log, its setup instructions and its wire message. */
    private val provider: String = "",
    private val instructions: () -> String = { "" },
    private val wire: (String) -> String? = { null },
) {
    @Volatile private var delivered: SpeakingStyle? = null

    /** A tool result went out: the next idle point checks the style once. */
    @Volatile var checkPending = false

    /** The style to compose instructions with now; it counts as delivered once they are sent. */
    fun take(): SpeakingStyle = current().also { delivered = it }

    /** True when the current style differs from the one the conversation was last given. */
    fun changed(): Boolean = current() != delivered

    /**
     * The message re-sending [instructions] (the setup text, composed via [take]) when the style
     * changed, or nothing; [wire] returns null when the provider takes no such message. Logs the
     * style name and the length only, never the text (I-8).
     */
    fun redelivery(provider: String, instructions: () -> String, wire: (String) -> String?): List<String> {
        if (!changed()) return emptyList()
        val text = instructions()
        val message = wire(text) ?: return emptyList()
        DebugVoiceLog.log("style_redelivered provider=$provider style=${delivered?.wireName} chars=${text.length}")
        return listOf(message)
    }

    /** At an idle point: once per tool result ([checkPending]), the re-delivery if the style changed. */
    fun atIdle(): List<String> {
        if (!checkPending) return emptyList()
        checkPending = false
        return redelivery(provider, instructions, wire)
    }
}
