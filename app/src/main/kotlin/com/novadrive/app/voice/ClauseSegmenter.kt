package com.novadrive.app.voice

/**
 * Cuts the provider's streamed transcript into clauses for the assistant voice (ADR-016). A clause
 * is released at a sentence end at once, at a comma once it is long enough, and at [maxChars]
 * regardless. The first clause of a reply may be short so the voice starts early (the measured
 * first clause arrives 0–740 ms after the first audio; VOICE-AB-002). Pure; not thread-safe.
 */
internal class ClauseSegmenter(
    private val firstSoftChars: Int = 3,
    private val softChars: Int = 12,
    private val maxChars: Int = 40,
) {
    private val pending = StringBuilder()
    private var released = 0

    /** Appends [chunk] and returns the clauses that are complete now, in order. */
    fun append(chunk: String): List<String> {
        val out = mutableListOf<String>()
        for (c in chunk) {
            pending.append(c)
            val length = pending.length
            val cut = when {
                c in HARD -> true
                c in SOFT -> length >= (if (released == 0) firstSoftChars else softChars)
                else -> length >= maxChars
            }
            if (cut) take()?.let(out::add)
        }
        return out
    }

    /** The rest of the reply (at its end), or null when nothing speakable is left. */
    fun flush(): String? = take()

    /** A new reply: forget any half clause of the previous one. */
    fun reset() {
        pending.setLength(0)
        released = 0
    }

    private fun take(): String? {
        val text = pending.toString().trim()
        pending.setLength(0)
        if (!text.any(Char::isLetterOrDigit)) return null
        released++
        return text
    }

    private companion object {
        val HARD = setOf('。', '！', '？', '!', '?', '；', ';', '…', '\n')
        val SOFT = setOf('，', ',', '、', '：', ':')
    }
}
