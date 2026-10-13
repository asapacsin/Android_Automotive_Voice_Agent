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

    /** The style delivered before the last re-delivery, until that one is known to be sent. */
    @Volatile private var beforeRedelivery: SpeakingStyle? = null
    @Volatile private var unconfirmed = false

    /** A tool result went out: the next idle point checks the style once. */
    @Volatile var checkPending = false

    /** The style to compose instructions with now; it counts as delivered once they are sent. */
    fun take(): SpeakingStyle {
        unconfirmed = false
        return current().also { delivered = it }
    }

    /** True when the current style differs from the one the conversation was last given. */
    fun changed(): Boolean = current() != delivered

    /** The last re-delivery never reached the provider: the next idle point sends it again. */
    fun forget() {
        if (!unconfirmed) return
        delivered = beforeRedelivery
        unconfirmed = false
    }

    /**
     * The message re-sending [instructions] (the setup text, composed via [take]) when the style
     * changed and the provider takes such a message ([supported]; nothing is composed otherwise),
     * or nothing. Logs the style name and the length only, never the text (I-8).
     */
    fun redelivery(
        provider: String,
        instructions: () -> String,
        wire: (String) -> String?,
        supported: Boolean = true,
    ): List<String> {
        if (!supported || !changed()) return emptyList()
        val before = delivered
        val text = instructions()
        val message = wire(text)
        if (message == null) {
            delivered = before
            return emptyList()
        }
        beforeRedelivery = before
        unconfirmed = true
        DebugVoiceLog.log("style_redelivered provider=$provider style=${delivered?.wireName} chars=${text.length}")
        return listOf(message)
    }

    /**
     * At an idle point: once per tool result ([checkPending]), the re-delivery if the style changed,
     * handed to [send]; a send the socket did not accept is retried at the next idle point.
     */
    fun atIdle(send: (String) -> Boolean) {
        if (!checkPending) return
        checkPending = false
        for (message in redelivery(provider, instructions, wire)) {
            if (!send(message)) {
                forget()
                checkPending = true
            }
        }
    }
}
