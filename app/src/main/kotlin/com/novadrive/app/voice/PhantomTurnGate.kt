package com.novadrive.app.voice

/**
 * Decides whether a finished model turn is worth playing out loud.
 *
 * The uplink gate ([SpeechUplinkGate]) stops taps and clicks reaching the model at all. What it
 * cannot stop is sound that *is* sustained — a cough, a door, a television — reaching the model
 * and coming back as a polite 「没听清，再说一遍。」 addressed to nobody. This is an end-to-end S2S
 * product by [decision][com.novadrive.app.nav.NavigationPhase] (ADR-002): there is no ASR
 * confidence score to consult, and adding a recogniser to get one is explicitly out of scope.
 *
 * So the judgement is made from application state that is already known and fully deterministic:
 *
 * 1. the model asked for **no action** — a turn that calls a tool is real by definition;
 * 2. **nothing on screen is waiting for an answer** — no destination or route list, no camera
 *    question outstanding, no app-requested turn;
 * 3. the audio that caused it was **short, sparse, or produced no words at all** — the provider's
 *    own transcription of the driver's turn came back empty, which is the end-to-end stack's
 *    equivalent of a failed recognition and costs nothing extra to read;
 * 4. the reply **carries no content** — it is empty, or too short to be telling the driver
 *    anything ( 「嗯。」 , 「没听清，再说一遍。」 ). This is a shape test, not a vocabulary one: a fixed
 *    phrase list cannot keep up with a generative model, which was measured on device — noise was
 *    answered with 「嗯。」 , a filler no blacklist would have contained. The known repair phrases
 *    stay as an extra catch for a *longer* apology, never as the mechanism.
 *
 * All four, or the turn is spoken. Any one of them failing means a real person is owed a reply:
 * a genuine question with no tool call is condition 1 satisfied but condition 4 refuted, so it is
 * spoken; a short 「暂停」 is conditions 3 satisfied but it calls a tool, so it is spoken.
 *
 * On condition 4 and keyword lists. The repair phrases are **not** the mechanism — they are the
 * last of four gates, and they are the model's own fixed fallback vocabulary rather than a
 * blacklist of things a driver might say. Removing the phrase check entirely would still leave a
 * safe gate; it would simply also swallow short genuine answers, which is why it is here. No
 * driver utterance is ever matched against a list.
 */
object PhantomTurnGate {
    /** Everything the decision needs, gathered at `response.done`. */
    data class Turn(
        val hadToolCall: Boolean,
        val contextAwaitingAnswer: Boolean,
        val audio: SpeechUplinkGate.Segment?,
        val assistantText: String,
        /**
         * Whether the provider transcribed anything the driver said during this turn. Empty means
         * its own recogniser found no words in the audio — the strongest evidence available that
         * the sound was not speech, and it comes free with the end-to-end stream.
         */
        val hadUserTranscript: Boolean = true,
    )

    sealed interface Verdict {
        /** Play it. */
        data object Speak : Verdict

        /** Drop it silently; [reason] goes to the log. */
        data class Drop(val reason: String) : Verdict
    }

    fun judge(turn: Turn): Verdict {
        if (turn.hadToolCall) return Verdict.Speak
        if (turn.contextAwaitingAnswer) return Verdict.Speak
        val audio = turn.audio ?: return Verdict.Speak
        val shortAudio = audio.durationMs < SpeechUplinkGate.SUSPICIOUS_BELOW_MS
        val sparseAudio = audio.voicedRatio < SpeechUplinkGate.SUSPICIOUS_VOICED_RATIO
        val noWords = !turn.hadUserTranscript
        if (!shortAudio && !sparseAudio && !noWords) return Verdict.Speak
        if (!isContentlessReply(turn.assistantText)) return Verdict.Speak
        val reason = when {
            noWords -> "no_user_speech"
            shortAudio -> "short_audio"
            else -> "weak_voiced_ratio"
        }
        return Verdict.Drop("generic_repair_no_action_$reason")
    }

    /**
     * A reply that tells the driver nothing: empty, or shorter than a sentence that could carry
     * information. Length is the primary test and needs no vocabulary; the phrase list below only
     * extends it to longer apologies. Judged on the assistant's own words, never on the driver's.
     *
     * A genuinely short answer — 「二十五度。」 — is never reached by this: it answers a question the
     * driver actually asked, and a question arrives as good audio, which fails condition 3 first.
     */
    fun isContentlessReply(text: String): Boolean {
        val trimmed = text.trim()
        if (!hasWords(trimmed)) return true
        val cjk = trimmed.any { it.code in CJK_RANGE }
        val shortLimit = if (cjk) SHORT_REPLY_CHARS_CJK else SHORT_REPLY_CHARS_LATIN
        if (trimmed.length <= shortLimit) return true
        return isGenericRepair(trimmed)
    }

    /** Below this a reply cannot be carrying an answer; measured phantoms were 2–9 characters. */
    const val SHORT_REPLY_CHARS_CJK = 12
    const val SHORT_REPLY_CHARS_LATIN = 40

    /**
     * The model's fallback when it heard sound but no request: one of a handful of fixed
     * apologies. A secondary catch for apologies longer than the shape test above.
     */
    fun isGenericRepair(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        // One Han character carries about as much as a short English word, so the bound differs
        // by script. Without this, 「没听清」 and "Sorry, I didn't catch that." cannot share a limit.
        val limit = if (trimmed.any { it.code in CJK_RANGE }) MAX_REPAIR_CHARS_CJK else MAX_REPAIR_CHARS_LATIN
        if (trimmed.length > limit) return false
        return REPAIR_MARKERS.any { trimmed.contains(it, ignoreCase = true) }
    }

    /**
     * Whether a transcript contains any actual word. Measured on device 2026-09-18: noise was
     * transcribed as 「。」 — a lone full stop — and a blank-string check counted that as the driver
     * speaking, so the phantom reply was released and spoken. Punctuation and whitespace are not
     * words in any of this product's languages.
     */
    fun hasWords(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /**
     * Whether a transcript is a request rather than a grunt. Measured on device 2026-09-18: noise
     * was transcribed as 「嗯。」 and 「。」 — one syllable and none — and treating either as "the
     * driver spoke" released the phantom reply. Two word-characters is the bar; a genuine
     * one-syllable command (「停」) calls a tool, and a tool call is never silenced.
     */
    fun isMeaningfulTranscript(text: String): Boolean =
        text.count { it.isLetterOrDigit() } >= MIN_TRANSCRIPT_WORD_CHARS

    const val MIN_TRANSCRIPT_WORD_CHARS = 2

    /** A repair is a dozen characters; anything longer is carrying information. */
    const val MAX_REPAIR_CHARS_CJK = 24
    const val MAX_REPAIR_CHARS_LATIN = 48
    private val CJK_RANGE = 0x4E00..0x9FFF

    private val REPAIR_MARKERS = listOf(
        "没听清", "没有听清", "听不清", "再说一遍", "再说一次", "请重复", "重复一遍",
        "没听懂", "没有听懂", "不太明白", "没听到", "没有说话",
        "didn't catch", "did not catch", "say that again", "repeat that", "pardon",
    )
}
