package com.novadrive.app.voice

import com.novadrive.ingress.realtime.ResponseOutcome
import org.json.JSONObject

/**
 * What one reply cost (owner, 2026-10-11: see the cost per turn). Qwen-Omni Realtime and the
 * OpenAI-style dialects report it in `response.done.response.usage` (Model Studio server-events
 * reference): total, input and output tokens, each split into text and audio. Numbers only (I-8).
 */
internal data class ReplyUsage(
    val total: Int,
    val input: Int,
    val inputText: Int,
    val inputAudio: Int,
    val output: Int,
    val outputText: Int,
    val outputAudio: Int,
) {
    fun describe(logPrefix: String): String =
        "${logPrefix}_reply_usage total=$total in=$input in_text=$inputText in_audio=$inputAudio " +
            "out=$output out_text=$outputText out_audio=$outputAudio"

    companion object {
        /** The usage of a `response.done` event, or null when the provider did not report one. */
        fun from(responseDone: String): ReplyUsage? = runCatching {
            val usage = JSONObject(responseDone).optJSONObject("response")?.optJSONObject("usage") ?: return null
            val inDetails = usage.optJSONObject("input_tokens_details")
            val outDetails = usage.optJSONObject("output_tokens_details")
            ReplyUsage(
                total = usage.optInt("total_tokens"),
                input = usage.optInt("input_tokens"),
                inputText = inDetails?.optInt("text_tokens") ?: 0,
                inputAudio = inDetails?.optInt("audio_tokens") ?: 0,
                output = usage.optInt("output_tokens"),
                outputText = outDetails?.optInt("text_tokens") ?: 0,
                outputAudio = outDetails?.optInt("audio_tokens") ?: 0,
            )
        }.getOrNull()
    }
}

/**
 * Keeps the re-billed conversation short for a provider that keeps one conversation (Qwen). Its
 * realtime API has no item delete or truncation (client-events reference, 2026-10-11), so once a
 * reply's input passes [capTokens] the conversation is started afresh at the next safe point.
 * Nothing the model needs is lost: car state and pending choices reach it through
 * [VoiceContextHints] on every turn, never through its memory (docs/ARCHITECTURE.md).
 *
 * Safe point: a finished spoken reply that asked for no tool (so no tool result is owed), not one
 * the driver talked over, and nothing on screen waiting for the driver's answer.
 */
internal class HistoryBudget(private val capTokens: Int = HISTORY_TOKEN_CAP) {
    fun full(usage: ReplyUsage?, outcome: ResponseOutcome, superseded: Boolean, awaitingAnswer: Boolean): Boolean =
        usage != null && usage.input >= capTokens &&
            outcome.spoke && !outcome.requestedTool && !superseded && !awaitingAnswer

    companion object {
        /**
         * The fixed part (persona, rules and 17 tool declarations) is about 7K input tokens on
         * every reply (estimated from the session.update, 2026-10-11); this allows about 5K of
         * history on top. Revisit with the logged `reply_usage` numbers from a real take.
         */
        const val HISTORY_TOKEN_CAP = 12_000
    }
}
