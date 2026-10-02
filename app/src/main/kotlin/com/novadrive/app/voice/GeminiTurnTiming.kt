package com.novadrive.app.voice

/**
 * P45 F1: where the time inside one Gemini turn goes, as timings only (I-8). A conversation reply
 * is held until `generationComplete`; when nothing is heard for seconds, this tells "Gemini sent
 * slowly or stalled" (a large [summary] `max_gap_ms`) from "Gemini streamed but never completed
 * the generation" (`gen_ms=-1` with many messages). Called under the client's lock.
 */
internal class GeminiTurnTiming(private val clock: () -> Long = System::currentTimeMillis) {
    private var openedAt = -1L
    private var lastAt = -1L
    private var firstTextAt = -1L
    private var generationAt = -1L
    private var messages = 0
    private var maxGap = 0L

    fun open() {
        openedAt = clock(); lastAt = openedAt
        firstTextAt = -1L; generationAt = -1L; messages = 0; maxGap = 0L
    }

    /** One server content message of the open turn. */
    fun message(hasText: Boolean, generationComplete: Boolean) {
        if (openedAt < 0) return
        val now = clock()
        maxGap = maxOf(maxGap, now - lastAt)
        lastAt = now
        messages++
        if (hasText && firstTextAt < 0) firstTextAt = now
        if (generationComplete && generationAt < 0) generationAt = now
    }

    /** `dur_ms first_text_ms gen_ms msgs max_gap_ms`, each from the turn's open; -1 = never. */
    fun summary(): String {
        if (openedAt < 0) return "dur_ms=-1"
        fun since(at: Long) = if (at < 0) -1L else at - openedAt
        return "dur_ms=${clock() - openedAt} first_text_ms=${since(firstTextAt)} gen_ms=${since(generationAt)} " +
            "msgs=$messages max_gap_ms=$maxGap"
    }
}
