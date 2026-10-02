package com.novadrive.app.voice

import com.novadrive.app.SpeakingStyle

/**
 * ADR-016: the one voice that speaks the agent's words. Gemini stays the agent; when an assistant
 * voice is configured, Gemini's own audio is discarded and its output transcript is spoken through
 * this port instead. Exactly one adapter is wired at a time (ADR-008); behaviour never branches on
 * the vendor outside the adapter.
 */
interface AssistantVoice {
    /** PCM rate of [synthesize]'s output; must equal the session's playback rate. */
    val outputSampleRateHz: Int get() = OUTPUT_SAMPLE_RATE_HZ

    /**
     * Speaks [text] (one clause or sentence) in [style]. [onPcm] receives mono PCM16LE chunks at
     * [outputSampleRateHz] as they arrive, in order. Returns when the clause is fully delivered.
     * Throws [AssistantVoiceException] on any failure; honours coroutine cancellation (barge-in).
     * Never logs the text or the key.
     */
    suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit)

    companion object {
        const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    }
}

/** A voice failure with a stable code (no text, no key in [message]). */
class AssistantVoiceException(val code: String, message: String = code, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Azure AI Speech, the vendor of the owner's pick `zh-CN-XiaoyiNeural` (ADR-016). The key lives in
 * the Keystore only; this object is built per session and never logged.
 */
data class AzureSpeechConfig(
    val key: String,
    val region: String,
    val voice: String = DEFAULT_VOICE,
) {
    /** Azure China regions (`chinaeast2`, `chinanorth3`, …) live under azure.cn. */
    val host: String
        get() = if (region.trim().lowercase().startsWith("china")) {
            "${region.trim().lowercase()}.tts.speech.azure.cn"
        } else {
            "${region.trim().lowercase()}.tts.speech.microsoft.com"
        }

    override fun toString(): String = "AzureSpeechConfig(region=$region, voice=$voice)"

    companion object {
        const val DEFAULT_VOICE = "zh-CN-XiaoyiNeural"
    }
}
